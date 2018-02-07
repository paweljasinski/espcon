/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ch.aerodigital.espcon;

import jssc.SerialPort;
import jssc.SerialPortEventListener;
import jssc.SerialPortException;

/**
 *
 * @author rejap
 */
public class SerialPortX extends SerialPort {

    public SerialPortX(String portName) {
        super(portName);
    }

    public String readStringX(int byteCount) {
        try {
            return super.readString(byteCount);
        } catch (SerialPortException ex) {
            throw new SerialPortXException("exception in readStringX - " + ex.getMessage(), ex);
        }
    }

    public void writeStringX(String string) {
        try {
            boolean ret =  super.writeString(string);
            if (!ret) {
                throw new SerialPortXException("writeStringX failed" );
            }
        } catch (SerialPortException ex) {
            throw new SerialPortXException("exception in writeStringX - " + ex.getMessage(), ex);
        }
    }

    public void writeBytesX(byte[] bytes) {
        try {
            boolean ret =  super.writeBytes(bytes);
            if (!ret) {
                throw new SerialPortXException("writeBytesX failed" );
            }
        } catch (SerialPortException ex) {
            throw new SerialPortXException("exception in writeBytesX - " + ex.getMessage(), ex);
        }
    }
    public void writeStringX(String string, String context) {
        try {
            boolean ret =  super.writeString(string);
            if (!ret) {
                throw new SerialPortXException("writeStringX failed ["+ context + "] ");
            }
        } catch (SerialPortException ex) {
            throw new SerialPortXException("exception in writeStringX [" + context + "] " + ex.getMessage(), ex);
        }
    }

    public void removeEventListenerX() {
        try {
            boolean ret = super.removeEventListener();
            if (!ret) {
                throw new SerialPortXException("removeEventListerX failed");
            }
        } catch (SerialPortException ex) {
            throw new SerialPortXException(ex.getMessage(), ex);
        }
    }

    public void addEventListenerX(SerialPortEventListener listener) {
        try {
            super.addEventListener(listener);
        } catch (SerialPortException ex) {
            throw new SerialPortXException(ex.getMessage(), ex);
        }
    }

}
