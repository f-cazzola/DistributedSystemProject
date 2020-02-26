package it.polimi.ds.replica;

import it.polimi.ds.network.Address;
import it.polimi.ds.network.Message;
import it.polimi.ds.network.MessageType;
import it.polimi.ds.network.TCPClient;

import java.io.*;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;


public class Replica {
    public static final int REPLICA_PORT = 2222; // will be taken from environment
    private Address replicaAddress;
    private Address trackerAddress;
    private List<Address> otherReplicaAddresses;
    private StateHandler state;
    private static final Logger logger = Logger.getLogger("Replica");
    public static void main(String[] args) {
        Replica tracker = new Replica();
        tracker.start(args[0], args[1], args[2], args[3]);
    }



    public void start(String trackerIp, String trackerPort, String replicaIp, String replicaPort) {
        int trackerIndex = 0;
        this.trackerAddress = new Address(trackerIp, Integer.valueOf(trackerPort));
        this.replicaAddress = new Address(replicaIp, Integer.valueOf(replicaPort));
        while (trackerIndex == 0){
            try {
                trackerIndex = joinNetwork(TCPClient.connect(trackerAddress));
            } catch (IOException | ClassNotFoundException e) {
                logger.log(Level.WARNING, "Impossible to contact the server, exiting.");
            }
        }
//        Try to get the state from one of the replicas
        for (int i = 0; state == null ; i++) {
            Address otherReplica = otherReplicaAddresses.get(i % otherReplicaAddresses.size());
            try {
                state = getState(TCPClient.connect(otherReplica), trackerIndex);
            } catch (IOException | ClassNotFoundException e) {
                logger.log(Level.WARNING, () -> "Impossible to get a valid state from " + otherReplicaAddresses + ", trying an other one.");
            }
        }

//        Here I have the state

    }


    private int joinNetwork(TCPClient client) throws IOException, ClassNotFoundException {
        client.out().writeObject(new Message(MessageType.ADD_REPLICA, replicaAddress));
        otherReplicaAddresses = ((Message) client.in().readObject()).getAddressSet();
        return ((Message) client.in().readObject()).getTrackerIndex();
    }

    private StateHandler getState(TCPClient client, int trackerIndex) throws IOException, ClassNotFoundException {
        client.out().writeObject(new Message(MessageType.GET_STATE, trackerIndex));
        Message reply = ((Message) client.in().readObject());
        if (reply.getType().equals(MessageType.SEND_STATE))
            return new StateHandler(reply.getState());
        throw new IOException();
    }
}
