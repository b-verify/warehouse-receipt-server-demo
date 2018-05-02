package server;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import api.BVerifyProtocolServerAPI;
import pki.PKIDirectory;
import rmi.ClientProvider;
import serialization.generated.BVerifyAPIMessageSerialization.IssueReceiptRequest;

public class BVerifyServer {
	
	/*
	 * Public Key Infrastructure - for identifying clients. For now this is mocked,
	 * but there are a variety of different possible ways to implement this.
	 */
	private final PKIDirectory pki;

	/*
	 * RMI (or other RPC framework) for sending requests
	 */
	private final ClientProvider rmi;
	
	/** 
	 * 			SHARED DATA
	 */
	
	/*
	 * ADS Manager used to update the authentication information
	 * stored on the server. 
	 */
	protected final ADSManager adsManager;

	/*
	 * This is a shared queue using the producer-consumer 
	 * design pattern. This queue contains requests to verify and,
	 * if they verify, to commit.
	 */
	private BlockingQueue<IssueReceiptRequest> requests;
	
	public BVerifyServer(String base, String registryHost, int registryPort) {
		this.pki = new PKIDirectory(base + "pki/");
		System.out.println("loaded PKI");
		this.rmi = new ClientProvider(registryHost, registryPort);
		
		// setup the shared data
		this.adsManager = new ADSManager(base, this.pki);
		this.requests = new LinkedBlockingQueue<>();

		// setup the components 
		
		// this component runs as its own thread
		BVerifyServerConfirmAndApply applierThread = 
				new BVerifyServerConfirmAndApply(this.requests, this.adsManager, 
						this.pki, this.rmi);
		applierThread.start();
		
		// this is an object exposed to the RMI interface.
		// the RMI library handles the threading and 
		// may invoke multiple methods concurrently on this 
		// object
		BVerifyServerRequestVerifier verifierForRMI = 
				new BVerifyServerRequestVerifier(this.requests, this.adsManager);
		
		// do an initial commitment 
		this.adsManager.commit();
		
		BVerifyProtocolServerAPI serverAPI;
		try {
			// port 0 = any free port
			serverAPI = (BVerifyProtocolServerAPI) UnicastRemoteObject.exportObject(verifierForRMI, 0);
			this.rmi.bindServer(serverAPI);
		} catch (RemoteException e) {
			e.printStackTrace();
			throw new RuntimeException();
		}
	}
	
	public static void main(String[] args) {
		String base = "/home/henryaspegren/eclipse-workspace/b_verify-server/demos/";
		String host = null;
		int port = 1099;
		// first create a registry
		try {
			LocateRegistry.createRegistry(port);
		} catch (RemoteException e) {
			e.printStackTrace();
			throw new RuntimeException(e.getMessage());
		}
		BVerifyServer server = new BVerifyServer(base, host, port);
	}
}
