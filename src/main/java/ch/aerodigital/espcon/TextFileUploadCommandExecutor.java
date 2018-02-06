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
import java.util.concurrent.BlockingQueue;
import jssc.SerialPortEvent;
import jssc.SerialPortEventListener;
import jssc.SerialPortException;

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
        EXECUTE,
        END,
    }

    private State state;

    private BufferedReader srcFileReader;

    // required to mark completion and give back the prompt
    private SerialPortEventListener restoreEventListener;
    private SerialPortEventListener nextSerialPortEventListener;
    private BlockingQueue promptQueue;

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
                + "file.remove('" + target + "');"
                + "file.open('" + target + "','w+');"
                + "w=file.writeline\n";
        // System.out.println(lua);
        try {
            serialPort.removeEventListener();
        } catch (SerialPortException ex) {
            System.out.println("Uploader: Unable to deactivate serial event listener " + ex);
        }
        try {
            serialPort.addEventListener(new SerialPortSink(nextSerialPortEventListener));
        } catch (SerialPortException ex) {
            System.out.println("Uploader: Add EventListener Error. Canceled. " + ex);
            return;
        }
        state = State.LUA_TRANSFER;
        try {
            serialPort.writeString(lua);
        } catch (SerialPortException ex) {
            System.out.println("Uploader: unable to send lua");
        }
    }

    private void completeTransfer(boolean includeAutoRun) {
        try {
            String cmd = "file.flush();file.close()";
            cmd += autoRun ? ";dofile('" + target + "')\n" : "\n";
            if (includeAutoRun) {
                cmd += ";dofile('" + target + "')\n";
                state = State.EXECUTE;
            } else {
                cmd += "\n";
                state = State.END;
            }
            //System.out.println(cmd);
            serialPort.writeString(cmd);
        } catch (SerialPortException ex) {
            System.out.println("unable to send final file transfer command " + ex);
        }
    }

    private void sendNextLine() {
        try {
            String line = srcFileReader.readLine();
            if (line == null) {
                completeTransfer(autoRun);
            } else {
                try {
                    //System.out.println("w([==[" + line + "]==]);");
                    serialPort.writeString("w([==[" + line + "]==]);\n");
                    System.out.print(".");
                } catch (SerialPortException ex) {
                    System.out.println("sending next line failed " + ex);
                }
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

            try {
                dataCollector = dataCollector + serialPort.readString(event.getEventValue());
            } catch (SerialPortException ex) {
                System.out.println("exception when receiving data:" + ex);
            }
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
                        dataCollector = dataCollector.substring(promptPos + 3);
                        try {
                            System.out.println();
                            promptQueue.put("> ");
                        } catch (InterruptedException ex) {
                            System.out.println("intrerrupted when giving prompt " + ex);
                        }
                        try {
                            serialPort.removeEventListener();
                            serialPort.addEventListener(restoreEventListener);
                        } catch (SerialPortException ex) {
                            System.out.println("Unable to restore command line processor");
                        }
                    }
                    break;
                case EXECUTE:
                    // evacuate back to interactive ?
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
     * @param promptQueue the promptQueue to set
     */
    public void setPromptQueue(BlockingQueue promptQueue) {
        this.promptQueue = promptQueue;
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
