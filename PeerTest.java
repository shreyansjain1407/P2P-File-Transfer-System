import java.util.*;

import java.util.logging.*;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

public class PeerTest {

    static Logger logger = Logger.getLogger("BitTorrentLog");
    static FileHandler fh; // for log
    static File newDir; // new directory for peer
    static Random rand = new Random();

    // to be changed with config file
    static String fileName = "";
    static int pieceSize = -1;
    static int floatPiece = -1;
    static double fileSize = -1;
    static int numPieces = -1;

    // connection information about all peers
    // this is used to connect peers
    static HashMap<Integer, String[]> peerInfoMap = new HashMap<Integer, String[]>();

    // keep track of which file pieces SELF has
    // this is accesible through all client and server threads
    static HashMap<Integer, byte[]> pieceMap = new HashMap<>();

    // map of EACH peer and their piece map
    // the map is as follows:
    // key: peerNumber value: map of (key: piece num, value: boolean of whether the peer has the piece)
    // this is filled for each peer when setting up bitfield
    // when there are NO values of "false" in this map, all peers have all pieces.
    // we will need to periodically check this map, probably after receiving "have" messages.
    static HashMap<Integer, HashMap<Integer, Boolean>> peersPieceMap = new HashMap<>();

    // message types to numbers for easy access
    static HashMap<String, byte[]> messageTypeMap;

    // list of neighbors by connection sockets, peer ID's don't need to be stored in this case
    // this is used to send "have" messages to all neighbors
    static ArrayList<Socket> neighbors = new ArrayList<>();
    static ArrayList<DataOutputStream> neighbor_douts = new ArrayList<>();

    // to be changed with peer info
    static int portNum = -1;
    static int isOwner = -1;
    static int myPeerID = -1;

    static ByteArrayOutputStream peerByteOS = new ByteArrayOutputStream();

    public static void main(String[] args) throws IOException {

        // self id
        myPeerID = Integer.valueOf(args[0]);
        messageTypeMap = createMessageHashMap();

        // method to create a new logger for peer
        createLogger();

        // set properties from config
        getProperties();

        // get all peer info from config file
        createPeerInfoMap();

        // get self information
        getSelfInfo();

        // client server connection process
        boolean clientConnect = false;
        boolean setUpFile = false;

        // start a new listener at the port number
        ServerSocket listener = new ServerSocket(portNum);

        try {
            while (true) {
                if (isOwner == 1) { // if this peer is an owner of the file
                    if (!setUpFile) {
                        fileToPieceMap();
                        setUpFile = true;
                    }

                    Socket clientConnection = listener.accept();
                    neighbors.add(clientConnection);
                    neighbor_douts.add(new DataOutputStream(clientConnection.getOutputStream()));
                    new Handler(clientConnection).start();
                } else { // if the peer is not an owner of the file
                    if (!clientConnect) { // set up clients first
                        for (int id : peerInfoMap.keySet()) {

                            String[] peerInfo = peerInfoMap.get(id);

                            // connect to each peer that already has a server running
                            // it to work this way, peers need to be established in peerNum order
                            peersPieceMap.put(id, new HashMap<>()); // set up the piece map for each peer
                            if (id < myPeerID) {

                                String connectToHost = peerInfo[0];
                                int connectToPort = Integer.valueOf(peerInfo[1]);

                                System.out.println("connecting to peerid " + id + " at host " + connectToHost + " port "
                                        + connectToPort);
                                Socket requestSocket = new Socket(connectToHost, connectToPort);

                                // adding to map neighbors of ALL neighbors (both peers we are clients of and peers we are servers of)
                                neighbors.add(requestSocket);

                                // add to map douts of ALL douts
                                neighbor_douts.add(new DataOutputStream(requestSocket.getOutputStream()));
                                new Thread(new Client(requestSocket, id)).start();
                            }

                        }
                        clientConnect = true;
                    }
                    // finished connecting to all open servers, now waiting for clients
                    Socket clientConnection = listener.accept();
                    neighbor_douts.add(new DataOutputStream(clientConnection.getOutputStream()));
                    new Handler(clientConnection).start();
                }
            }
        } finally {
            listener.close();
        }

    }

