package demo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.github.javafaker.Faker;
import com.google.protobuf.InvalidProtocolBufferException;

import crpyto.CryptographicUtils;
import mpt.core.Utils;
import mpt.set.MPTSetFull;
import pki.Account;
import serialization.generated.BVerifyAPIMessageSerialization.Receipt;

/**
 * This class is used to create mock data
 * for testing and demo purposes. All mock data 
 * is created in the /mock-data/ directory and 
 * can be read in using the MockClient class
 * to create mock clients.
 * @author henryaspegren
 *
 */
public class BootstrapMockSetup {
	
	public static final String PKI_DIR = "pki/";
	public static final String CLIENT_DATA = "client-data/";
	

	
	public static void bootstrapSimpleDemo(String base) {
		String pkiDirectoryFile = base+PKI_DIR;
		String clientADSDirectoryFile = base+CLIENT_DATA;
		
		Account alice = new Account("Alice", "A");
		Account bob = new Account("Bob", "B");
		Account warehouse = new Account("Warehouse", "Holdings");
		
		List<Account> ads1accounts = new ArrayList<>();
		List<Account> ads2accounts = new ArrayList<>();
		
		// ads 1 = alice + warehouse
		ads1accounts.add(alice);
		ads1accounts.add(warehouse);
		byte[] ads1Id = CryptographicUtils.listOfAccountsToADSKey(ads1accounts);
		String ads1IdString = Utils.byteArrayAsHexString(ads1Id);
		MPTSetFull ads1 = new MPTSetFull();
		// add 3 random receipts 
		for(int i = 0; i < 2; i++) {
			Receipt randomReceipt = BootstrapMockSetup.generateReceipt(warehouse, alice);
			byte[] witness = CryptographicUtils.witnessReceipt(randomReceipt);
			File receiptFile = new File(clientADSDirectoryFile+ads1IdString+"/"+i);
			writeBytesToFile(receiptFile, randomReceipt.toByteArray());
			ads1.insert(witness);
		}
		alice.addADSKey(ads1Id);
		warehouse.addADSKey(ads1Id);
		
		
		// ads 2 = bob + warehouse
		ads2accounts.add(alice);
		ads2accounts.add(warehouse);
		byte[] ads2Id = CryptographicUtils.listOfAccountsToADSKey(ads2accounts);
		String ads2IdString = Utils.byteArrayAsHexString(ads2Id);
		MPTSetFull ads2 = new MPTSetFull();
		// add 3 random receipts 
		for(int i = 0; i < 2; i++) {
			Receipt randomReceipt = BootstrapMockSetup.generateReceipt(warehouse, bob);
			byte[] witness = CryptographicUtils.witnessReceipt(randomReceipt);
			File receiptFile = new File(clientADSDirectoryFile+ads2IdString+"/"+i);
			writeBytesToFile(receiptFile, randomReceipt.toByteArray());
			ads2.insert(witness);
		}
		bob.addADSKey(ads2Id);
		warehouse.addADSKey(ads2Id);

		// save the accounts 
		alice.saveToFile(pkiDirectoryFile);
		bob.saveToFile(pkiDirectoryFile);
		warehouse.saveToFile(clientADSDirectoryFile);
	}
	
	
	/**
	 * Used to generate fake but vaguely realistic data
	 */
	private static Faker FAKER = new Faker();
	
	public static Receipt generateReceipt(Account issuer, Account recepient) {
		Receipt.Builder rec = Receipt.newBuilder();
		rec.setWarehouseId(issuer.getIdAsString());
		rec.setDepositorId(recepient.getIdAsString());
		rec.setAccountant(FAKER.name().name());
		rec.setCategory(FAKER.yoda().quote());
		Date now = new Date();
		rec.setDate(now.toString());
		rec.setInsurance("");
		rec.setWeight(FAKER.number().randomDouble(2, 0, 1000));
		rec.setVolume(FAKER.number().randomDouble(2, 0, 1000));
		rec.setHumidity(FAKER.number().randomDouble(3, 0, 1));
		rec.setPrice(FAKER.number().randomDouble(2, 1000, 10000));
		rec.setDetails(""+FAKER.number().randomNumber());
		return rec.build();
	}
	
	public static Set<Receipt> loadReceipts(String base, byte[] adsKey){
		String keyAsString = Utils.byteArrayAsHexString(adsKey);
		return loadReceipts(base, keyAsString);
	}
	
	public static Set<Receipt> loadReceipts(String base, String adsKey){
		File clientDataDir = new File(base+CLIENT_DATA+"/"+adsKey);
		Set<Receipt> receipts = new HashSet<>();
		try {
			for(File f : clientDataDir.listFiles()) {
				byte[] receiptBytes = readBytesFromFile(f);
					Receipt r = Receipt.parseFrom(receiptBytes);	
					receipts.add(r);
			}
		}catch (InvalidProtocolBufferException e) {
			e.printStackTrace();
			throw new RuntimeException("corrupted data");
		}
		return receipts;
	}
	
	public static void writeBytesToFile(File f, byte[] bytes) {
		try {
			FileOutputStream fos = new FileOutputStream(f);
			fos.write(bytes);
			fos.close();
		}catch(Exception e) {
			throw new RuntimeException(e.getMessage());
		}
	}
	
	public static byte[] readBytesFromFile(File f) {
		try {
			FileInputStream fis = new FileInputStream(f);
			byte[] data = new byte[(int) f.length()];
			fis.read(data);
			fis.close();
			return data;
		} catch (IOException e) {
			e.printStackTrace();
			throw new RuntimeException("corrupted data");
		}
	}
	
	public static void main(String[] args) {
		
		// runs the bootstrap to setup the mock data
		// String base = "/home/henryaspegren/eclipse-workspace/b_verify-server/mock-data/";
		// BootstrapMockSetup.bootstrapWarehouseUsecase(10, 1, 10, base);
	}
}
