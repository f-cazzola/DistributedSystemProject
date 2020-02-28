package it.polimi.ds.replica;

import it.polimi.ds.network.Address;
import it.polimi.ds.network.Message;
import it.polimi.ds.network.MessageType;
import it.polimi.ds.network.TCPClient;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Replica {
    private Address replicaAddress;
    private Address trackerAddress;
    private List<Address> otherReplicaAddresses;
    private StateHandler state;
    private ServerSocket serverSocket;
    private TrackerIndexHandler trackerIndexHandler;     //need to be shared
    private static AtomicInteger messagesLeftToSend = new AtomicInteger(0);
    private static final Logger logger = Logger.getLogger("Replica");

    public static void main(String[] args) {
        Replica tracker = new Replica();
        tracker.start(args[0], args[1], args[2], args[3]);
    }

    public void start(String trackerIp, String trackerPort, String replicaIp, String replicaPort) {
        this.trackerAddress = new Address(trackerIp, Integer.valueOf(trackerPort));
        this.replicaAddress = new Address(replicaIp, Integer.valueOf(replicaPort));
        while (trackerIndexHandler == null){
            try {
                trackerIndexHandler = joinNetwork(TCPClient.connect(trackerAddress));
            } catch (IOException | ClassNotFoundException e) {
                logger.log(Level.SEVERE, "Impossible to contact the server, exiting.");
            }
        }
//        Try to get the state from one of the replicas
        if (otherReplicaAddresses.isEmpty()){
            state = new StateHandler(new State(replicaAddress), replicaAddress);
        }

        for (int i = 0; state == null ; i++) {
            Address otherReplica = otherReplicaAddresses.get(i % otherReplicaAddresses.size());
            try {
                state = getState(TCPClient.connect(otherReplica), trackerIndexHandler.getTrackerIndex());
            } catch (IOException | ClassNotFoundException e) {
                logger.log(Level.WARNING, () -> "Impossible to get a valid state from " + otherReplicaAddresses + ", trying an other one.");
            }
        }

//        Here I have the state
        Thread replica = new Thread(() -> runReplica(replicaPort));
        replica.start();
        do {
            logger.log(Level.INFO, "Press 1 to close the Tracker");
        }
        while (getChoice() != 1);
        logger.log(Level.INFO, "Waiting until all messages are sent...");
        while (messagesLeftToSend.get() != 0) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                logger.log(Level.SEVERE, "Could not send all the messages to other replica properly.");
                Thread.currentThread().interrupt();
            }
        }
        try {
            TCPClient tracker = TCPClient.connect(trackerAddress);
            tracker.out().writeObject(new Message(MessageType.REMOVE_REPLICA, replicaAddress));
            tracker.close();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not inform the tracker of the replica closure.");
        }
        replica.interrupt();
        logger.log(Level.INFO, "This replica has correctly been closed.");
    }

    private static int getChoice() {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        try {
            return Integer.parseInt(reader.readLine());
        } catch (NumberFormatException | IOException e) {
            return -1;
        }
    }

    private void runReplica(String replicaPort) {
        try {
            serverSocket = new ServerSocket(Integer.parseInt(replicaPort));
            while (true) {
                new IncomingMessageHandler(replicaAddress, otherReplicaAddresses, serverSocket.accept(), state, trackerIndexHandler).start();
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not accept the request.");
        }
        stop();
    }

    public void stop() {
        try {
            serverSocket.close();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not close tracker properly.");
        }
    }

    private TrackerIndexHandler joinNetwork(TCPClient client) throws IOException, ClassNotFoundException {
        client.out().writeObject(new Message(MessageType.ADD_REPLICA, replicaAddress));
        otherReplicaAddresses = ((Message) client.in().readObject()).getAddressSet();
        return new TrackerIndexHandler(((Message) client.in().readObject()).getTrackerIndex());
    }

    private StateHandler getState(TCPClient client, int trackerIndex) throws IOException, ClassNotFoundException {
        client.out().writeObject(new Message(MessageType.GET_STATE, trackerIndex));
        Message reply = ((Message) client.in().readObject());
        if (reply.getType().equals(MessageType.SEND_STATE))
            return new StateHandler(reply.getState(), replicaAddress);
        throw new IOException();
    }

    public static void addMessageToBeSent() {
        messagesLeftToSend.incrementAndGet();
    }

    public static void removeMessageToBeSent() {
        messagesLeftToSend.decrementAndGet();
    }

    private static class IncomingMessageHandler extends Thread {
        private Address replicaAddress;
        private List<Address> otherReplicaAddresses;
        private Socket clientSocket;
        private StateHandler state;
        private TrackerIndexHandler trackerIndexHandler;

        public IncomingMessageHandler(Address replicaAddress, List<Address> otherReplicaAddresses, Socket socket, StateHandler state, TrackerIndexHandler trackerIndexHandler) {
            this.replicaAddress = replicaAddress;
            this.otherReplicaAddresses = new ArrayList<>(otherReplicaAddresses);
            this.clientSocket = socket;
            this.state = state;
            this.otherReplicaAddresses = otherReplicaAddresses;
            this.trackerIndexHandler = trackerIndexHandler;
        }

        @Override
        public void run() {
            try {
                TCPClient client = new TCPClient(clientSocket);
                Message inputMessage = (Message) client.in().readObject();
                switch (inputMessage.getType()) {
                    case READ_FROM_CLIENT:
                        client.out().writeObject(readFromClient(inputMessage.getResource()));
                        break;
                    case WRITE_FROM_CLIENT:
                        writeFromClient(inputMessage.getResource(), inputMessage.getValue());
                        break;
                    case UPDATE_FROM_REPLICA:
                        if (updateFromReplica(inputMessage.getUpdate(), inputMessage.getTrackerIndex()))
                            client.out().writeObject(new Message(MessageType.ACK));
                        else
                            client.out().writeObject(new Message(MessageType.WAIT));
                        break;
                    case GET_STATE:
                        getReplicaState();
                        break;
                    case SEND_NEW_REPLICA:
                        addNewReplica();
                        break;
                    case REMOVE_OLD_REPLICA:
                        removeOldReplica();
                        break;
                    default:
                        logger.log(Level.WARNING, "Message type not found.");
                }
                client.close();
                clientSocket.close();
            } catch (IOException | ClassNotFoundException e) {
                logger.log(Level.WARNING, "Communication with a replica interrupted.");
            }
        }

        private Message readFromClient(String resource) {
                return new Message(MessageType.READ_ANSWER, resource, state.read(resource));
        }

        private void writeFromClient(String resource, String value) {
            Update update = state.clientWrite(resource, value);
            // TODO: GET indexTracker (because not send to new replicas)
            int trackerIndex = trackerIndexHandler.getTrackerIndex();
/*          here after reading the trackerIndex a thread could increment it and update the otherReplicaAddress,
            we don't care, because if it was updated by an Exit from another replica it'ok if we don't send the update to the exited replica (would be check later otherwise)
            if it was updated by a Join we will simply send the update to the new replica who will reply with `wait` causing the resend of the message, no biggy
 */

            for (Address address : otherReplicaAddresses) {
                Replica.addMessageToBeSent();
                Thread writeSender = new Thread(() -> runWriteSender(address, update, otherReplicaAddresses, trackerIndex));
                writeSender.start();
            }
        }

        /**
         * This method continuously try to connect to otherReplica to send the update if otherReplica is still in the list of active replicas (activeReplicas)
         * @param otherReplica this is the
         * @param update this is the update to be sent
         * @param activeReplicas this is used to check other replica is removed from the list of activeReplica
         */
        private void runWriteSender(Address otherReplica, Update update, List<Address> activeReplicas, int trackerIndex) {
            try {
                TCPClient replica = TCPClient.connect(otherReplica);
                replica.out().writeObject(new Message(MessageType.UPDATE_FROM_REPLICA, update, trackerIndex));
                /* TODO: need to check if reply with `wait` (my trackerIndex is less then the receiver) and if so put the message in a queue.
                         The queue need to listen on trackerIndex update, when trackerIndex is updated (incremented) try resend the message to all otherReplica */
                Message reply = (Message) replica.in().readObject();
//                if (reply.getType() == MessageType.WAIT) {
//                    trackerIndexHandler.checkIfTrackerIndexIncreasedAndIfNotAddToQueue(update, trackerIndex);
//                    // this method check if trackerIndex has increased during the communication, if so resend the Update with trackerIndex increased by 1, otherwise put the update in a queue
//                }
                // otherwise the reply should be an ACK and nothing need to be done
                replica.close();
                Replica.removeMessageToBeSent();
            } catch (IOException | ClassNotFoundException e) {
                logger.log(Level.SEVERE, () -> "Could not update replica " + otherReplica + " properly.");
                if (activeReplicas.contains(otherReplica))
                    runWriteSender(otherReplica, update, activeReplicas, trackerIndex);
            }
        }

        /**
         *
         * @param update
         * @param incomingTrackerIndex
         * @return true if updateTaken, false otherwise
         */
        private boolean updateFromReplica(Update update, int incomingTrackerIndex) {
            /* TODO: Check the incoming trackerInsex ITI, if:
                ITI > my trackerIndex MTI then put the message in `updates from replicas waiting for T` queue
                            (maybe could be processed thanks to assumption `before exit finish propagate update`, TOTHINK)
                            no, because if myVectorClock not contain X I don't know
                ITI < MTI then reply with `wait` message
                ITI = MTI then process then execute state.replicaWrite
                NOTE: check ITI = MTI and execute state.replicaWrite should be atomic, otherwise after the check and before the replicaWrite
                the replica could receive a Join from the Tracker and give his state to the neo joined Replica
                and then process the update without replying with `wait`*/
            return trackerIndexHandler.checkTrackerIndexAndExecuteUpdate(update, incomingTrackerIndex, state);
        }

        private void getReplicaState() {
            /* TODO: Check the incoming trackerInsex ITI, if:
                ITI > my trackerIndex MTI reply with `I do not know u yet`
                ITI < MTI then should be ok to send the state otherwise (reply with `wait` message)
                ITI = MTI Send the whole state to the requesting replica.
                Note: send state and queue
                */
        }

        private void addNewReplica() {
            //Use trackerIndexHandler.executeTrackerUpdate
            /* TODO: Check the incoming trackerInsex ITI, if:
                ITI > my trackerIndex MTI + 1 then put the message in `updates from tracker waiting for T` queue
                ITI < MTI + 1 message already received, ignore
                ITI = MTI + 1 then add the Replica to the VClock and update MTI
                Note: the update of the MTI should cause the checking of the `updates from tracker waiting for T` and  `updates from replicas waiting for T` queues */
        }

        private void removeOldReplica() {
            //Use trackerIndexHandler.executeTrackerUpdate
            /* TODO: Check the incoming trackerInsex ITI, if:
                ITI > my trackerIndex MTI + 1 then put the message in `updates from tracker waiting for T` queue
                ITI < MTI + 1 message already received, ignore
                ITI = MTI + 1 then remove the Replica from the VClock and update MTI
                Note: the update of the MTI should cause the checking of the `updates from tracker waiting for T` and  `updates from replicas waiting for T` queues */
        }
    }
}
