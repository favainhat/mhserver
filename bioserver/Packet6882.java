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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A mysterious packet :D
 */
public class Packet6882 {
    private static byte[][] data = null;
    //private static byte[][] data = new byte[][]{
    //    {              
    //    }
    //};    
    
    
    //FOR 1
    //In the case of event quests, it seems that it is possible by just placing the uncompressed mib.
    
    //FOR G
    //from 0x27E 5byte short to allow or deny?
    //HR_START, HR_END
    //shoud left blank from 0x8 from start? (ex 0x28f)
    //0x280 (0x288)
    //0x380 (0x388)
    //0x480 (0x488)
    //0x580 (0x588)
    //0x680 (0x688) (end at 0x77F)
    //from 0x780 city desc exist
    //city discriptoin1 start at 0x28f (also 0x100)
    //city discriptoin2-5 start at 0x28f (also 0x100)
    //shoud left from 0x8 from start? (ex) area discriptoin start at 0x28f) (also 0x100)
    ///some garbage bytes to need for monster size from 0x990
    //Before those, there seems to be a value for the synchronization period.
    //nr 0 is area information and nr 1-3 are event quests.


    //FOR DOS
    //at 0x17C Area String start?(but cropped)
    //at 0x0260 -0x360? HR RANGE CHECK EXIST?
    //HR0 to HR 999
    //eg. 00 00 E7 03 // 00 00 E7 03 //00 00 E7 03 //00 00 E7 03
    //some garbage bytes to need for monster show size somewhere?
    //ok I didn't figure out yet, just fill some bytes from 0x0b80 to 0x0bc0
    //Before those, there seems to be a value for the synchronization period.
    //nr 0 is area information and nr 1-3 are event quests.

    
    public static void init() {
        if (data == null) {
            //ByteBuffer z = ByteBuffer.wrap(new byte[1000]);
            //byte[] retval = new byte[z.position()];
            //z.rewind();
            //z.get(retval);
            data = new byte[][]{
                {},{},{},{}
            };
            //data[0] = retval;
            String filename = "area.bin";
            //String filename = "areaDos.bin";
            //String filename = "areaG.bin";
            try {                
                data[0] = Files.readAllBytes(Paths.get(filename));
            } catch (IOException ex) {
                Logging.println("Error reading file: "+filename);
            }
            //event quests
            //filename = "m60029.mib.unpacked";
            //try {                
            //    data[1] = Files.readAllBytes(Paths.get(filename));
            //} catch (IOException ex) {
            //    Logging.println("Error reading file: "+filename);
            //}
        }
    };
    public static byte[] getData(int nr, int offset, int sizeL) {
        if (data == null) {
            init();
        }
        byte[] d = new byte[sizeL + 11];
        // check if more bytes are requested than left in buffer
        if(sizeL > (data[nr].length-offset)) sizeL = data[nr].length - offset;
        d[0] = (byte)nr;
        d[1] = (byte)((offset>>24)&0xff);
        d[2] = (byte)((offset>>16)&0xff);
        d[3] = (byte)((offset>>8)&0xff);
        d[4] = (byte)((offset)&0xff);
        d[5] = (byte)((sizeL>>24)&0xff);
        d[6] = (byte)((sizeL>>16)&0xff);
        d[7] = (byte)((sizeL>>8)&0xff);
        d[8] = (byte)((sizeL)&0xff);
        d[9] = d[7];
        d[10] = d[8];
        System.arraycopy(data[nr], offset, d, 11, sizeL);
        return(d);
    }
    public static int getlen(int i){
        if (data == null) {
            init();
        }
        return data[i].length;
    }
}
