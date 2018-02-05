/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ch.aerodigital.espcon;

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
            if ((s256.get(i).length() + subs.trim().length()) <= 250) {
                s256.set(i, s256.get(i) + " " + subs.trim());
            } else {
                s256.set(i, s256.get(i) + "\r");
                s256.add(subs);
                i++;
            }
        }
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
}
