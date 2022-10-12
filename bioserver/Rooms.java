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

import java.util.LinkedList;
import java.util.List;

/**
 * a class containing all rooms
 */
public class Rooms {
    private List rooms;
    private int numberOfAreas;
    private final int numberOfRooms = 14;

    public Rooms(int numberOfAreas) {
        rooms = new LinkedList();
        this.numberOfAreas = numberOfAreas;

        for(int i=1; i<=numberOfAreas; i++) {
            rooms.add(new Room(i, "R1", Room.STATUS_ACTIVE));
            rooms.add(new Room(i, "R2", Room.STATUS_ACTIVE));
            rooms.add(new Room(i, "R3", Room.STATUS_ACTIVE));
            rooms.add(new Room(i, "R4", Room.STATUS_ACTIVE));
            rooms.add(new Room(i, "R5", Room.STATUS_ACTIVE));
            rooms.add(new Room(i, "R6", Room.STATUS_ACTIVE));
            rooms.add(new Room(i, "R7", Room.STATUS_ACTIVE));
            rooms.add(new Room(i, "R8", Room.STATUS_ACTIVE));
            rooms.add(new Room(i, "R9", Room.STATUS_ACTIVE));
            rooms.add(new Room(i, "RA", Room.STATUS_ACTIVE));
            rooms.add(new Room(i, "RB", Room.STATUS_ACTIVE));
            rooms.add(new Room(i, "RC", Room.STATUS_ACTIVE));
            rooms.add(new Room(i, "RD", Room.STATUS_ACTIVE));
            rooms.add(new Room(i, "RE", Room.STATUS_ACTIVE));
        }
    }
    
    public byte getStatus(int areanr, int roomnr) {
        Room r = (Room) rooms.get((areanr-1)*this.numberOfRooms + roomnr-1);
        return r.getStatus();
    }
    
    
    public void setStatus(int areanr, int roomnr, byte nr) {
        Room r = (Room) rooms.get((areanr-1)*this.numberOfRooms + roomnr-1);
        r.setStatus(nr);
    }
    
    public String getName(int areanr, int roomnr) {
        Room r = (Room) rooms.get((areanr-1)*this.numberOfRooms + roomnr-1);
        return r.getName();
    }
    
    public void setName(int areanr, int roomnr, String name) {
        Room r = (Room) rooms.get((areanr-1)*this.numberOfRooms + roomnr-1);
        r.setName(name);
    }
    
    public String getDesc(int areanr, int roomnr) {
        Room r = (Room) rooms.get((areanr-1)*this.numberOfRooms + roomnr-1);
        return r.getDescription();
    }
    
    void setDesc(int areanr, int roomnr, String desc) {
        Room r = (Room) rooms.get((areanr-1)*this.numberOfRooms + roomnr-1);
        r.setDescription(desc);
    }

    public int getRoomCount() {
        return this.numberOfRooms;
    }
}
