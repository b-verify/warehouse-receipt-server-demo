package demo;

import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import api.BVerifyProtocolClientAPI;
import crpyto.CryptographicSignature;
import crpyto.CryptographicUtils;
import mpt.core.InsufficientAuthenticationDataException;
import mpt.core.InvalidSerializationException;
import mpt.dictionary.AuthenticatedDictionaryClient;
import mpt.dictionary.MPTDictionaryPartial;
import mpt.set.AuthenticatedSetServer;
import mpt.set.MPTSetFull;
import pki.Account;
import rmi.ClientProvider;
import serialization.generated.BVerifyAPIMessageSerialization.ADSData;
import serialization.generated.BVerifyAPIMessageSerialization.AuthProof;
import serialization.generated.BVerifyAPIMessageSerialization.IssueReceiptRequest;
import serialization.generated.BVerifyAPIMessageSerialization.Receipt;
import serialization.generated.BVerifyAPIMessageSerialization.Signature;

public class MockDepositor implements BVerifyProtocolClientAPI {

	private final Account account;
	private final ClientProvider rmi;
	private final byte[] adsKey;
	private final Set<Receipt> adsData;
	private final AuthenticatedSetServer ads;
	private final AuthenticatedDictionaryClient authADS;
	private int currentCommitmentNumber;
	private byte[] currentCommitment;
	
	public MockDepositor(Account a, String host, int port) {
		this.account = a;
		assert a.getADSKeys().size() == 1;
		this.adsKey = a.getADSKeys().iterator().next();
		this.rmi = new ClientProvider(host, port);
	
		try {
			// first ask for the receipts and current commitment
			this.ads = new MPTSetFull();
			this.adsData = new HashSet<>();
			byte[] recieptDataBytes = this.rmi.getServer().getReceipts(this.adsKey);
			ADSData receiptDataMsg = ADSData.parseFrom(recieptDataBytes);
			for(Receipt r : receiptDataMsg.getReceiptsList()) {
				this.adsData.add(r);
				byte[] receiptWitness = CryptographicUtils.witnessReceipt(r);
				this.ads.insert(receiptWitness);
			}
			this.currentCommitmentNumber = receiptDataMsg.getCommitmentNumber();
			
			// next ask for the auth path
			List<byte[]> adsKeys = new ArrayList<>();
			adsKeys.add(this.adsKey);
			byte[] pathBytes = this.rmi.getServer().getAuthPath(adsKeys, this.currentCommitmentNumber);
			this.authADS = MPTDictionaryPartial.deserialize(AuthProof.parseFrom(pathBytes).getPath());
			
			// check that the auth proof is correct
			if(Arrays.equals(this.authADS.get(this.adsKey), this.ads.commitment())){
				throw new RuntimeException("sever returned invalid proof!");
			}
		}catch(Exception e) {
			throw new RuntimeException("cannot parse response from server");
		}
	}
	
	@Override
	public byte[] approveDeposit(byte[] request) throws RemoteException {
		IssueReceiptRequest requestMsg;
		try {
			requestMsg = IssueReceiptRequest.parseFrom(request);
			Receipt receipt = requestMsg.getReceipt();
			this.adsData.add(receipt);
			byte[] witness = CryptographicUtils.witnessReceipt(receipt);
			this.ads.insert(witness);
			
			byte[] newRoot = CryptographicSignature.sign(this.ads.commitment(), this.account.getPrivateKey());			
			return Signature.newBuilder()
					.setSignerId(this.account.getIdAsString())
					.setSignature(ByteString.copyFrom(newRoot))
					.build()
					.toByteArray();
		} catch (InvalidProtocolBufferException e) {
			e.printStackTrace();
			throw new RuntimeException(e.getMessage());
		}
	}

	@Override
	public void addNewCommitment(byte[] commitment) throws RemoteException {
		// add the commitment 
		this.currentCommitmentNumber = this.currentCommitmentNumber+1;
		this.currentCommitment = commitment;
		
		// ask the server for a proof!
		List<byte[]> adsKeys = new ArrayList<>();
		adsKeys.add(this.adsKey);
		byte[] respBytes = this.rmi.getServer().getAuthPath(adsKeys, this.currentCommitmentNumber);
		try {
			AuthProof proof = AuthProof.parseFrom(respBytes);
			MPTDictionaryPartial mpt = MPTDictionaryPartial.deserialize(proof.getPath());
			byte[] valueInProof = mpt.get(this.adsKey);
			byte[] commitmentProof = mpt.commitment();
			if(!Arrays.equals(valueInProof, this.ads.commitment())) {
				throw new RuntimeException("value in proof does not match");
			}
			if(!Arrays.equals(commitmentProof, this.currentCommitment)) {
				throw new RuntimeException("commitment in proof does not match");
			}
		} catch (InvalidProtocolBufferException | InvalidSerializationException | InsufficientAuthenticationDataException e) {
			e.printStackTrace();
			throw new RuntimeException(e.getMessage());
		}
	}
	
}