    static class Handler extends Thread {

        Socket connection;

        private DataOutputStream server_dout;
        private DataInputStream server_din;

        ByteArrayOutputStream byteOS = new ByteArrayOutputStream();

        private int clientPeerID;

        public Handler(Socket connection) {
            this.connection = connection;
        }

        public void run() {
            System.out.println("my connection: " + connection.toString());
            System.out.println("my peer id:" + myPeerID);

            try {
                server_dout = new DataOutputStream(connection.getOutputStream());
                server_din = new DataInputStream(connection.getInputStream());

                byte[] incomingHandshake = new byte[32]; // 32 byte handshake

                // server waits for client handshake
                this.server_din.read(incomingHandshake);
                parseAndSendHandshake(incomingHandshake);

                // server waits for client bitfield
                byte[] messageLength = new byte[4];
                byte[] messageType = new byte[1];
                this.server_din.read(messageLength); // first get the message length to set up the bitfield
                this.server_din.read(messageType);
                int bitfieldLength = ByteBuffer.wrap(messageLength).getInt();

                if (Arrays.equals(messageType, messageTypeMap.get("bitfield"))) {
                    System.out.println("server received bitfield message");
                    byte[] bitfieldMessage = new byte[bitfieldLength];
                    this.server_din.read(bitfieldMessage);

                    // parse the bitfield from the client, and then send the client the bitfield
                    parseAndSendBitfield(bitfieldMessage, this.clientPeerID);


                }

                messageLength = new byte[4];
                messageType = new byte[1];

                // wait for messages
                while (this.server_din.read(messageLength) > 0){ // read in 4 bytes for the message length

                    this.server_din.read(messageType); //read in 1 byte for the message type

                    if (Arrays.equals(messageType, messageTypeMap.get("request"))) {
                        // System.out.println(myPeerID + " received request message" + this.clientPeerID);

                        // parse the request and send the piece
                        byte[] requestIndex = new byte[ByteBuffer.wrap(messageLength).getInt()];
                        this.server_din.read(requestIndex);
                        sendPiece(requestIndex);

                    } else if (Arrays.equals(messageType, messageTypeMap.get("have"))) { // on receving this have, we should update peerPieceMap

                        byte[] haveIndex = new byte[4];

                        this.server_din.read(haveIndex);
                        int haveIndexInt = ByteBuffer.wrap(haveIndex).getInt();

                        // updating the overall piece map for the client peer we are connected to
                        if (peersPieceMap.get(this.clientPeerID).containsKey(haveIndexInt)) {
                            peersPieceMap.get(this.clientPeerID).replace(haveIndexInt, true);
                        } else {
                            peersPieceMap.get(this.clientPeerID).put(haveIndexInt, true);
                        }

                        logHave(myPeerID, this.clientPeerID, ByteBuffer.wrap(haveIndex).getInt());

                        // TO DO HERE:
                        // check if all peers are done after receiving this have message
                        // if we are done, move to a file out method/portion/whatever
                    }

                    messageLength = new byte[4];
                    messageType = new byte[1];

                }
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

        }

        // "server" peer recieves handshake from "client" peer & sends one back
        void parseAndSendHandshake(byte[] incomingHandshake) throws IOException, InterruptedException {
            this.clientPeerID = ByteBuffer.wrap(Arrays.copyOfRange(incomingHandshake, 28, 32)).getInt();
            logConnectionFrom(myPeerID, this.clientPeerID);

            byte[] header = new String("P2PFILESHARINGPROJ").getBytes(); // header
            byte[] zerobits = new byte[10]; // 10 byte zero bits
            Arrays.fill(zerobits, (byte) 0);
            byte[] peerID = ByteBuffer.allocate(4).putInt(myPeerID).array(); // peer ID in byte array

            // write all information to a byte array
            byteOS.reset();
            byteOS.write(header);
            byteOS.write(zerobits);
            byteOS.write(peerID);

            byte[] handshake = byteOS.toByteArray();

            // Thread.sleep(500);
            serverSendMessage(handshake); // server sends handshake message to client
            byteOS.reset();
        }

        // "server" peer recieves bitfield from "client" peer & sends one back
        void parseAndSendBitfield(byte[] bitfieldMessage, int clientID) throws IOException {

            int counter = 1; // used to count pieces

            if (!peersPieceMap.containsKey(clientID)){
                peersPieceMap.put(clientID, new HashMap<>());
            }

            for (int i = 0; i < bitfieldMessage.length; i++) {

                String bs = String.format("%7s", Integer.toBinaryString(bitfieldMessage[i])).replace(' ', '0'); // ensure

                for (int j = 0; j < bs.length(); j++) {
                    if (bs.charAt(j) == '0') {
                        // peerPieceMap.put(counter, false); // local connection map
                        peersPieceMap.get(clientID).put(counter, false); // changing the global static map
                    } else if (bs.charAt(j) == '1') {
                        // peerPieceMap.put(counter, true); // local connection map
                        peersPieceMap.get(clientID).put(counter, false);
                    }
                    if (counter == numPieces) {
                        break; // ignore the final 0 bits in the bitstrings
                    }
                    counter++;
                }
            }

            logBitfieldFrom(myPeerID, clientID);

            byte bitfield[] = generateBitfield();
            int payload = bitfield.length; // payload is done incorrectly when sending pieces /// check ?
            byte[] messageLength = ByteBuffer.allocate(4).putInt(payload).array();
            byte[] messageType = ByteBuffer.allocate(1).put(messageTypeMap.get("bitfield")).array();

            this.byteOS.reset(); // make sure byteOS is empty
            this.byteOS.write(messageLength);
            this.byteOS.write(messageType);
            this.byteOS.write(bitfield);
            byte[] newBitfieldMessage = this.byteOS.toByteArray();

            serverSendMessage(newBitfieldMessage);
            // System.out.println(myPeerID + " sent bitfield message to " + this.clientPeerID);
        }

        // "server" peer sends piece of byte file to "client" peer
        void sendPiece(byte[] requestIndex) throws IOException {

            int pieceNum = ByteBuffer.wrap(requestIndex).getInt();
            byte[] piece = pieceMap.get(pieceNum);

            int payload = piece.length;

            byte[] messageLength = ByteBuffer.allocate(4).putInt(payload).array();
            byte[] messageType = ByteBuffer.allocate(1).put(messageTypeMap.get("piece")).array();
            byte[] indexField = ByteBuffer.allocate(4).putInt(pieceNum).array(); // index of starting point

            this.byteOS.reset(); // make sure byteOS is empty

            // header
            this.byteOS.write(messageLength);
            this.byteOS.write(messageType); // should equal binary 7 for "piece"
            this.byteOS.write(indexField);

            // actual piece
            this.byteOS.write(piece);

            byte[] sendMessage = byteOS.toByteArray();
            serverSendMessage(sendMessage); // sending the piece message
            // System.out.println("Sending to client: ");
            // String test = new String(piece, StandardCharsets.UTF_8);
            // System.out.println(test);

            this.byteOS.reset();
        }

        void serverSendMessage(byte[] msg) throws IOException {
            this.server_dout.flush();
            this.server_dout.write(msg);
        }
    }

