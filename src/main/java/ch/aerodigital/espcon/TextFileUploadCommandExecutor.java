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

/**
 *
 * @author Pawel Jasinski
 */
public class TextFileUploadCommandExecutor extends AbstractCommandExecutor {

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
        // writer.println(lua);
        serialPort.pushEventListener(new SerialPortSink());
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
                writer.println(); // after the progress dots
                completeTransfer(autoRun);
            } else {
                //writer.println("w([==[" + line + "]==]);");
                serialPort.writeStringX("w([==[" + line + "]==]);\n");
                writer.print(".");
                writer.flush();
            }
        } catch (IOException ex) {
            writer.println("unable to read file: " + src);
            completeTransfer(false);
        }
    }

    private class SerialPortSink implements SerialPortEventListenerX {

        private String dataCollector;

        public SerialPortSink() {
            dataCollector = "";
        }

        @Override
        public boolean serialEvent(SerialPortEvent event) {
            if (!event.isRXCHAR() || event.getEventValue() <= 0) {
                return false;
            }            dataCollector = dataCollector + serialPort.readStringX(event.getEventValue());
            int promptPos;
            switch (state) {
                case IDLE:
                    writer.println("unexpected data when in idle: " + dataCollector);
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
                        serialPort.popEventListener();
                        serialPort.writeStringX("\n");
                    }
                    break;
                case AUTORUN:
                    promptPos = dataCollector.indexOf("> ");
                    if (-1 != promptPos) {
                        dataCollector = dataCollector.substring(promptPos + 2);
                        serialPort.popEventListener();
                        serialPort.writeStringX("\n");
                    }
                    writer.print(dataCollector);
                    dataCollector = "";
                    break;

                default:
                    break;
            }
            return true;
        }
    }

    /**
     * @param autoRun the autoRun to set
     */
    public void setAutoRun(boolean autoRun) {
        this.autoRun = autoRun;
    }

}
