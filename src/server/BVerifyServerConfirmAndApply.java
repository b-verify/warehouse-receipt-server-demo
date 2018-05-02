package server;

import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;

import com.google.protobuf.InvalidProtocolBufferException;

import api.BVerifyProtocolClientAPI;
import crpyto.CryptographicSignature;
import crpyto.CryptographicUtils;
import mpt.set.AuthenticatedSetServer;
import pki.Account;
import pki.PKIDirectory;
import rmi.ClientProvider;
import serialization.generated.BVerifyAPIMessageSerialization.IssueReceiptRequest;
import serialization.generated.BVerifyAPIMessageSerialization.Receipt;
import serialization.generated.BVerifyAPIMessageSerialization.Signature;

public class BVerifyServerConfirmAndApply extends Thread {
		
	/**
	 * Shared data!
	 */
	private final BlockingQueue<IssueReceiptRequest> requests;
	private final ADSManager adsManager;
	private final PKIDirectory pki;
	private final ClientProvider rmi;
	
	public BVerifyServerConfirmAndApply(BlockingQueue<IssueReceiptRequest> requests, 
			ADSManager adsManager, PKIDirectory pki, ClientProvider rmi) {
		this.requests = requests;
		this.adsManager = adsManager;
		this.pki = pki;
		this.rmi = rmi;
	}

	@Override
	public void run() {
		try {
			while(true) {
				// block and wait for a request to be issued
				IssueReceiptRequest request = this.requests.take();
				Receipt receipt = request.getReceipt();
				Account warehouse = this.pki.getAccount(receipt.getWarehouseId());
				Account depositor = this.pki.getAccount(receipt.getDepositorId());
				List<Account> accounts = new ArrayList<>();
				accounts.add(warehouse);
				accounts.add(depositor);
				byte[] adsKey = CryptographicUtils.listOfAccountsToADSKey(accounts);
				AuthenticatedSetServer ads = this.adsManager.getADS(adsKey);
				Set<Receipt> adsData = this.adsManager.getADSData(adsKey);
				
				// insert the receipt into the ADS
				byte[] receiptWitness = CryptographicUtils.witnessReceipt(receipt);
				ads.insert(receiptWitness);
				adsData.add(receipt);
				byte[] newRoot = ads.commitment();
				
				// get the depositor to sign
				BVerifyProtocolClientAPI stub = this.rmi.getClient(depositor);
				byte[] signatureMsgBytes = stub.approveDeposit(receipt.toByteArray());
				
				Signature signatureDepositor = Signature.parseFrom(signatureMsgBytes);
				Signature signatureWarehouse = request.getSignature();
				
				// now check the signatures 
				boolean signedDepositor = CryptographicSignature.verify(newRoot, signatureDepositor.toByteArray(),
						depositor.getPublicKey());
				boolean signedWarehouse = CryptographicSignature.verify(newRoot, signatureWarehouse.toByteArray(),
						warehouse.getPublicKey());
				
				// if both have signed, update the authentication
				// and commit
				if(signedDepositor && signedWarehouse) {
					System.out.println("Updating ADS");
					this.adsManager.updateADS(adsKey, adsData, ads);
				}else {
					System.out.println("Update rejected");
				}
			}
		} catch(InterruptedException | RemoteException | InvalidProtocolBufferException e) {
			e.printStackTrace();
		}
	}
	
}
