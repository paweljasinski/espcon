/*
 *
 */
package ch.aerodigital.espcon;

import static ch.aerodigital.espcon.App.serialPort;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import jssc.SerialPortEvent;
import jssc.SerialPortEventListener;
import jssc.SerialPortException;

/**
 *
 * @author Pawel Jasinski
 */
public class FileUploadCommandExecutor implements CommandExecutor {

    private String src;
    private String target;

    private enum State {
        IDLE,
        LUA_TRANSFER,
        FILE_TRANSFER,
        END,
        WAIT_FINAL_PROMPT
    }

    private State state;

    private static final int FILE_UPLOAD_PACKET_SIZE = 250;
    private int sendIndex = 0;
    private ArrayList<String> luaCodeBuffer;
    private InputStream srcFileIs;
    private final byte[] fileReadBuffer;
    private byte[] currentChunk;

    private long nFullPackets;
    private long lastPacketSize;
    private long totalPackets;
    private long regularPacketSize;

    // required to mark completion and give back the prompt
    private SerialPortEventListener restoreEventListener;
    private SerialPortEventListener nextSerialPortEventListener;
    private BlockingQueue promptQueue;

    public FileUploadCommandExecutor(String command) throws InvalidCommandException {
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
        fileReadBuffer = new byte[FILE_UPLOAD_PACKET_SIZE];
        state = State.IDLE;
    }

    public void start() throws InvalidCommandException {
        File srcFile = new File(src);
        try {
            srcFileIs = new BufferedInputStream(new FileInputStream(srcFile));
        } catch (FileNotFoundException ex) {
            throw new InvalidCommandException("Unable to open: " + src);
        }

        nFullPackets = srcFile.length() / FILE_UPLOAD_PACKET_SIZE;
        lastPacketSize = srcFile.length() % FILE_UPLOAD_PACKET_SIZE;
        totalPackets = nFullPackets + (lastPacketSize != 0 ? 1 : 0);
        regularPacketSize = (totalPackets == 1 && lastPacketSize != 0) ? lastPacketSize : FILE_UPLOAD_PACKET_SIZE;

        String lua = ""
                + "_up=function(n,l,ll)\n"
                + "  local cs=0\n"
                + "  local i=0\n"
                + "  local f\n"
                + "  print('>'..' ')\n"
                + "  uart.on('data', l, function(b)\n"
                + "    i=i+1\n"
                + "    f=file.open('" + target + "','a+')\n"
                + "    f:write(b)\n"
                + "    f:close()\n"
                + "    cs=0\n"
                + "    for j=1,l do\n"
                + "      cs=cs+(b:byte(j)*20)%19\n"
                + "    end\n"
                + "    uart.write(0,'~~~CRC-'..'START~~~'..cs..'~~~CRC-'..'END~~~')\n"
                + "    if i==n then\n"
                + "      uart.on('data')\n"
                + "      print('~~~'..'END'..'~~~')\n"
                + "    end\n"
                + "    if i==n-1 and ll>0 then\n"
                + "      _up(1,ll,ll)\n"
                + "    end\n"
                + "  end,0)\n"
                + "end\n"
                + "file.remove('" + target + "')\n";
        luaCodeBuffer = Util.cmdPrep(lua);
        luaCodeBuffer.add("_up(" + totalPackets + "," + regularPacketSize + "," + lastPacketSize + ")");
        // TODO: this should be optional, controlled with --set
        // System.out.println(luaCodeBuffer);
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
        sendIndex = 0;
        state = State.LUA_TRANSFER;
        sendNextPacket();
    }

    private void sendNextPacket() {
        if (sendIndex < luaCodeBuffer.size()) {
            try {
                serialPort.writeString(luaCodeBuffer.get(sendIndex) + "\r");
            } catch (SerialPortException ex) {
                System.out.println("failed to send file upload program " + ex);
            }
        } else if ((sendIndex - luaCodeBuffer.size()) < totalPackets) {
            state = State.FILE_TRANSFER;
            try {
                int size = srcFileIs.read(fileReadBuffer);
                if (size != fileReadBuffer.length) {
                    currentChunk = Arrays.copyOfRange(fileReadBuffer, 0, size);
                } else {
                    currentChunk = fileReadBuffer;
                }
                serialPort.writeBytes(currentChunk);
            } catch (IOException ex) {
                System.out.println("failed to read file chunk" + ex);
            } catch (SerialPortException ex) {
                System.out.println("failed to send file file chunk " + ex);
            }

        } else {
            try {
                srcFileIs.close();
            } catch (IOException ex) {
                System.out.println("error when closing input " + ex);
            }
            state = State.END;
        }
        sendIndex++;
    }

    private class SerialPortSink implements SerialPortEventListener {

        private String dataCollector;
        private SerialPortEventListener next;

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
            switch (state) {
                case IDLE: {
                    System.out.println("unexpected data when in idle: " + dataCollector);
                    break;
                }
                case LUA_TRANSFER: {
                    int promptPos = dataCollector.indexOf("> ");
                    if (-1 != promptPos) {
                        dataCollector = dataCollector.substring(promptPos + 3);
                        sendNextPacket();
                    }
                    break;
                }
                case FILE_TRANSFER:
                    int crcEndMarkerPos = dataCollector.indexOf("~~~CRC-END~~~");
                    if (-1 != crcEndMarkerPos) {
                        int start = dataCollector.indexOf("~~~CRC-START~~~");
                        String crcString = dataCollector.substring(start + 15, crcEndMarkerPos);
                        dataCollector = dataCollector.substring(crcEndMarkerPos + 13);
                        int receivedCrc = Integer.parseInt(crcString);
                        int expectedCrc = Util.CRC(currentChunk);
                        System.out.print(expectedCrc == receivedCrc ? "." : "e");
                        sendNextPacket();
                    }
                    break;
                case END:
                    int endPos = dataCollector.indexOf("~~~END~~~");
                    if (-1 != endPos) {
                        dataCollector = dataCollector.substring(endPos + 9);
                        try {
                            serialPort.writeString("_up=nil\n");
                        } catch (SerialPortException ex) {
                            System.out.println("failed to send final command " + ex);
                        }
                        state = State.WAIT_FINAL_PROMPT;
                    }
                    break;
                case WAIT_FINAL_PROMPT: {
                    int promptPos = dataCollector.indexOf("> ");
                    if (-1 != promptPos) {
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
                }
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
}
