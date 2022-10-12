/*
    BioServer - Emulation of the long gone server for 
                Biohazard Outbreak File #1 (Playstation 2)

    Copyright (C) 2013-2019 obsrv.org (no23@deathless.net)

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package bioserver;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Process messages
 */
class PacketHandler implements Runnable {

    // database for sessionhandling
    private Database db;

    // the list with packets :-)
    private List queue = new LinkedList();

    // list of connected clients
    private ClientList clients = new ClientList();
    
    // server packet id if needed
    private int packetidcounter;
    
    // list of areas
    private Areas areas;
    
    // list of rooms
    private Rooms rooms;
    
    // list of slots
    private Slots slots;
    
    // counter for games this server creates
    private int gamenumber;
    
    // the packethandler of the gameserver
    // used to get the clientlist
    // for counting players and buddy checker
    private GameServerPacketHandler gameserverpackethandler;
    
    // url collection
    private Information information = new Information();
    
    // status of a client
    public static int STATUS_OFFLINE    = 0;
    public static int STATUS_LOBBY      = 1;
    public static int STATUS_GAME       = 2;
    public static int STATUS_AGLOBBY    = 3;
    
    private byte[] gs_ip;

    private Patch patch;
    
    @Override
    public void run() {
        ServerDataEvent dataEvent;
    
        // get configuration
        Configuration conf = new Configuration();
        
        InetAddress tmp_ip;
        try {
            tmp_ip = InetAddress.getByName(conf.gs_ip);
            this.gs_ip = tmp_ip.getAddress();
            Logging.println("Gameserver IP: " + tmp_ip);
        } catch (UnknownHostException ex) {
            Logging.println("Unknown Host, check properties file!");
        }

        // open database for the handler
        this.db = new Database(conf.db_user, conf.db_password);
        this.packetidcounter = 0;
        this.gamenumber = 1;
        
        // setup patch
        //patch =  new Patch();

        // setup areas, rooms, slots
        areas = new Areas();
        rooms = new Rooms(areas.getAreaCount());
        slots = new Slots(areas.getAreaCount(), rooms.getRoomCount());
        
        while(true) {
        // Wait for data to become available
        synchronized(queue) {
            while(queue.isEmpty()) {
                try {
                    queue.wait();
                } catch (InterruptedException e) { }
            }
            dataEvent = (ServerDataEvent) queue.remove(0);
        }

        // send the data
        dataEvent.server.send(dataEvent.socket, dataEvent.data);
        }
    }
    
    // set the corresponding packethandler of gameserver
    public void setGameServerPacketHandler(GameServerPacketHandler handler) {
        this.gameserverpackethandler = handler;
    }

    // simple getter to let the serverthread manage disconnected clients
    public ClientList getClients() {
        return clients;
    }
    
    // increase the server packet id
    public int getNextPacketID() {
        return(++this.packetidcounter);
    }
    
    // get next gamenumber
    public int getNextGameNumber() {
        return(++this.gamenumber);
    }
    
    // here comes the raw packet data and is tranformed into messages
    void processData(ServerThread server, SocketChannel socket, byte[] data, int count) {
        // for debugging purposes

        ByteBuffer bbuf = ByteBuffer.wrap(data, 0, count);
        int offset = 0;
        byte[] tmpbuf = new byte[count];
        
        while(count>0) {
            bbuf.position(offset);
            bbuf.get(tmpbuf, 0, count);
            Packet p = new Packet(tmpbuf);
            
            handleInPacket(server, socket, p);

            offset = offset + p.getLength() + Packet.HEADERSIZE;
            count = count - p.getLength() - Packet.HEADERSIZE;
        }                
    }

    // add a packet to the queue
    void addOutPacket(ServerThread server, SocketChannel socket, Packet packet) {
        Logging.println("Sends");
        Logging.printBuffer(packet.getPacketData()); //temp
        synchronized(queue) {
            queue.add(new ServerDataEvent(server, socket, packet.getPacketData()));
            queue.notify();
        }
    }
    
    // broadcast a packet to all connected clients
    void broadcastPacket(ServerThread server, Packet packet) {
        Logging.println("Sends");
        Logging.printBuffer(packet.getPacketData()); //temp
        List cls = clients.getList();
        synchronized(queue) {
            for(int i=0; i<cls.size(); i++) {
                Client cl = (Client) cls.get(i);
                queue.add(new ServerDataEvent(server, cl.getSocket(), packet.getPacketData()));
            }
            queue.notify();
        }
    }
    
    
    // broadcast a packet to all clients in area and area selection, not in room or slot
    void broadcastInAreaNAreaSelect(ServerThread server, Packet packet, int area) {
        Logging.println("Sends");
        Logging.printBuffer(packet.getPacketData()); //temp
        synchronized(queue) {
            // who is the causing client ?
            List cls = clients.getList();

            // broadcasting in area
            if(area > 0) {
                for(int i=0; i<cls.size(); i++) {
                    Client cl = (Client) cls.get(i);
                    //if((cl.getArea() == area || cl.getArea() == 0) && cl.getRoom() == 0) {
                    if((cl.getArea() == area || cl.getArea() == 0)) {
                        queue.add(new ServerDataEvent(server, cl.getSocket(), packet.getPacketData()));
                    }
                }
            }

            // at last notify the queue
            queue.notify();
        }
    }

    // broadcast a packet to all clients in a certain area, not in room or slot or area selection
    void broadcastInArea(ServerThread server, Packet packet, int area) {
        Logging.println("Sends");
        Logging.printBuffer(packet.getPacketData()); //temp
        synchronized(queue) {
            // who is the causing client ?
            List cls = clients.getList();

            // broadcasting in area
            if(area > 0) {
                for(int i=0; i<cls.size(); i++) {
                    Client cl = (Client) cls.get(i);
                    //if(cl.getArea() == area && cl.getRoom() == 0) {
                    if(cl.getArea() == area) {
                        queue.add(new ServerDataEvent(server, cl.getSocket(), packet.getPacketData()));
                    }
                }
            }

            // at last notify the queue
            queue.notify();
        }
    }
    // broadcast a packet to all clients in a certain area, not in room or slot or area selection
    void broadcastInAreaWithoutSender(ServerThread server, Packet packet, int area, Client clr) {
        Logging.println("Sends");
        Logging.printBuffer(packet.getPacketData()); //temp
        synchronized(queue) {
            // who is the causing client ?
            List cls = clients.getList();

            // broadcasting in area
            if(area > 0) {
                for(int i=0; i<cls.size(); i++) {
                    Client cl = (Client) cls.get(i);
                    //if(cl.getArea() == area && cl.getRoom() == 0) {
                    if(cl.getArea() == area && cl != clr) {
                        queue.add(new ServerDataEvent(server, cl.getSocket(), packet.getPacketData()));
                    }
                }
            }

            // at last notify the queue
            queue.notify();
        }
    }

    // broadcast a packet to all clients in room, not in gameslot, area or area selection
    void broadcastInRoomWithoutSender(ServerThread server, Packet packet, int area, int room, Client clr) {
        Logging.println("Sends");
        Logging.printBuffer(packet.getPacketData()); //temp
        synchronized(queue) {
            // who is the causing client ?
            List cls = clients.getList();
            
            // broadcasting in gameslot
            if(room > 0) {
                for(int i=0; i<cls.size(); i++) {
                    Client cl = (Client) cls.get(i);
                    //if(cl.getArea() == area && cl.getRoom() == room && cl.getSlot() == 0 && cl != clr) {                    
                    if(cl.getArea() == area && cl.getRoom() == room && cl != clr) {
                        queue.add(new ServerDataEvent(server, cl.getSocket(), packet.getPacketData()));
                    }
                }
            }

            // at last notify the queue
            queue.notify();
        }
    }
    
    // broadcast a packet to all clients in gameslots and same room, not in area or area selection
    void broadcastInRoomNArea(ServerThread server, Packet packet, int area, int room) {
        Logging.println("Sends");
        Logging.printBuffer(packet.getPacketData()); //temp
        synchronized(queue) {
            // who is the causing client ?
            List cls = clients.getList();
            
            // broadcasting in gameslot
            if(room > 0) {
                for(int i=0; i<cls.size(); i++) {
                    Client cl = (Client) cls.get(i);
                    if(cl.getArea() == area && cl.getRoom() == room && cl.getSlot() == 0) {
                        queue.add(new ServerDataEvent(server, cl.getSocket(), packet.getPacketData()));
                    }
                }
            }

            // at last notify the queue
            queue.notify();
        }
    }
    
    //why exclude if slot is greater than 0?
    // broadcast a packet to all clients in room, not in gameslot, area or area selection
    //Even If one received the quest but if not are in the game, must have to send a message.
    void broadcastInRoom(ServerThread server, Packet packet, int area, int room) {
        Logging.println("Sends");
        Logging.printBuffer(packet.getPacketData()); //temp
        synchronized(queue) {
            // who is the causing client ?
            List cls = clients.getList();
            
            // broadcasting in gameslot
            if(room > 0) {
                for(int i=0; i<cls.size(); i++) {
                    Client cl = (Client) cls.get(i);
                    //if(cl.getArea() == area && cl.getRoom() == room && cl.getSlot() == 0) {
                    if(cl.getArea() == area && cl.getRoom() == room) {
                        queue.add(new ServerDataEvent(server, cl.getSocket(), packet.getPacketData()));
                    }
                }
            }

            // at last notify the queue
            queue.notify();
        }
    }
    

    // broadcast a packet to all clients in certain gameslot and their room, not to area or area selection or other slots
    void broadcastInSlotNRoom(ServerThread server, Packet packet, int area, int room, int slot) {
        Logging.println("Sends");
        Logging.printBuffer(packet.getPacketData()); //temp
        synchronized(queue) {
            // who is the causing client ?
            List cls = clients.getList();
            
            // broadcasting in gameslot
            if(slot > 0) {
                for(int i=0; i<cls.size(); i++) {
                    Client cl = (Client) cls.get(i);
                    if(cl.getArea() == area && cl.getRoom() == room && (cl.getSlot() == slot || cl.getSlot() == 0)) {
                        queue.add(new ServerDataEvent(server, cl.getSocket(), packet.getPacketData()));
                    }
                }
            }

            // at last notify the queue
            queue.notify();
        }
    }
    
    // broadcast a packet to all clients in gameslot, not in room, area or area selection
    void broadcastInSlot(ServerThread server, Packet packet, int area, int room, int slot) {
        Logging.println("Sends");
        Logging.printBuffer(packet.getPacketData()); //temp
        synchronized(queue) {
            // who is the causing client ?
            List cls = clients.getList();
            
            // broadcasting in gameslot
            if(slot > 0) {
                for(int i=0; i<cls.size(); i++) {
                    Client cl = (Client) cls.get(i);
                    if(cl.getArea() == area && cl.getRoom() == room && cl.getSlot() == slot) {
                        queue.add(new ServerDataEvent(server, cl.getSocket(), packet.getPacketData()));
                    }
                }
            }

            // at last notify the queue
            queue.notify();
        }
    }
    
        // broadcast a packet to all clients in gameslot, not in room, area or area selection
    void broadcastInSlotWithoutSender(ServerThread server, Packet packet, int area, int room, int slot, Client clr) {
        Logging.println("Sends");
        Logging.printBuffer(packet.getPacketData()); //temp
        synchronized(queue) {
            // who is the causing client ?
            List cls = clients.getList();
            
            // broadcasting in gameslot
            if(slot > 0) {
                for(int i=0; i<cls.size(); i++) {
                    Client cl = (Client) cls.get(i);
                    if(cl.getArea() == area && cl.getRoom() == room && cl.getSlot() == slot && cl !=clr) {
                        queue.add(new ServerDataEvent(server, cl.getSocket(), packet.getPacketData()));
                    }
                }
            }

            // at last notify the queue
            queue.notify();
        }
    }
    
    // broadcast a packet to all clients in aftergamelobby of gameslot
    void broadcastInAgl(ServerThread server, Packet packet, int gamenum) {
        Logging.println("Sends");
        Logging.printBuffer(packet.getPacketData()); //temp
        synchronized(queue) {
            // who is the causing client ?
            List cls = clients.getList();
            // broadcasting in gameslot's after game lobby
            for(int i=0; i<cls.size(); i++) {
                Client cl = (Client) cls.get(i);
                if(cl.gamenumber == gamenum && cl.gamenumber>0) {
                    queue.add(new ServerDataEvent(server, cl.getSocket(), packet.getPacketData()));
                }
            }
            // at last notify the queue
            queue.notify();
        }
    }

