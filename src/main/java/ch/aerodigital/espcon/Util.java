/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ch.aerodigital.espcon;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;

/**
 *
 * @author rejap
 */
public class Util {

    public static ArrayList<String> cmdPrep(String cmd) {
        ArrayList<String> s256 = new ArrayList<String>();
        int i = 0;
        s256.add("");
        for (String subs : cmd.split("\n")) {
            // TODO: this should be a string builder
            if ((s256.get(i).length() + subs.trim().length()) <= 250) {
                s256.set(i, s256.get(i) + " " + subs.trim());
            } else {
                s256.set(i, s256.get(i) + "\n");
                s256.add(subs);
                i++;
            }
        }
        // s256.set(i-1, s256.get(i-1)+"\n");
        // could help in some situations, this makes sures the last one has \n as well
        return s256;
    }

    public static int CRC(byte[] s) {
        int cs = 0;
        int x;
        for (int i = 0; i < s.length; i++) {
            x = s[i] & 0xFF;
            cs = cs + (x * 20) % 19;
        }
        return cs;
    }

    public static void close(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ex) {
            // ignore
        }
    }
}
