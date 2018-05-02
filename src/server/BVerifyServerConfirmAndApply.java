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
import mpt.core.Utils;
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
				System.out.println("---------------Processing updated--------------");
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
				System.out.println("NEW ADS ROOT: "+Utils.byteArrayAsHexString(newRoot));
				
				// get the depositor to sign
				System.out.println("---------------Asking depositor to sign------------");
				BVerifyProtocolClientAPI depositorStub = this.rmi.getClient(depositor);
				byte[] signatureMsgBytes = depositorStub.approveDeposit(request.toByteArray());
				
				Signature signatureDepositor = Signature.parseFrom(signatureMsgBytes);
				Signature signatureWarehouse = request.getSignature();
				
				// now check the signatures 
				boolean signedDepositor = CryptographicSignature.verify(newRoot, 
						signatureDepositor.getSignature().toByteArray(),
						depositor.getPublicKey());
				boolean signedWarehouse = CryptographicSignature.verify(newRoot, 
						signatureWarehouse.getSignature().toByteArray(),
						warehouse.getPublicKey());
				
				// if both have signed, update the authentication
				// and commit
				if(signedDepositor && signedWarehouse) {
					this.adsManager.updateADS(adsKey, adsData, ads);
					// committing!
					byte[] newCommitment = this.adsManager.commit();
					System.out.println("Update ADS and Commiting! - NEW COMMITMENT: "+
							Utils.byteArrayAsHexString(newCommitment));
					// message both clients about the new commitment
					BVerifyProtocolClientAPI warehouseStub = this.rmi.getClient(warehouse);
					warehouseStub.addNewCommitment(newCommitment);
					depositorStub.addNewCommitment(newCommitment);
				}else {
					System.out.println("Update rejected");
					System.out.println("signed depositor: "+signedDepositor);
					System.out.println("signed warehouse: "+signedWarehouse);
				}
			}
		} catch(InterruptedException | RemoteException | InvalidProtocolBufferException e) {
			e.printStackTrace();
		}
	}
	
}