    static class Client extends Thread {

        Socket connection;

        private DataOutputStream client_dout;
        private DataInputStream client_din;

        ByteArrayOutputStream byteOS = new ByteArrayOutputStream();

        private int serverPeerID;

        // this list is used to know which pieces to request from the "server" peer
        private ArrayList<Integer> serverPieceList = new ArrayList<>();

        public Client(Socket connection, int sID) {
            this.connection = connection;
            this.serverPeerID = sID;
        }

        public void run() {
            try {

                this.client_dout = new DataOutputStream(connection.getOutputStream());
                this.client_din = new DataInputStream(connection.getInputStream());

                System.out.println("my connection: " + connection.toString());
                System.out.println("my peer id:" + myPeerID);
                System.out.println("server id:" + this.serverPeerID);

                sendHandshake(this.serverPeerID);
                // System.out.println("waiting for handshake from : " + this.serverPeerID);
                byte[] incomingHandshake = new byte[32];
                this.client_din.read(incomingHandshake);

                parseHandshake(incomingHandshake, this.serverPeerID);
                // System.out.println("Server peer id: " + serverPeerID);

                // client sends the first bitfield message
                sendBitfield();

                // client waits for bitfield message
                byte[] messageLength = new byte[4];
                byte[] messageType = new byte[1];
                this.client_din.read(messageLength); // first get the message length to set up the bitfield
                this.client_din.read(messageType);
                int bitfieldLength = ByteBuffer.wrap(messageLength).getInt();

                // "client" peer receives bitfield message from "server" peer
                if (Arrays.equals(messageType, messageTypeMap.get("bitfield"))) {
                    // System.out.println(myPeerID + " received bitfield message from " + this.serverPeerID);
                    byte[] bitfieldMessage = new byte[bitfieldLength];
                    this.client_din.read(bitfieldMessage);

                    parseBitfield(bitfieldMessage, this.serverPeerID);
                }

                // send the first request before the client loop starts
                int requestPieceNum = selectRandomPieceNum();
                // System.out.println( myPeerID + " going to request piece " + requestPieceNum + " from " + this.serverPeerID);

                // only send a request if there are available pieces to request
                if (requestPieceNum > 0) {
                    sendRequest(requestPieceNum);
                }

                // start the client loop
                messageLength = new byte[4];
                messageType = new byte[1];

                while (this.client_din.read(messageLength) > 0) {

                    this.client_din.read(messageType);

                    // "client" peer receives piece from "server" peer
                    if (Arrays.equals(messageType, messageTypeMap.get("piece"))) {
                        // System.out.println(myPeerID + " recieved a piece from " + this.serverPeerID);

                        byte[] pieceIndex = new byte[4];
                        byte[] newPiece = new byte[ByteBuffer.wrap(messageLength).getInt()];

                        this.client_din.read(pieceIndex);
                        this.client_din.read(newPiece);
                        parseNewPiece(pieceIndex, newPiece);

                        // Thread.sleep(500);
                        int newRequestPieceNum = selectRandomPieceNum();

                        if (newRequestPieceNum > 0){
                            Thread.sleep(100); // for the sake of not overloading the connection
                            sendRequest(newRequestPieceNum);
                        } else {
                            System.out.println(peersPieceMap.get(myPeerID).toString());
                            System.out.println(serverPieceList.toString());
                        }
                    } else if (Arrays.equals(messageType, messageTypeMap.get("have"))) {

                        byte[] haveIndex = new byte[4];

                        this.client_din.read(haveIndex);
                        int haveIndexInt = ByteBuffer.wrap(haveIndex).getInt();


                        // after receiving a "have" message, update the peers piece map
                        if (peersPieceMap.get(this.serverPeerID).containsKey(haveIndexInt)) {
                            peersPieceMap.get(this.serverPeerID).replace(haveIndexInt, true);
                        } else {
                            peersPieceMap.get(this.serverPeerID).put(haveIndexInt, true);
                        }

                        if (!pieceMap.containsKey(haveIndexInt)){
                            this.serverPieceList.add(haveIndexInt); // if we don't have this piece, we can now ask this server
                        }

                        int newRequestPieceNum = selectRandomPieceNum();

                        if (newRequestPieceNum > 0){
                            Thread.sleep(250);
                            sendRequest(newRequestPieceNum);
                        } else {
                            System.out.println(peersPieceMap.get(myPeerID).toString());
                            System.out.println(serverPieceList.toString());
                        }

                        // TODO: If this peers needs the piece that was just "haved" we need to send an interested message

                        // TODO: check if all connected peers have all pieces. if so, shut down

                        logHave(myPeerID, this.serverPeerID, haveIndexInt);

                        //Checking for shutdown

                    }


                    if (pieceMap.size() == numPieces){
                        logDone(myPeerID);

                        // System.out.println(pieceMap.toString());
                    }

                    messageLength = new byte[4];
                    messageType = new byte[1];
                }

                //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
                //=======================================================================================================
                System.out.println("1");

                boolean shut = shutdown(peersPieceMap);
                System.out.println("2");
                if (!shut) {
                    System.out.println("3");
                    System.out.println(shut);
                    //generateFinalFile();
                    //shutDown();
                }
                System.out.println("4");
                //=======================================================================================================
                //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++


            } catch (IOException e1) {
                e1.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

        }

        // check for shutdown procedure to start
//        public boolean checkShutDown() {
//            System.out.printf("Checking shutdown clause.");
//            boolean flag = true;
//            for (Integer key : peersPieceMap.keySet())
//            {
//                HashMap<Integer innerKey,Boolean String> inner = peersPieceMap.get(key);
//                if (inner.containsValue(false)){
//                return true;
//            }
//            }
//        }

//        public void shutDown() {
//            //Any actions that may need to be performed after the file is generated, for cleanup purposes
//            System.out.printf("Shutdown Command Executed");
//        }
        static boolean shutdown(HashMap<Integer, HashMap<Integer, Boolean>> pp) {
            System.out.println("Exterior");
            for (HashMap.Entry<Integer, HashMap<Integer, Boolean>> letterEntry : pp.entrySet()) {
                Integer letter = letterEntry.getKey();
                System.out.println("For Loop Outer");
                for (HashMap.Entry<Integer, Boolean> nameEntry : letterEntry.getValue().entrySet()) {
                    System.out.println("For Loop Inner");
                    System.out.println("Sab Moh Maya Hai " + nameEntry.getValue());
                    if(nameEntry.getValue() == false){
                        System.out.println("Inside If");
                        return true;
                    }
                }
            }
            return false;
        }

        // sending handshake message to "server" peer
        void sendHandshake(int serverPeerID) throws IOException, InterruptedException {

            System.out.println("sending handshake to " + serverPeerID);
            byte[] header = new String("P2PFILESHARINGPROJ").getBytes(); // header
            byte[] zerobits = new byte[10]; // 10 byte zero bits
            Arrays.fill(zerobits, (byte) 0);
            byte[] peerID = ByteBuffer.allocate(4).putInt(myPeerID).array(); // peer ID in byte array

            // write all information to a byte array
            this.byteOS.reset();
            this.byteOS.write(header);
            this.byteOS.write(zerobits);
            this.byteOS.write(peerID);

            byte[] handshake = this.byteOS.toByteArray();

            clientSendMessage(handshake); // client sends handshake message to server

            this.byteOS.reset();

        }

        // parse incoming handshake from "server"
        void parseHandshake(byte[] incomingHandshake, int serverPeerID) {

            System.out.println("recieved handshake from " + serverPeerID);
            logConnectionTo(myPeerID, serverPeerID);
        }

        // send bitfield to server
        void sendBitfield() throws IOException {
            // System.out.println(myPeerID + " sending bitfield");
            byte bitfield[] = generateBitfield();
            int payload = bitfield.length; // payload is done incorrectly when sending pieces /// check ?
            byte[] messageLength = ByteBuffer.allocate(4).putInt(payload).array();
            byte[] messageType = ByteBuffer.allocate(1).put(messageTypeMap.get("bitfield")).array();

            this.byteOS.reset(); // make sure byteOS is empty
            this.byteOS.write(messageLength);
            this.byteOS.write(messageType);
            this.byteOS.write(bitfield);
            byte[] bitfieldMessage = this.byteOS.toByteArray();

            clientSendMessage(bitfieldMessage);
            System.out.println(myPeerID + " sent bitfield message to " + this.serverPeerID);
        }

        // parse incoming bitfield from server
        void parseBitfield(byte[] bitfieldMessage, int serverPeerID) {

            int counter = 1; // used to count pieces

            if (!peersPieceMap.containsKey(this.serverPeerID)){
                peersPieceMap.put(this.serverPeerID, new HashMap<>());
            }

            for (int i = 0; i < bitfieldMessage.length; i++) {

                String bs = String.format("%7s", Integer.toBinaryString(bitfieldMessage[i])).replace(' ', '0'); // ensure

                for (int j = 0; j < bs.length(); j++) {
                    if (bs.charAt(j) == '0') {
                        // peerPieceMap.put(counter, false); // local connection map
                        peersPieceMap.get(this.serverPeerID).put(counter, false); // changing the global static map
                    } else if (bs.charAt(j) == '1') {
                        // peerPieceMap.put(counter, true); // local connection map
                        peersPieceMap.get(this.serverPeerID).put(counter, true);
                        if (!pieceMap.containsKey(counter)){ // if this client doesn't have this piece, then it can request from server
                            this.serverPieceList.add(counter); // add to list of requests to server
                        }
                    }
                    if (counter == numPieces) {
                        break; // ignore the final 0 bits in the bitstrings
                    }
                    counter++;
                }
                if (counter == numPieces){
                    break;
                }
            }

            // System.out.println("Possible requests for " + this.serverPeerID + " are: " + this.serverPieceList.toString());
            logBitfieldFrom(myPeerID, this.serverPeerID);
            // System.out.println(peersPieceMap.get(this.serverPeerID).toString());
        }

        // send a piece request to the server
        void sendRequest(int requestPieceNum) throws IOException {

            byte[] messageLength = ByteBuffer.allocate(4).putInt(4).array();
            byte[] messageType = ByteBuffer.allocate(1).put(messageTypeMap.get("request")).array();
            byte[] requestIndex = ByteBuffer.allocate(4).putInt(requestPieceNum).array();

            this.byteOS.reset();

            // header & request index
            this.byteOS.write(messageLength);
            this.byteOS.write(messageType);
            this.byteOS.write(requestIndex);

            byte[] msg = this.byteOS.toByteArray();
            System.out.println(myPeerID + " sending request " + requestPieceNum + " to " + this.serverPeerID);
            clientSendMessage(msg); // requesting the piece
            this.byteOS.reset();
        }

        // selecting a random piece number based on "serverPieceList"
        int selectRandomPieceNum() {
            while (this.serverPieceList.size() > 0) {
                int pieceNum = this.serverPieceList.get(rand.nextInt(this.serverPieceList.size()));
                if (!pieceMap.containsKey(pieceNum)) { // if we dont have this piece
                    return pieceNum;
                } else {
                    // remove the piece from the list if we already have it
                    this.serverPieceList.remove(this.serverPieceList.indexOf(pieceNum));
                }
            }
            return -1;
        }

        // what to do with new piece
        void parseNewPiece(byte[] pieceIndex, byte[] newPiece) throws IOException {

            int pieceNum = ByteBuffer.wrap(pieceIndex).getInt();
            byte[] newPieceCopy = Arrays.copyOf(newPiece, newPiece.length);

            if (!pieceMap.containsKey(pieceNum)){
                pieceMap.put(pieceNum, newPieceCopy);
                this.serverPieceList.removeAll(Collections.singleton(pieceNum));
                sendHave(pieceNum); //letting all peers know about the new piece
            }

            logDownload(myPeerID, this.serverPeerID, pieceNum);

        }

        // letting all the neighbors know that we have receieved a new piece
        // this information is used to check the peerpiecemap
        void sendHave(int pieceNum) throws IOException {

            byte[] messageLength = ByteBuffer.allocate(4).putInt(4).array(); // allocate 4 bytes for index
            byte[] messageType = ByteBuffer.allocate(1).put(messageTypeMap.get("have")).array();
            byte[] requestIndex = ByteBuffer.allocate(4).putInt(pieceNum).array();

            this.byteOS.reset();
            this.byteOS.write(messageLength);
            this.byteOS.write(messageType);
            this.byteOS.write(requestIndex);

            byte[] msg = this.byteOS.toByteArray();

            this.byteOS.reset();

            for (DataOutputStream n_dout: neighbor_douts){
                n_dout.flush();
                n_dout.write(msg);
            }
        }

        void clientSendMessage(byte[] msg) throws IOException {
            this.client_dout.flush();
            this.client_dout.write(msg);
        }
    }

