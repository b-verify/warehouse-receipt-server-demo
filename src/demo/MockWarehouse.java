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
	private final ClientProvider rmi;
	private final PKIDirectory pki;
	private final Map<String, AuthenticatedSetServer> adsKeyToADS;
	private final Map<String, Set<Receipt>> adsKeyToADSData;

	private final AuthenticatedDictionaryClient authADS;
	private int currentCommitmentNumber;
	private byte[] currentCommitment;
	
	public MockWarehouse(String base, Account a, String host, int port) {
		this.account = a;
		assert a.getADSKeys().size() > 1;
		this.rmi = new ClientProvider(host, port);
		this.pki = new PKIDirectory(base+"pki/");
		
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
	
	public void createDeposit(Receipt r) {
		if(r.getWarehouseId() != this.account.getIdAsString()) {
			throw new RuntimeException("wrong warehouse");
		}
		Account depositor = this.pki.getAccount(r.getDepositorId());
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
	
}

