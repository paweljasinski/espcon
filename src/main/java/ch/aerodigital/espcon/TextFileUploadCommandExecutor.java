/*
 *
 */
package ch.aerodigital.espcon;

import static ch.aerodigital.espcon.App.serialPort;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import jssc.SerialPortEvent;
import jssc.SerialPortEventListener;

/**
 *
 * @author Pawel Jasinski
 */
public class TextFileUploadCommandExecutor implements CommandExecutor {

    private String src;
    private String target;
    private boolean autoRun;

    private enum State {
        IDLE,
        LUA_TRANSFER,
        FILE_TRANSFER,
        END,
        AUTORUN,
    }

    private State state;

    private BufferedReader srcFileReader;

    // required to mark completion and give back the prompt
    private SerialPortEventListener restoreEventListener;
    private SerialPortEventListener nextSerialPortEventListener;

    public TextFileUploadCommandExecutor(String command) throws InvalidCommandException {
        String args[] = command.split("\\s+");
        if (args.length == 1 || args.length > 3) {
            throw new InvalidCommandException("upload needs 1 or 2 arguments");
        }
        if (args.length == 2) {
            src = args[1];
            target = args[1];
        } else {
            src = args[1];
            target = args[2];
        }
        state = State.IDLE;
    }

    public void start() throws InvalidCommandException {
        File srcFile = new File(src);
        try {
            srcFileReader = new BufferedReader(new FileReader(srcFile));
        } catch (FileNotFoundException ex) {
            throw new InvalidCommandException("Unable to open: " + src);
        }
        String lua = ""
                + "file.remove('" + target + "') "
                + "file.open('" + target + "','w+') "
                + "w=file.writeline\n";
        // System.out.println(lua);
        serialPort.removeEventListenerX();
        serialPort.addEventListenerX(new SerialPortSink(nextSerialPortEventListener));
        state = State.LUA_TRANSFER;
        serialPort.writeStringX(lua);
    }

    private void completeTransfer(boolean includeAutoRun) {
        String cmd = "file.flush() file.close()";
        if (includeAutoRun) {
            cmd += " dofile('" + target + "')\n";
            state = State.AUTORUN;
        } else {
            cmd += "\n";
            state = State.END;
        }
        serialPort.writeStringX(cmd);
    }

    private void sendNextLine() {
        try {
            String line = srcFileReader.readLine();
            if (line == null) {
                System.out.println(); // after the progress dots
                completeTransfer(autoRun);
            } else {
                //System.out.println("w([==[" + line + "]==]);");
                serialPort.writeStringX("w([==[" + line + "]==]);\n");
                System.out.print(".");
            }
        } catch (IOException ex) {
            System.out.println("unable to read file: " + src);
            completeTransfer(false);
        }
    }

    private class SerialPortSink implements SerialPortEventListener {
        private String dataCollector;
        private final SerialPortEventListener next;

        public SerialPortSink(SerialPortEventListener next) {
            dataCollector = "";
            this.next = next;
        }

        public void serialEvent(SerialPortEvent event) {
            if (!event.isRXCHAR() || event.getEventValue() <= 0) {
                if (next != null) {
                    next.serialEvent(event);
                }
                return;
            }

            dataCollector = dataCollector + serialPort.readStringX(event.getEventValue());
            int promptPos;
            switch (state) {
                case IDLE:
                    System.out.println("unexpected data when in idle: " + dataCollector);
                    break;
                case LUA_TRANSFER:
                case FILE_TRANSFER:
                    promptPos = dataCollector.indexOf("> ");
                    if (-1 != promptPos) {
                        dataCollector = dataCollector.substring(promptPos + 2);
                        sendNextLine();
                    }
                    break;
                case END:
                    promptPos = dataCollector.indexOf("> ");
                    if (-1 != promptPos) {
                        dataCollector = dataCollector.substring(promptPos + 2);
                        serialPort.removeEventListenerX();
                        serialPort.addEventListenerX(restoreEventListener);
                        serialPort.writeStringX("\n");
                    }
                    break;
                case AUTORUN:
                    promptPos = dataCollector.indexOf("> ");
                    if (-1 != promptPos) {
                        dataCollector = dataCollector.substring(promptPos + 2);
                        serialPort.removeEventListenerX();
                        serialPort.addEventListenerX(restoreEventListener);
                        serialPort.writeStringX("\n");
                    }
                    System.out.print(dataCollector);
                    dataCollector = "";
                    break;

                default:
                    break;
            }
        }
    }

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

    /**
     * @param autoRun the autoRun to set
     */
    public void setAutoRun(boolean autoRun) {
        this.autoRun = autoRun;
    }
}
