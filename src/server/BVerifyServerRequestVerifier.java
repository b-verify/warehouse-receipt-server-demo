package server;

import java.rmi.RemoteException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;

import api.BVerifyProtocolServerAPI;
import serialization.generated.BVerifyAPIMessageSerialization.ADSData;
import serialization.generated.BVerifyAPIMessageSerialization.IssueReceiptRequest;
import serialization.generated.BVerifyAPIMessageSerialization.Receipt;
import serialization.generated.MptSerialization.MerklePrefixTrie;

public class BVerifyServerRequestVerifier implements BVerifyProtocolServerAPI {
	
	// shared data
	private final ADSManager adsManager;
	private BlockingQueue<IssueReceiptRequest> requests;

	public BVerifyServerRequestVerifier( 
			BlockingQueue<IssueReceiptRequest> requests, ADSManager ads) {
		this.adsManager = ads;
		this.requests = requests;
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
		System.out.println("Response: "+proof);
		return proof.toByteArray();
	}

	@Override
	public byte[] getReceipts(byte[] adsId) throws RemoteException {
		System.out.println("getReceipts "+adsId);
		Set<Receipt> adsData = this.adsManager.getADSData(adsId);
		ADSData response = ADSData.newBuilder()
				.addAllReceipts(adsData)
				.setCommitmentNumber(this.adsManager.getCurrentCommitmentNumber())
				.build();
		System.out.println("Response: "+response);
		return response.toByteArray();
	}

}
