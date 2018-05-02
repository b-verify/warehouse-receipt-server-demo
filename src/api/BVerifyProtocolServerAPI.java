package api;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

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
	 * (see format in bverifyprotocolapi.proto). This
	 * includes a signature over the root of the new ADS
	 * @return
	 */
	public void issueReceipt(byte[] request) throws RemoteException;
	
	/**
	 * Invoked by a client to get the authentication proof
	 * from a commitment to the root of a client ads
	 * @param List<byte[]> - the Id of the ADSes
	 * @param commitmentNumber - the commitmentNumber
	 * @return an AuthProof (see format in the bverifyprotocolapi.proto)
	 * this is just a merkle proof.
	 * @throws RemoteException
	 */
	public byte[] getAuthPath(List<byte[]> adsIds, int commitmentNumber)  throws RemoteException;
	
	
	/**
	 * Invoked by a client to get the specified ads 
	 * @param adsId
	 * @return
	 * @throws RemoteException 
	 */
	public byte[] getReceipts(byte[] adsId) throws RemoteException;
	
}