    // send this one every 30 seconds to all connected clients
    // TODO: what means the payload ?
    void broadcastPing(ServerThread server) {
        byte[] data = {0x00, 0x02, 0x00, 0x01, 0x03, (byte)0xe7, 0x00, 0x01};
        Packet p = new Packet(Commands.HEARTBEAT, Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), data);
        this.broadcastPacket(server, p);
    }

    // send a query to every client
    // the answer sets back the alive flag
    // if this doesn't happen, the client is deleted from list
    void broadcastConnCheck(ServerThread server) {
        Packet p = new Packet(Commands.CONNCHECK, Commands.QUERY, Commands.SERVER, this.getNextPacketID());
        List cls = clients.getList();
        synchronized(queue) {
            for(int i=0; i<cls.size(); i++) {
                Client cl = (Client) cls.get(i);
                // check if connection is alive and initiate next check
                // not in after game lobby ?
                if(cl.getArea() != 51) {
                    if(cl.connalive) {
                        cl.connalive = false;
                        queue.add(new ServerDataEvent(server, cl.getSocket(), p.getPacketData()));
                    } else {
                            // this client left us :-(
                            this.removeClient(server, cl);
                    }
                }
            }
            queue.notify();
        }        
    }
    

    void broadcastGetReady(ServerThread server, SocketChannel socket) {
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        int slot = cl.getSlot();
        int gamenr = slots.getSlot(area, room, slot).gamenr;
        
        // if this slot has no gamenumber, create one ;-)
        if(gamenr == 0) {
            // create a gamesession and save it to the clients in slot
            // used for gameserver and after game lobby
            gamenr = this.getNextGameNumber();
            List cls = clients.getList();
            for(int i=0; i<cls.size(); i++) {
                cl = (Client) cls.get(i);
                if(cl.getArea()==area && cl.getRoom()==room && cl.getSlot()==slot) {
                    cl.gamenumber = gamenr;
                    db.updateClientGame(cl.getUserID(), gamenr);
                }
            }

            slots.getSlot(area, room, slot).gamenr = gamenr;
        }
        
        // set slot so noone can join
        slots.getSlot(area, room, slot).setStatus(Slot.STATUS_BUSY);
        this.broadcastSlotStatus(server, area, room, slot);

        // now inform the players
        Packet p = new Packet(Commands.GETREADY, Commands.BROADCAST, Commands.SERVER, this.getNextPacketID());
        this.broadcastInSlot(server, p, area, room, slot);
    }
    
    // after connection the server sends its first packet, client answers
    void sendLogin(ServerThread server, SocketChannel socket) {
        byte[] seed = {0x28, 0x37};
        
        Packet p = new Packet(Commands.LOGIN, Commands.QUERY, Commands.SERVER,  getNextPacketID(), seed);
        this.addOutPacket(server, socket, p);
    }

    boolean check_session(ServerThread server, SocketChannel socket, Packet p) {
        int seed  = p.getPacketID();
        int sessA =  ((int) p.getPayload()[2]  - 0x30)*10000
                    +((int) p.getPayload()[3]  - 0x30)*1000
                    +((int) p.getPayload()[4]  - 0x30)*100
                    +((int) p.getPayload()[5]  - 0x30)*10
                    +((int) p.getPayload()[6]  - 0x30);
        int sessB =  ((int) p.getPayload()[7]  - 0x30)*10000
                    +((int) p.getPayload()[8]  - 0x30)*1000
                    +((int) p.getPayload()[9]  - 0x30)*100
                    +((int) p.getPayload()[10] - 0x30)*10
                    +((int) p.getPayload()[11] - 0x30);
        String session = String.format("%04d%04d", sessA-seed, sessB-seed);

        String userid = db.getUserid(session);
        Client cl;
        
        // session check is OK, a user with this session is in database
        if(!"".equals(userid)) {
            // kill old connections of this client
            cl = clients.findClient(userid);
            if(cl != null) {
                List cls = clients.getList();
                for(int i=0; i<cls.size(); i++) {
                    cl = (Client) cls.get(i);
                    if(cl.getUserID().equals(userid)) {
                        Logging.println("LS: Disconnect double session for userid "+userid);
                        server.disconnect(cl.getSocket());
                        this.removeClient(server, cl);
                    }
                }
            }

            // setup client object for this user/session
            clients.add((new Client(socket, userid, session)));
            cl = clients.findClient(socket);
            db.updateClientOrigin(userid, STATUS_LOBBY, 0, 0, 0);

            int gamenr = db.getGameNumber(cl.getUserID());
            if(gamenr > 0) {
                // we are in meeting room then
                // gamenumber not set yet because needed for broadcast packets in AGL!
                //cl.setArea(51);
                //db.updateClientOrigin(userid, STATUS_AGLOBBY, 51, 0, 0);
            }
            
            return(true);
        } else {
            // the session check failed, disconnect this client
            Logging.println("LS: Disconnect invalid session "+session+" for userid "+userid);
            server.disconnect(socket);
            return(false);
        }        
    }
    
    // after the client returns his answer to the servers login query
    // 0x708 = 1800 = 30 min in seconds
    // 0x258 =  600 = 10 min in seconds
    // TODO: is this latency or timeout ?
    void send61A0(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] latency = {0x00,0x00,0x07,0x08,0x00,0x00,0x02,0x58};
        
        Packet p = new Packet(Commands.UNKN61A0, Commands.TELL, Commands.SERVER, ps.getPacketID(), latency);
        this.addOutPacket(server, socket, p);
    }
    
    // before the server answers the 61a0 packet the clients version is checked
    void sendVersionCheck(ServerThread server, SocketChannel socket) {
        Packet p = new Packet(Commands.CHECKVERSION, Commands.QUERY, Commands.SERVER, getNextPacketID());
        this.addOutPacket(server, socket, p);
    }
    
    // based on the seed from server the client sends a couple of "0" shifted
    // by a pseudo random number, server sends back the backshifted "0"
    void sendCheckRnd(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] teststring = {0x00,0x01,0x30};

        ps.cryptString();
        teststring[2] = ps.getPayload()[4];
        
        Packet p = new Packet(Commands.CHECKRND, Commands.TELL, Commands.SERVER, ps.getPacketID(), teststring);
        this.addOutPacket(server, socket, p);
    }

    // TODO: is this latency or timout ?
    void send61A1(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] latency = {0x00,0x00,0x03,(byte)0x84,0x00,0x00,0x07,0x08,0x00,0x00};
        
        Packet p = new Packet(Commands.UNKN61A1, Commands.TELL, Commands.SERVER, ps.getPacketID(), latency);
        this.addOutPacket(server, socket, p);
    }

    void sendIDhnpairs(ServerThread server, SocketChannel socket) {
        // get the handles tied to this user id (max 3)
        String userid = clients.findClient(socket).getUserID();
        HNPairs hn = db.getHNPairs(userid);
        
        Packet p = new Packet(Commands.IDHNPAIRS, Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), hn.getArray());
        this.addOutPacket(server, socket, p);
    }
    
    // id/hn is chosen by client, the server sends back the ID as answer
    // id and handle are encrypted
    void sendHNselect(ServerThread server, SocketChannel socket, Packet ps) {
        Packet p;
        byte[] chosen = new byte[8];
        HNPair hn = ps.getDecryptedHNpair();
        
        // update client
        clients.findClient(socket).setHNPair(hn);

        // on ****** we have to create a new handle and save it to the database
        if("******".equals(new String(hn.getHandle()))) {
            hn.createHandle(db);
            db.createNewHNPair(clients.findClient(socket));
        }
        
        // update name of the handle in database
        db.updateHNPair(clients.findClient(socket));

        // send chosen handle as answer
        chosen[0] = 0;
        chosen[1] = 6;
        System.arraycopy(hn.getHandle(), 0, chosen, 2, 6);
        p = new Packet(Commands.HNSELECT, Commands.TELL, Commands.SERVER, ps.getPacketID(), chosen);
        this.addOutPacket(server, socket, p);
        
        String userid = clients.findClient(socket).getUserID();
        int gamenr = db.getGameNumber(userid);
        // ask for info if user is coming from a game
        if(gamenr>0) {
            // get statistics for this game and player
            p = new Packet(Commands.POSTGAMEINFO, Commands.QUERY, Commands.SERVER, this.getNextPacketID());
            this.addOutPacket(server, socket, p);
        }
        
        // this signals the end of the login procedure!
        p = new Packet(Commands.UNKN6104, Commands.BROADCAST, Commands.SERVER, this.getNextPacketID());
        this.addOutPacket(server, socket, p);
    }
     
    //<BODY><LF=6><CENTER>HELLO<LF=1></END>
    // sending the requested message of the day
    void sendMotheday(ServerThread server, SocketChannel socket, Packet ps) {
        //byte[] motd = {0};    // no message
        //MessageOfTheDay motd = new MessageOfTheDay(1, db.getMOTD());
        String md = db.getMOTD();
        if(md.isEmpty()){
            md = "<BODY><LF=6><CENTER>HELLO<LF=1></END>";
        }
        MessageOfTheDay motd = new MessageOfTheDay(1, md);
        Packet p = new Packet(Commands.MOTHEDAY, Commands.TELL, Commands.SERVER, ps.getPacketID(), motd.getPacket());
        this.addOutPacket(server, socket, p);
    }
    
    void sendCharSelect(ServerThread server, SocketChannel socket, Packet ps) {
        Client cl = clients.findClient(socket);
        cl.setCharacterStats(ps.getCharacterStats());
        
        Packet p = new Packet(Commands.CHARSELECT, Commands.TELL, Commands.SERVER, ps.getPacketID());
        this.addOutPacket(server, socket, p);
        broadcastCharSelect(server,socket);
    }
    
    void broadcastCharSelect(ServerThread server, SocketChannel socket) {
        ByteBuffer retval = ByteBuffer.wrap(new byte[1024]);
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        retval.putShort((short) cl.getHNPair().getHandle().length);
        retval.put(cl.getHNPair().getHandle());        
        retval.putShort((short)cl.getCharacterStats().length);
        retval.put(cl.getCharacterStats());

        byte[] r = new byte[retval.position()];
        retval.rewind();
        retval.get(r);
        Packet p = new Packet(0x6191, Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), r);
        this.broadcastInRoom(server, p, area, room);
    }

    // this one sends the sizes of the following 6882 packets!
    // Currently this is file1 only    
    void send6881(ServerThread server, SocketChannel socket, Packet ps) {
        //byte[] datacount = {0x01, 0,0,0x12,0x5D};
        ByteBuffer retval = ByteBuffer.wrap(new byte[100]);
        byte count = 4;
        retval.put(count); //bin count //0:area info 1~3:event?
        for(int i = 0;i<count;i++){
            int len = Packet6882.getlen(i);
            retval.putInt(len);
        }
        byte[] datacount = new byte[retval.position()];
        retval.rewind();
        retval.get(datacount); 
        Packet p = new Packet(Commands.UNKN6881, Commands.TELL, Commands.SERVER, ps.getPacketID(), datacount);
        this.addOutPacket(server, socket, p);
    }
    
    void send6882(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] pl = ps.getPayload();
        int nr = pl[0];
        int offset = (((int)pl[1]<<24)&0xFF000000) |
                     (((int)pl[2]<<16)&0x00FF0000) |
                     (((int)pl[3]<<8) &0x0000FF00) |
                     (((int)pl[4])    &0x000000FF);
        int sizeL =  (((int)pl[5]<<24)&0xFF000000) |
                     (((int)pl[6]<<16)&0x00FF0000) |
                     (((int)pl[7]<<8) &0x0000FF00) |
                     (((int)pl[8])    &0x000000FF);
        //byte[] data = Packet6881.getData(nr, offset, sizeL);
        byte[] data = Packet6882.getData(nr, offset, sizeL);
        
        Packet p = new Packet(Commands.UNKN6882, Commands.TELL, Commands.SERVER, ps.getPacketID(), data);
        this.addOutPacket(server, socket, p);
    }
    
    //might size like 6881
    void send6883(ServerThread server, SocketChannel socket, Packet ps) {
        ByteBuffer retval = ByteBuffer.wrap(new byte[9]);
        retval.put(ps.getPayload()[0]); //number
        int len = Packet6882.getlen(ps.getPayload()[0]);
        retval.putInt(len); //size?
        retval.putInt(0); //unk
        byte[] r = new byte[retval.position()];
        retval.rewind();
        retval.get(r);   
        Packet p = new Packet(Commands.UNKN6883, Commands.TELL, Commands.SERVER, ps.getPacketID(), datacount);
        this.addOutPacket(server, socket, p);
    }
    
    void send670f(ServerThread server, SocketChannel socket, Packet ps) {
        int nr = ps.getPayload()[0];
        //Client cl = clients.findClient(socket);
        //cl.setArea(nr);
      
        Packet p = new Packet(Commands.UNKN670f, Commands.TELL, Commands.SERVER, ps.getPacketID());
        this.addOutPacket(server, socket, p);        
    }
    
    //plaza exit
    void send6306(ServerThread server, SocketChannel socket, Packet ps) {
        Client cl = clients.findClient(socket);
        Packet p = new Packet(Commands.UNKN6306, Commands.TELL, Commands.SERVER, ps.getPacketID());
        this.addOutPacket(server, socket, p);        
        int area = cl.getArea();
        cl.setArea(0);
        db.updateClientOrigin(cl.getUserID(), STATUS_LOBBY, 0, 0, 0);
        this.broadcastAreaPlayerCnt(server, socket, area);
    }
    
    //notify plaza remove
    void broadcastPlazaRemove(ServerThread server, SocketChannel socket) {
        ByteBuffer retval = ByteBuffer.wrap(new byte[1024]);
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        byte[] s = "<BODY><LF=6><CENTER>Area Remove<LF=1></END>".getBytes();
        retval.putShort((short) s.length);
        retval.put(cl.getHNPair().getHandle());        

        byte[] r = new byte[retval.position()];
        retval.rewind();
        retval.get(r);
        Packet p = new Packet(0x6307, Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), r);
        //this.broadcastInRoom(server, p, area, room);
        this.broadcastInArea(server, p, area);
    }
    
    void send640d(ServerThread server, SocketChannel socket, Packet ps) {
        int slotnr = ps.getNumber();
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();

        byte[] description;
        description = slots.getDescription(area, room, slotnr);
        ByteBuffer broadcast = ByteBuffer.wrap(new byte[1024]);
        broadcast.putShort((short)slotnr);
        broadcast.putShort((short) description.length);
        broadcast.put(description);
        
        byte[] r = new byte[broadcast.position()];
        broadcast.rewind();
        broadcast.get(r);
        Packet p = new Packet(Commands.UNKN640d, Commands.TELL, Commands.SERVER, ps.getPacketID(), r);
        this.addOutPacket(server, socket, p); 
    }
        //should I broadcast 6890 periodically to sync players time?
        void send6890(ServerThread server, SocketChannel socket, Packet ps) {
        int sec = (int) (new Date().getTime()/1000);
        ByteBuffer r = ByteBuffer.wrap(new byte[4]);
        r.putInt(sec);
        byte[] time = r.array();
        Packet p = new Packet(Commands.UNKN6890, Commands.TELL, Commands.SERVER, ps.getPacketID(), time);
        this.addOutPacket(server, socket, p);
        }
        
        void send6144(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] time = {0,0,0,1};    // no message
        Packet p = new Packet(Commands.UNKN6144, Commands.TELL, Commands.SERVER, ps.getPacketID(), time);
        this.addOutPacket(server, socket, p);
        }
        
        //server name!!!
        void send68c0(ServerThread server, SocketChannel socket, Packet ps) {
        ps.getDecryptedString();
        byte[] handle ="test".getBytes();
        ByteBuffer mess = ByteBuffer.wrap(new byte[handle.length+2]);
        mess.putShort((short) handle.length);
        mess.put(handle);
        Packet p = new Packet(Commands.UNKN68c0, Commands.TELL, Commands.SERVER, ps.getPacketID(), mess.array());
        this.addOutPacket(server, socket, p);
        }
        
        //season? 
        //seems buggy
        //should I broadcast 6892 periodically to sync players time?
        void send6892(ServerThread server, SocketChannel socket, Packet ps) {

        ByteBuffer retval = ByteBuffer.wrap(new byte[1024]);
        int sec = (int) (new Date().getTime()/1000);
        int inteval  = 3600;
        //retval.putInt(sec%inteval); //current time?
        retval.putInt(sec); //current time?
        retval.putInt(inteval); //total length? tick count?
        retval.putInt((int)((((sec%inteval*1.0)%3)/inteval/3)*6)); //how many days left to change season? (max 5?) (unk)
        retval.putInt((int)(((sec%inteval*1.0)/inteval)*3)); //current season (0 = orange, 1 = blue, 2 = green)?
        retval.putInt(0); //unk
        byte[] r = new byte[retval.position()];
        retval.rewind();
        retval.get(r);
            Packet p = new Packet(Commands.UNKN6892, Commands.TELL, Commands.SERVER, ps.getPacketID(), r);
        this.addOutPacket(server, socket, p);
        }
        
        //more analysing need
        //monster price list?
        void send6884(ServerThread server, SocketChannel socket, Packet ps) {

        byte nr = ps.getPayload()[0];
        
        ByteBuffer retval = ByteBuffer.wrap(new byte[0x300]);
        retval.put(nr);
        retval.putShort((short)0x300);
        //temp value
        //Should be changed to be handled by sql.
        short monster_price[] = {1500,8000,0,0,0,1500,10000,1500,0,0,1500,0,0,1500,1500,0,1500,0,0,1500,1500,1500,0,0,0,1500,1500,1500,0,0,1500,0,8000,0,0,10000,1500,1500,1500,1500,1500,1500,1500,1500,1500,1500,1500,1500,1500,1500,1500,1500,1500,1500,1500,0,0,1500,8000,8000,0,0,0,1500,1500,0,1500,0,0,0,8000,1500,0};
        
        if(nr ==0){
            //wanted
            //need more investigate

        }else if(nr == 1){
            //monster price
            //temporary value
            for(int i= 0;i<monster_price.length;i++){
                retval.put((byte)((monster_price[i])&0xff));
                retval.put((byte)((monster_price[i]>>8)&0xff));
            }
        }else if(nr == 2){
            //monster price changes
            //temporary value
            for(int i= 0;i<monster_price.length;i++){
                byte changes = (byte)0xff;
                retval.put(changes);                
            }
        }else if(nr ==3){
            //percentage of observatory
            //temporary value
            //Even if it exceeds 100%, It doesn't mean Elder Dragon Interception start automatically.
            //it seems that it can be specified the time when Old dragon Interception is started and when it is finished.(each int32)
            //Depending on the time, quest id specified at 0x2x?, 0x24? is activated.
            byte[] arr = new byte[0x29];
            retval.put(arr);
            retval.put((byte)20);
            retval.put((byte)20);
        }
        byte[] r = new byte[retval.position()];
        retval.rewind();
        retval.get(r);
        r[1] = (byte)((r.length>>8)&0xff);
        r[2] = (byte)((r.length)&0xff);
        Packet p = new Packet(Commands.UNKN6884, Commands.TELL, Commands.SERVER, ps.getPacketID(), r);
        this.addOutPacket(server, socket, p);
        }
        
        void send620b(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] retval = {0,0,0};    // no message
        int nr = ps.getNumber();
        retval[0] = (byte) ((nr >> 8)&0xff);
        retval[1] = (byte) (nr &0xff);
        retval[2] = (byte) 0;
        Packet p = new Packet(Commands.UNKN620b, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);
        }
        
        void send631b(ServerThread server, SocketChannel socket, Packet ps) {
        //16 -> area num?
        //8 ->unk
        //8 ->unk
        //8 ->unk
        //8 ->unk
        byte[] retval = {0,0,0,0,0,0};    // no message
        int nr = ps.getNumber();
        retval[0] = (byte) ((nr >> 8)&0xff);
        retval[1] = (byte) (nr &0xff);
        Packet p = new Packet(Commands.UNKN631b, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);
        }
        
        //  unk
        void send6312(ServerThread server, SocketChannel socket, Packet ps) {
        //8 ->num
        //16 ->unk?
        byte[] retval = {0,0,0};    // no message
        retval[1] = ps.getPayload()[0];
        retval[2] = ps.getPayload()[1];
        Packet p = new Packet(Commands.UNKN6312, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);
        }
        //  unk
        void send6313(ServerThread server, SocketChannel socket, Packet ps) {
        //8 ->num
        //16 ->unk?
        byte[] retval = {0,0,0};    // no message
        retval[1] = ps.getPayload()[0];
        retval[2] = ps.getPayload()[1];
        Packet p = new Packet(Commands.UNKN6313, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);
        }
        
        // unk
        void send6314(ServerThread server, SocketChannel socket, Packet ps) {
        //8 ->num
        //16 ->unk?
        byte[] retval = {0,0,0};    // no message
        retval[1] = ps.getPayload()[0];
        retval[2] = ps.getPayload()[1];
        Packet p = new Packet(Commands.UNKN6314, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);
        }
        
        //quest related?
        //quest name/desc get from all?
        void send650c(ServerThread server, SocketChannel socket, Packet ps) {
        //16 ->nr?
        //string -> unk
        int nr = ps.getNumber();
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        Slot slot = slots.getSlot(area, room, nr);
        byte str[] = slot.getDescription();
        ByteBuffer retval = ByteBuffer.wrap(new byte[1024]);
        retval.putShort((short)nr); //temp value
        retval.putShort((short)str.length);
        retval.put(str);
        byte[] r = new byte[retval.position()];
        retval.rewind();
        retval.get(r);
        Packet p = new Packet(Commands.UNKN650c, Commands.TELL, Commands.SERVER, ps.getPacketID(), r);
        this.addOutPacket(server, socket, p);
        }
        
        //quest related?
        //quest name/desc set for quests?
        //assum slot desc.. should I make new field to slots?
        void send650b(ServerThread server, SocketChannel socket, Packet ps) {
        //16 ->nr?
        //string -> unk
        byte[] temp = ps.getDecryptedString();
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        int slotnr = cl.getSlot();
        Slot slot = slots.getSlot(area, room, slotnr);
        slot.setDescription(temp);
        Packet p = new Packet(Commands.UNKN650b, Commands.TELL, Commands.SERVER, ps.getPacketID());
        this.addOutPacket(server, socket, p);
        }
        
        
    void send6148(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] time = {0};
        Packet p = new Packet(Commands.UNKN6148, Commands.TELL, Commands.SERVER, ps.getPacketID(), time);
        this.addOutPacket(server, socket, p);
    }

    

    //it might contain monster killing record for updating monster price
    void send6139(ServerThread server, SocketChannel socket, Packet ps) {
        //SetSendData16
        int nr = ps.getNumber(); //quest num
        //unk
        //byte[] record = ps.getPayload();
        Packet p = new Packet(Commands.UNKN6139, Commands.TELL, Commands.SERVER, ps.getPacketID());
        this.addOutPacket(server, socket, p);
    }
    
    //room desc set if room status  = 1?
    void send6310(ServerThread server, SocketChannel socket, Packet ps) {
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int nr = ps.getNumber();
        Packet p = new Packet(Commands.UNKN6310, Commands.TELL, Commands.SERVER, ps.getPacketID());
        this.addOutPacket(server, socket, p);
    }

    // helper function
    private byte calc_shift(byte i, byte p) {
        byte[] fixval = {  21,   23,   10,   17,   23,   19,    6,   13};
        byte[] masks  = {0x33, 0x30, 0x3c, 0x34, 0x2d, 0x30, 0x3c, 0x34};
        return(byte) (fixval[i&7] - (i&(byte)0xf8) - p + ((p - 9 + i)&masks[i&7])*2);
    }
    private int decryptBuff(byte[] b, int offset,int pid) {
        int mlen = ((((int)b[offset]&0xff) << 8)|((int)b[offset+1]&0xff)) -2;
        for(int i=0; i<mlen; i++) b[offset+4+i] = (byte) (b[offset+4+i] ^ calc_shift((byte)i, (byte)(pid & (byte)0xff)));
        return mlen;
    }
    
    //temp not working
    void send6149(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] retval = {0};    // no message
        int offset =0;
        int nr = ps.getNumber();
        offset +=2;
        byte[] pl = ps.getPayload();
        byte[] pt = pl.clone();
        int len = decryptBuff(pt, offset, ps.getPacketID());
        byte[] temp = new byte[len];
        System.arraycopy(pt, 4+offset, temp, 0, len);
        offset += 4+len;
        Logging.println(new String(temp));
        Packet p = new Packet(Commands.UNKN6149, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);
    }
    void send660f(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] permit = {1};    // no message
        Packet p = new Packet(Commands.UNKN660f, Commands.TELL, Commands.SERVER, ps.getPacketID(), permit);
        this.addOutPacket(server, socket, p);
    }
    
    //not work bad stub?
    void send614d(ServerThread server, SocketChannel socket, Packet ps) {
        //GetRecvData16(0x894d60,0x8f8ea0);
        //GetRecvDataString(DAT_00894d58,0x8f8ea0);
        //byte[] str = "Test string".getBytes();
        byte[] str = "".getBytes();
        ByteBuffer retval = ByteBuffer.wrap(new byte[1024]);
        retval.putShort((short)0); //temp value
        retval.putShort((short)str.length);
        retval.put(str);
        byte[] r = new byte[retval.position()];
        retval.rewind();
        retval.get(r);
        Packet p = new Packet(Commands.UNKN614d, Commands.TELL, Commands.SERVER, ps.getPacketID(), r);
        this.addOutPacket(server, socket, p);
    }
    
   void send68a0(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] retval = {0,0,0,0,0};
        Packet p = new Packet(Commands.UNKN68a0, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);
    }
   
   void send68a1(ServerThread server, SocketChannel socket, Packet ps) {
       
        //byte[] name = "Testgame".getBytes();
        byte[] name = "".getBytes();
        byte[] retval = new byte[name.length + 2];
        retval[0] = (byte) ((name.length >> 8)&0xff);
        retval[1] = (byte) (name.length &0xff);
        System.arraycopy(name, 0, retval, 2, name.length);
        Packet p = new Packet(Commands.UNKN68a1, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);
    }
   
   //current place
   //first ploblem area, room, slot are 0 if return from quest
   //second if entered with this, doesn't send broadcastRoomPlayerComing
   void send6891(ServerThread server, SocketChannel socket, Packet ps) {
       
       Client cl = clients.findClient(socket);
       int area = cl.getArea();
       int room = cl.getRoom();
       int slot = cl.getSlot(); 
       /*
       area = 2;
       room = 3;
       slot = 0; 
       */
       cl.setArea(area);
       cl.setRoom(room);
       cl.setSlot(0);
       byte[] retval = {0,0,0,0,0,0};    // no message
       retval[0] = (byte)((area >> 8) & 0xff);
       retval[1] = (byte)(area & 0xff);
       retval[2] = (byte)((room >> 8) & 0xff);
       retval[3] = (byte)(room & 0xff);
       retval[4] = (byte)((slot >> 8) & 0xff);
       retval[5] = (byte)(slot & 0xff);
        Packet p = new Packet(Commands.UNKN6891, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);
    }
    
    //unk
    void send630d(ServerThread server, SocketChannel socket, Packet ps) {
       int nr = ps.getNumber();
       ByteBuffer retval = ByteBuffer.wrap(new byte[1024]);
       retval.putShort((short)nr);
  
        retval.putInt(0);
        
        byte[] r = new byte[retval.position()];
        retval.rewind();
        retval.get(r);        
        Packet p = new Packet(Commands.UNKN630d, Commands.TELL, Commands.SERVER, ps.getPacketID(), r);
        this.addOutPacket(server, socket, p);
    }

    //might quest slot attribute?
    void send6415(ServerThread server, SocketChannel socket, Packet ps) {
        byte b[] = ps.getPayload();
        int nr = (((int)ps.getPayload()[0] << 8)|((int)ps.getPayload()[1]));
        Packet p = new Packet(Commands.UNKN6415, Commands.TELL, Commands.SERVER, ps.getPacketID());
        this.addOutPacket(server, socket, p);
    }

    
    void send630a(ServerThread server, SocketChannel socket, Packet ps) {
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = (((int)ps.getPayload()[0] << 8)|((int)ps.getPayload()[1])); // skip the sum

        byte [] playerstats = clients.getPlayerStats2(area, room);
        Packet p = new Packet(Commands.UNKN630a, Commands.TELL, Commands.SERVER, ps.getPacketID(), playerstats);
        this.addOutPacket(server, socket, p); 
    }
   
     
    
    // request player rankings per area
    // for the moment we are sending the same (empty) rankings for every area
    // areanumber, x1, x2, x3, x4, points rank 7204th, cleartime rank 16998th, 13500 points, x8, x9, x10
    // status(1 alive), character, sizeid, id, size handle, handle
    void sendRankings(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] emptyrankings = {0x00,0x00,
                      0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00, 0x00, 0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00, 
                      0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x00, 0x00,0x00,0x00,0x000, 0x00,0x00,0x00,0x00,
                      0x00, 0x00, 0x00,0x06, 0x20,0x20,0x20,0x20,0x20,0x20,
                                  0x00,0x10, 0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,
                      0x00, 0x00, 0x00,0x06, 0x20,0x20,0x20,0x20,0x20,0x20,
                                  0x00,0x10, 0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,
                      0x00, 0x00, 0x00,0x06, 0x20,0x20,0x20,0x20,0x20,0x20,
                                  0x00,0x10, 0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,
                      0x00, 0x00, 0x00,0x06, 0x20,0x20,0x20,0x20,0x20,0x20,
                                  0x00,0x10, 0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,
                      0x00, 0x00, 0x00,0x06, 0x20,0x20,0x20,0x20,0x20,0x20,
                                  0x00,0x10, 0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,
                      0x00, 0x00, 0x00,0x06, 0x20,0x20,0x20,0x20,0x20,0x20,
                                  0x00,0x10, 0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20,0x20};
    
        // ranking for which area is requested
        emptyrankings[0] = ps.getPayload()[0];
        emptyrankings[1] = ps.getPayload()[1];
        
        ByteBuffer r = ByteBuffer.wrap(new byte[2+8+1+7*4+6*(1+1+2+6+2+16)]);
