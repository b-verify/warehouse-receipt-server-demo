package demo;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import api.BVerifyProtocolClientAPI;
import crpyto.CryptographicSignature;
import crpyto.CryptographicUtils;
import mpt.core.InsufficientAuthenticationDataException;
import mpt.core.InvalidSerializationException;
import mpt.core.Utils;
import mpt.dictionary.AuthenticatedDictionaryClient;
import mpt.dictionary.MPTDictionaryPartial;
import mpt.set.AuthenticatedSetServer;
import mpt.set.MPTSetFull;
import pki.Account;
import pki.PKIDirectory;
import rmi.ClientProvider;
import serialization.generated.BVerifyAPIMessageSerialization.ADSData;
import serialization.generated.BVerifyAPIMessageSerialization.AuthProof;
import serialization.generated.BVerifyAPIMessageSerialization.IssueReceiptRequest;
import serialization.generated.BVerifyAPIMessageSerialization.Receipt;
import serialization.generated.BVerifyAPIMessageSerialization.Signature;

public class MockDepositor implements BVerifyProtocolClientAPI {

	private final Account account;
	private final ClientProvider rmi;
	private final byte[] adsKey;
	private final Set<Receipt> adsData;
	private final AuthenticatedSetServer ads;
	private AuthenticatedDictionaryClient authADS;
	private int currentCommitmentNumber;
	private byte[] currentCommitment;
	
	public MockDepositor(Account a, String host, int port) {
		this.account = a;
		assert a.getADSKeys().size() == 1;
		this.adsKey = a.getADSKeys().iterator().next();
		this.rmi = new ClientProvider(host, port);
		BVerifyProtocolClientAPI clientStub;
		try {
			// port 0 = any free port
			clientStub = (BVerifyProtocolClientAPI) UnicastRemoteObject.exportObject(this, 0);
			this.rmi.getServer().bindClient(this.account.getIdAsString(), clientStub);
		} catch (RemoteException e) {
			e.printStackTrace();
			throw new RuntimeException();
		}
	
		System.out.println("loading mock depositor "+a.getFirstName());
		System.out.println("cares about ads: "+Utils.byteArrayAsHexString(this.adsKey));
		
		try {
			// first ask for the receipts and current commitment
			this.ads = new MPTSetFull();
			this.adsData = new HashSet<>();
			byte[] recieptDataBytes = this.rmi.getServer().getReceipts(this.adsKey);
			ADSData receiptDataMsg = ADSData.parseFrom(recieptDataBytes);
			for(Receipt r : receiptDataMsg.getReceiptsList()) {
				System.out.println("adding receipt: "+r);
				this.adsData.add(r);
				byte[] receiptWitness = CryptographicUtils.witnessReceipt(r);
				this.ads.insert(receiptWitness);
			}
			this.currentCommitment = receiptDataMsg.getCommitment().toByteArray();
			this.currentCommitmentNumber = receiptDataMsg.getCommitmentNumber();
			
			// next ask for the auth path
			List<byte[]> adsKeys = new ArrayList<>();
			adsKeys.add(this.adsKey);
			System.out.println("asking for proof of data");
			byte[] pathBytes = this.rmi.getServer().getAuthPath(adsKeys, this.currentCommitmentNumber);
			AuthProof proof = AuthProof.parseFrom(pathBytes);
			this.authADS = MPTDictionaryPartial.deserialize(proof.getPath());
			
			// check that the auth proof is correct
			if(!Arrays.equals(this.authADS.get(this.adsKey), this.ads.commitment())){
				throw new RuntimeException("sever returned invalid proof!");
			}
		}catch(Exception e) {
			e.printStackTrace();
			throw new RuntimeException("cannot parse response from server");
		}
	}
	
	@Override
	public byte[] approveDeposit(byte[] request) throws RemoteException {
		IssueReceiptRequest requestMsg;
		try {
			requestMsg = IssueReceiptRequest.parseFrom(request);
			Receipt receipt = requestMsg.getReceipt();
			System.out.println("Recieved Request To Issue Receipt: "+receipt);
			this.adsData.add(receipt);
			byte[] witness = CryptographicUtils.witnessReceipt(receipt);
			this.ads.insert(witness);
			byte[] newRoot = this.ads.commitment();
			System.out.println("new ads root: "+Utils.byteArrayAsHexString(newRoot));
			byte[] sig = CryptographicSignature.sign(newRoot, this.account.getPrivateKey());			
			return Signature.newBuilder()
					.setSignerId(this.account.getIdAsString())
					.setSignature(ByteString.copyFrom(sig))
					.build()
					.toByteArray();
		} catch (InvalidProtocolBufferException e) {
			e.printStackTrace();
			throw new RuntimeException(e.getMessage());
		}
	}

	@Override
	public void addNewCommitment(byte[] commitment) throws RemoteException {
		// add the commitment 
		this.currentCommitmentNumber = this.currentCommitmentNumber+1;
		this.currentCommitment = commitment;
		
		// ask the server for a proof!
		List<byte[]> adsKeys = new ArrayList<>();
		adsKeys.add(this.adsKey);
		byte[] respBytes = this.rmi.getServer().getAuthPath(adsKeys, this.currentCommitmentNumber);
		try {
			AuthProof proof = AuthProof.parseFrom(respBytes);
			MPTDictionaryPartial mpt = MPTDictionaryPartial.deserialize(proof.getPath());
			byte[] valueInProof = mpt.get(this.adsKey);
			byte[] commitmentProof = mpt.commitment();
			System.out.println("------ checking commitment matches----------");
			if(!Arrays.equals(commitmentProof, this.currentCommitment)) {
				throw new RuntimeException("commitment in proof does not match");
			}
			System.out.println("------ checking ADS"+Utils.byteArrayAsHexString(this.adsKey)+"----------");
			if(!Arrays.equals(valueInProof, this.ads.commitment())) {
				throw new RuntimeException("value in proof does not match");
			}
			System.out.println("------ update accepted!----------");
			this.authADS = mpt;
		} catch (InvalidProtocolBufferException | InvalidSerializationException | InsufficientAuthenticationDataException e) {
			e.printStackTrace();
			throw new RuntimeException(e.getMessage());
		}
	}
	
	public static void main(String[] args) {
		String base = System.getProperty("user.dir")  + "/demos/";
		PKIDirectory pki = new PKIDirectory(base+"pki/");
		String host = "18.85.22.252";
		int port = 1099;
		/**
		 * Alice: 59d6dd79-4bbe-4043-ba3e-e2a91e2376ae
		 * Bob: b132bbfa-98bc-4e5d-b32d-f78d603600f5
		 * Warehouse: 2cd00d43-bf5c-4728-9323-d2ea0092ed36
		 */
		Account alice = pki.getAccount("59d6dd79-4bbe-4043-ba3e-e2a91e2376ae");
		MockDepositor aliceClient = new MockDepositor(alice, host, port);
		Scanner sc = new Scanner(System.in);
		sc.nextLine();
		System.out.println("Press enter to shutdown");
		sc.close();
		System.out.println("shutdown");
	}
	
	
}
