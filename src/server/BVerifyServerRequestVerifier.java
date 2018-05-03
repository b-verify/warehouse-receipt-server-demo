package server;

import java.rmi.RemoteException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;

import com.google.protobuf.ByteString;

import api.BVerifyProtocolClientAPI;
import api.BVerifyProtocolServerAPI;
import rmi.ClientProvider;
import serialization.generated.BVerifyAPIMessageSerialization.ADSData;
import serialization.generated.BVerifyAPIMessageSerialization.AuthProof;
import serialization.generated.BVerifyAPIMessageSerialization.IssueReceiptRequest;
import serialization.generated.BVerifyAPIMessageSerialization.Receipt;
import serialization.generated.MptSerialization.MerklePrefixTrie;

public class BVerifyServerRequestVerifier implements BVerifyProtocolServerAPI {
	
	// shared data
	private final ADSManager adsManager;
	private final BlockingQueue<IssueReceiptRequest> requests;
	private final ClientProvider rmi;

	public BVerifyServerRequestVerifier( 
			BlockingQueue<IssueReceiptRequest> requests, ADSManager ads, ClientProvider rmi) {
		this.adsManager = ads;
		this.requests = requests;
		this.rmi = rmi;
	}
	
	@Override
	public void issueReceipt(byte[] request) throws RemoteException {
		try{
			IssueReceiptRequest requestMsg = IssueReceiptRequest.parseFrom(request);
			System.out.println("issueReceipt for "+requestMsg);
			this.requests.add(requestMsg);
		}catch(Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public byte[] getAuthPath(List<byte[]> adsIds, int commitmentNumber) throws RemoteException {
		System.out.println("GetAuthPath for "+adsIds+" commiment#: "+commitmentNumber);
		MerklePrefixTrie proof = this.adsManager.getProof(adsIds, commitmentNumber);
		AuthProof response = AuthProof.newBuilder()
				.setPath(proof)
				.build();
		return response.toByteArray();
	}

	@Override
	public byte[] getReceipts(byte[] adsId) throws RemoteException {
		System.out.println("getReceipts "+adsId);
		Set<Receipt> adsData = this.adsManager.getADSData(adsId);
		ADSData response = ADSData.newBuilder()
				.addAllReceipts(adsData)
				.setCommitmentNumber(this.adsManager.getCurrentCommitmentNumber())
				.setCommitment(ByteString.copyFrom(this.adsManager.getCommitment(
						this.adsManager.getCurrentCommitmentNumber())))
				.build();
		return response.toByteArray();
	}

	@Override
	public boolean bindClient(String clientName, BVerifyProtocolClientAPI clientStub) {
		this.rmi.bind(clientName, clientStub);
		return true;
	}

}
