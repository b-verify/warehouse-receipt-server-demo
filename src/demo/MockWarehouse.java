package demo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.google.protobuf.ByteString;

import crpyto.CryptographicSignature;
import crpyto.CryptographicUtils;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.bverify.BVerifyServerAPIGrpc;
import io.grpc.bverify.BVerifyServerAPIGrpc.BVerifyServerAPIBlockingStub;
import io.grpc.bverify.CommitmentsRequest;
import io.grpc.bverify.CommitmentsResponse;
import io.grpc.bverify.DataRequest;
import io.grpc.bverify.DataResponse;
import io.grpc.bverify.ForwardRequest;
import io.grpc.bverify.GetForwardedRequest;
import io.grpc.bverify.GetForwardedResponse;
import io.grpc.bverify.IssueReceiptRequest;
import io.grpc.bverify.PathRequest;
import io.grpc.bverify.PathResponse;
import io.grpc.bverify.Receipt;
import io.grpc.bverify.TransferReceiptRequest;
import mpt.core.InsufficientAuthenticationDataException;
import mpt.core.InvalidSerializationException;
import mpt.core.Utils;
import mpt.dictionary.MPTDictionaryPartial;
import mpt.set.AuthenticatedSetServer;
import mpt.set.MPTSetFull;
import pki.Account;
import pki.PKIDirectory;

public class MockWarehouse implements Runnable {
	private static final Logger logger = Logger.getLogger(MockWarehouse.class.getName());

	private final Account account;
	
	private final Map<String, Account> depositors;
	
	// data
	private final Map<String, byte[]> adsStringToKey;
	private final Map<String, AuthenticatedSetServer> adsKeyToADS;
	private final Map<String, Set<Receipt>> adsKeyToADSData;
	
	// witnessing 
	private byte[] currentCommitment;
	private int currentCommitmentNumber;
	
	// gRPC
	private final ManagedChannel channel;
	private final BVerifyServerAPIBlockingStub blockingStub;
	
	public MockWarehouse(Account thisWarehouse, 
			List<Account> deps, String host, int port) {
		logger.log(Level.INFO, "...loading mock warehouse connected to server on host: "+host+" port: "+port);

		this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
	    this.blockingStub = BVerifyServerAPIGrpc.newBlockingStub(channel);

	    
		this.account = thisWarehouse;
		logger.log(Level.INFO, "...loading mock warehouse "+thisWarehouse.getFirstName());
		logger.log(Level.INFO, "...with clients: ");
		this.depositors = new HashMap<>();
		for(Account a : deps) {
			logger.log(Level.INFO, "..."+a.getFirstName());
			this.depositors.put(a.getIdAsString(), a);
		}
		assert this.account.getADSKeys().size() == this.depositors.size();
		logger.log(Level.INFO, "...cares about adses: "+
				this.account.getADSKeys().stream().map(x -> 
					Utils.byteArrayAsHexString(x)).collect(Collectors.toList()));

		logger.log(Level.INFO, "...getting commitments from server");
		List<byte[]> commitments = this.getCommitments();
		this.currentCommitmentNumber = commitments.size()-1;
		this.currentCommitment = commitments.get(this.currentCommitmentNumber);
		
		logger.log(Level.INFO, "...current commitment: #"+this.currentCommitmentNumber+" - "+
				Utils.byteArrayAsHexString(this.currentCommitment));
		
		this.adsStringToKey = new HashMap<>();
		this.adsKeyToADS = new HashMap<>();
		this.adsKeyToADSData = new HashMap<>();
		for(byte[] adsKey : this.account.getADSKeys()) {
			String adsKeyString = Utils.byteArrayAsHexString(adsKey);
			this.adsStringToKey.put(adsKeyString, adsKey);
			logger.log(Level.INFO, "...asking for data from the server for ads: "+adsKeyString);
			MPTSetFull ads = new MPTSetFull();
			Set<Receipt> adsData = new HashSet<>();
			List<Receipt> receipts = this.getDataRequest(adsKey, this.currentCommitmentNumber);
			for(Receipt r : receipts) {
				adsData.add(r);
				byte[] receiptWitness = CryptographicUtils.witnessReceipt(r);
				ads.insert(receiptWitness);
			}
			logger.log(Level.INFO, "...added "+adsData.size()+" receipts");
			this.adsKeyToADS.put(adsKeyString, ads);
			this.adsKeyToADSData.put(adsKeyString, adsData);
		}
		
		logger.log(Level.INFO, "...asking for a proof, checking latest commitment");
		this.checkCommitment(this.currentCommitment, this.currentCommitmentNumber);
		logger.log(Level.INFO, "...setup complete!");
	}
	
