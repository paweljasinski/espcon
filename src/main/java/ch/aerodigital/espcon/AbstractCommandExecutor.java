/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ch.aerodigital.espcon;

import java.io.PrintWriter;

/**
 *
 * @author rejap
 */
public abstract class AbstractCommandExecutor implements CommandExecutor {

    // all user info goes into writer
    protected PrintWriter writer;

    @Override
    public void setWriter(PrintWriter writer) {
        this.writer = writer;
    }

}
