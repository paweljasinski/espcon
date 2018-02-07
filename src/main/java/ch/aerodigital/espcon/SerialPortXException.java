/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ch.aerodigital.espcon;

/**
 *
 * @author rejap
 */
public class SerialPortXException extends RuntimeException {

    public SerialPortXException(String msg) {
        super(msg);
    }

    public SerialPortXException(String msg, Throwable nested) {
        super(msg, nested);
    }
}
