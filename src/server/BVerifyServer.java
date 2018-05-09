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
import io.grpc.bverify.TransferReceiptRequest;
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
		private final Map<String, io.grpc.bverify.GetForwardedResponse> approvalRequests;
		private static final Logger logger = Logger.getLogger(BVerifyServerImpl.class.getName());

		
		public BVerifyServerImpl(final PKIDirectory pki, final ADSManager ads) {
			this.pki = pki;
			this.adsManager = ads;
			this.approvalRequests = new HashMap<>();
		}
		
		public void forward(io.grpc.bverify.ForwardRequest request,
		        io.grpc.stub.StreamObserver<io.grpc.bverify.ForwardResponse> responseObserver) {
			logger.log(Level.INFO, "ForwardRequest("+request.getRequestCase()+" to ", request.getForwardToId()+")");
			// forward the request to the depositor
			io.grpc.bverify.GetForwardedResponse toForward = null;
			switch(request.getRequestCase()){
			case ISSUE_RECEIPT:
				toForward = io.grpc.bverify.GetForwardedResponse.newBuilder()
				.setIssueReceipt(request.getIssueReceipt())
				.build();
				break;
			case TRANSFER_RECEIPT:
				toForward = io.grpc.bverify.GetForwardedResponse.newBuilder()
				.setTransferReceipt(request.getTransferReceipt())
				.build();
			}
			if(toForward != null) {
				synchronized(this){
					this.approvalRequests.put(request.getForwardToId(), toForward);
				}
				responseObserver.onNext(io.grpc.bverify.ForwardResponse.newBuilder()
					.setAdded(true)
					.build());
			}else {
				responseObserver.onNext(io.grpc.bverify.ForwardResponse.newBuilder()
						.setAdded(false)
						.build());
			}
			responseObserver.onCompleted();
		}

		@Override
		public void getForwarded(io.grpc.bverify.GetForwardedRequest request,
		        io.grpc.stub.StreamObserver<io.grpc.bverify.GetForwardedResponse> responseObserver) {
			String id = request.getId();
			logger.log(Level.INFO, "GetForwardedRequests("+id+")");
			// lookup requests to forward
			io.grpc.bverify.GetForwardedResponse response = null;
			synchronized(this) {
				response = this.approvalRequests.get(id);
				this.approvalRequests.remove(id);
			}
			if(response == null) {
				response = io.grpc.bverify.GetForwardedResponse.newBuilder()
						.build();
			}
			responseObserver.onNext(response);
			responseObserver.onCompleted();
		}

		@Override
	    public void submit(io.grpc.bverify.SubmitRequest request,
	            io.grpc.stub.StreamObserver<io.grpc.bverify.SubmitResponse> responseObserver) {
			boolean accepted = false;
			switch(request.getRequestCase()) {
			case ISSUE_RECEIPT:
				accepted = this.submitIssueRequest(request.getIssueReceipt());
				break;
			case TRANSFER_RECEIPT:
				accepted = this.submitTransferRequest(request.getTransferReceipt());
			}
			io.grpc.bverify.SubmitResponse response = io.grpc.bverify.SubmitResponse.newBuilder()
					.setAccepted(accepted)
					.build();
			responseObserver.onNext(response);
			responseObserver.onCompleted();
		}
		
		private boolean submitTransferRequest(TransferReceiptRequest request) {
			Receipt receipt = request.getReceipt();
			Account warehouse = this.pki.getAccount(receipt.getWarehouseId());
			Account currentOwner = this.pki.getAccount(request.getCurrentOwnerId());
			Account newOwner = this.pki.getAccount(request.getNewOwnerId());
			logger.log(Level.INFO, "TransferReceiptRequest("+receipt+" from "+currentOwner+" --> "+newOwner+")");
			
			List<Account> currentOwnerADSAccounts = Arrays.asList(warehouse, currentOwner);
			byte[] currentOwnerADSId = CryptographicUtils.listOfAccountsToADSKey(currentOwnerADSAccounts);
			AuthenticatedSetServer currentOwnerADS = this.adsManager.getADS(currentOwnerADSId);
			Set<Receipt> currentOwnerData = this.adsManager.getADSData(currentOwnerADSId);
			
			List<Account> newOwnerADSAccounts = Arrays.asList(warehouse, newOwner);
			byte[] newOwnerADSId = CryptographicUtils.listOfAccountsToADSKey(newOwnerADSAccounts);
			AuthenticatedSetServer newOwnerADS = this.adsManager.getADS(newOwnerADSId);
			Set<Receipt> newOwnerData = this.adsManager.getADSData(newOwnerADSId);
			
			byte[] receiptWitness = CryptographicUtils.witnessReceipt(receipt);

			currentOwnerData.remove(receipt);
			currentOwnerADS.delete(receiptWitness);
			byte[] currentOwnerNewCmt = currentOwnerADS.commitment();
			
			boolean signedWarehouseCurrent = CryptographicSignature.verify(currentOwnerNewCmt, 
					request.getSignatureWarehouseCurrent().toByteArray(),
					warehouse.getPublicKey());
			boolean signedCurrentOwner = CryptographicSignature.verify(currentOwnerNewCmt, 
					request.getSignatureCurrentOwner().toByteArray(),
					currentOwner.getPublicKey());
	
			newOwnerData.add(receipt);
			newOwnerADS.insert(receiptWitness);
			byte[] newOwnerCmt = newOwnerADS.commitment();
			
			boolean signedWarehouseNew = CryptographicSignature.verify(newOwnerCmt, 
					request.getSignatureWarehouseNew().toByteArray(),
					warehouse.getPublicKey());
			boolean signedNewOwner = CryptographicSignature.verify(newOwnerCmt, 
					request.getSignatureNewOwner().toByteArray(),
					newOwner.getPublicKey());
			
			if(signedWarehouseCurrent && signedCurrentOwner && signedWarehouseNew && signedNewOwner) {
				logger.log(Level.INFO, "Update Accepted! : "
						+Utils.byteArrayAsHexString(currentOwnerADSId)+"->"+
						Utils.byteArrayAsHexString(currentOwnerNewCmt) + "\n"+
						Utils.byteArrayAsHexString(newOwnerADSId)+"->"+
						Utils.byteArrayAsHexString(newOwnerCmt));
				
				this.adsManager.updateADS(currentOwnerADSId, currentOwnerData, currentOwnerADS);
				this.adsManager.updateADS(newOwnerADSId, newOwnerData, newOwnerADS);
				// committing!
				byte[] newCommitment = this.adsManager.commit();
				logger.log(Level.INFO, "NEW COMMITMENT: "+
						Utils.byteArrayAsHexString(newCommitment));
				return true;
			}
			logger.log(Level.INFO, "Update rejected - signed current owner: "+
					signedCurrentOwner+"|signed new owner: "+signedNewOwner+"|signed warehouse:"+
					signedWarehouseCurrent+" "+signedWarehouseNew);
			return false;
		}
		
		private boolean submitIssueRequest(IssueReceiptRequest request) {
			Receipt receipt = request.getReceipt();
			logger.log(Level.INFO, "IssueReceiptRequest("+receipt+")");
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
					request.getSignatureWarehouse().toByteArray(),
					warehouse.getPublicKey());
			boolean signedDepositor = CryptographicSignature.verify(newRoot, 
					request.getSignatureDepositor().toByteArray(),
					depositor.getPublicKey());
			
			// if both have signed, update the authentication
			// and commit
			if(signedDepositor && signedWarehouse) {
				logger.log(Level.INFO, "Update Accepted! : "
						+Utils.byteArrayAsHexString(adsKey)+"->"+
						Utils.byteArrayAsHexString(newRoot));
				this.adsManager.updateADS(adsKey, adsData, ads);
				// committing!
				byte[] newCommitment = this.adsManager.commit();
				logger.log(Level.INFO, "NEW COMMITMENT: "+
						Utils.byteArrayAsHexString(newCommitment));
				return true;
			}
			logger.log(Level.INFO, "Update rejected - signed depositor: "+
						signedDepositor+"|signed warehouse: "+signedWarehouse);
			return false;
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