	/**
	 * Periodically the mock depositor polls the serve and approves any requests
	 */
	@Override
	public void run() {
		logger.log(Level.FINE, "...polling server for forwarded requests");
		GetForwardedResponse approvals = this.getForwarded();
		if(approvals.hasTransferReceipt()) {
			logger.log(Level.INFO, "...transfer request recieved");
			ForwardRequest forward = this.approveTransferRequestAndApply(approvals.getTransferReceipt());
			logger.log(Level.INFO, "...forwarding request to "+forward.getForwardToId());
			this.blockingStub.forward(forward);
		}
		logger.log(Level.FINE, "...polling sever for new commitments");
		List<byte[]> commitments  = this.getCommitments();
		// get the new commitments if any
		List<byte[]> newCommitments = commitments.subList(this.currentCommitmentNumber+1, commitments.size());
		if(newCommitments.size() > 0) {
			for(byte[] newCommitment : newCommitments) {
				int newCommitmentNumber = this.currentCommitmentNumber + 1;
				logger.log(Level.INFO, "...new commitment found asking for proof");
				boolean result = this.checkCommitment(newCommitment, newCommitmentNumber);
				this.currentCommitmentNumber = newCommitmentNumber;
				this.currentCommitment = newCommitment;
			}
		}
	}
	
	private ForwardRequest approveTransferRequestAndApply(TransferReceiptRequest request) {
		Receipt receipt = request.getReceipt();
		Account currentOwner = this.depositors.get(request.getCurrentOwnerId());
		Account newOwner = this.depositors.get(request.getNewOwnerId());
		logger.log(Level.INFO, "... transfering "+receipt+" from "+currentOwner+" -> "+newOwner);
		
		List<Account> currentOwnerADSAccounts = Arrays.asList(this.account, currentOwner);
		String currentOwnerADSId = Utils.byteArrayAsHexString(
				CryptographicUtils.listOfAccountsToADSKey(currentOwnerADSAccounts));
		AuthenticatedSetServer currentOwnerADS = this.adsKeyToADS.get(currentOwnerADSId);
		Set<Receipt> currentOwnerData = this.adsKeyToADSData.get(currentOwnerADSId);
		
		List<Account> newOwnerADSAccounts = Arrays.asList(this.account, newOwner);
		String newOwnerADSId = Utils.byteArrayAsHexString(
				CryptographicUtils.listOfAccountsToADSKey(newOwnerADSAccounts));
		AuthenticatedSetServer newOwnerADS = this.adsKeyToADS.get(newOwnerADSId);
		Set<Receipt> newOwnerData = this.adsKeyToADSData.get(newOwnerADSId);
		
		byte[] receiptWitness = CryptographicUtils.witnessReceipt(receipt);

		currentOwnerData.remove(receipt);
		currentOwnerADS.delete(receiptWitness);
		byte[] currentOwnerNewCmt = currentOwnerADS.commitment();
		byte[] signatureCurrent = CryptographicSignature.sign(currentOwnerNewCmt, this.account.getPrivateKey());
		logger.log(Level.INFO, "... current owner ADS "+currentOwnerADSId+ 
				" NEW ROOT: "+Utils.byteArrayAsHexString(currentOwnerNewCmt));
		
		newOwnerData.add(receipt);
		newOwnerADS.insert(receiptWitness);
		byte[] newOwnerCmt = newOwnerADS.commitment();
		byte[] signatureNew = CryptographicSignature.sign(newOwnerCmt, this.account.getPrivateKey());
		logger.log(Level.INFO, "... new owner ADS "+newOwnerADSId+ 
				" NEW ROOT: "+Utils.byteArrayAsHexString(newOwnerCmt));
		
		request = request.toBuilder().setSignatureWarehouseCurrent(ByteString.copyFrom(signatureCurrent))
		.setSignatureWarehouseNew(ByteString.copyFrom(signatureNew)).build();
		ForwardRequest forward = ForwardRequest.newBuilder()
				.setForwardToId(request.getNewOwnerId())
				.setTransferReceipt(request)
				.build();
		return forward;
	}
	