    // create a logger for the peer
    static void createLogger() {
        newDir = new File(System.getProperty("user.dir") + "/" + myPeerID);
        newDir.mkdir();
        String logPathname = newDir.getAbsolutePath() + "/" + myPeerID + "_BitTorrent.log";

        try {

            fh = new FileHandler(logPathname);
            logger.addHandler(fh);
            logger.setUseParentHandlers(false);
            SimpleFormatter formatter = new SimpleFormatter();
            fh.setFormatter(formatter);

        } catch (SecurityException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static void getProperties() {
        try (InputStream input = new FileInputStream("config.properties")) {

            Properties prop = new Properties();

            // load a properties file
            prop.load(input);

            // get properties
            fileName = prop.getProperty("FileName");
            pieceSize = Integer.valueOf(prop.getProperty("PieceSize"));
            fileSize = Integer.valueOf(prop.getProperty("FileSize"));
            numPieces = (int) Math.ceil(fileSize / pieceSize); // total number of pieces we will need to transfer

        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    static void createPeerInfoMap() throws IOException {

        BufferedReader reader = new BufferedReader(new FileReader("LocalP.txt"));

        String line = reader.readLine();

        // store the connection info of each peer
        while (line != null) {
            String lineArr[] = line.split(" ");
            int tempPeerID = Integer.valueOf(lineArr[0]);
            String peerInfo[] = Arrays.copyOfRange(lineArr, 1, 4);
            peerInfoMap.put(tempPeerID, peerInfo);
            line = reader.readLine();
        }
        reader.close();

    }

    static void getSelfInfo() {
        String[] myInfo = peerInfoMap.get(myPeerID);
        portNum = Integer.valueOf(myInfo[1]);
        isOwner = Integer.valueOf(myInfo[2]);
        System.out.println("I am peer " + myPeerID + " listening on port " + portNum + " and owner: " + isOwner);
    }

    // only used on the owner of the file
    static void fileToPieceMap() throws IOException {
        File file = new File(fileName);

        byte[] buffer = new byte[pieceSize];
        int counter = 1;

        // putting contents of the file into a map
        try (FileInputStream fileInputStream = new FileInputStream(file);
             BufferedInputStream bufferedInputStream = new BufferedInputStream(fileInputStream)) {

            int bytesRead = 0;
            // add each piece of the file to a map
            while ((bytesRead = bufferedInputStream.read(buffer)) > 0) {
                // System.out.println ("Bytes read: " + bytesRead);
                // byteOS.write(buffer, 0, pieceSize);
                peerByteOS.write(buffer, 0, bytesRead);
                byte[] piece = peerByteOS.toByteArray();
                pieceMap.put(counter, piece);
                peerByteOS.flush();
                peerByteOS.reset();

                counter++;

            }
        }
    }

    // goes through piece map to check which pieces it has
    static byte[] generateBitfield() {

        String bitstring = "";
        peerByteOS.reset();

        for (int i = 1; i <= numPieces; i++) {

            if (bitstring.equals("")) { // first bit in the bit string must be 0
                bitstring = "0";
            }

            if (pieceMap.containsKey(i)) { // if the map contains the key, add 1, if not add 0
                bitstring += "1";
            } else {
                // System.out.println ("no " + i);
                bitstring += "0";
            }

            if (i % 7 == 0 && i != 1) { // every 8 bits the bitstring must be written out and reset
                byte b = Byte.parseByte(bitstring, 2);
                peerByteOS.write(b);
                // System.out.println("Bitstring: " + bitstring);
                bitstring = "";
            }

            if (i == numPieces) { // at the end of the map, all remaining bits are 0
                int bsLength = bitstring.length();
                int j = 7 - bsLength;

                for (int k = 0; k <= j; k++) {
                    bitstring += "0";
                }
                byte b = Byte.parseByte(bitstring, 2);
                peerByteOS.write(b);
            }
        }

        byte[] bitfield = peerByteOS.toByteArray();
        return bitfield;
    }

    // this is the method that needs to be called after we shut down the program
    static void generateFinalFile() throws IOException {

        peerByteOS.reset();

        for (int i = 1; i <= pieceMap.size(); i++) {
            peerByteOS.write(pieceMap.get(i)); // write all pieces from map into byteOS
        }
        byte[] finalFile = peerByteOS.toByteArray();

        String pathname = newDir.getAbsolutePath() + "/" + myPeerID + "_copy.txt"; // stored in the user's
        // folder
        File copiedFile = new File(pathname);

        // write out the final file
        try (FileOutputStream fos = new FileOutputStream(copiedFile)) {
            fos.write(finalFile);
        }

        peerByteOS.flush();
        peerByteOS.reset();

    }

    // map used for message typing
    static HashMap<String, byte[]> createMessageHashMap() {
        HashMap<String, byte[]> map = new HashMap<String, byte[]>();
        byte zero = 0b000;
        byte one = 0b001;
        byte two = 0b010;
        byte three = 0b011;
        byte four = 0b100;
        byte five = 0b101;
        byte six = 0b110;
        byte seven = 0b111;
        // byte zeroArr[] = ByteBuffer.allocate(1).put(zero).array();
        map.put("choke", ByteBuffer.allocate(1).put(zero).array());
        map.put("unchoke", ByteBuffer.allocate(1).put(one).array());
        map.put("interested", ByteBuffer.allocate(1).put(two).array());
        map.put("not_interested", ByteBuffer.allocate(1).put(three).array());
        map.put("have", ByteBuffer.allocate(1).put(four).array());
        map.put("bitfield", ByteBuffer.allocate(1).put(five).array());
        map.put("request", ByteBuffer.allocate(1).put(six).array());
        map.put("piece", ByteBuffer.allocate(1).put(seven).array());

        return map;
    }

    static void logConnectionTo(int peerID1, int peerID2) {
        logger.info("Peer [" + peerID1 + "] made a connection to Peer [" + peerID2 + "]");
    }

    static void logConnectionFrom(int peerID1, int peerID2) {
        logger.info("Peer [" + peerID1 + "] is connected from Peer [" + peerID2 + "]");
    }

    static void logBitfieldFrom(int peerID1, int peerID2) {
        logger.info("Peer [" + peerID1 + "] has received a bitfield message from [" + peerID2 + "]");
    }

    static void logchangeNeighbors(int peerID1, int[] peerList) {
        logger.info("Peer [" + peerID1 + "] has the preferred neighbors [" + Arrays.toString(peerList) + "]");
    }

    static void logchangeOpUnchokeNeighbor(int peerID1, int opUnNeighbor) {
        logger.info("Peer [" + peerID1 + "] has the optimistically unchoked neighbor [" + opUnNeighbor + "]");
    }

    static void logUnchoked(int peerID1, int peerID2) {
        logger.info("Peer [" + peerID1 + "] is unchoked by [" + peerID2 + "]");
    }

    static void logChoked(int peerID1, int peerID2) {
        logger.info("Peer [" + peerID1 + "] is choked by [" + peerID2 + "]");
    }

    static void logHave(int peerID1, int peerID2, int pieceNum) {
        logger.info("Peer [" + peerID1 + "] received the 'have' message from [" + peerID2 + "] for the piece ["
                + pieceNum + "]");
    }

    static void logInterested(int peerID1, int peerID2) {
        logger.info("Peer [" + peerID1 + "] received the 'interested' message from [" + peerID2);
    }

    static void logNotInterested(int peerID1, int peerID2) {
        logger.info("Peer [" + peerID1 + "] received the 'not interested' message from [" + peerID2);
    }

    static void logDownload(int peerID1, int peerID2, int pieceNum) {
        logger.info("Peer [" + peerID1 + "] has downloaded the piece [" + pieceNum + "] from [" + peerID2 + "]. "
                + "Now the number of pieces it has is " + pieceMap.size() + ".");
    }

    static void logDone(int peerID) {
        logger.info("Peer [" + peerID + "] has downloaded the complete file.");
    }


}
