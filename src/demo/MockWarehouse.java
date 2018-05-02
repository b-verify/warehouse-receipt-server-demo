package demo;

import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import api.BVerifyProtocolClientAPI;
import crpyto.CryptographicSignature;
import crpyto.CryptographicUtils;
import mpt.core.InsufficientAuthenticationDataException;
import mpt.core.InvalidSerializationException;
import mpt.core.Utils;
import mpt.dictionary.AuthenticatedDictionaryClient;
import mpt.dictionary.MPTDictionaryPartial;
import mpt.set.AuthenticatedSetServer;
import mpt.set.MPTSetFull;
import pki.Account;
import pki.PKIDirectory;
import rmi.ClientProvider;
import serialization.generated.BVerifyAPIMessageSerialization.ADSData;
import serialization.generated.BVerifyAPIMessageSerialization.AuthProof;
import serialization.generated.BVerifyAPIMessageSerialization.IssueReceiptRequest;
import serialization.generated.BVerifyAPIMessageSerialization.Receipt;
import serialization.generated.BVerifyAPIMessageSerialization.Signature;

public class MockWarehouse implements BVerifyProtocolClientAPI {

	private final Account account;
	private final List<Account> depositors;
	private final ClientProvider rmi;
	private final Map<String, AuthenticatedSetServer> adsKeyToADS;
	private final Map<String, Set<Receipt>> adsKeyToADSData;

	private final AuthenticatedDictionaryClient authADS;
	private int currentCommitmentNumber;
	private byte[] currentCommitment;
	
	public MockWarehouse(Account thisWarehouse, 
			List<Account> depositors, String host, int port) {
		this.account = thisWarehouse;
		this.depositors = depositors;

		assert this.account.getADSKeys().size() == this.depositors.size();
		
		this.rmi = new ClientProvider(host, port);
		
		this.adsKeyToADS = new HashMap<>();
		this.adsKeyToADSData = new HashMap<>();
		try {
			// first ask for the receipts and current commitment
			for(byte[] adsKey : this.account.getADSKeys()) {
				String adsKeyString = Utils.byteArrayAsHexString(adsKey);
				MPTSetFull ads = new MPTSetFull();
				Set<Receipt> adsData = new HashSet<>();
				byte[] recieptDataBytes = this.rmi.getServer().getReceipts(adsKey);
				ADSData receiptDataMsg = ADSData.parseFrom(recieptDataBytes);
				for(Receipt r : receiptDataMsg.getReceiptsList()) {
					adsData.add(r);
					byte[] receiptWitness = CryptographicUtils.witnessReceipt(r);
					ads.insert(receiptWitness);
				}
				this.currentCommitmentNumber = receiptDataMsg.getCommitmentNumber();
				this.adsKeyToADS.put(adsKeyString, ads);
				this.adsKeyToADSData.put(adsKeyString, adsData);
			}

			
			// next ask for the auth paths
			List<byte[]> keys = this.account.getADSKeys().stream().collect(Collectors.toList());
			byte[] pathBytes = this.rmi.getServer().getAuthPath(keys, this.currentCommitmentNumber);
			this.authADS = MPTDictionaryPartial.deserialize(AuthProof.parseFrom(pathBytes).getPath());
			
			// check that the auth proof is correct
			for(byte[] adsKey : this.account.getADSKeys()) {
				String adsKeyString = Utils.byteArrayAsHexString(adsKey);
				if(!Arrays.equals(this.authADS.get(adsKey), 
						this.adsKeyToADS.get(adsKeyString).commitment())){
					throw new RuntimeException("sever returned invalid proof!");
				}
			}
		}catch(Exception e) {
			throw new RuntimeException("cannot parse response from server");
		}
	}
	
	@Override
	public byte[] approveDeposit(byte[] request) throws RemoteException {
		throw new RuntimeException("warehouse in demo only submits approved deposits");
	}
	
	
	public void deposit() {
		Account depositor = this.depositors.get(0);
		this.deposit(BootstrapMockSetup.generateReceipt(this.account, depositor), depositor);
	}
	
