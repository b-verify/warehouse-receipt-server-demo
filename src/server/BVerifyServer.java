package server;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.google.protobuf.ByteString;

import crpyto.CryptographicSignature;
import crpyto.CryptographicUtils;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.bverify.BVerifyServerAPIGrpc.BVerifyServerAPIImplBase;
import io.grpc.bverify.CommitmentsResponse;
import io.grpc.bverify.IssueReceiptRequest;
import io.grpc.bverify.Receipt;
import mpt.core.Utils;
import mpt.set.AuthenticatedSetServer;
import pki.Account;
import pki.PKIDirectory;
import serialization.generated.MptSerialization.MerklePrefixTrie;

public class BVerifyServer {
	private static final Logger logger = Logger.getLogger(BVerifyServer.class.getName());
	private Server server;
	/*
	 * Public Key Infrastructure - for identifying clients. For now this is mocked,
	 * but there are a variety of different possible ways to implement this.
	 */
	private final PKIDirectory pki;
	/*
	 * ADS Manager used to update the authentication information stored on the
	 * server.
	 */
	private final ADSManager adsManager;
	

	private void start() throws IOException {
		/* The port on which the server should run */
		int port = 50051;
		server = ServerBuilder.forPort(port).addService(
				new BVerifyServerImpl(this.pki, this.adsManager)).build().start();
		logger.info("...server started, listening on " + port);
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				// Use stderr here since the logger may have been reset by its JVM shutdown
				// hook.
				System.err.println("*** shutting down gRPC server since JVM is shutting down");
				BVerifyServer.this.stop();
				System.err.println("*** server shut down");
			}
		});
	}

	private void stop() {
		if (server != null) {
			server.shutdown();
		}
	}

	/**
	 * Await termination on the main thread since the grpc library uses daemon
	 * threads.
	 */
	private void blockUntilShutdown() throws InterruptedException {
		if (server != null) {
			server.awaitTermination();
		}
	}
	

	public BVerifyServer(String base) {
		this.pki = new PKIDirectory(base + "pki/");
		logger.log(Level.INFO, "...pki loaded");

		// setup the components
		this.adsManager = new ADSManager(base, this.pki);
		logger.log(Level.INFO, "...adses loaded");

		// do an initial commitment
		logger.log(Level.INFO, "...doing initial commit");
		this.adsManager.commit();
		

	}
	
	/**
	 * Main launches the server from the command line.
	 */
	public static void main(String[] args) throws IOException, InterruptedException {
		String base = System.getProperty("user.dir") + "/demos/";
		final BVerifyServer server = new BVerifyServer(base);
		server.start();
		server.blockUntilShutdown();
	}


	private static class BVerifyServerImpl extends BVerifyServerAPIImplBase {
		private final PKIDirectory pki;
		private final ADSManager adsManager;
		// keep track of requests to forward to clients
		private final Map<String, IssueReceiptRequest> approvalRequests;
		private static final Logger logger = Logger.getLogger(BVerifyServerImpl.class.getName());

		
		public BVerifyServerImpl(final PKIDirectory pki, final ADSManager ads) {
			this.pki = pki;
			this.adsManager = ads;
			this.approvalRequests = new HashMap<>();
		}
		
		public void forward(io.grpc.bverify.ForwardRequest request,
		        io.grpc.stub.StreamObserver<io.grpc.bverify.ForwardResponse> responseObserver) {
			logger.log(Level.INFO, "ForwardRequest("+request.getRequest()+" to ", request.getForwardToId()+")");
			// forward the request to the depositor
			synchronized(this){
				this.approvalRequests.put(request.getForwardToId(), request.getRequest());

			}
			// add a response
			io.grpc.bverify.ForwardResponse res =  io.grpc.bverify.ForwardResponse.newBuilder()
					.setAdded(true)
					.build();
			responseObserver.onNext(res);
			responseObserver.onCompleted();
		}

		@Override
		public void getForwarded(io.grpc.bverify.GetForwardedRequest request,
		        io.grpc.stub.StreamObserver<io.grpc.bverify.GetForwardedResponse> responseObserver) {
			String id = request.getId();
			logger.log(Level.INFO, "GetForwardedRequests("+id+")");
			IssueReceiptRequest requestMsg;
			// lookup requests to forward
			synchronized(this) {
				requestMsg = this.approvalRequests.get(id);
			}
			io.grpc.bverify.GetForwardedResponse response;
			if(requestMsg != null) {
				response = io.grpc.bverify.GetForwardedResponse.newBuilder()
						.setRequest(requestMsg)
						.build();
			}else {
				response = io.grpc.bverify.GetForwardedResponse.newBuilder()
						.build();
			}
			responseObserver.onNext(response);
			responseObserver.onCompleted();
		}

		@Override
	    public void submit(io.grpc.bverify.SubmitRequest request,
	            io.grpc.stub.StreamObserver<io.grpc.bverify.SubmitResponse> responseObserver) {
			Receipt receipt = request.getRequest().getReceipt();
			logger.log(Level.INFO, "Submit("+receipt+")");
			Account warehouse = this.pki.getAccount(receipt.getWarehouseId());
			Account depositor = this.pki.getAccount(receipt.getDepositorId());
			List<Account> accounts = Arrays.asList(warehouse, depositor);
			byte[] adsKey = CryptographicUtils.listOfAccountsToADSKey(accounts);
			AuthenticatedSetServer ads = this.adsManager.getADS(adsKey);
			Set<Receipt> adsData = this.adsManager.getADSData(adsKey);
			
			// insert the receipt into the ADS
			byte[] receiptWitness = CryptographicUtils.witnessReceipt(receipt);
			ads.insert(receiptWitness);
			adsData.add(receipt);
			byte[] newRoot = ads.commitment();
			
			boolean signedWarehouse = CryptographicSignature.verify(newRoot, 
					request.getRequest().getSignatureWarehouse().toByteArray(),
					warehouse.getPublicKey());
			boolean signedDepositor = CryptographicSignature.verify(newRoot, 
					request.getRequest().getSignatureDepositor().toByteArray(),
					depositor.getPublicKey());
			
			// if both have signed, update the authentication
			// and commit
			if(signedDepositor && signedWarehouse) {
				logger.log(Level.INFO, "Update Accepted!");
				this.adsManager.updateADS(adsKey, adsData, ads);
				// committing!
				byte[] newCommitment = this.adsManager.commit();
				logger.log(Level.INFO, "NEW COMMITMENT: "+
						Utils.byteArrayAsHexString(newCommitment));
				io.grpc.bverify.SubmitResponse response = io.grpc.bverify.SubmitResponse.newBuilder()
						.setAccepted(true)
						.build();
				responseObserver.onNext(response);
			}else {
				logger.log(Level.INFO, "Update rejected - signed depositor: "+
							signedDepositor+"|signed warehouse: "+signedWarehouse);
				io.grpc.bverify.SubmitResponse response = io.grpc.bverify.SubmitResponse.newBuilder()
						.setAccepted(false)
						.build();
				responseObserver.onNext(response);
			}
			responseObserver.onCompleted();
		}

		@Override
		public void getDataRequest(io.grpc.bverify.DataRequest request,
				io.grpc.stub.StreamObserver<io.grpc.bverify.DataResponse> responseObserver) {
			logger.log(Level.INFO, "GetDataRequest("+Utils.byteArrayAsHexString(request.getAdsId().toByteArray())+
					", "+request.getCommitmentNumber()+")");
			Set<io.grpc.bverify.Receipt> adsData = this.adsManager.getADSData(request.getAdsId().toByteArray(),
					request.getCommitmentNumber());
			io.grpc.bverify.DataResponse response = io.grpc.bverify.DataResponse.newBuilder()
					.addAllReceipts(adsData)
					.build();
			responseObserver.onNext(response);
			responseObserver.onCompleted();

		}

		@Override
		public void getAuthPath(io.grpc.bverify.PathRequest request,
				io.grpc.stub.StreamObserver<io.grpc.bverify.PathResponse> responseObserver) {
			List<byte[]> keys = request.getAdsIdsList().stream().map(x -> x.toByteArray()).collect(Collectors.toList());
			List<String> keyStrings  = keys.stream().map(x -> Utils.byteArrayAsHexString(x)).collect(Collectors.toList());
			logger.log(Level.INFO, "GetAuthPath("+keyStrings+", "+request.getCommitmentNumber()+")");
			MerklePrefixTrie proof = this.adsManager.getProof(keys, request.getCommitmentNumber());
			io.grpc.bverify.PathResponse response = io.grpc.bverify.PathResponse.newBuilder()
					.setPath(proof)
					.build();
			responseObserver.onNext(response);
			responseObserver.onCompleted();
		}
		
		@Override
		public void getCommitments(io.grpc.bverify.CommitmentsRequest request,
		        io.grpc.stub.StreamObserver<io.grpc.bverify.CommitmentsResponse> responseObserver) {
			logger.log(Level.INFO, "GetCommitments()");
			CommitmentsResponse.Builder responseBuilder = CommitmentsResponse.newBuilder();
			int numberOfCommitments = this.adsManager.getCurrentCommitmentNumber();
			for(int i = 0; i <= numberOfCommitments; i++) {
				byte[] commitment = this.adsManager.getCommitment(i);
				responseBuilder.addCommitments(ByteString.copyFrom(commitment));
			}
			responseObserver.onNext(responseBuilder.build());
			responseObserver.onCompleted();
		}

	}

}
