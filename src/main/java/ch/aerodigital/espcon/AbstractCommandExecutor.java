/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ch.aerodigital.espcon;

import java.io.PrintWriter;
import jssc.SerialPortEventListener;

/**
 *
 * @author rejap
 */
public abstract class AbstractCommandExecutor implements CommandExecutor {


    // all user info goes into writer
    protected PrintWriter writer;

    // required to mark completion and give back the prompt
    protected SerialPortEventListener restoreEventListener;
    protected SerialPortEventListener nextSerialPortEventListener;


    /**
     * @param restoreEventListener the restoreEventListener to set
     */
    public void setRestoreEventListener(SerialPortEventListener restoreEventListener) {
        this.restoreEventListener = restoreEventListener;
    }

    /**
     * @param nextSerialPortEventListener the nextSerialPortEventListener to set
     */
    public void setNextSerialPortEventListener(SerialPortEventListener nextSerialPortEventListener) {
        this.nextSerialPortEventListener = nextSerialPortEventListener;
    }

    public void setWriter(PrintWriter writer) {
        this.writer = writer;
    }


}