	public void shutdown() throws InterruptedException {
	    this.channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
	}
	
	public void deposit(Account depositor) {
		this.deposit(BootstrapMockSetup.generateReceipt(this.account, depositor), depositor);
	}
	
	public void deposit(Receipt r, Account depositor) {
		logger.log(Level.INFO, "...depositing receipt: "+r+" to "+depositor.getFirstName());
		byte[] adsId = CryptographicUtils.listOfAccountsToADSKey(Arrays.asList(this.account, depositor));
		String adsIdString = Utils.byteArrayAsHexString(adsId);
		if(!this.adsKeyToADS.containsKey(adsIdString)) {
			throw new RuntimeException("not a valid depositor");
		}
		AuthenticatedSetServer ads = this.adsKeyToADS.get(adsIdString);
		Set<Receipt> adsData = this.adsKeyToADSData.get(adsIdString);
		adsData.add(r);
		byte[] receiptWitness = CryptographicUtils.witnessReceipt(r);
		ads.insert(receiptWitness);
		byte[] newRoot = ads.commitment();
		logger.log(Level.INFO, "...new ads root: "+Utils.byteArrayAsHexString(newRoot));
		byte[] signature = CryptographicSignature.sign(newRoot, this.account.getPrivateKey());
		
		IssueReceiptRequest request = IssueReceiptRequest.newBuilder()
				.setReceipt(r)
				.setSignatureWarehouse(ByteString.copyFrom(signature))
				.build();
		
		ForwardRequest requestToForward = ForwardRequest.newBuilder()
				.setIssueReceipt(request)
				.setForwardToId(depositor.getIdAsString())
				.build();
		logger.log(Level.INFO, "...forwarding request to client via server");
		this.blockingStub.forward(requestToForward);
	}

	private GetForwardedResponse getForwarded() {
		GetForwardedRequest request = GetForwardedRequest.newBuilder()
				.setId(this.account.getIdAsString())
				.build();
		return this.blockingStub.getForwarded(request);
	}
	
	private List<byte[]> getCommitments() {
		CommitmentsRequest request = CommitmentsRequest.newBuilder().build();
		CommitmentsResponse response = this.blockingStub.getCommitments(request);
		return response.getCommitmentsList().stream().map(x -> x.toByteArray()).collect(Collectors.toList());
	}
	
	
	private List<Receipt> getDataRequest(byte[] adsId, int commitmentNumber){
		DataRequest request = DataRequest.newBuilder()
				.setAdsId(ByteString.copyFrom(adsId))
				.setCommitmentNumber(commitmentNumber)
				.build();
		DataResponse response = this.blockingStub.getDataRequest(request);
		return response.getReceiptsList();
		
	}
	
