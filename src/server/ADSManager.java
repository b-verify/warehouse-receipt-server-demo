package server;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import crpyto.CryptographicUtils;
import demo.BootstrapMockSetup;
import mpt.core.InvalidSerializationException;
import mpt.core.Utils;
import mpt.dictionary.MPTDictionaryFull;
import mpt.dictionary.MPTDictionaryPartial;
import mpt.set.AuthenticatedSetServer;
import mpt.set.MPTSetFull;
import pki.Account;
import pki.PKIDirectory;
import io.grpc.bverify.Receipt;
import serialization.generated.MptSerialization.MerklePrefixTrie;

/**
 * THREADSAFE
 * 
 * @author henryaspegren
 *
 */
public class ADSManager {
	private static final Logger logger = Logger.getLogger(ADSManager.class.getName());

	private final String adsDir;

	// we store a mapping from adsKeys
	// to sets of clients who control the ADS.
	// The protocol requires that these
	// clients must all sign updates to the ADS.
	// Java NOTE: cannot use byte[] as a key since
	// implements referential equality so
	// instead we wrap it with a string
	private final Map<String, AuthenticatedSetServer> adsKeyToADS;
	private final Map<String, Set<Receipt>> adsKeyToADSData;
	private final Map<String, Set<Account>> adsKeyToADSOwners;
	private final Map<String, byte[]> adsKeyStringToBytes;

	// current server authentication
	// information.
	// this is a mapping from a client ads key
	// (also referred to as ads id) to the
	// root value of that ADS.
	// also save old authentication data 
	// to generate proofs
	private MPTDictionaryFull serverAuthADS;
	private List<MPTDictionaryFull> serverAuthADSVersions;
	// also (although not strictly required)
	// store the old ads data
	private List<Map<String, Set<Receipt>>> oldADSData;

	// witness information
	// this should be witnessed using Bitcoin
	private final List<byte[]> commitments;

	public ADSManager(String adsDir, PKIDirectory pki) {
		this.adsDir = adsDir;
		this.serverAuthADSVersions = new ArrayList<>();
		this.oldADSData = new ArrayList<>();
		this.commitments = new ArrayList<>();

		// First all the ADS Keys and
		// determine which clients care about
		// each ADS
		this.adsKeyToADSOwners = new HashMap<>();
		this.adsKeyStringToBytes = new HashMap<>();
		Set<Account> accounts = pki.getAllAccounts();
		for (Account a : accounts) {
			logger.log(Level.INFO, "...loading account: " + a.getFirstName());
			Set<byte[]> adsKeys = a.getADSKeys();
			for (byte[] adsKey : adsKeys) {
				String adsKeyString = Utils.byteArrayAsHexString(adsKey);
				logger.log(Level.INFO, "...has key: " + adsKeyString);
				this.adsKeyStringToBytes.put(adsKeyString, adsKey);
				Set<Account> accs = this.adsKeyToADSOwners.get(adsKeyString);
				if (accs == null) {
					accs = new HashSet<>();
				}
				accs.add(a);
				this.adsKeyToADSOwners.put(adsKeyString, accs);
			}
		}

		// next load the actual receipt data
		// and generate the ADSes
		this.adsKeyToADSData = new HashMap<>();
		this.adsKeyToADS = new HashMap<>();
		this.serverAuthADS = new MPTDictionaryFull();
		for (String adsKeyString : this.adsKeyStringToBytes.keySet()) {
			Set<Receipt> receipts = BootstrapMockSetup.loadReceipts(adsDir, adsKeyString);
			MPTSetFull ads = new MPTSetFull();
			for (Receipt r : receipts) {
				byte[] witness = CryptographicUtils.witnessReceipt(r);
				ads.insert(witness);
			}
			logger.log(Level.INFO, "... ads: " + adsKeyString + " - has " + receipts.size() + " receipts");
			this.adsKeyToADSData.put(adsKeyString, receipts);
			this.adsKeyToADS.put(adsKeyString, ads);
			this.serverAuthADS.insert(this.adsKeyStringToBytes.get(adsKeyString), ads.commitment());
		}

		// finally add the ADS as the first version
		logger.log(Level.INFO, "...ADSManager Loaded!");
	}

