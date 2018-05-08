package demo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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
import io.grpc.bverify.GetForwardedRequest;
import io.grpc.bverify.GetForwardedResponse;
import io.grpc.bverify.IssueReceiptRequest;
import io.grpc.bverify.PathRequest;
import io.grpc.bverify.PathResponse;
import io.grpc.bverify.Receipt;
import io.grpc.bverify.SubmitRequest;
import io.grpc.bverify.SubmitResponse;
import mpt.core.InsufficientAuthenticationDataException;
import mpt.core.InvalidSerializationException;
import mpt.core.Utils;
import mpt.dictionary.MPTDictionaryPartial;
import mpt.set.AuthenticatedSetServer;
import mpt.set.MPTSetFull;
import pki.Account;
import pki.PKIDirectory;
import server.BVerifyServer;


public class MockDepositor implements Runnable {
	private static final Logger logger = Logger.getLogger(BVerifyServer.class.getName());


	private final Account account;
	
	// data 
	private final byte[] adsKey;
	private final Set<Receipt> adsData;
	private final AuthenticatedSetServer ads;
	
	// witnessing 
	private byte[] currentCommitment;
	private int currentCommitmentNumber;
	
	// gRPC
	private final ManagedChannel channel;
	private final BVerifyServerAPIBlockingStub blockingStub;

	
	public MockDepositor(Account a, String host, int port) {
		logger.log(Level.INFO, "...loading mock depositor connected to server on host: "+host+" port: "+port);
	   
		this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
	    this.blockingStub = BVerifyServerAPIGrpc.newBlockingStub(channel);

	    this.account = a;
		this.ads = new MPTSetFull();
		this.adsData = new HashSet<>();
		assert a.getADSKeys().size() == 1;
		this.adsKey = a.getADSKeys().iterator().next();	
		
		logger.log(Level.INFO, "...loading mock depositor "+a.getFirstName());
		logger.log(Level.INFO, "...cares about ads: "+Utils.byteArrayAsHexString(this.adsKey));
		
		logger.log(Level.INFO, "...getting commitments from server");
		List<byte[]> commitments = this.getCommitments();
		this.currentCommitmentNumber = commitments.size()-1;
		this.currentCommitment = commitments.get(this.currentCommitmentNumber);
		
		logger.log(Level.INFO, "...current commitment: #"+this.currentCommitmentNumber+" - "+
				Utils.byteArrayAsHexString(this.currentCommitment));
		
		logger.log(Level.INFO, "...asking for data from the server");
		List<Receipt> receipts = this.getDataRequest(this.adsKey, this.currentCommitmentNumber);
		for(Receipt r : receipts) {
			logger.log(Level.INFO, "...adding receipt: "+r);
			this.adsData.add(r);
			byte[] receiptWitness = CryptographicUtils.witnessReceipt(r);
			this.ads.insert(receiptWitness);
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
		logger.log(Level.INFO, "...polling server for forwarded requests");
		IssueReceiptRequest request = this.getForwarded();
		if(request != null) {
			logger.log(Level.INFO, "...request recieved, approving");
			IssueReceiptRequest approvedRequest = this.approveRequestAndApply(request);
			logger.log(Level.INFO, "...submitting approved request to server");
			this.submitApprovedRequest(approvedRequest);
		}
		logger.log(Level.INFO, "...polling sever for new commitments");
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
	
	public void shutdown() throws InterruptedException {
	    this.channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
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
	
	private IssueReceiptRequest getForwarded() {
		GetForwardedRequest request = GetForwardedRequest.newBuilder()
				.setId(this.account.getIdAsString())
				.build();
		GetForwardedResponse response = this.blockingStub.getForwarded(request);
		if(response.hasRequest()) {
			return response.getRequest();
		}
		return null;
	}
	
	private IssueReceiptRequest approveRequestAndApply(IssueReceiptRequest request) {
		logger.log(Level.INFO, "...approving request: "+request);
		Receipt r = request.getReceipt();
		this.adsData.add(r);
		byte[] witness = CryptographicUtils.witnessReceipt(r);
		this.ads.insert(witness);
		byte[] newRoot = this.ads.commitment();
		logger.log(Level.INFO, "...NEW ADS ROOT: "+Utils.byteArrayAsHexString(newRoot));
		byte[] sig = CryptographicSignature.sign(newRoot, this.account.getPrivateKey());
		return request.toBuilder().setSignatureDepositor(ByteString.copyFrom(sig)).build();
	}
	
	private boolean submitApprovedRequest(IssueReceiptRequest request) {
		logger.log(Level.INFO, "...submitting request to server: "+request);
		assert request.getSignatureDepositor().toByteArray() != null;
		assert request.getSignatureWarehouse().toByteArray() != null;
		SubmitRequest requestToSend = SubmitRequest.newBuilder()
				.setRequest(request)
				.build();
		SubmitResponse response = this.blockingStub.submit(requestToSend);
		boolean accepted = response.getAccepted();
		logger.log(Level.INFO,"...accepted? - "+accepted);
		return accepted;
	}
	
	private boolean checkCommitment(final byte[] commitment, final int commitmentNumber) {
		logger.log(Level.INFO, "...checking commtiment : #"+commitmentNumber+
				" | "+Utils.byteArrayAsHexString(commitment));
		logger.log(Level.INFO, "...asking for proof from the server");
		MPTDictionaryPartial mpt = this.getPath(Arrays.asList(this.adsKey), commitmentNumber);
		logger.log(Level.INFO, "...checking proof");
		// check that the auth proof is correct
		try {
			logger.log(Level.INFO, "...checking that mapping is correct");
			if(!Arrays.equals(mpt.get(this.adsKey), this.ads.commitment())){
				logger.log(Level.WARNING, "...MAPPING DOES NOT MATCH");
				System.err.println("MAPPING DOES NOT MATCH");
				return false;
			}
			logger.log(Level.INFO, "...checking that commitment matches");
			System.out.println(Utils.byteArrayAsHexString(mpt.commitment()));
			System.out.println(Utils.byteArrayAsHexString(commitment));
			if(!Arrays.equals(commitment, mpt.commitment())) {
				logger.log(Level.WARNING, "...COMMITMENT DOES NOT MATCH");
				System.err.println("COMMITMENT DOES NOT MATCH");
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
		String base = System.getProperty("user.dir")  + "/demos/";
		PKIDirectory pki = new PKIDirectory(base+"pki/");
		String host = "127.0.0.1";
		int port = 50051;
		/**
		 * Alice: 7795ad85-9a9e-47a4-b7fc-4a58c8697d21
		 * Bob: b132bbfa-98bc-4e5d-b32d-f78d603600f5
		 * Warehouse: 495ead33-b08d-4a47-adf0-b4664043f762
		 */
		Account alice = pki.getAccount("7795ad85-9a9e-47a4-b7fc-4a58c8697d21");
		MockDepositor aliceClient = new MockDepositor(alice, host, port);
		
		// create a thread that polls the server and automatically approves any requests
		ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
		exec.scheduleAtFixedRate(aliceClient, 0, 5, TimeUnit.SECONDS);
		Scanner sc = new Scanner(System.in);
		sc.nextLine();
		System.out.println("Press enter to shutdown");
		sc.close();
		System.out.println("shutdown");
		try {
			aliceClient.shutdown();
			exec.shutdown();
		} catch (InterruptedException e) {
			e.printStackTrace();
			throw new RuntimeException("something went wrong trying to shutdown");
		}
	}
	
	
}