	private MPTDictionaryPartial getPath(List<byte[]> adsIds, int commitment) {
		PathRequest request = PathRequest.newBuilder()
				.setCommitmentNumber(commitment)
				.addAllAdsIds(adsIds.stream().map(x -> ByteString.copyFrom(x)).collect(Collectors.toList()))
				.build();
		PathResponse response = this.blockingStub.getAuthPath(request);
		MPTDictionaryPartial res;
		try {
			res = MPTDictionaryPartial.deserialize(response.getPath());
		} catch (InvalidSerializationException e) {
			e.printStackTrace();
			throw new RuntimeException("MPT cannot be deserialized");
		}
		return res;
	}
	
	
	private boolean checkCommitment(byte[] commitment, int commitmentNumber) {
		logger.log(Level.INFO, "...checking commtiment : #"+commitmentNumber+
				" | "+Utils.byteArrayAsHexString(commitment));
		logger.log(Level.INFO, "...asking for proof from the server");
		List<byte[]> adsIds = this.adsStringToKey.values().stream().collect(Collectors.toList());
		MPTDictionaryPartial mpt = this.getPath(adsIds, commitmentNumber);
		logger.log(Level.INFO, "...checking proof");
		// check that the auth proof is correct
		try {
			for(Map.Entry<String, byte[]> kv : this.adsStringToKey.entrySet()) {
				String adsIdAsString = kv.getKey();
				byte[] adsId = kv.getValue();
				byte[] cmt = this.adsKeyToADS.get(adsIdAsString).commitment();
				logger.log(Level.INFO, "...checking "+adsIdAsString+" -> "+
						Utils.byteArrayAsHexString(cmt));
				if(!Arrays.equals(mpt.get(adsId), cmt)){
					logger.log(Level.WARNING, "...MAPPING DOES NOT MATCH");
					System.err.println("MAPPING DOES NOT MATCH");
					return false;
				}
			}
			logger.log(Level.INFO, "...checking that commitment matches");
			if(!Arrays.equals(commitment, mpt.commitment())) {
				logger.log(Level.WARNING, "...COMMITMENT DOES NOT MATCH");
				System.err.println("COMMITMENT DOES NOT MATCH");
				return false;
			}
			logger.log(Level.INFO, "...commitment accepted");
			return true;
		} catch (InsufficientAuthenticationDataException e) {
			e.printStackTrace();
			System.err.println("Error!");
			throw new RuntimeException("bad proof!");
		}
	}

	
	public static void main(String[] args) {
		if(args.length != 1) {
			System.out.println("Usage: <host> \n of b_verify server");
		}		
		String base = System.getProperty("user.dir")  + "/demos/";
		PKIDirectory pki = new PKIDirectory(base+"pki/");
		String host = args[0];
		int port = 50051;
		/**
		 * Alice: 7795ad85-9a9e-47a4-b7fc-4a58c8697d21
		 * Bob: 495ead33-b08d-4a47-adf0-b4664043f762
		 * Warehouse: 86ab72e2-f404-4549-babb-ad332b85f07a
		 */
		for(Account a : pki.getAllAccounts()) {
			System.out.println(a.getFirstName() + a.getIdAsString());
		}
		Account alice = pki.getAccount("7795ad85-9a9e-47a4-b7fc-4a58c8697d21");
		Account bob = pki.getAccount("495ead33-b08d-4a47-adf0-b4664043f762");
		Account warehouse = pki.getAccount("86ab72e2-f404-4549-babb-ad332b85f07a");
		
		List<Account> depositors = new ArrayList<>();
		depositors.add(alice);
		depositors.add(bob);
		MockWarehouse warehouseClient = new MockWarehouse(warehouse, depositors, host, port);
		
		ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
		exec.scheduleAtFixedRate(warehouseClient, 0, 5, TimeUnit.SECONDS);
		
		try {
			Thread.sleep(10*1000);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		Scanner sc = new Scanner(System.in);
		while(true) {
			System.out.println("[Press enter to issue receipt to ALICE]");
			sc.nextLine();
			warehouseClient.deposit(alice);
		}
	}
	
	
}