	public synchronized AuthenticatedSetServer getADS(byte[] adsId) {
		// serializes to bytes and deserializes
		// to get a deep copy with no references
		String adsKey = Utils.byteArrayAsHexString(adsId);
		if (this.adsKeyToADS.containsKey(adsKey)) {
			byte[] toBytes = this.adsKeyToADS.get(adsKey).serialize().toByteArray();
			try {
				MPTSetFull copy = MPTSetFull.deserialize(toBytes);
				return copy;
			} catch (Exception e) {
				throw new RuntimeException(e.getMessage());
			}
		}
		return null;
	}

	public synchronized Set<Receipt> getADSData(byte[] adsId, int commitmentNumber) {
		if (commitmentNumber < 0 || commitmentNumber >= this.commitments.size()) {
			return null;
		}
		String adsKey = Utils.byteArrayAsHexString(adsId);
		Set<Receipt> receipts;
		// if current commitment 
		if(commitmentNumber == this.commitments.size() - 1) {
			receipts = this.adsKeyToADSData.get(adsKey);
		}
		// if older commitment
		else {
			receipts = this.oldADSData.get(commitmentNumber).get(adsKey);
		}
		return new HashSet<Receipt>(receipts);
	}
	
	public synchronized Set<Receipt> getADSData(byte[] adsId) {
		String adsKey = Utils.byteArrayAsHexString(adsId);
		Set<Receipt> receipts = this.adsKeyToADSData.get(adsKey);
		return new HashSet<Receipt>(receipts);
	}

	public synchronized void updateADS(byte[] adsKey, Set<Receipt> adsData, AuthenticatedSetServer ads) {
		String adsKeyString = Utils.byteArrayAsHexString(adsKey);
		this.adsKeyToADS.put(adsKeyString, ads);
		this.adsKeyToADSData.put(adsKeyString, adsData);
		this.serverAuthADS.insert(adsKey, ads.commitment());
	}

	public synchronized byte[] commit() {
		// copy the auth ADS
		MerklePrefixTrie asBytes = this.serverAuthADS.serialize();
		MPTDictionaryFull copy;
		try {
			copy = MPTDictionaryFull.deserialize(asBytes);
			
			// save the old auth version
			this.serverAuthADSVersions.add(copy);
			
			// also store the old ads data
			Map<String, Set<Receipt>> adsDataCopy = new HashMap<>();
			for(Entry<String, Set<Receipt>> kv : this.adsKeyToADSData.entrySet()) {
				Set<Receipt> setCopy = new HashSet<>(kv.getValue());
				adsDataCopy.put(kv.getKey(), setCopy);
			}
			
		} catch (InvalidSerializationException e) {
			e.printStackTrace();
			throw new RuntimeException("internal error");
		}
		// add the commitment
		byte[] commitment = this.serverAuthADS.commitment();
		this.commitments.add(commitment);

		// clear any changes
		this.serverAuthADS.reset();
		
		return commitment;
	}

	public synchronized int getCurrentCommitmentNumber() {
		assert this.commitments.size() > 0;
		assert this.serverAuthADSVersions.size() == this.commitments.size();
		return this.commitments.size() - 1;
	}

	public synchronized byte[] getCommitment(int commitmentNumber) {
		return this.commitments.get(commitmentNumber);
	}

	public synchronized MerklePrefixTrie getProof(List<byte[]> keys, int commitmentNumber) {
		if (commitmentNumber < 0 || commitmentNumber >= this.commitments.size()) {
			return null;
		}
		MPTDictionaryFull full = this.serverAuthADSVersions.get(commitmentNumber);
		MPTDictionaryPartial partial = new MPTDictionaryPartial(full, keys);
		return partial.serialize();
	}

	public synchronized void save() {
		// TBD
		byte[] asBytes = this.serverAuthADS.serialize().toByteArray();
		try {
			File f = new File(adsDir + "-" + this.serverAuthADS.commitment());
			FileOutputStream fos = new FileOutputStream(f);
			fos.write(asBytes);
			fos.close();
		} catch (Exception e) {
			throw new RuntimeException(e.getMessage());
		}
	}

}