//        if(ps.getPayload()[1] == 5) {
            r.putShort((short) ps.getPayload()[1]);     // scenario: 0 WT,1 UB,2 FB,3 DT,4 EOTR,5 E1,6 E2,7 E3,8 S1,9 S2,A S3
            r.putInt((int) 111*100);
            r.putInt((int) ps.getPayload()[1]);
            r.put((byte) 0);
            r.putInt((int) 310*10);
            r.putInt((int) 320*10);
            r.putInt((int) 330*100);    // rank cleartime
            r.putInt((int) 340*100);
            r.putInt((int) 350);
            r.putInt((int) 360*100);
            r.putInt((int) 370);
            for(int t=0; t<6; t++) {
                r.put((byte) 1);                        // status: 1=alive
                r.put((byte) t);                        // character
                r.putShort((short) 6);                  // handle length
                r.put("HANDLE".getBytes());             // handle
                r.putShort((short) 16);                 // fixed, rest is spaced 0x20
                r.put((byte) (0x41+t));         // 1st byte of name to mark
                r.put("- RANKTEST     ".getBytes());   // name
            }
            // looks like first half  = ranking with resultpoints
            //            second hald = ranking with cleartimepoints
            emptyrankings = r.array();
//        }
        
        Packet p = new Packet(Commands.RANKINGS, Commands.TELL, Commands.SERVER, ps.getPacketID(), emptyrankings);
        this.addOutPacket(server, socket, p);
    }
    
    void sendAreaCount(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] areacount = {0, 10};

        areacount[0] = (byte) ((areas.getAreaCount() >> 8)&0xff);
        areacount[1] = (byte) (areas.getAreaCount() &0xff);
        
        Packet p = new Packet(Commands.AREACOUNT, Commands.TELL, Commands.SERVER, ps.getPacketID(), areacount);
        this.addOutPacket(server, socket, p);
    }
    
    void broadcastAreaPlayerCnt(ServerThread server, SocketChannel socket, int nr) {
        byte[] areaplayercount = {0,0, 0,0, 0,0, (byte)0xff,(byte)0xff, 0,0};
        int[] cnt = clients.countPlayersInArea(nr);
        //cnt[2] = cnt[2] + clients.countPlayersInRoom(51, 0) + this.gameserverpackethandler.countInGamePlayers();    // TODO: check it
        int maxplayer = 200; // todo: move to proper position
        areaplayercount[0] = (byte) ((nr >> 8)&0xff);
        areaplayercount[1] = (byte) (nr &0xff);
        areaplayercount[2] = (byte) ((cnt[0] >> 8)&0xff);
        areaplayercount[3] = (byte) (cnt[0] &0xff);
        areaplayercount[4] = (byte) ((cnt[1] >> 8)&0xff);
        areaplayercount[5] = (byte) (cnt[1] &0xff);
        areaplayercount[6] = (byte) ((maxplayer >> 8)&0xff);
        areaplayercount[7] = (byte) (maxplayer &0xff);
        areaplayercount[8] = (byte) ((cnt[2] >> 8)&0xff);
        areaplayercount[9] = (byte) (cnt[2] &0xff);
        
        Packet p = new Packet(Commands.AREAPLAYERCNT, Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), areaplayercount);
        //this.broadcastInAreaNAreaSelect(server, p, nr);
        this.broadcastPacket(server,p);
    }
    
    void sendAreaPlayercnt(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] areaplayercount = {0,0, 0,0, 0,0, (byte)0xff,(byte)0xff, 0,0};
        int nr = ps.getNumber();
        Client cl = clients.findClient(socket);
        int[] cnt = clients.countPlayersInArea(nr);
        int maxplayer = 200; // todo: move to proper position
        areaplayercount[0] = (byte) ((nr >> 8)&0xff);
        areaplayercount[1] = (byte) (nr &0xff);
        areaplayercount[2] = (byte) ((cnt[0] >> 8)&0xff);
        areaplayercount[3] = (byte) (cnt[0] &0xff);
        areaplayercount[4] = (byte) ((cnt[1] >> 8)&0xff);
        areaplayercount[5] = (byte) (cnt[1] &0xff);
        areaplayercount[6] = (byte) ((maxplayer >> 8)&0xff);
        areaplayercount[7] = (byte) (maxplayer &0xff);
        areaplayercount[8] = (byte) ((cnt[2] >> 8)&0xff);
        areaplayercount[9] = (byte) (cnt[2] &0xff);
        
        Packet p = new Packet(Commands.AREAPLAYERCNT, Commands.TELL, Commands.SERVER, ps.getPacketID(), areaplayercount);
        this.addOutPacket(server, socket, p);
    }
    
    void sendAreaStatus(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] areastatus = {0,0, 0};
        int nr = ps.getNumber();
        areastatus[0] = (byte) ((nr >> 8)&0xff);
        areastatus[1] = (byte) (nr &0xff);
        areastatus[2] = areas.getStatus(nr);
        
        Packet p = new Packet(Commands.AREASTATUS, Commands.TELL, Commands.SERVER, ps.getPacketID(), areastatus);
        this.addOutPacket(server, socket, p);        
    }
    
    void sendAreaName(ServerThread server, SocketChannel socket, Packet ps) {
        int nr = ps.getNumber();
        String areaname = areas.getName(nr);
        byte[] retval = new byte[areaname.length() + 4];
        retval[0] = (byte) ((nr >> 8)&0xff);
        retval[1] = (byte) (nr &0xff);
        retval[2] = (byte) ((areaname.length() >> 8)&0xff);
        retval[3] = (byte) (areaname.length() &0xff);
        System.arraycopy(areaname.getBytes(), 0, retval, 4, areaname.length());
        Packet p = new Packet(Commands.AREANAME, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);        
    }
    
    // request area description just like areaname
    void sendAreaDescript(ServerThread server, SocketChannel socket, Packet ps) {
        int nr = ps.getNumber();
        String areadesc = areas.getDescription(nr);
        byte[] retval = new byte[areadesc.length() + 4];
        retval[0] = (byte) ((nr >> 8)&0xff);
        retval[1] = (byte) (nr &0xff);
        retval[2] = (byte) ((areadesc.length() >> 8)&0xff);
        retval[3] = (byte) (areadesc.length() &0xff);
        System.arraycopy(areadesc.getBytes(), 0, retval, 4, areadesc.length());
        Packet p = new Packet(Commands.AREADESCRIPT, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);        
    }

    void sendAreaSelect(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] retval = {0,0};
        
        int nr = ps.getNumber();
        Client cl = clients.findClient(socket);
        cl.setArea(nr);
        db.updateClientOrigin(cl.getUserID(), STATUS_LOBBY, nr, 0, 0);
        retval[0] = (byte) ((nr >> 8)&0xff);
        retval[1] = (byte) (nr &0xff);
        Packet p = new Packet(Commands.AREASELECT, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);
        
        // tell others in area selection screen and area
        this.broadcastAreaPlayerCnt(server, socket, nr);
    }
    
    void sendRoomsCount(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] retval = {0,0};
        int nr = rooms.getRoomCount();
        //int nr = 0; //temp for dos stub for !!!! Temporarily set to make as if room is zero because there is a problem with the room packet in DOS
        retval[0] = (byte) ((nr >> 8)&0xff);
        retval[1] = (byte) (nr &0xff);
        Packet p = new Packet(Commands.ROOMSCOUNT, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);
    }
    
    // Count players in given room not in quests
    public int countPlayersInRoomNotIngame(int area, int room) {
        int retval = 0;
        Client cl;
        for(int i = 0; i<clients.getList().size(); i++) {
            cl = (Client) clients.getList().get(i);
            if(cl.getArea() == area && cl.getRoom() == room){
                Client gcl =  gameserverpackethandler.getClients().findClientBySession(cl.getsession());
                if(gcl == null){
                    retval++;
                }else{
                }
            }
        }
        return retval;
    }
    
    // Count players in given in quests.
    public int countPlayersInRoomIngmae(int area, int room) {
        int retval = 0;
        Client cl;
        for(int i = 0; i<clients.getList().size(); i++) {
            cl = (Client) clients.getList().get(i);
            if(cl.getArea() == area && cl.getRoom() == room){
                Client gcl =  gameserverpackethandler.getClients().findClientBySession(cl.getsession());
                if(gcl == null){
                }else{
                    retval++;
                }
            }
        }
        return retval;
    }

    void sendRoomPlayerCnt(ServerThread server, SocketChannel socket, Packet ps) {
        int area = clients.findClient(socket).getArea();
        int room = ps.getNumber();
        
        byte[] retval = {0x00,0x01, 0x00,0x00, 0x00,0x03, (byte)0xff,(byte)0xff, 0x00,0x00};
        retval[0] = (byte)((room >> 8) & 0xff);
        retval[1] = (byte)(room & 0xff);
        int cnt = clients.countPlayersInRoom(area, room);
        retval[2] = (byte)((cnt >> 8)& 0xff);
        retval[3] = (byte)(cnt & 0xff);
        //cnt = this.gameserverpackethandler.countInGamePlayers() + clients.countPlayersInRoom(51, 0);    // TODO: agl counting room specific
        cnt =  countPlayersInRoomIngmae(area, room);
        retval[4] = (byte)((cnt >> 8)& 0xff);
        retval[5] = (byte)(cnt & 0xff);
        int maxplayer = 10; // todo: move to proper position
        retval[6] = (byte) ((maxplayer >> 8)&0xff);
        retval[7] = (byte) (maxplayer &0xff);
        Packet p = new Packet(Commands.ROOMPLAYERCNT, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);
    }

    void broadcastRoomPlayerCnt(ServerThread server, int area, int room) {
        byte[] retval = {0x00,0x01, 0x00,0x00, 0x00,0x03, (byte)0xff,(byte)0xff, 0x00,0x00};
        retval[0] = (byte)((room >> 8) & 0xff);
        retval[1] = (byte)(room & 0xff);
        int cnt = clients.countPlayersInRoom(area, room);
        retval[2] = (byte)((cnt >> 8)& 0xff);
        retval[3] = (byte)(cnt & 0xff);
        //cnt = this.gameserverpackethandler.countInGamePlayers() + clients.countPlayersInRoom(51, 0);    // TODO: agl counting room specific
        cnt =  countPlayersInRoomIngmae(area, room);
        retval[4] = (byte)((cnt >> 8)& 0xff);
        retval[5] = (byte)(cnt & 0xff);
        int maxplayer = 10; // todo: move to proper position
        retval[6] = (byte) ((maxplayer >> 8)&0xff);
        retval[7] = (byte) (maxplayer &0xff);
        Packet p = new Packet(Commands.ROOMPLAYERCNT, Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), retval);
        this.broadcastInArea(server, p, area);
    }
    
    void broadcastRoomPlayerComing(ServerThread server, SocketChannel socket) {
        ByteBuffer retval = ByteBuffer.wrap(new byte[1024]);
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        byte[] status = cl.getCharacterStat();
        Packet p = new Packet(0x6411, Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), status);
        this.broadcastInRoomWithoutSender(server, p, area,room,cl);
    }
    
    void broadcastLeaveRoom(ServerThread server, SocketChannel socket) {
        ByteBuffer retval = ByteBuffer.wrap(new byte[1024]);
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        byte[] status = cl.getCharacterStat();
        Packet p = new Packet(0x6410, Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), status);
        this.broadcastInRoomWithoutSender(server, p, area,room,cl);
    }

    void sendRoomStatus(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] retval = {0x00,0x00, 0x00};
        int roomnr = ps.getNumber();
        Client cl = clients.findClient(socket);
        int areanr = cl.getArea();
        retval[0] = (byte) ((roomnr >> 8)&0xff);
        retval[1] = (byte) (roomnr &0xff);
        retval[2] = rooms.getStatus(areanr,roomnr);
        Packet p = new Packet(Commands.ROOMSTATUS, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);
    }
    
    void sendRoomName(ServerThread server, SocketChannel socket, Packet ps) {
        int roomnr = ps.getNumber();
        Client cl = clients.findClient(socket);
        int areanr = cl.getArea();
        String roomname = rooms.getName(areanr,roomnr);
        byte[] retval = new byte[roomname.length() + 4];
        retval[0] = (byte) ((roomnr >> 8)&0xff);
        retval[1] = (byte) (roomnr &0xff);
        retval[2] = (byte) ((roomname.length() >> 8)&0xff);
        retval[3] = (byte) (roomname.length() &0xff);
        System.arraycopy(roomname.getBytes(), 0, retval, 4, roomname.length());
        Packet p = new Packet(Commands.ROOMNAME, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);
    }
    
    /*
    void send6308(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] retval = {0x00,0x01, 0x00,0x02, (byte)0x81,0x40};
        retval[0] = ps.getPayload()[0];
        retval[1] = ps.getPayload()[1];
        Packet p = new Packet(Commands.UNKN6308, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);        
    }
    */
    
    // request room description just like roomname?
    //also broadcast needed?
    void send6308(ServerThread server, SocketChannel socket, Packet ps) {
        int nr = ps.getNumber();
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        String roomdesc = rooms.getDesc(area,nr);
        ByteBuffer retval = ByteBuffer.wrap(new byte[1024]);
        retval.putShort((short) nr);
        retval.putShort((short) roomdesc.getBytes().length);
        retval.put(roomdesc.getBytes());
        byte[] r = new byte[retval.position()];
        retval.rewind();
        retval.get(r);
        Packet p = new Packet(Commands.UNKN6308, Commands.TELL, Commands.SERVER, ps.getPacketID(), r);
        this.addOutPacket(server, socket, p);
    }
    
    void sendEnterRoom(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] retval = {0,0};
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = ps.getNumber();
        cl.setRoom(room);
        db.updateClientOrigin(cl.getUserID(), STATUS_LOBBY, area, room, 0);
        retval[0] = (byte) ((room >> 8)&0xff);
        retval[1] = (byte) (room &0xff);
        Packet p = new Packet(Commands.ENTERROOM, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);
        
        this.broadcastAreaPlayerCnt(server, socket, area);
        this.broadcastRoomPlayerCnt(server, area, room);
        this.broadcastRoomPlayerComing(server,socket);
    }

    void sendSlotCount(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] retval = {0,0};
        int cnt = slots.getSlotCount();
        retval[0] = (byte) ((cnt >> 8)&0xff);
        retval[1] = (byte) (cnt &0xff);
        Packet p = new Packet(Commands.SLOTCOUNT, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);
    }
    
    void sendSlotStatus(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] retval = {0x00,0x00, 0x00};
        int slotnr = ps.getNumber();
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        retval[0] = (byte) ((slotnr >> 8)&0xff);
        retval[1] = (byte) (slotnr &0xff);
        retval[2] = slots.getStatus(area, room, slotnr);
        Packet p = new Packet(Commands.SLOTSTATUS, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);
    }
    
    void broadcastSlotStatus(ServerThread server, int area, int room, int slot) {
        byte[] retval = {0x00,0x00, 0x00};
        retval[0] = (byte) ((slot >> 8)&0xff);
        retval[1] = (byte) (slot &0xff);
        retval[2] = slots.getStatus(area, room, slot);
        Packet p = new Packet(Commands.SLOTSTATUS, Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), retval);
        this.broadcastInSlotNRoom(server, p, area, room, slot);
    }

    void sendSlotPlayerStatus(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] retval = {0x00,0x00, 0x00,0x00, 0x00,0x00, 0x00,0x00, 0x00,0x00};
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        int slotnr = ps.getNumber();
        retval[0] = (byte) ((slotnr >> 8)&0xff);
        retval[1] = (byte) (slotnr &0xff);
        retval[3] = (byte) clients.countPlayersInSlot(area, room, slotnr);
        retval[5] = (byte) 0; // TODO: what is this value ?
        retval[7] = (byte) slots.getMaximumPlayers(area, room, slotnr);
        retval[9] = retval[3];  // TODO: what is playin2 ?
        Packet p = new Packet(Commands.SLOTPLRSTATUS, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);
    }
    
    void broadcastSlotPlayerStatus(ServerThread server, int area, int room, int slot) {
        byte[] retval = {0x00,0x00, 0x00,0x00, 0x00,0x00, 0x00,0x00, 0x00,0x00};
        retval[0] = (byte) ((slot >> 8)&0xff);
        retval[1] = (byte) (slot &0xff);
        retval[3] = (byte) clients.countPlayersInSlot(area, room, slot);
        retval[5] = (byte) 0; // TODO: what is this value ?
        retval[7] = (byte) slots.getMaximumPlayers(area, room, slot);
        retval[9] = retval[3];      // TODO: what is playerin2 ?
        Packet p = new Packet(Commands.SLOTPLRSTATUS, Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), retval);
        this.broadcastInSlotNRoom(server, p, area, room, slot);
    }

    void sendSlotTitle(ServerThread server, SocketChannel socket, Packet ps) {
        int slotnr = ps.getNumber();
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();

        byte[] slotname;

        slotname = slots.getName(area, room, slotnr);
        byte[] retval = new byte[slotname.length + 4];
        retval[0] = (byte) ((slotnr >> 8)&0xff);
        retval[1] = (byte) (slotnr &0xff);
        retval[2] = (byte) ((slotname.length >> 8)&0xff);
        retval[3] = (byte) (slotname.length &0xff);
        System.arraycopy(slotname, 0, retval, 4, slotname.length);
        Packet p = new Packet(Commands.SLOTTITLE, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p); 
    }
    
    void broadcastSlotAttrib2(ServerThread server, int area, int room, int slot) {
        byte[] retval = {0,1,   // slotnr 
                         0,4,   // max players for slot
                         0,4, 
                         0,1, 
                         0,4, 
                         0,1
                        };
        retval[0] = (byte) ((slot >> 8)&0xff);
        retval[1] = (byte) (slot &0xff);
        retval[3] = slots.getMaximumPlayers(area, room, slot);
        // TODO: what do these attributes mean? extend slots get/set with those
        Packet p = new Packet(Commands.SLOTATTRIB2, Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), retval);
        this.broadcastInSlotNRoom(server, p, area, room, slot);

    }

    void sendSlotAttrib2(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] retval = {0,1,   // slotnr 
                         0,4,   // max players for slot
                         0,4, 
                         0,1, 
                         0,4, 
                         0,1
                        };
        int slotnr = ps.getNumber();
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        retval[0] = (byte) ((slotnr >> 8)&0xff);
        retval[1] = (byte) (slotnr &0xff);
        retval[3] = slots.getMaximumPlayers(area, room, slotnr);
        // TODO: what do these attributes mean? extend slots get/set with those
        Packet p = new Packet(Commands.SLOTATTRIB2, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);
    }
    
    void sendPasswdProtect(ServerThread server, SocketChannel socket, Packet ps) {
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        int slot = ps.getNumber();
        byte[] retval = {0,1, 0};
        retval[0] = (byte) ((slot >> 8)&0xff);
        retval[1] = (byte) (slot &0xff);
        retval[2] = slots.getProtection(area, room, slot);
        Packet p = new Packet(Commands.SLOTPWDPROT, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);        
    }
    
    void broadcastPasswdProtect(ServerThread server, int area, int room, int slot) {
        byte[] retval = {0,1, 0};
        retval[0] = (byte) ((slot >> 8)&0xff);
        retval[1] = (byte) (slot &0xff);
        retval[2] = slots.getProtection(area, room, slot);
        Packet p = new Packet(Commands.SLOTPWDPROT, Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), retval);
        this.broadcastInRoom(server, p, area, room);
    }

    void sendSlotSceneType(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] retval = {0,0, 0,0, 0,0};
        int slotnr = ps.getNumber();
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        retval[0] = (byte) ((slotnr >> 8)&0xff);
        retval[1] = (byte) (slotnr &0xff);
        //retval[3] = slots.getSlotType(area, room, slotnr);
       // retval[5] = slots.getScenario(area, room, slotnr);   
        short slottype = slots.getSlotType(area, room, slotnr);
        short Scenetype = slots.getScenario(area, room, slotnr);   
        retval[2] = (byte) ((slottype >> 8)&0xff);
        retval[3] = (byte) (slottype &0xff);
        retval[4] = (byte) ((Scenetype >> 8)&0xff);
        retval[5] = (byte) (Scenetype &0xff);
        
        Packet p = new Packet(Commands.SLOTSCENTYPE, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);        
    }
    
    void broadcastSlotSceneType(ServerThread server, int area, int room, int slot) {
        byte[] retval = {0,0, 0,0, 0,0};
        retval[0] = (byte) ((slot >> 8)&0xff);
        retval[1] = (byte) (slot &0xff);
        //retval[3] = slots.getSlotType(area, room, slot);
       // retval[5] = slots.getScenario(area, room, slot);    
        short slottype = slots.getSlotType(area, room, slot);
        short Scenetype = slots.getScenario(area, room, slot);   
        retval[2] = (byte) ((slottype >> 8)&0xff);
        retval[3] = (byte) (slottype &0xff);
        retval[4] = (byte) ((Scenetype >> 8)&0xff);
        retval[5] = (byte) (Scenetype &0xff);
        Packet p = new Packet(Commands.SLOTSCENTYPE, Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), retval);
        this.broadcastInSlotNRoom(server, p, area, room, slot);
    }

    void sendRulesCount(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] retval = {0};
        int slotnr = ps.getNumber();    // slotnumber
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        retval[0] = slots.getRulesCount(area, room, slotnr);
        Packet p = new Packet(Commands.RULESCOUNT, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);
    }
    
    void sendRuleAttCount(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] retval = {0,0};
        int slotnr = ps.getNumber();    // slotnumber
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        retval[0] = ps.getPayload()[2];
        retval[1] = slots.getRulesAttCount(area, room, slotnr, ps.getPayload()[2]);
        Packet p = new Packet(Commands.RULEATTCOUNT, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);
    }
    
    void send6601(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] retval = {1, 0,0};
        int nr = ps.getNumber();
        retval[1] = (byte) ((nr >> 8)&0xff);
        retval[2] = (byte) (nr &0xff);
        Packet p = new Packet(Commands.UNKN6601, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);        
    }

    void send6602(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] retval = {1, 0,0};
        int nr = ps.getNumber();
        retval[1] = (byte) ((nr >> 8)&0xff);
        retval[2] = (byte) (nr &0xff);
        Packet p = new Packet(Commands.UNKN6602, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);        
    }

    void sendRuleDescript(ServerThread server, SocketChannel socket, Packet ps) {
        int slotnr = ps.getNumber();        // slotnumber
        byte rulenr = ps.getPayload()[2];   // rulenumber
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        String rulename = slots.getRuleName(area, room, slotnr, rulenr);
        byte[] retval = new byte[rulename.length() + 3];
        retval[0] = rulenr;
        retval[1] = (byte) ((rulename.length() >> 8)&0xff);
        retval[2] = (byte) (rulename.length() &0xff);
        System.arraycopy(rulename.getBytes(), 0, retval, 3, rulename.length());
        Packet p = new Packet(Commands.RULEDESCRIPT, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);         
    }
    
    void sendRuleValue(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] retval = {0,0};
        int slotnr = ps.getNumber();        // slotnumber
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        byte rulenr = ps.getPayload()[2];   // rulenumber
        retval[0] = rulenr;
        retval[1] = slots.getRuleValue(area, room, slotnr, rulenr);
        Packet p = new Packet(Commands.RULEVALUE, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p); 
    }
    
    void sendRuleattrib(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] retval = {0,0};
        int slotnr = ps.getNumber();        // slotnumber
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        byte rulenr = ps.getPayload()[2];   // rulenumber
        retval[0] = rulenr;
        retval[1] = slots.getRuleAttribute(area, room, slotnr, rulenr);
        Packet p = new Packet(Commands.RULEATTRIB, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);         
    }
    
    void sendAttrDescript(ServerThread server, SocketChannel socket, Packet ps) {
        int slotnr = ps.getNumber();        // slotnumber
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        byte rulenr = ps.getPayload()[2];   // rulenumber
        byte attnr = ps.getPayload()[3];    // attribute number
        String attdesc = slots.getRuleAttributeDescription(area, room, slotnr, rulenr, attnr);
        byte[] retval = new byte[attdesc.length() + 4];
        retval[0] = rulenr;
        retval[1] = attnr;
        retval[2] = (byte) ((attdesc.length() >> 8)&0xff);
        retval[3] = (byte) (attdesc.length() &0xff);
        System.arraycopy(attdesc.getBytes(), 0, retval, 4, attdesc.length());
        Packet p = new Packet(Commands.ATTRDESCRIPT, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);
    }
    
    void sendAttrAttrib(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] retval = {0, 0, 0};
        int slotnr = ps.getNumber();        // slotnumber
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        byte rulenr = ps.getPayload()[2];   // rulenumber
        byte attnr = ps.getPayload()[3];    // attribute number
        retval[0] = rulenr;
        retval[1] = attnr;
        retval[2] = slots.getRuleAttributeAtt(area, room, slotnr, rulenr, attnr);
        Packet p = new Packet(Commands.ATTRATTRIB, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);        
    }
    
    // get the statistics from participants
    void sendPlayerStats(ServerThread server, SocketChannel socket, Packet ps) {
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        int slot = cl.getSlot();
        int nr = ps.getNumber();            // slotnumber
        byte [] playerstats = clients.getPlayerStats(area, room, slot);
                
        Packet p = new Packet(Commands.PLAYERSTATS, Commands.TELL, Commands.SERVER, ps.getPacketID(), playerstats);
        this.addOutPacket(server, socket, p); 
    }
    
    // exiting the slotlist back to rooms
    void sendExitSlotlist(ServerThread server, SocketChannel socket, Packet ps) {
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        this.broadcastLeaveRoom(server,socket);
        cl.setRoom(0);
        db.updateClientOrigin(cl.getUserID(), STATUS_LOBBY, area, 0, 0);
        
        Packet p = new Packet(Commands.EXITSLOTLIST, Commands.TELL, Commands.SERVER, ps.getPacketID());
        this.addOutPacket(server, socket, p);
        this.broadcastAreaPlayerCnt(server, socket, area);
        this.broadcastRoomPlayerCnt(server, area, room);
    }
    //seems not exit area. but select area?
    void sendExitArea(ServerThread server, SocketChannel socket, Packet ps) {
        int nr = clients.findClient(socket).getArea();
        Client cl = clients.findClient(socket);
        
        //cl.setArea(0);
        byte[] pay = ps.getPayload();
        int area = (((int)ps.getPayload()[0] << 8)|((int)ps.getPayload()[1])); // skip the sum
        int room = (((int)ps.getPayload()[2] << 8)|((int)ps.getPayload()[3])); // skip the sum
        int slot = (((int)ps.getPayload()[4] << 8)|((int)ps.getPayload()[5])); // skip the sum

        cl.setArea(area);
        cl.setRoom(room);
        cl.setRoom(slot);
        db.updateClientOrigin(cl.getUserID(), STATUS_LOBBY, area, room, slot);
        
        Packet p = new Packet(Commands.EXITAREA, Commands.TELL, Commands.SERVER, ps.getPacketID());
        this.addOutPacket(server, socket, p);

        // let other clients know in area selection screen and area
        this.broadcastAreaPlayerCnt(server, socket, nr);
        this.broadcastAreaPlayerCnt(server, socket, area);
    }

   
    // These are the chat packages
    // chatting is shifted by client, server sends cleartext
    void broadcastChatOut(ServerThread server, SocketChannel socket, Packet ps) {
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        int slot = cl.getSlot();

        ByteBuffer broadcast = ByteBuffer.wrap(new byte[1024]);
        
        // who is sending the message
        broadcast.put(cl.getHNPair().getHNPair());
        
        // copy message and save to database
        byte[] mess = ps.getChatOutData();
        broadcast.putShort((short) mess.length);
        broadcast.put(mess);
//        retval.put((byte)0x04); // not working in area 
        broadcast.put((byte) 0);
        broadcast.putInt(0x000000ff);
        
        byte[] r = new byte[broadcast.position()];
        broadcast.rewind();
        broadcast.get(r);

        Packet p = new Packet(Commands.CHATOUT,Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), r);
        /*
        if(slot > 0) {
            this.broadcastInSlot(server, p, area, room, slot);
        } else if(area != 0 && area != 51){
            this.broadcastInArea(server, p, area);
        } else if(cl.gamenumber > 0) {
            this.broadcastInAgl(server, p, cl.gamenumber);
        }
        */
        if(area != 0 && area != 51 && room == 0){
            this.broadcastInArea(server, p, area);
        } else if(room > 0) {
            this.broadcastInRoom(server, p, area, room);
        }
    }
    
    // These are the chat packages
    // chatting is shifted by client, server sends cleartext
    void broadcastChatOut2(ServerThread server, SocketChannel socket, Packet ps) {
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        int slot = cl.getSlot();
        ByteBuffer broadcast = ByteBuffer.wrap(new byte[1024]);
        
        // who is sending the message
        broadcast.put(cl.getHNPair().getHNPair());
        
        // copy message and save to database
        byte[] mess = ps.getChatOutData();
        byte chattype = ps.getPayload()[4+mess.length+2-1]; //last bytes
        broadcast.putShort((short) mess.length);
        broadcast.put(mess);
        //broadcast.put((byte) 0);
        //broadcast.putInt(0x000000ff);
        broadcast.put((byte) 0);
        broadcast.put((byte) 0);
        broadcast.put((byte) 0);
        broadcast.put((byte) 0);
        broadcast.put((byte) chattype); //chat type //for dos //1 for whole area //2 for group only
        byte[] r = new byte[broadcast.position()];
        broadcast.rewind();
        broadcast.get(r);

        Packet p = new Packet(Commands.CHATOUT,Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), r);
        
        if(chattype == 1){
            this.broadcastInArea(server, p, area);
        } else if(chattype == 2) {
            this.broadcastInRoom(server, p, area, room);
        }
    }
    
    // These are the BinaryChat packages
    // chatting is shifted by client, server sends cleartext
    void broadcastBinaryChatOut(ServerThread server, SocketChannel socket, Packet ps) {
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        int slot = cl.getSlot();
        ByteBuffer broadcast = ByteBuffer.wrap(new byte[1024]);
        broadcast.putShort((short) cl.getHNPair().getHandle().length);
        broadcast.put(cl.getHNPair().getHandle());
        byte[] mess = ps.getChatOutData();
        //Logging.println(new String(mess));
        broadcast.putShort((short) mess.length);
        broadcast.put(mess);
        
        byte[] r = new byte[broadcast.position()];
        broadcast.rewind();
        broadcast.get(r);
        
        Packet p = new Packet(Commands.BINARYCHAT,Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), r);
        //To avoid shutter, should broadcast without sender
        /*
        if(slot > 0 && cl.gamenumber > 0) {
            this.broadcastInSlot(server, p, area, room, slot);
        } else if(area != 0 && area != 51 && room == 0){
            this.broadcastInArea(server, p, area);
        } else if(room > 0) {
            this.broadcastInRoom(server, p, area, room);
        }*/
        if(area != 0 && area != 51 && room == 0){
            this.broadcastInAreaWithoutSender(server, p, area,cl);
        } else if(room > 0) {
            this.broadcastInRoomWithoutSender(server, p, area, room,cl);
        }
    }
    
    void sendCreateSlot(ServerThread server, SocketChannel socket, Packet ps) {
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();

        byte[] answer = {0,0};
        int slot = ps.getNumber();    // slotnumber

        // set usage and playerstatus
        cl.setSlot(slot);
        db.updateClientOrigin(cl.getUserID(), STATUS_LOBBY, area, room, slot);
        cl.setHostFlag((byte) 1);
        cl.setPlayerNum((byte) 1);
        slots.getSlot(area, room, slot).setStatus(Slot.STATUS_INCREATE);
        slots.getSlot(area, room, slot).setLivetime();

        // set the room master
        slots.getSlot(area, room, slot).setHost(cl.getUserID());
        
        // broadcast status and playercount of slot
        broadcastSlotPlayerStatus(server, area, room, slot);
        broadcastSlotStatus(server, area, room, slot);
        
        // also answer the query
        answer[1] = (byte)(slot & 0xff);
        Packet p = new Packet(Commands.CREATESLOT, Commands.TELL, Commands.SERVER, ps.getPacketID(), answer);
        this.addOutPacket(server, socket, p);        
    }
    
    // 1st word is slottype: 0011 = DVD, 0012 = HDD
    // 2nd word are the scenes: wild things 0001, underbelly 0002, flashback 0003, desperate times 0004
    void sendSceneSelect(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] scenetype = {0,0, 0,0x12, 0,2};
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        int slot = cl.getSlot();

        // set scene and type, copy payload
        scenetype[1] = (byte)(slot & 0xff); //slot
        scenetype[2] = ps.getPayload()[0];  // type
        scenetype[3] = ps.getPayload()[1];  // type
        scenetype[4] = ps.getPayload()[2];  // scenario
        scenetype[5] = ps.getPayload()[3];  // scenario
        
        //slots.getSlot(area, room, slot).setSlotType(ps.getPayload()[1]);
        //slots.getSlot(area, room, slot).setSscenario(ps.getPayload()[3]);
       
        int slottyp = ((((int)ps.getPayload()[0] &0xff)<< 8)|((int)ps.getPayload()[1])&0xff);
        int scenetyp = ((((int)ps.getPayload()[2] &0xff)<< 8)|((int)ps.getPayload()[3])&0xff);
        slots.getSlot(area, room, slot).setSlotType((short)slottyp);
        slots.getSlot(area, room, slot).setSscenario((short)scenetyp);
        
        
        Packet p = new Packet(Commands.SCENESELECT, Commands.TELL, Commands.SERVER, ps.getPacketID(), scenetype);
        this.addOutPacket(server, socket, p);        
        // set usage and playerstatus
        if(cl.getHostFlag()==1) {
            slots.getSlot(area, room, slot).setStatus(Slot.STATUS_GAMESET);
            slots.getSlot(area, room, slot).setLivetime();
        }
        this.broadcastSlotPlayerStatus(server, area, room, slot);
        this.broadcastPasswdProtect(server, area, room, slot);
        this.broadcastSlotStatus(server, area, room, slot);
        this.broadcastSlotSceneType(server, area, room, slot);
        this.broadcastSlotAttrib2(server, area, room, slot);
        //this.broadcastPlayerOK(server, socket);
    }
    
    void broadcastSlotTitle(ServerThread server, int area, int room, int slot) {
        byte[] slottitle = slots.getSlot(area, room, slot).getName();
        // broadcast name
        byte[] broadcast = new byte[slottitle.length + 4];
        broadcast[0] = (byte)((slot >>8) & 0xff);
        broadcast[1] = (byte)(slot & 0xff);
        broadcast[2] = (byte)((slottitle.length >>8) & 0xff);
        broadcast[3] = (byte)(slottitle.length & 0xff);
        System.arraycopy(slottitle, 0, broadcast, 4, slottitle.length);
        Packet p = new Packet(Commands.SLOTTITLE, Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), broadcast);
        this.broadcastInSlotNRoom(server, p, area, room, slot);
    }
    
    void sendSlotName(ServerThread server, SocketChannel socket, Packet ps) {
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        int slot = cl.getSlot();
        
        // get the decrypted name from payload
        byte[] slottitle = ps.getDecryptedString();
        slots.getSlot(area, room, slot).setName(slottitle);
        
        // accept slottitle
        Packet p = new Packet(Commands.SLOTNAME, Commands.TELL, Commands.SERVER, ps.getPacketID());
        this.addOutPacket(server, socket, p);
        this.broadcastSlotTitle(server, area, room, slot);
    }
    
    void sendSetRule(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] retval = {1};
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        int slot = cl.getSlot();
        slots.getSlot(area, room, slot).setRuleValue(ps.getPayload()[0], ps.getPayload()[1]);
        
        Packet p = new Packet(Commands.SETRULE, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);
    }
    
    void send660c(ServerThread server, SocketChannel socket, Packet ps) {
        Packet p = new Packet(Commands.UNKN660C, Commands.TELL, Commands.SERVER, ps.getPacketID());
        this.addOutPacket(server, socket, p);        
    }
    
    // check the livetime of the slot
    // broadcast the autostart on zero
    // used for the East Town (area 0x001)
    void checkAutoStart(ServerThread server) {
        List cls = clients.getList();
        for(int i=0; i<cls.size(); i++) {
            Client cl = (Client) cls.get(i);
            int area = cl.getArea();
            int room = cl.getRoom();
            int slot = cl.getSlot();
            if(area == 1 && cl.gamenumber == 0 && slot != 0) {
                Long livetime = slots.getSlot(area, room, slot).getLivetime();
                if(livetime == 0) {
                    this.broadcastGetReady(server, cl.getSocket());
                }
            }
        }
    }
    
    void sendSlotTimer(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] timing = {0,0, 7,8};
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        int slot = ps.getNumber();
        Long livetime = slots.getSlot(area, room, slot).getLivetime();
        timing[1] = (byte) (slot & 0xff);
        timing[2] = (byte) ((livetime >> 8) & 0xff);
        timing[3] = (byte) (livetime & 0xff);

        Packet p = new Packet(Commands.SLOTTIMER, Commands.TELL, Commands.SERVER, ps.getPacketID(), timing);
        this.addOutPacket(server, socket, p);
        
        // when timer reaches 0 the game is started automatically
        if(livetime == 0) {
            this.broadcastGetReady(server, socket);
        }
    }
    
    void send6412(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] answer = {0,1, 0,0,0,0};
        int nr = ps.getNumber();    // slotnumber
        answer[1] = (byte) (nr & 0xff);
        Packet p = new Packet(Commands.UNKN6412, Commands.TELL, Commands.SERVER, ps.getPacketID(), answer);
        this.addOutPacket(server, socket, p);
    }
    
    void broadcastPlayerOK(ServerThread server, SocketChannel socket) {
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        int slot = cl.getSlot();

        byte[] playerok = {0,0, 0,0};
        playerok[1] = cl.getPlayerNum();
        Packet p = new Packet(Commands.PLAYEROK, Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), playerok);
        this.broadcastInSlot(server, p, area, room, slot);
    }
    
    // last packet from slot creator!
    void send6504(ServerThread server, SocketChannel socket, Packet ps) {
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        int slot = cl.getSlot();

        byte[] retval = {1};

        // just incase
        retval[0] = ps.getPayload()[0];
        //move to 0x6509
        /*
        // set usage and playerstatus
        if(cl.getHostFlag()==1) {
            slots.getSlot(area, room, slot).setStatus(Slot.STATUS_GAMESET);
            slots.getSlot(area, room, slot).setLivetime();
        }
        this.broadcastSlotPlayerStatus(server, area, room, slot);
        this.broadcastPasswdProtect(server, area, room, slot);
        this.broadcastSlotStatus(server, area, room, slot);
        this.broadcastSlotSceneType(server, area, room, slot);
        this.broadcastSlotAttrib2(server, area, room, slot);
        */
        this.broadcastPlayerOK(server, socket);
        Packet p = new Packet(Commands.UNKN6504, Commands.TELL, Commands.SERVER, ps.getPacketID(), retval);
        this.addOutPacket(server, socket, p);
    }
    
    // broadcast to all players in slot that the host cancelled the game
    void broadcastCancelSlot(ServerThread server, int area, int room, int slot,Client cl) {
        byte[] mess = new PacketString("<LF=6><BODY><CENTER>host cancelled game<END>").getData();
        
        Packet p = new Packet(Commands.CANCELSLOTBC, Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), mess);
        this.broadcastInSlotWithoutSender(server, p, area, room, slot,cl);
    }
    // broadcast to all players in slot that the host cancelled the game
    void broadcastCancelSlot(ServerThread server, int area, int room, int slot) {
        byte[] mess = new PacketString("<LF=6><BODY><CENTER>host cancelled game<END>").getData();
        
        Packet p = new Packet(Commands.CANCELSLOTBC, Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), mess);
        this.broadcastInSlot(server, p, area, room, slot);
    }

    // this is send when a client gets the rules but decides not to join
    // also when a host decides not to create a gameslot
    // AND when client or host leave the set gameslot
    void sendCancelSlot(ServerThread server, SocketChannel socket, Packet ps) {
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        int slot = cl.getSlot();
        byte ishost = cl.getHostFlag();
        
        // game creation is cancelled
        // reset slot and leave it
        if(ishost == 1) {
            cl.setHostFlag((byte) 0);
            slots.getSlot(area, room, slot).reset();
            //this.broadcastCancelSlot(server, area, room, slot);
            this.broadcastCancelSlot(server, area, room, slot,cl);
            this.broadcastPasswdProtect(server, area, room, slot);
            this.broadcastSlotSceneType(server, area, room, slot);
            this.broadcastSlotTitle(server, area, room, slot);
        }

        // normal players just leave
        this.broadcastLeaveSlot(server, socket);
        cl.setPlayerNum((byte) 0);
        cl.setSlot((byte) 0);        
        db.updateClientOrigin(cl.getUserID(), STATUS_LOBBY, area, room, 0);
        
        this.broadcastSlotAttrib2(server, area, room, slot);
        
        // set status back to let others in
        int n = clients.countPlayersInSlot(area, room, slot);
        if(n == 0) {
            slots.getSlot(area, room, slot).setStatus(Slot.STATUS_FREE);
        }
        if( (n<slots.getMaximumPlayers(area, room, slot)) && (n>0) && (ishost==0)) {
            slots.getSlot(area, room, slot).setStatus(Slot.STATUS_GAMESET);
        }
        
        this.broadcastSlotPlayerStatus(server, area, room, slot);
        this.broadcastSlotStatus(server, area, room, slot);
        
        Packet p = new Packet(Commands.CANCELSLOT, Commands.TELL, Commands.SERVER, ps.getPacketID());
        this.addOutPacket(server, socket, p); 
    }
    
    // this is sent to notify the players in slot about leaving
    void broadcastLeaveSlot(ServerThread server, SocketChannel socket) {
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        int slot = cl.getSlot();

        // broadcast to other players
        byte[] wholeaves = {0,6, 0,0,0,0,0,0};
        byte[] who = cl.getHNPair().getHandle();
        System.arraycopy(who, 0, wholeaves, 2, who.length);
        Packet p = new Packet(Commands.LEAVESLOT, Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), wholeaves);
        this.broadcastInSlot(server, p, area, room, slot);
    }
        
    void sendSlotPasswd(ServerThread server, SocketChannel socket, Packet ps) {
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        int slotnr = cl.getSlot();
        Slot slot = slots.getSlot(area, room, slotnr);
        slot.setPassword(ps.getDecryptedString());
        Packet p = new Packet(Commands.SLOTPASSWD, Commands.TELL, Commands.SERVER, ps.getPacketID());
        this.addOutPacket(server, socket, p);         
    }
    
    void sendSlotDescription(ServerThread server, SocketChannel socket, Packet ps) {
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        int slotnr = cl.getSlot();
        Slot slot = slots.getSlot(area, room, slotnr);
        slot.setDescription(ps.getDecryptedString());
        Packet p = new Packet(Commands.SLOTDESC, Commands.TELL, Commands.SERVER, ps.getPacketID());
        this.addOutPacket(server, socket, p);         
    }
        
    void sendPlayerCount(ServerThread server, SocketChannel socket, Packet ps) {
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        int slot = cl.getSlot();
        byte[] cnt = {1};
        cnt[0] = (byte) clients.countPlayersInSlot(area, room, slot);
        Packet p = new Packet(Commands.PLAYERCOUNT, Commands.TELL, Commands.SERVER, ps.getPacketID(),cnt);
        this.addOutPacket(server, socket, p);
    }
    
    void sendPlayerNumber(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] num = {0};
        num[0] = clients.findClient(socket).getPlayerNum();
        Packet p = new Packet(Commands.PLAYERNUMBER, Commands.TELL, Commands.SERVER, ps.getPacketID(),num);
        this.addOutPacket(server, socket, p);
    }
    
    void sendPlayerStat(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] status = {0, 0};
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        int slot = cl.getSlot();
        byte player = ps.getPayload()[0];   // query which player
        cl = clients.findClient(area, room, slot, player);
        if(cl != null) {
            status = cl.getPreGameStat(player);
        } else {                    // client left us :-(
            status[0] = player;     //TODO: not sure if this will help
        }
        Packet p = new Packet(Commands.PLAYERSTAT, Commands.TELL, Commands.SERVER, ps.getPacketID(),status);
        this.addOutPacket(server, socket, p);
    }
    
    void sendPlayerScore(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] score = {0x01, 0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00};

        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        int slot = cl.getSlot();

        // TESTpacket. Where can I see those ?
        ByteBuffer r = ByteBuffer.wrap(new byte[1+2+5*4]);
        r.put(ps.getPayload()[0]);  // player number
        r.putShort((short) slots.getScenario(area, room, slot));      // scenario ?
        r.putInt((int) 110);
        r.putInt((int) 220);
        r.putInt((int) 330);
        r.putInt((int) 440);
        r.putInt((int) 550);
        score = r.array();
        
        //TODO: send the scoring from ranklist for this player
        score[0] = ps.getPayload()[0];
        Packet p = new Packet(Commands.PLAYERSCORE, Commands.TELL, Commands.SERVER, ps.getPacketID(),score);
        this.addOutPacket(server, socket, p);
    }
    
    void sendGameSession(ServerThread server, SocketChannel socket, Packet ps) {
        ByteBuffer seq = ByteBuffer.wrap(new byte[19]);
        seq.putShort((short) 0x0f);
        seq.put(String.format("%015d", clients.findClient(socket).gamenumber).getBytes());
        seq.putShort((short) 0);

        Packet p = new Packet(Commands.GAMESESSION, Commands.TELL, Commands.SERVER, ps.getPacketID(),seq.array());
        this.addOutPacket(server, socket, p);
    }
    
    //send at quest start
    void sendDifficulty(ServerThread server, SocketChannel socket, Packet ps) {
        //short(string length)
        //String
        byte[] difficulty = {0x00, 0x10, 
                             0x01, 0x03, 0x00, 0x00, 0x00, 0x00, 
                             0x00, 0x00, 0x00, 0x00, 
                             0x00, 0x00, 0x00, 0x00, 0x00, 0x00 };
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        int slot = cl.getSlot();
        
        //TODO: here is sent more (different game modes). more tests!
        //difficulty[2] = 50; //50 min for area pvp //temp
        difficulty[3] = slots.getDifficulty(area, room, slot);
        difficulty[4] = slots.getFriendlyFire(area, room, slot);
        Packet p = new Packet(Commands.GAMEDIFF, Commands.TELL, Commands.SERVER, ps.getPacketID(),difficulty);
        this.addOutPacket(server, socket, p);
    }
    
    void sendGSinfo(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] gsinfo = {0x00,0x04, (byte)192, (byte)168, (byte)6, (byte)66,     //IP    192.168.6.66
                         0x00,0x02, 0x21, (byte)0xf2,               //PORT  8690
                         0x00, 0x00, 0x1e, 0x00};                   //???
        
        gsinfo[2] = this.gs_ip[0];
        gsinfo[3] = this.gs_ip[1];
        gsinfo[4] = this.gs_ip[2];
        gsinfo[5] = this.gs_ip[3];

        // TODO: usage of multiple gameservers
        Packet p = new Packet(Commands.GSINFO, Commands.TELL, Commands.SERVER, ps.getPacketID(), gsinfo);
        this.addOutPacket(server, socket, p);        
    }
    
    void send6002(ServerThread server, SocketChannel socket, Packet ps) {
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        int slot = cl.getSlot();
        
        // reset this clients' area/slot
        //cl.setArea((byte) 0);
        //cl.setRoom((byte) 0);
        //cl.setSlot((byte) 0);
        //cl.setPlayerNum((byte) 0);

        if (slot != 0) {
            // free slot for other players when the last player left
            if (clients.countPlayersInSlot(area, room, slot) == 0) {
                slots.getSlot(area, room, slot).reset();
                this.broadcastSlotPlayerStatus(server, area, room, slot);
                this.broadcastPasswdProtect(server, area, room, slot);
                this.broadcastSlotTitle(server, area, room, slot);
                this.broadcastSlotSceneType(server, area, room, slot);
                this.broadcastSlotAttrib2(server, area, room, slot);
                this.broadcastSlotStatus(server, area, room, slot);
            }
        }
        
        Packet p = new Packet(Commands.UNKN6002, Commands.TELL, Commands.SERVER, ps.getPacketID());
        this.addOutPacket(server, socket, p);
    }
    
    void broadcastAglPlayerCnt(ServerThread server, int gamenr) {
        byte[] cnt = {0,1};
        cnt[1] = clients.getPlayerCountAgl(gamenr);
        Packet p = new Packet(Commands.AGLPLAYERCNT, Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), cnt);
        this.broadcastInAgl(server, p, gamenr);
    }
    
    void sendEnterAGL(ServerThread server, SocketChannel socket, Packet ps) {
        Packet p;
        Client cl = clients.findClient(socket);
        int gamenr = db.getGameNumber(cl.getUserID());
        cl.gamenumber = gamenr;
        cl.setArea(51);        // we are in meeting room then
        db.updateClientOrigin(cl.getUserID(), STATUS_AGLOBBY, 51, cl.getRoom(), cl.getSlot());

        // accept this player in post-game lobby
        p = new Packet(Commands.ENTERAGL, Commands.TELL, Commands.SERVER, ps.getPacketID());
        this.addOutPacket(server, socket, p);

        // broadcast new playercounter
        this.broadcastAglPlayerCnt(server, cl.gamenumber);

        // broadcast player's stats
        byte[] status = cl.getCharacterStat();
        p = new Packet(Commands.AGLJOIN, Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), status);
        this.broadcastInAgl(server, p, cl.gamenumber);
    }
    
    void sendAGLstats(ServerThread server, SocketChannel socket, Packet ps) {
        int gamenum = clients.findClient(socket).gamenumber;
        ByteBuffer aglstats = ByteBuffer.wrap(new byte[1024]);
        
        aglstats.putShort((short) 0);
        aglstats.put((byte) 3);        // ?
        aglstats.put((byte) clients.getPlayerCountAgl(gamenum));
        List cls = clients.getList();
        for(int i=0; i<cls.size(); i++) {
            Client cl = (Client)cls.get(i);
            if(cl.gamenumber == gamenum) aglstats.put(cl.getCharacterStat());
        }
        
        byte[] status = new byte[aglstats.position()];
        aglstats.rewind();
        aglstats.get(status);
        Packet p = new Packet(Commands.AGLSTATS, Commands.TELL, Commands.SERVER, ps.getPacketID(), status);
        this.addOutPacket(server, socket, p);
    }
    
    void sendAGLplayerCnt(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] cnt = {0,1};
        cnt[1] = clients.getPlayerCountAgl(clients.findClient(socket).gamenumber);
        Packet p = new Packet(Commands.AGLPLAYERCNT, Commands.TELL, Commands.SERVER, ps.getPacketID(), cnt);
        this.addOutPacket(server, socket, p);
    }
    
    void sendLeaveAGL(ServerThread server, SocketChannel socket, Packet ps) {
        Client cl = clients.findClient(socket);
        int game = cl.gamenumber;
        
        // broadcast leaving of player
        byte[] wholeaves = {0,6, 0,0,0,0,0,0};
        byte[] who = cl.getHNPair().getHandle();
        System.arraycopy(who, 0, wholeaves, 2, who.length);
        Packet p = new Packet(Commands.AGLLEAVE, Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), wholeaves);
        this.broadcastInAgl(server, p, game);
        this.broadcastLeaveRoom(server,socket);
        
        // set player back into area selection
        cl.setArea(0);
        cl.gamenumber = 0;
        db.updateClientOrigin(cl.getUserID(), STATUS_LOBBY, 0, 0, 0);
        db.updateClientGame(cl.getUserID(), 0);
        
        p = new Packet(Commands.LEAVEAGL, Commands.TELL, Commands.SERVER, ps.getPacketID());
        this.addOutPacket(server, socket, p);
        
        // broadcast new number of players in agl
        this.broadcastAglPlayerCnt(server, game);
        this.broadcastRoomPlayerCnt(server, 1, 1);  // TODO: this is an assumption, need to differentiate with multiple rooms
    }
    
    // join a game not as host
    void sendJoinGame(ServerThread server, SocketChannel socket, Packet ps) {
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();

        byte[] retslot = {0,1};
        int slot = ps.getNumber();

        // check if we can join the slot
        if(slots.getSlot(area, room, slot).getStatus() == Slot.STATUS_BUSY) {
            byte[] mess = new PacketString("<LF=6><BODY><CENTER>game is full<END>").getData();
            Packet p = new Packet(Commands.JOINGAME, Commands.TELL, Commands.SERVER, ps.getPacketID(), mess);
            p.setErr();
            this.addOutPacket(server, socket, p);
            return;
        }

        // check2 if we can join the slot
        if(slots.getSlot(area, room, slot).getStatus() != Slot.STATUS_GAMESET) {
            byte[] mess = new PacketString("<LF=6><BODY><CENTER>not possible<END>").getData();
            Packet p = new Packet(Commands.JOINGAME, Commands.TELL, Commands.SERVER, ps.getPacketID(), mess);
            p.setErr();
            this.addOutPacket(server, socket, p);
            return;
        }

        String hostuser = (slots.getSlot(area, room, slot).getHost());
        String clntuser = cl.getUserID();

        // get password and check it
        byte[] pass = ps.getPassword();
        if(Arrays.equals(pass, slots.getSlot(area, room, slot).getPassword()) || slots.getSlot(area, room, slot).getProtection()==Slot.PROTECTION_OFF) {
            retslot[1] = (byte) (slot & 0xff);

            // assign a player number, set slot
            byte player = clients.getFreePlayerNum(area, room, slot);
            cl.setPlayerNum(player);
            cl.setSlot(slot);
            db.updateClientOrigin(cl.getUserID(), STATUS_LOBBY, area, room, slot);

            Packet p = new Packet(Commands.JOINGAME, Commands.TELL, Commands.SERVER, ps.getPacketID(), retslot);
            this.addOutPacket(server, socket, p);

            // set status to busy when slot is full!
            int n = clients.countPlayersInSlot(area, room, slot);
            if(n >= slots.getMaximumPlayers(area, room, slot)) {
                slots.getSlot(area, room, slot).setStatus(Slot.STATUS_BUSY);
            }
            
            this.broadcastSlotPlayerStatus(server, area, room, slot);
            this.broadcastSlotStatus(server, area, room, slot);
            this.broadcastSlotAttrib2(server, area, room, slot);

            // broadcast statistics of new player
            byte[] status = cl.getCharacterStat();
            p = new Packet(Commands.PLAYERSTATBC, Commands.BROADCAST, Commands.SERVER, ps.getPacketID(), status);
            this.broadcastInSlot(server, p, area, room, slot);
        }
        else {
            byte[] mess = new PacketString("<LF=6><BODY><CENTER>wrong password<END>").getData();
            Packet p = new Packet(Commands.JOINGAME, Commands.TELL, Commands.SERVER, ps.getPacketID(), mess);
            p.setErr();
            this.addOutPacket(server, socket, p);
        }
    }
    
    void sendGetInfo(ServerThread server, SocketChannel socket, Packet ps) {
        // decrypt the desired url and get the page from class
        byte[] url = ps.getDecryptedString();
        byte[] d = information.getData(new String(url));

        // create answer packet
        ByteBuffer mess = ByteBuffer.wrap(new byte[url.length + d.length + 4]);
        mess.putShort((short) url.length);
        mess.put(url);
        mess.putShort((short) d.length);
        mess.put(d);
        
        Packet p = new Packet(Commands.GETINFO, Commands.TELL, Commands.SERVER, ps.getPacketID(), mess.array());
        this.addOutPacket(server, socket, p);
    }
    
    // unknown, simply accept it ?!?
    void send6181(ServerThread server, SocketChannel socket, Packet ps) {
        Packet p = new Packet(Commands.UNKN6181, Commands.TELL, Commands.SERVER, ps.getPacketID());
        this.addOutPacket(server, socket, p);        
    }
       
    void sendEventDat(ServerThread server, SocketChannel socket, Packet ps) {
        Packet p;
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        int room = cl.getRoom();
        int slot = cl.getSlot();
        int game = cl.gamenumber;

        byte[] rcpthandle = {0,6, 0,0,0,0,0,0};
        byte[] recpt = {0,0,0,0,0,0};
        byte[] event = ps.getEvenData();
        System.arraycopy(event,2, rcpthandle, 2, 6);
        System.arraycopy(event,2, recpt, 0, 6);
        
        int eventlen = ((((int)event[8] &0xff)<< 8)|((int)event[9])&0xff);

        // create the event packet: sender, eventdat and their lengths
        ByteBuffer broad = ByteBuffer.wrap(new byte[eventlen+2+6+2]);
        broad.putShort((short) 6);
        broad.put(cl.getHNPair().getHandle());
        broad.putShort((short) eventlen);
        broad.put(event, 10, eventlen);
        p = new Packet(Commands.EVENTDATBC, Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), broad.array());

        Client rcl = clients.findClientByHandle(recpt);
        if(rcl != null){ //temp
            this.addOutPacket(server, rcl.getSocket(), p);
        }
        
        // accept event data by sending back unencrypted recipient
        p = new Packet(Commands.EVENTDAT, Commands.TELL, Commands.SERVER, ps.getPacketID(), rcpthandle);
        this.addOutPacket(server, socket, p);
    }

    void sendBuddyList(ServerThread server, SocketChannel socket, Packet ps) {
        Packet p;
        byte[] offline = new PacketString("<BODY><SIZE=3>not connected<END>").getData();
        byte[] online = {0,0, 0,0, 0,0, 0};
        byte[] ingame = {0,0, 0,0, 0,0, 1};
        
        byte[] handle = ps.getDecryptedString();
        
        int status = clients.getClientStatus(handle);
        
        switch(status) {
            case 1: 
                p = new Packet(Commands.BUDDYLIST, Commands.TELL, Commands.SERVER, ps.getPacketID(), online);
                break;
            case 3:
                p = new Packet(Commands.BUDDYLIST, Commands.TELL, Commands.SERVER, ps.getPacketID(), ingame);
                break;
            default:
                p = new Packet(Commands.BUDDYLIST, Commands.TELL, Commands.SERVER, ps.getPacketID(), offline);
                p.setErr();
        }
        this.addOutPacket(server, socket, p);
    }

    void sendCheckBuddy(ServerThread server, SocketChannel socket, Packet ps) {
        Packet p;
        byte[] offline = new PacketString("<BODY><SIZE=3><CENTER>not connected<END>").getData();
        byte[] online = {0x00,0x0c, 0x30,0x61,0x64,0x36,0x30,0x31,0x30,0x38,0x32,0x30,0x30,0x38,    // 0ad601082008
                         0x00,0x01, 0x00,0x00, 0x00,0x00, 0x00,0x03,
                         0x00,0x29, 0x3c,0x42,0x4f,0x44,0x59,0x3e,               // <BODY>
                         0x3c,0x53,0x49,0x5a,0x45,0x3d,0x33,0x3e,          // <SIZE=3>
                        (byte)0x82,0x65, (byte)0x82,0x71, (byte)0x82,0x64,
                        (byte)0x82,0x64, (byte)0x83,0x47, (byte)0x83,(byte)0x8a,
                        (byte)0x83,0x41, (byte)0x82,(byte)0xc9, (byte)0x82,(byte)0xa2,
                        (byte)0x82,(byte)0xdc, (byte)0x82,(byte)0xb7, // 
                        0x3c,0x45,0x4e,0x44,0x3e                 // <END>
                        };

        byte[] ingame = {0x00,0x2b, 
                0x3c,0x42,0x4f,0x44,0x59, 0x3e, 
                0x3c,0x53,0x49,0x5a,0x45,0x3d,0x33,0x3e,
                (byte)0x8c,(byte)0xbb,(byte)0x8d,(byte)0xdd,
                (byte)0x81,0x41,(byte)0x83,0x51,(byte)0x81,0x5b,(byte)0x83,(byte)0x80,
                (byte)0x83,0x76,(byte)0x83,(byte)0x8c,(byte)0x83,0x43,(byte)0x92,(byte)0x86,
                (byte)0x82,(byte)0xc5,(byte)0x82,(byte)0xb7,
                0x3c,0x45,0x4e,0x44,0x3e };
        
        byte[] handle = ps.getDecryptedString();
        
        int status = clients.getClientStatus(handle);
        
        switch(status) {
            case 1: 
                p = new Packet(Commands.CHECKBUDDY, Commands.TELL, Commands.SERVER, ps.getPacketID(), online);
                break;
            case 3:
                p = new Packet(Commands.CHECKBUDDY, Commands.TELL, Commands.SERVER, ps.getPacketID(), ingame);
                p.setErr();
                break;
            default:
                p = new Packet(Commands.CHECKBUDDY, Commands.TELL, Commands.SERVER, ps.getPacketID(), offline);
                p.setErr();
        }
        this.addOutPacket(server, socket, p);
    }
  
    
    //should test more
    //search user with Conditionally
    void searchUserCond(ServerThread server, SocketChannel socket, Packet ps){
        int offset = 0;
        int len =0;
        byte[] pl = ps.getPayload();
        byte[] pt = pl.clone(); 
        byte unk0 = pl[0];
        byte num = pl[1];
        offset += 2;
        byte[] temp;
        ByteBuffer retval = ByteBuffer.wrap(new byte[1024]);
        retval.put((byte)0);    //not unk but entry length?
        retval.put((byte)0);   //entry length?
        retval.put((byte)0);  //found?; will update latter
        retval.put((byte)1); //if zero, __cnet_Send_ConditionSearchUserCertify
        Client cl;
        Client usercl = clients.findClient(socket);
;
        List <Client>clList = new LinkedList();
        int found = 0;
        if(num == 0){
            clList = clients.getList();
            if (clList != null) {
                for (Client cls : clList) {
                    if(cls.getArea() == usercl.getArea()){
                        retval.put(cls.getHNPair().getHNPair());
                        retval.putShort((short) cls.getCharacterStats().length);
                        retval.put(cls.getCharacterStats());
                        found++; //if found found++
                    }
                }
            }
        }
        for (int i = 0; i < num; i++) {
            byte case_num = pl[offset];
            offset += 1;
            switch (case_num) {
                case 1: //search by hn
                    len = decryptBuff(pt, offset, ps.getPacketID());
                    temp = new byte[len];
                    System.arraycopy(pt, 4 + offset, temp, 0, len);
                    offset += len + 4;
                    pt = pl.clone();
                    cl = clients.findClientByHandle(temp);
                    if (cl != null && cl.getArea() == usercl.getArea()) {
                        retval.put(cl.getHNPair().getHNPair());
                        retval.putShort((short) cl.getCharacterStats().length);
                        retval.put(cl.getCharacterStats());
                        found++; //if found found++
                    }
                    break;
                case 2: //search by username?
                    len = decryptBuff(pt, offset, ps.getPacketID());
                    temp = new byte[len];
                    System.arraycopy(pt, 4 + offset, temp, 0, len);
                    offset += len + 4;
                    pt = pl.clone();
                    clList = clients.findClientByName(temp);
                    if (clList != null) {
                        for(Client cls : clList){
                        if(cls.getArea() == usercl.getArea()){
                            retval.put(cls.getHNPair().getHNPair());
                            retval.putShort((short) cls.getCharacterStats().length);
                            retval.put(cls.getCharacterStats());
                            found++; //if found found++
                        }
                        }
                    }
                    break;
                case 3: //search by weapon?
                case 4:
                case 5:
                    byte weapon = pl[offset];
                    offset += 1;
                    //temp //hack
                    byte[] arr = {4,3,2,1,0};
                    if(weapon <= 4){
                            weapon = arr[weapon]; //hmm, in mh1, search value are different with real value. 
                    }
                    clList = clients.findClientByWeapon(weapon);
                    if (clList != null) {
                        for(Client cls : clList){
                            if(cls.getArea() == usercl.getArea()){
                                retval.put(cls.getHNPair().getHNPair());
                                retval.putShort((short) cls.getCharacterStats().length);
                                retval.put(cls.getCharacterStats());
                                found++; //if found found++
                            }
                        }
                    }
                    break;
                case 6: //search by HR range? 
                    byte hr1 = pl[offset]; //HR Start
                    offset++;
                    byte hr2 = pl[offset]; //HR END
                    offset++;
                    clList = clients.findClientByHR(hr1,hr2);
                    if (clList != null) {
                        for(Client cls : clList){
                            if(cls.getArea() == usercl.getArea()){       
                                retval.put(cls.getHNPair().getHNPair());
                                retval.putShort((short) cls.getCharacterStats().length);
                                retval.put(cls.getCharacterStats());
                                found++; //if found found++
                            }
                        }
                    }
                    break;
                default:
                    break;
            }
        }
        
        byte[] r = new byte[retval.position()];
        retval.rewind();
        retval.get(r);
        r[0] = (byte)found;
        r[2] = (byte)found;
        Packet p = new Packet(0x6709, Commands.TELL, Commands.SERVER, ps.getPacketID(), r);
        this.addOutPacket(server, socket, p);
    }
    
    //should test more
    //search user with Conditionally
    void searchUserCondLand(ServerThread server, SocketChannel socket, Packet ps){
        int offset = 0;
        int len =0;
        byte[] pl = ps.getPayload();
        byte[] pt = pl.clone(); 
        byte[] temp;
        ByteBuffer retval = ByteBuffer.wrap(new byte[1024]);
        Client cl;
        int found = 0;
        len = decryptBuff(pt, offset, ps.getPacketID());
        temp = new byte[len];
        System.arraycopy(pt, 4 + offset, temp, 0, len);
        offset += len + 4;
        pt = pl.clone();
        cl = clients.findClientByHandle(temp);
        if (cl != null) {
           // retval.put(cl.getHNPair().getHNPair());
            //retval.putShort((short) cl.getCharacterStats().length);
            //retval.put(cl.getCharacterStats());
                retval.putShort((short)(cl.getHNPair().getHandle().length +1));
                retval.put(cl.getHNPair().getHandle());
                retval.put((byte)0);
                
                retval.putShort((short)0);
                retval.putShort((short)0);
                retval.putShort((short)0);
                retval.put((byte)0);
                retval.put((byte)0);
                                
                retval.putShort((short) cl.getCharacterStats().length);
                retval.put(cl.getCharacterStats());
            found++; //if found found++
        }
        byte[] r = new byte[retval.position()];
        retval.rewind();
        retval.get(r);
        Packet p = new Packet(0x6710, Commands.TELL, Commands.SERVER, ps.getPacketID(), r);
        this.addOutPacket(server, socket, p);
    }
    
    //should test more
    //search user with Conditionally
    //freeze when more than one search result
    void searchUserCondLand2(ServerThread server, SocketChannel socket, Packet ps){
        int offset = 0;
        int len =0;
        byte[] pl = ps.getPayload();
        byte[] pt = pl.clone(); 
        byte unk0 = pl[0];
        offset += 1;
        byte[] temp;
        ByteBuffer retval = ByteBuffer.wrap(new byte[1024]);
        retval.put((byte)0);    //not unk but entry length?
        retval.put((byte)0);   //entry length?
        retval.put((byte)0);  //found?; will update latter
        retval.put((byte)1); //if zero, __cnet_Send_ConditionSearchUserCertify
        Client cl;
        int found = 0;
        len = decryptBuff(pt, offset, ps.getPacketID());
        temp = new byte[len];
        System.arraycopy(pt, 4 + offset, temp, 0, len);
        offset += len + 4;
        pt = pl.clone();
        List<Client> clList = new LinkedList();
        clList = clients.findClientByName(temp);
        if (clList != null) {
            for (Client cls : clList) {
                //retval.put(cls.getHNPair().getHNPair());
                retval.putShort((short)(cls.getHNPair().getHandle().length +1));
                retval.put(cls.getHNPair().getHandle());
                retval.put((byte)0);
                retval.putShort((short)(cls.getHNPair().getNickname().length +1));
                retval.put(cls.getHNPair().getNickname());
                retval.put((byte)0);
                retval.putShort((short) cls.getCharacterStats().length);
                retval.put(cls.getCharacterStats());
                found++; //if found found++
            }
        }
        byte[] r = new byte[retval.position()];
        retval.rewind();
        retval.get(r);
        r[0] = (byte)found;
        r[2] = (byte)found;
        Packet p = new Packet(0x6711, Commands.TELL, Commands.SERVER, ps.getPacketID(), r);
        this.addOutPacket(server, socket, p);
    }
    
    //should test more
    //search user with Conditionally
    void searchUserCondLand3(ServerThread server, SocketChannel socket, Packet ps){
        int offset = 0;
        int len =0;
        byte[] pl = ps.getPayload();
        byte[] pt = pl.clone(); 
        byte[] temp;
        ByteBuffer retval = ByteBuffer.wrap(new byte[1024]);
        retval.put((byte)0);   //entry length?
        retval.put((byte)0);   //entry length?
        retval.put((byte)0);  //found?; will update latter
        retval.put((byte)1); //if zero, __cnet_Send_ConditionSearchUserCertify
        Client cl;
        int found = 0;
        len = decryptBuff(pt, offset, ps.getPacketID());
        temp = new byte[len];
        System.arraycopy(pt, 4 + offset, temp, 0, len);
        offset += len + 4;
        pt = pl.clone();
        cl = clients.findClientByHandle(temp);
        if (cl != null) {
            //retval.put(cl.getHNPair().getHNPair());
            retval.putShort((short) (cl.getHNPair().getHandle().length + 1));
            retval.put(cl.getHNPair().getHandle());
            retval.put((byte) 0);
            retval.putShort((short) (cl.getHNPair().getNickname().length + 1));
            retval.put(cl.getHNPair().getNickname());
            retval.put((byte) 0);
            retval.putShort((short) cl.getCharacterStats().length);
            retval.put(cl.getCharacterStats());
            found++; //if found found++
        }
        byte[] r = new byte[retval.position()];
        retval.rewind();
        retval.get(r);
        r[0] = (byte)found;
        r[2] = (byte)found;
        Packet p = new Packet(0x670a, Commands.TELL, Commands.SERVER, ps.getPacketID(), r);
        this.addOutPacket(server, socket, p);
    }
    
    
    void sendPrivateMsg(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] offline = new PacketString("<BODY><SIZE=3>not connected<END>").getData();
        Packet p;
        Client cl = clients.findClient(socket);

        // get recipient and message
        PrivateMessage mess = ps.getDecryptedPvtMess(cl);
        cl = clients.findClientByHandle(mess.getRecipient());
        if(cl != null) {
            byte[] broadcast = mess.getPacketData();

            // accept the message packet
            p = new Packet(Commands.PRIVATEMSG, Commands.TELL, Commands.SERVER, ps.getPacketID());
            this.addOutPacket(server, socket, p);
            
            // broadcast message to recipient
            p = new Packet(Commands.PRIVATEMSGBC, Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), broadcast);
            this.addOutPacket(server, cl.getSocket(), p);
        } else {
            // tell sender that recipient is offline
            p = new Packet(Commands.PRIVATEMSG, Commands.TELL, Commands.SERVER, ps.getPacketID(), offline);
            p.setErr();
            this.addOutPacket(server, socket, p);
        }
    }
    
    void sendChatOut(ServerThread server, SocketChannel socket, Packet ps) {
        byte[] offline = new PacketString("not connected").getData();
        Packet p;
        Client cl = clients.findClient(socket);

        // get recipient and message
        PrivateMessage mess = ps.getDecryptedPvtMess(cl);
        cl = clients.findClientByHandle(mess.getRecipient());
        if(cl != null) {
            byte[] broadcast = mess.getPacketData();

            // accept the message packet
            p = new Packet(Commands.CHATIN_TU, Commands.TELL, Commands.SERVER, ps.getPacketID());
            this.addOutPacket(server, socket, p);
            
            // broadcast message to recipient
            p = new Packet(Commands.CHATIN_TU_BC, Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), broadcast);
            this.addOutPacket(server, cl.getSocket(), p);
        } else {
            // tell sender that recipient is offline
            p = new Packet(Commands.CHATIN_TU, Commands.TELL, Commands.SERVER, ps.getPacketID(), offline);
            p.setErr();
            this.addOutPacket(server, socket, p);
        }
    }    
    void sendLogout(ServerThread server, SocketChannel socket, Packet ps) {
        Client cl = clients.findClient(socket);
        int area = cl.getArea();
        db.updateClientGame(cl.getUserID(), 0);
        this.removeClient(server, cl);

        this.broadcastAreaPlayerCnt(server, socket, area);
        Packet p = new Packet(Commands.LOGOUT, Commands.TELL, Commands.SERVER, ps.getPacketID());
        this.addOutPacket(server, socket, p);
    }

     boolean check_patchlevel(ServerThread server, SocketChannel socket, Packet p) {
         // for testing just get the version string and dump the packet
         Logging.printBuffer(p.getPacketData());
         byte[] version = p.getVersion();
         Logging.println("Decrypted:");
         Logging.printBuffer(version);

         // if we return true, the patch file is loaded
		 // it's copyrighted material and DNAS protected
		 // of course we don't provide it
		 return(false);
     }

    void sendShutdown(ServerThread server, SocketChannel socket) {
        // since this is the only time we use shutdown, message can be hardcoded.
        byte[] message = new PacketString("<LF=2><BODY><CENTER>Update completed.<BR><BODY>You were disconnected from server.<LF=3><BODY>From now,<C=3>no connection from<BR><BODY>\"PlayStation 2\" network<BR><BODY>can be made.<BR><BODY><C=7>Please notice.<END>").getData();
        Packet p = new Packet(Commands.SHUTDOWN, Commands.BROADCAST, Commands.SERVER, getNextPacketID(), message);
        this.addOutPacket(server, socket, p);        
    }

    void sendPatchStart(ServerThread server, SocketChannel socket) {
        byte[] patchinfo = {
            0x00,0x0e,
            0x31,0x30,0x31,0x20,0x30,0x33,0x31,0x31,0x31,0x37,0x32,0x35,0x30,0x30,  // 101 0311172500   version number
            0x00,0x7b,                                                              // count of chunks with max 0x100 each
            0x00,0x00,0x7a,(byte)0xa0,                                              // size of patchdata
            0x00,0x3c,(byte)0xda,(byte)0xe2                                         // checksum perhaps???
        };
        
        Packet p = new Packet(Commands.PATCHSTART, Commands.BROADCAST, Commands.SERVER, getNextPacketID(), patchinfo);
        this.addOutPacket(server, socket, p);                
    }
    
    void sendPatchData(ServerThread server, SocketChannel socket, int chunk) {
        byte[] patchdata = patch.getData(chunk);
        Packet p = new Packet(Commands.PATCHDATA, Commands.BROADCAST, Commands.SERVER, getNextPacketID(), patchdata);
        this.addOutPacket(server, socket, p);                        
    }
    
    void sendPatchLineCheck(ServerThread server, SocketChannel socket, int chunk) {
        byte[] buffer = {0x00,0x00};
        buffer[0] = (byte) ((chunk >> 8) & 0xff);
        buffer[1] = (byte) (chunk & 0xff);
        Packet p = new Packet(Commands.PATCHLINECHECK, Commands.QUERY, Commands.SERVER, getNextPacketID(), buffer);
        this.addOutPacket(server, socket, p);                                
    }
    
    void sendPatchFooter(ServerThread server, SocketChannel socket) {
        Packet p = new Packet(Commands.PATCHFOOTER, Commands.BROADCAST, Commands.SERVER, getNextPacketID());
        this.addOutPacket(server, socket, p);                                
    }
    
    void sendPatchFinish(ServerThread server, SocketChannel socket) {
        Packet p = new Packet(Commands.PATCHFINISH, Commands.QUERY, Commands.SERVER, getNextPacketID());
        this.addOutPacket(server, socket, p);                                
    }
    
    void beginPatch(ServerThread server, SocketChannel socket) {
        // notify about the patch
        sendPatchStart(server, socket);

        // send the first 8 packets
        for(int t=0; t<8; t++) {
            sendPatchData(server, socket, t);
        }
        
        sendPatchLineCheck(server, socket, 7);
    }

    void continuePatch(ServerThread server, SocketChannel socket, Packet ps) {
        int chunk = ps.getNumber() + 1;

        int cnt = patch.cntChunks(chunk);
        for(int t=0; t<cnt; t++) {
            sendPatchData(server, socket, chunk + t);
        }
        
        if(cnt != 8) {
            // this is the end of the patching
            sendPatchFooter(server, socket);
            sendPatchFinish(server, socket);
        }
        else {
            sendPatchLineCheck(server, socket, chunk + 7);
        }

    }
    
    
    public void removeClientNoDisconnect(ServerThread server, SocketChannel socket) {
        try {
            Client cl = clients.findClient(socket);
            if(cl == null) return;
            synchronized(cl) {      // we have to sync because this function can be called by server and handlerthread
                int area = cl.getArea();
                int room = cl.getRoom();
                int slot = cl.getSlot();
                int game = cl.gamenumber;
                byte host = cl.getHostFlag();
                byte[] who = cl.getHNPair().getHandle();

                // set the player to offline status
                db.updateClientOrigin(cl.getUserID(), STATUS_OFFLINE, -1, 0, 0);
                
                //this.broadcastLeaveRoom(server,socket);
                // first of all remove this client from list
                //clients.remove(cl);

                // it's a host, so we are in normal lobbies
                if(host==1 && slot!=0) {
                    slots.getSlot(area, room, slot).reset();
                    this.broadcastCancelSlot(server, area, room, slot);
                    this.broadcastPasswdProtect(server, area, room, slot);
                    this.broadcastSlotSceneType(server, area, room, slot);
                    this.broadcastSlotTitle(server, area, room, slot);
                    this.broadcastSlotAttrib2(server, area, room, slot);
                    this.broadcastSlotPlayerStatus(server, area, room, slot);
                    this.broadcastSlotStatus(server, area, room, slot);
                }

                // not a host but in a slot
                if(slot!=0 && host==0) {
                    // broadcast to other players
                    byte[] wholeaves = {0,6, 0,0,0,0,0,0};
                    System.arraycopy(who, 0, wholeaves, 2, who.length);
                    Packet p = new Packet(Commands.LEAVESLOT, Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), wholeaves);
                    this.broadcastInSlot(server, p, area, room, slot);

                    // set status back to let others in
                    // but only if still a host is in that slot!
                    int n = clients.countPlayersInSlot(area, room, slot);
                    if(n < slots.getMaximumPlayers(area, room, slot)) {
                        if(clients.getHostofSlot(area, room, slot) != null) { 
                            slots.getSlot(area, room, slot).setStatus(Slot.STATUS_GAMESET);
                        }
                    }

                    // the last client in this slot left
                    if(clients.countPlayersInSlot(area, room, slot) == 0) {
                        slots.getSlot(area, room, slot).reset();
                        this.broadcastPasswdProtect(server, area, room, slot);
                        this.broadcastSlotSceneType(server, area, room, slot);
                        this.broadcastSlotTitle(server, area, room, slot);
                  }

                    this.broadcastSlotAttrib2(server, area, room, slot);
                    this.broadcastSlotPlayerStatus(server, area, room, slot);
                    this.broadcastSlotStatus(server, area, room, slot);            
                }

                // we are in after game lobby
                if(area==51 && game != 0) {
                    // TODO: is this really neccessary ?
                }

                // some broadcasting
                this.broadcastAreaPlayerCnt(server, socket, area);
                this.broadcastRoomPlayerCnt(server, area, room);
            }
        } catch (Exception e) {
            // some error we don't want to look at ?
        }
    }
    
    // when a host or client leaves unexspected, we have to handle this
    public void removeClient(ServerThread server, Client cl) {
        try {
            if(cl == null) return;
            synchronized(cl) {      // we have to sync because this function can be called by server and handlerthread
                int area = cl.getArea();
                int room = cl.getRoom();
                int slot = cl.getSlot();
                int game = cl.gamenumber;
                SocketChannel socket = cl.getSocket();
                byte host = cl.getHostFlag();
                byte[] who = cl.getHNPair().getHandle();

                // set the player to offline status
                db.updateClientOrigin(cl.getUserID(), STATUS_OFFLINE, -1, 0, 0);

                this.broadcastLeaveRoom(server,socket);
                // first of all remove this client from list
                clients.remove(cl);
                
                // it's a host, so we are in normal lobbies
                if(host==1 && slot!=0) {
                    slots.getSlot(area, room, slot).reset();
                    this.broadcastCancelSlot(server, area, room, slot);
                    this.broadcastPasswdProtect(server, area, room, slot);
                    this.broadcastSlotSceneType(server, area, room, slot);
                    this.broadcastSlotTitle(server, area, room, slot);
                    this.broadcastSlotAttrib2(server, area, room, slot);
                    this.broadcastSlotPlayerStatus(server, area, room, slot);
                    this.broadcastSlotStatus(server, area, room, slot);
                 }

                // not a host but in a slot
                if(slot!=0 && host==0) {
                    // broadcast to other players
                    byte[] wholeaves = {0,6, 0,0,0,0,0,0};
                    System.arraycopy(who, 0, wholeaves, 2, who.length);
                    Packet p = new Packet(Commands.LEAVESLOT, Commands.BROADCAST, Commands.SERVER, this.getNextPacketID(), wholeaves);
                    this.broadcastInSlot(server, p, area, room, slot);

                    // set status back to let others in
                    // but only if still a host is in that slot!
                    int n = clients.countPlayersInSlot(area, room, slot);
                    if(n < slots.getMaximumPlayers(area, room, slot)) {
                        if(clients.getHostofSlot(area, room, slot) != null) { 
                            slots.getSlot(area, room, slot).setStatus(Slot.STATUS_GAMESET);
                        }
                    }

                    // the last client in this slot left
                    if(clients.countPlayersInSlot(area, room, slot) == 0) {
                        slots.getSlot(area, room, slot).reset();
                        this.broadcastPasswdProtect(server, area, room, slot);
                        this.broadcastSlotSceneType(server, area, room, slot);
                        this.broadcastSlotTitle(server, area, room, slot);
                    }

                    this.broadcastSlotAttrib2(server, area, room, slot);
                    this.broadcastSlotPlayerStatus(server, area, room, slot);
                    this.broadcastSlotStatus(server, area, room, slot);            
                }

                // we are in after game lobby
                if(area==51 && game != 0) {
                    // TODO: is this really neccessary ?
                }

                // some broadcasting
                this.broadcastAreaPlayerCnt(server, socket, area);
                this.broadcastRoomPlayerCnt(server, area, room);
                
                // close connection from server side
                server.disconnect(socket);
            }
        } catch (Exception e) {
            // some error we don't want to look at ?
        }
    }
    
    // helper for keeping slots open when something went wrong
    synchronized void cleanGhostRooms(ServerThread server) {
        for(int area=1; area<=areas.getAreaCount(); area ++) {
            for(int room=1;room<=rooms.getRoomCount(); room++) {
                for(int slot=1; slot<=slots.getSlotCount(); slot++) {
                    if(slots.getSlot(area, room, slot).getStatus() == Slot.STATUS_GAMESET && clients.countPlayersInSlot(area, room, slot) == 0) {
                        slots.getSlot(area, room, slot).setStatus(Slot.STATUS_FREE);
                        this.broadcastSlotStatus(server, area, room, slot); 
                        Logging.println("Cleaned ghost room area "+area+" room "+room+" slot "+slot);
                    }
                }
            }
        }
    }
     
  public static String stringToHex0x(String s) {
    String result = "";

    for (int i = 0; i < s.length(); i++) {
      result += String.format("0x%02X ", (int) s.charAt(i));
    }

    return result;
  }
  
