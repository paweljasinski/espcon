/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ch.aerodigital.espcon;

import jssc.SerialPortEvent;

/**
 *
 * @author rejap
 */
public interface SerialPortEventListenerX {

    /**
     *
     * @param event
     * @return true if event was processed, false otherwise
     */
    public boolean serialEvent(SerialPortEvent event);
}
