package server;

import java.rmi.RemoteException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;

import api.BVerifyProtocolServerAPI;
import serialization.generated.BVerifyAPIMessageSerialization.ADSData;
import serialization.generated.BVerifyAPIMessageSerialization.IssueReceiptRequest;
import serialization.generated.BVerifyAPIMessageSerialization.Receipt;

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
			this.requests.add(requestMsg);
		}catch(Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public byte[] getAuthPath(List<byte[]> adsIds, int commitmentNumber) throws RemoteException {
		return this.adsManager.getProof(adsIds, commitmentNumber).toByteArray();
	}

	@Override
	public byte[] getReceipts(byte[] adsId) {
		Set<Receipt> adsData = this.adsManager.getADSData(adsId);
		ADSData response = ADSData.newBuilder()
				.addAllReceipts(adsData)
				.setCommitmentNumber(this.adsManager.getCurrentCommitmentNumber())
				.build();
		return response.toByteArray();
	}

}
