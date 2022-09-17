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

/**
 * Object for rooms within areas
 */
public class Room {
    public final static byte STATUS_INACTIVE = 0;
    public final static byte STATUS_FREE = 1;
    public final static byte STATUS_INCREATE = 2;    // TODO: find this out
    public final static byte STATUS_ACTIVE = 3;    // while creating ruleset
    public final static byte STATUS_BUSY = 4;       // FULL
    
    private int areanumber;
    private String name;
    private String description;
    private byte status;
    
    public Room(int area, String name, byte status) {
        this.name = name;
        this.status = status;
        this.areanumber = area;
        this.description = "Let's play";
    }
    
    public String getName() {
        return this.name;
    }
    
    public void setName(String name) {
       this.name = name;
    }
    
    public String getDescription() {
        return this.description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public byte getStatus() {
        return this.status;
    }
    
    public byte setStatus(byte nr) {
        return this.status = nr;
    }
    
    public int getAreaNumber() {
        return this.areanumber;
    }

}
