package api;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * The API exposed by the b_verify server to the b_verify clients
 * 
 * @author henryaspegren
 *
 */
public interface BVerifyProtocolServerAPI extends Remote {
	
	/**
	 * Invoked by the warehouse on the server to 
	 * start issuing a receipt
	 * @param request - should be an IssueReceiptRequest
	 * (see format in bverifyprotocolapi.proto)
	 * @param signature - the warehouse should sign the 
	 * new root and include its signature (see format
	 * in bverifyprotocolapi.proto)
	 * @return
	 */
	public void issueReceipt(byte[] request, byte[] signature) throws RemoteException;
	
	/**
	 * Invoked by a client to get the authentication proof
	 * from a commitment to the root of a client ads
	 * @param adsId - the Id of the ADS
	 * @param commitmentNumber - the commitmentNumber
	 * @return an AuthProof (see format in the bverifyprotocolapi.proto)
	 * this is just a merkle proof.
	 * @throws RemoteException
	 */
	public byte[] getAuthPath(byte[] adsId, int commitmentNumber)  throws RemoteException;
	
	
	/**
	 * Invoked by a client to get the specified ads 
	 * @param adsId
	 * @return
	 */
	public byte[] getADS(byte[] adsId);
	
	
	
}
