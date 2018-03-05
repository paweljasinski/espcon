/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ch.aerodigital.espcon;

import java.util.Stack;
import jssc.SerialPort;
import jssc.SerialPortEvent;
import jssc.SerialPortEventListener;
import jssc.SerialPortException;

/**
 *
 * @author rejap
 */
public class SerialPortX extends SerialPort {

    private SerialPortEventListenerX commonEventListener;

    private final Stack<SerialPortEventListenerX> eventListeners;

    private boolean rts;

    private boolean dtr;

    public SerialPortX(String portName) {
        super(portName);
        eventListeners = new Stack<>();
    }

    public boolean closePortX() {
        try {
            boolean ret = super.closePort();
            return ret;
        } catch (SerialPortException ex) {
            throw new SerialPortXException("exception in closePortX - " + ex.getMessage(), ex);
        }
    }

    public void openPortX() {
        boolean ret = false;
        try {
            ret = openPort();
        } catch (SerialPortException ex) {
            throw new SerialPortXException("failed to open port - " + ex.getMessage(), ex);
        }
        if (!ret) {
            throw new SerialPortXException("failed to open port");
        }
        try {
            ret = setEventsMask(SerialPort.MASK_BREAK | SerialPort.MASK_CTS | SerialPort.MASK_DSR
                    | SerialPort.MASK_ERR | SerialPort.MASK_RING | SerialPort.MASK_RLSD | SerialPort.MASK_RXCHAR
                    | SerialPort.MASK_RXFLAG | SerialPort.MASK_TXEMPTY);
        } catch (SerialPortException ex) {
            throw new SerialPortXException("failed to set event mask - " + ex.getMessage(), ex);
        }
        if (!ret) {
            throw new SerialPortXException("failed to set event mask");
        }
        addEventListenerX(new MasterEventListener());
    }

    public void setParamsX(int baudRate, int dataBits, int stopBits, int parity, boolean setRTS, boolean setDTR) {
        boolean ret = false;
        try {
            ret = setParams(baudRate, dataBits, stopBits, parity, setRTS, setDTR);
        } catch (SerialPortException ex) {
            throw new SerialPortXException("failed to set port parameters - " + ex.getMessage(), ex);
        }
        if (!ret) {
            throw new SerialPortXException("failed to set port parameters");
        }
    }

    private class MasterEventListener implements SerialPortEventListener {

        @Override
        public void serialEvent(SerialPortEvent event) {
            boolean processed = false;
            if (!eventListeners.empty()) {
                processed = eventListeners.peek().serialEvent(event);
            }
            if (!processed && commonEventListener != null) {
                commonEventListener.serialEvent(event);
            }
        }
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
            boolean ret = super.writeString(string);
            if (!ret) {
                throw new SerialPortXException("writeStringX failed");
            }
        } catch (SerialPortException ex) {
            throw new SerialPortXException("exception in writeStringX - " + ex.getMessage(), ex);
        }
    }

    public void writeBytesX(byte[] bytes) {
        try {
            boolean ret = super.writeBytes(bytes);
            if (!ret) {
                throw new SerialPortXException("writeBytesX failed");
            }
        } catch (SerialPortException ex) {
            throw new SerialPortXException("exception in writeBytesX - " + ex.getMessage(), ex);
        }
    }

    public void writeStringX(String string, String context) {
        try {
            boolean ret = super.writeString(string);
            if (!ret) {
                throw new SerialPortXException("writeStringX failed [" + context + "] ");
            }
        } catch (SerialPortException ex) {
            throw new SerialPortXException("exception in writeStringX [" + context + "] " + ex.getMessage(), ex);
        }
    }

    private void removeEventListenerX() {
        try {
            boolean ret = super.removeEventListener();
            if (!ret) {
                throw new SerialPortXException("removeEventListerX failed");
            }
        } catch (SerialPortException ex) {
            throw new SerialPortXException(ex.getMessage(), ex);
        }
    }

    private void addEventListenerX(SerialPortEventListener listener) {
        try {
            super.addEventListener(listener);
        } catch (SerialPortException ex) {
            throw new SerialPortXException(ex.getMessage(), ex);
        }
    }

    public void setDTRX(boolean enabled) {
        try {
            boolean ret = super.setDTR(enabled);
            if (!ret) {
                throw new SerialPortXException("setDTR failed");
            }
            dtr = enabled;
        } catch (SerialPortException ex) {
            throw new SerialPortXException(ex.getMessage(), ex);
        }
    }

    public void setRTSX(boolean enabled) {
        try {
            boolean ret = super.setRTS(enabled);
            if (!ret) {
                throw new SerialPortXException("setRTS failed");
            }
            rts = enabled;
        } catch (SerialPortException ex) {
            throw new SerialPortXException(ex.getMessage(), ex);
        }
    }

    public boolean isCTSX() {
        try {
            return super.isCTS();
        } catch (SerialPortException ex) {
            throw new SerialPortXException(ex.getMessage(), ex);
        }
    }

    public boolean isDSRX() {
        try {
            return super.isDSR();
        } catch (SerialPortException ex) {
            throw new SerialPortXException(ex.getMessage(), ex);
        }
    }


    public void pushEventListener(SerialPortEventListenerX listener) {
        eventListeners.push(listener);
    }

    public void popEventListener() {
        eventListeners.pop();
    }

    public void installCommonEventListener(SerialPortEventListenerX listener) {
        commonEventListener = listener;
    }

    /**
     * @return the rts
     */
    public boolean isRts() {
        return rts;
    }

    /**
     * @return the dtr
     */
    public boolean isDtr() {
        return dtr;
    }

}