// this is the protocol function that glues together all the the packet functions
    void handleInPacket(ServerThread server, SocketChannel socket, Packet packet) {
        switch(packet.getwho()) {
            case Commands.CLIENT:
                switch(packet.getqsw()) {
                    case Commands.QUERY:
                        // client questions
                        Logging.println("QUERY Commands: 0x" + Integer.toHexString(packet.getCmd()));
                        Logging.printBuffer(packet.getPacketData());
                        switch(packet.getCmd()) {
                            case Commands.UNKN61A0:         send61A0(server, socket, packet);           break;
                            case Commands.CHECKRND:         sendCheckRnd(server, socket, packet);       break;
                            case Commands.UNKN61A1:         send61A1(server, socket, packet);           break;
                            case Commands.UNKN6148:         send6148(server, socket, packet);     break;
                            case Commands.UNKN6149:         send6149(server, socket, packet);     break;
                            case Commands.HNSELECT:         sendHNselect(server, socket, packet);       break;
                            case Commands.MOTHEDAY:         sendMotheday(server, socket, packet);       break;
                            case Commands.CHARSELECT:       sendCharSelect(server, socket, packet);     break;
                            case Commands.UNKN6881:         send6881(server, socket, packet);           break;
                            case Commands.UNKN6882:         send6882(server, socket, packet);           break;
                            
                            //Might Area realted
                            case Commands.UNKN6883:         send6883(server, socket, packet);           break;                     
                            case Commands.UNKN68a0:         send68a0(server, socket, packet);           break;
                            case Commands.UNKN68a1:         send68a1(server, socket, packet);           break;
                            case Commands.UNKN6891:         send6891(server, socket, packet);           break; 
                            case Commands.UNKN630d:         send630d(server, socket, packet);           break;
                                
                            case Commands.UNKN6415:         send6415(server, socket, packet);           break;                              
                            case Commands.UNKN6306:         send6306(server, socket, packet);           break;//leave Plaza?
                            case Commands.UNKN630a:         send630a(server, socket, packet);           break;//Room Member List?
                            case Commands.UNKN670f:         send670f(server, socket, packet);           break;//it might land select?
                            case Commands.UNKN640d:         send640d(server, socket, packet);           break;//need for quest?
                            case Commands.UNKN6890:         send6890(server, socket, packet);           break;//for market should send int time;
                            case Commands.UNKN6144:         send6144(server, socket, packet);           break;
                            case Commands.UNKN614d:         send614d(server, socket, packet);           break;
                            case Commands.UNKN660f:         send660f(server, socket, packet);           break;// permit to view and change quest desc?
                            
                            //dos stubs
                            case Commands.UNKN68c0:         send68c0(server, socket, packet);           break;
                            case Commands.UNKN6892:         send6892(server, socket, packet);           break;
                            case Commands.UNKN6884:         send6884(server, socket, packet);           break;
                            case Commands.UNKN620b:         send620b(server, socket, packet);           break;
                            case Commands.UNKN631b:         send631b(server, socket, packet);           break;
                            
                           //unk..
                            case Commands.UNKN6312:         send6312(server, socket, packet);           break;
                            case Commands.UNKN6313:         send6313(server, socket, packet);           break;
                            case Commands.UNKN6314:         send6314(server, socket, packet);           break;
                            
                            //dos quests
                            case Commands.UNKN650c:         send650c(server, socket, packet);           break;
                            case Commands.UNKN650b:         send650b(server, socket, packet);           break;
                            
                            //dos quest return?
                            case Commands.UNKN6139:         send6139(server, socket, packet);           break;                            
                            
                             //dos unk func
                            //case Commands.UNKN6310:         send6310(server, socket, packet);           break;//temp //not work 
                            //
                            case Commands.RANKINGS:         sendRankings(server, socket, packet);       break;
                            case Commands.AREACOUNT:        sendAreaCount(server, socket, packet);      break;
                            case Commands.AREAPLAYERCNT:    sendAreaPlayercnt(server, socket, packet);  break;
                            case Commands.AREASTATUS:       sendAreaStatus(server, socket, packet);     break;
                            case Commands.AREANAME:         sendAreaName(server, socket, packet);       break;
                            case Commands.AREADESCRIPT:     sendAreaDescript(server, socket, packet);   break;
                            case Commands.AREASELECT:       sendAreaSelect(server, socket, packet);     break;
                            case Commands.ROOMSCOUNT:       sendRoomsCount(server, socket, packet);     break;
                            case Commands.ROOMPLAYERCNT:    sendRoomPlayerCnt(server, socket, packet);  break;
                            case Commands.ROOMSTATUS:       sendRoomStatus(server, socket, packet);     break;
                            case Commands.ROOMNAME:         sendRoomName(server, socket, packet);       break;
                            case Commands.UNKN6308:         send6308(server, socket, packet);           break; //room desc?
                            case Commands.ENTERROOM:        sendEnterRoom(server, socket, packet);      break;
                            case Commands.SLOTCOUNT:        sendSlotCount(server, socket, packet);      break;
                            case Commands.SLOTSTATUS:       sendSlotStatus(server, socket, packet);     break;
                            case Commands.SLOTPLRSTATUS:    sendSlotPlayerStatus(server, socket, packet); break;
                            case Commands.SLOTTITLE:        sendSlotTitle(server, socket, packet);      break;
                            case Commands.SLOTATTRIB2:      sendSlotAttrib2(server, socket, packet);    break;
                            case Commands.SLOTPWDPROT:      sendPasswdProtect(server, socket, packet);  break;
                            case Commands.SLOTSCENTYPE:     sendSlotSceneType(server, socket, packet);  break;
                            case Commands.RULESCOUNT:       sendRulesCount(server, socket, packet);     break;
                            case Commands.RULEATTCOUNT:     sendRuleAttCount(server, socket, packet);   break;
                            case 0x6601:                    send6601(server, socket, packet);           break;
                            case 0x6602:                    send6602(server, socket, packet);           break;
                            case Commands.RULEDESCRIPT:     sendRuleDescript(server, socket, packet);   break;
                            case Commands.RULEVALUE:        sendRuleValue(server, socket, packet);      break;
                            case Commands.RULEATTRIB:       sendRuleattrib(server, socket, packet);     break;
                            case Commands.ATTRDESCRIPT:     sendAttrDescript(server, socket, packet);   break;
                            case Commands.ATTRATTRIB:       sendAttrAttrib(server, socket, packet);     break;
                            case Commands.PLAYERSTATS:      sendPlayerStats(server, socket, packet);    break;
                            case Commands.EXITSLOTLIST:     sendExitSlotlist(server, socket, packet);   break; //room exit 
                            case Commands.EXITAREA:         sendExitArea(server, socket, packet);       break; //area select
                            case Commands.CREATESLOT:       sendCreateSlot(server, socket, packet);     break;
                            case Commands.SCENESELECT:      sendSceneSelect(server, socket, packet);    break;
                            case Commands.SLOTNAME:         sendSlotName(server, socket, packet);       break;
                            case Commands.SETRULE:          sendSetRule(server, socket, packet);        break;
                            case 0x660c:                    send660c(server, socket, packet);           break;
                            case Commands.SLOTTIMER:        sendSlotTimer(server, socket, packet);      break;
                            case 0x6412:                    send6412(server, socket, packet);           break;
                            case 0x6504:                    send6504(server, socket, packet);           break;
                            case Commands.CANCELSLOT:       sendCancelSlot(server, socket, packet);     break;
                            case Commands.SLOTPASSWD:       sendSlotPasswd(server, socket, packet);     break;
                            case Commands.SLOTDESC:         sendSlotDescription(server, socket, packet);break;
                            case Commands.PLAYERCOUNT:      sendPlayerCount(server, socket, packet);    break;
                            case Commands.PLAYERNUMBER:     sendPlayerNumber(server, socket, packet);   break;
                            case Commands.PLAYERSTAT:       sendPlayerStat(server, socket, packet);     break;
                            case Commands.PLAYERSCORE:      sendPlayerScore(server, socket, packet);    break;
                            case Commands.GAMESESSION:      sendGameSession(server, socket, packet);    break;
                            case Commands.GAMEDIFF:         sendDifficulty(server, socket, packet);     break;
                            case Commands.GSINFO:           sendGSinfo(server, socket, packet);         break;
                            case 0x6002:                    send6002(server, socket, packet);           break;
                            case Commands.ENTERAGL:         sendEnterAGL(server, socket, packet);       break;
                            case Commands.AGLSTATS:         sendAGLstats(server, socket, packet);       break;
                            case Commands.AGLPLAYERCNT:     sendAGLplayerCnt(server, socket, packet);   break;
                            case Commands.LEAVEAGL:         sendLeaveAGL(server, socket, packet);       break;
                            case Commands.JOINGAME:         sendJoinGame(server, socket, packet);       break;
                            case Commands.GETINFO:          sendGetInfo(server, socket, packet);        break;
                            case Commands.EVENTDAT:         sendEventDat(server, socket, packet);       break;
                           
                            //search stuff (quest will be later) //buggy except 6709?
                            case Commands.BUDDYLIST:        sendBuddyList(server, socket, packet);      break;
                            case Commands.CHECKBUDDY:       sendCheckBuddy(server, socket, packet);     break;
                            case 0x6709:                    searchUserCond(server, socket, packet);     break;
                            case 0x6710:                    searchUserCondLand(server, socket, packet);     break;
                            case 0x6711:                    searchUserCondLand2(server, socket, packet);     break;
                            case 0x670a:                    searchUserCondLand3(server, socket, packet);     break;
                            
                            case Commands.PRIVATEMSG:       sendPrivateMsg(server, socket, packet);     break;
                            case Commands.CHATIN_TU:        sendChatOut(server, socket, packet);           break;//temp
                            case Commands.UNKN6181:         send6181(server, socket, packet);           break;
                            case Commands.LOGOUT:           sendLogout(server, socket, packet);         break;
                                
                             default:
                                Logging.println("Unknown QUERY command on query: 0x" + Integer.toHexString(packet.getCmd()));
                                //Logging.printBuffer(packet.getPacketData());
                        }
                        break;
                        
                    case Commands.TELL:
                        // client answers
                        Logging.println("TELL Commands: 0x" + Integer.toHexString(packet.getCmd()));
                        Logging.printBuffer(packet.getPacketData());
                        switch(packet.getCmd()) {
                            case Commands.CONNCHECK:
                                // this client is still alive
                                Client cl = clients.findClient(socket); 
                                if(cl!=null) cl.connalive = true;
                                break;
                                
                            case Commands.LOGIN:
                                if(check_session(server, socket, packet)) {
                                    // correct session established                                    
                                    // next step is the version check for File#1 update
                                    sendVersionCheck(server, socket);
                                } else {
                                    Logging.println("Session check failed!");
                                }
                                break;
                                
                            case Commands.CHECKVERSION:
                                // here we need to check the version number and make a decision
                                if(check_patchlevel(server, socket, packet)) {
                                    // if version is older than actual patch, send the patch
                                    beginPatch(server, socket);
                                } else {
                                    // next step is to offer the registered Handle/Name pairs
                                    sendIDhnpairs(server, socket);
                                }
                                break;                                    
                            case Commands.PATCHLINECHECK:
                                continuePatch(server, socket, packet);
                                break;

                            case Commands.PATCHFINISH:
                                sendShutdown(server, socket);
                                break;

                            case Commands.POSTGAMEINFO:
                                break;
                                
                            default:
                                // for debugging purposes
                                //Logging.println("Unknown command on answer");
                                //Logging.printBuffer(packet.getPacketData());
                                Logging.println("Unknown TELL command on query: 0x" + Integer.toHexString(packet.getCmd()));
                        }
                        break;

                    // broadcast commands from client
                    case Commands.BROADCAST:
                        Logging.println("BROADCAST Commands: 0x" + Integer.toHexString(packet.getCmd()));
                        Logging.printBuffer(packet.getPacketData());
                        switch(packet.getCmd()) {
                            case Commands.STARTGAME:
                                broadcastGetReady(server, socket);
                                break;
                               
                            case Commands.CHATIN:
                                broadcastChatOut(server, socket, packet);
                                break;
                            case Commands.CHATIN2:
                                //not work!!!!
                                broadcastChatOut2(server, socket, packet);
                                break;
                            case Commands.BINARYCHAT: //_cnet_RecvFromLbs_NoticeChatBinary
                                broadcastBinaryChatOut(server, socket, packet);
                                break;
                            default:
                                Logging.println("Unknown BROADCAST command on query: 0x" + Integer.toHexString(packet.getCmd()));
                        }

                        break;
                        
                    default:
                        // for debugging purposes
                        //Logging.println("Unknown qsw type on incoming packet!");
                        //Logging.printBuffer(packet.getPacketData());
                        Logging.println("Unknown command on query: 0x" + Integer.toHexString(packet.getCmd()));
                }
                break;
                
            default:
                // for debugging purposes
                Logging.println("Not a client who on incoming packet!");
                Logging.printBuffer(packet.getPacketData());
        }
    }
}