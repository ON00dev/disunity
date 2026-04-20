/*
 ** 2015 April 20
 **
 ** The author disclaims copyright to this source code. In place of
 ** a legal notice, here is a blessing:
 **    May you do good and not evil.
 **    May you find forgiveness for yourself and forgive others.
 **    May you share freely, never taking more than you give.
 */
package info.ata4.unity.util;

import info.ata4.io.DataReader;
import info.ata4.io.DataWriter;
import info.ata4.unity.asset.VersionInfo;
import java.io.IOException;

/**
 *
 * @author Nico Bergemann <barracuda415 at yahoo.de>
 */
public class UnityHash128 extends UnityStruct {
    
    private final byte[] hash = new byte[16];
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    public UnityHash128(VersionInfo versionInfo) {
        super(versionInfo);
    }
    
    public byte[] hash() {
        return hash;
    }

    @Override
    public void read(DataReader in) throws IOException {
        in.readBytes(hash);
    }

    @Override
    public void write(DataWriter out) throws IOException {
        out.writeBytes(hash);
    }

    @Override
    public String toString() {
        char[] out = new char[hash.length * 2];
        for (int i = 0; i < hash.length; i++) {
            int b = hash[i] & 0xFF;
            int j = i * 2;
            out[j] = HEX[b >>> 4];
            out[j + 1] = HEX[b & 0x0F];
        }
        return new String(out);
    }
}