	public void deposit(Receipt r, Account depositor) {
		List<Account> accounts = new ArrayList<>();
		accounts.add(this.account);
		accounts.add(depositor);
		byte[] adsId = CryptographicUtils.listOfAccountsToADSKey(accounts);
		String adsIdString = Utils.byteArrayAsHexString(adsId);
		if(!this.adsKeyToADS.containsKey(adsIdString)) {
			throw new RuntimeException("not a valid depositor");
		}
		AuthenticatedSetServer ads = this.adsKeyToADS.get(adsIdString);
		Set<Receipt> adsData = this.adsKeyToADSData.get(adsIdString);
		adsData.add(r);
		byte[] receiptWitness = CryptographicUtils.witnessReceipt(r);
		ads.insert(receiptWitness);
		byte[] witnessToSign = ads.commitment();
		byte[] signature = CryptographicSignature.sign(witnessToSign, this.account.getPrivateKey());
		
		IssueReceiptRequest request = IssueReceiptRequest.newBuilder()
				.setIssuerId(this.account.getIdAsString())
				.setRecepientId(depositor.getIdAsString())
				.setReceipt(r)
				.setSignature(Signature.newBuilder()
						.setSignerId(this.account.getIdAsString())
						.setSignature(ByteString.copyFrom(signature))
				)
				.build();
		
		// invoke on the server
		try {
			this.rmi.getServer().issueReceipt(request.toByteArray());
		} catch (RemoteException e) {
			e.printStackTrace();
			throw new RuntimeException("can't invoke receipt issue method on server");
		}
		
	}

	@Override
	public void addNewCommitment(byte[] commitment) throws RemoteException {
		// add the commitment 
		this.currentCommitmentNumber = this.currentCommitmentNumber+1;
		this.currentCommitment = commitment;
		
		// ask the server for a proof!
		List<byte[]> keys = this.account.getADSKeys().stream().collect(Collectors.toList());
		byte[] respBytes = this.rmi.getServer().getAuthPath(keys, this.currentCommitmentNumber);
		try {
			AuthProof proof = AuthProof.parseFrom(respBytes);
			MPTDictionaryPartial mpt = MPTDictionaryPartial.deserialize(proof.getPath());
			if(!Arrays.equals(mpt.commitment(), this.currentCommitment)) {
				throw new RuntimeException("commitment in proof does not match");
			}
			// check that the auth proof is correct
			for(byte[] adsKey : this.account.getADSKeys()) {
				String adsKeyString = Utils.byteArrayAsHexString(adsKey);
				if(!Arrays.equals(this.authADS.get(adsKey), 
						this.adsKeyToADS.get(adsKeyString).commitment())){
					throw new RuntimeException("sever returned invalid proof!");
				}
			}
		} catch (InvalidProtocolBufferException | InvalidSerializationException | 
				InsufficientAuthenticationDataException e) {
			e.printStackTrace();
			throw new RuntimeException(e.getMessage());
		}
	}
	
	public static void main(String[] args) {
		String base = "/home/henryaspegren/eclipse-workspace/b_verify-server/demos/";
		PKIDirectory pki = new PKIDirectory(base+"pki/");
		String host = null;
		int port = 1099;
		/**
		 * Alice: 59d6dd79-4bbe-4043-ba3e-e2a91e2376ae
		 * Bob: b132bbfa-98bc-4e5d-b32d-f78d603600f5
		 * Warehouse: 2cd00d43-bf5c-4728-9323-d2ea0092ed36
		 */
		Account alice = pki.getAccount("59d6dd79-4bbe-4043-ba3e-e2a91e2376ae");
		Account bob = pki.getAccount("b132bbfa-98bc-4e5d-b32d-f78d603600f5");
		Account warehouse = pki.getAccount("2cd00d43-bf5c-4728-9323-d2ea0092ed36");
		
		List<Account> depositors = new ArrayList<>();
		depositors.add(alice);
		depositors.add(bob);
		MockWarehouse warehouseClient = new MockWarehouse(warehouse, depositors, host, port);
	}
	
}

