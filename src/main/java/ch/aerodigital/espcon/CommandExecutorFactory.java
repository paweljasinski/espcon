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
public class CommandExecutorFactory {

    static public CommandExecutor createExecutor(String command) throws InvalidCommandException {

        return new FileUploadCommandExecutor(command);
    }

}
