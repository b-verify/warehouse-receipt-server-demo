package api;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * The API exposed by b_verify clients to the b_verify server
 * 
 * @author Henry Aspegren
 *
 */
public interface BVerifyProtocolClientAPI extends Remote {
	
	/**
	 * Invoked by the server on the client to approve a deposit
	 * 
	 * @param request - should be an IssueReceiptRequest message
	 * (see format in bverifyprotocolapi.proto)
	 * @return a signature over the new root! - should be a Signature message
	 * (see format in bverifyprotocolapi.proto)
	 */
	public byte[] approveDeposit(byte[] request) throws RemoteException;
	
	public void addNewCommitment(byte[] commitment) throws RemoteException;
	
}
