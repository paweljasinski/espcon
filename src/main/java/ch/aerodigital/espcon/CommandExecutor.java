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
public interface CommandExecutor {

    public void start() throws InvalidCommandException;

    public void setRestoreEventListener(SerialPortEventListener restoreEventListener);

    public void setNextSerialPortEventListener(SerialPortEventListener nextSerialPortEventListener);

    public void setWriter(PrintWriter writer);

}
