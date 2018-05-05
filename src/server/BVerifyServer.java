package server;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.bverify.BVerifyServerAPIGrpc.BVerifyServerAPIImplBase;
import io.grpc.bverify.IssueReceiptRequest;
import pki.PKIDirectory;

public class BVerifyServer {
	private static final Logger logger = Logger.getLogger(BVerifyServer.class.getName());

	/*
	 * Public Key Infrastructure - for identifying clients. For now this is mocked,
	 * but there are a variety of different possible ways to implement this.
	 */
	private final PKIDirectory pki;

	/*
	 * ADS Manager used to update the authentication information stored on the
	 * server.
	 */
	protected final ADSManager adsManager;

	protected final Map<String, IssueReceiptRequest> approvalRequests;

	private Server server;

	private void start() throws IOException {
		/* The port on which the server should run */
		int port = 50051;
		server = ServerBuilder.forPort(port).addService(new BVerifyServerImpl()).build().start();
		logger.info("Server started, listening on " + port);
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

	/**
	 * Main launches the server from the command line.
	 */
	public static void main(String[] args) throws IOException, InterruptedException {
		String base = System.getProperty("user.dir") + "/demos/";
		final BVerifyServer server = new BVerifyServer("", "", 1);
		server.start();
		server.blockUntilShutdown();
	}

	private static class BVerifyServerImpl extends BVerifyServerAPIImplBase {

		public void issueReceipt(io.grpc.bverify.IssueReceiptRequest request,
				io.grpc.stub.StreamObserver<io.grpc.bverify.IssueReceiptResponse> responseObserver) {

		}

		@Override
		public void getApprovalRequests(io.grpc.bverify.ApprovalsRequest request,
				io.grpc.stub.StreamObserver<io.grpc.bverify.ApprovalsResponse> responseObserver) {
			
		}

		@Override
		public void submitSignature(io.grpc.bverify.SubmitSignatureRequest request,
				io.grpc.stub.StreamObserver<io.grpc.bverify.SubmitSignatureResponse> responseObserver) {
		}

		@Override
		public void getDataRequest(io.grpc.bverify.DataRequest request,
				io.grpc.stub.StreamObserver<io.grpc.bverify.DataResponse> responseObserver) {
		}

		@Override
		public void getAuthPath(io.grpc.bverify.PathRequest request,
				io.grpc.stub.StreamObserver<io.grpc.bverify.PathResponse> responseObserver) {
		}

	}

	public BVerifyServer(String base, String registryHost, int registryPort) {
		this.pki = new PKIDirectory(base + "pki/");
		System.out.println("loaded PKI");

		// setup the components
		this.adsManager = new ADSManager(base, this.pki);
		this.approvalRequests = new HashMap<>();

		// do an initial commitment
		this.adsManager.commit();

	}

}
