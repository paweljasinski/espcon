/*
 *
 */
package ch.aerodigital.espcon;

import static ch.aerodigital.espcon.App.baud;
import static ch.aerodigital.espcon.App.echo;
import static ch.aerodigital.espcon.App.serialPort;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import jssc.SerialPortEvent;
import jssc.SerialPortEventListener;

/**
 *
 * @author Pawel Jasinski
 */
public class TurboTextFileUploadCommandExecutor implements CommandExecutor {

    private String src;
    private String target;
    private boolean autoRun;

    private enum State {
        IDLE,
        BUF_TRANSFER,
        WAIT_DONE,
        AUTORUN,
    }

    private State state;

    private static final int CHUNK_SIZE = 255; // effective payload is 254
    private ArrayList<byte[]> sendBuffer;
    private int sendIndex;
    private final byte[] fileReadBuffer = new byte[CHUNK_SIZE];

    // required to mark completion and give back the prompt
    private SerialPortEventListener restoreEventListener;
    private SerialPortEventListener nextSerialPortEventListener;

    public TurboTextFileUploadCommandExecutor(String command) throws InvalidCommandException {
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
        sendBuffer = new ArrayList<byte[]>();
    }

    public void start() throws InvalidCommandException {
        // this upload performs implicit dos2unix conversion
        String lua1 = ""
                + "F='" + target + "' "
                + "file.remove(F) "
                + "file.open(F,'w+') "
                + "F=nil "
                + "uart.setup(0," + baud + ",8,0,1,0)\n";
        String lua2 = ""
                + "rcv=function(b) "
                + "  local s,e"
                + "  s,e=string.find(b,'~~~esp~eof~~~',1,true)"
                + "  if s==nil then"
                + "    file.write(string.gsub(b,'\\r',''))"
                + "    uart.write(0,'> ')"
                + "  else"
                + "    uart.on('data')\n";
        String lua3 = ""
                + "    file.write(string.sub(b,1,s-1))"
                + "    file.close()"
                + "    rcv=nil"
                + "    uart.setup(0," + baud + ",8,0,1," + echo + ")\n";
        String lua4 = ""
                + "    print('\\r\\n--Done--\\r\\n> ')"
                + "    collectgarbage()"
                + "  end "
                + "end "
                + "uart.on('data','\\r',rcv,0)\n";

        sendBuffer.add(lua1.getBytes());
        sendBuffer.add(lua2.getBytes());
        sendBuffer.add(lua3.getBytes());
        sendBuffer.add(lua4.getBytes());
        File srcFile = new File(src);
        InputStream srcFileIs;
        try {
            srcFileIs = new BufferedInputStream(new FileInputStream(srcFile));
        } catch (FileNotFoundException ex) {
            throw new InvalidCommandException("Unable to open src: " + src);
        }
        try {
            int read;
            while (-1 != (read = srcFileIs.read(fileReadBuffer, 0, fileReadBuffer.length - 1))) {
                fileReadBuffer[read] = '\r';
                sendBuffer.add(Arrays.copyOfRange(fileReadBuffer, 0, read));
            }
            sendBuffer.add("~~~esp~eof~~~\r".getBytes());
        } catch (IOException ex) {
            Util.close(srcFileIs);
            throw new InvalidCommandException("Failed to read file chunk" + ex);
        }
        Util.close(srcFileIs);
        serialPort.removeEventListenerX();
        serialPort.addEventListenerX(new SerialPortSink(nextSerialPortEventListener));
        sendIndex = 0;
        state = State.BUF_TRANSFER;
        sendNext();
    }

    private void sendNext() {
        if (sendIndex < sendBuffer.size()) {
            serialPort.writeBytesX(sendBuffer.get(sendIndex));
            sendIndex++;
        }
    }

    private void completed() {
        serialPort.removeEventListenerX();
        serialPort.addEventListenerX(restoreEventListener);
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
            switch (state) {
                case IDLE:
                    System.out.println("unexpected data when in idle: " + dataCollector);
                    break;
                case BUF_TRANSFER:
                    int endPos = dataCollector.indexOf("\r\n--Done--\r\n> ");
                    if (-1 != endPos) {
                        System.out.println(); // after dots
                        if (autoRun) {
                            dataCollector = dataCollector.substring(endPos + 12);
                            state = State.AUTORUN;
                        } else {
                            completed();
                        }
                        serialPort.writeStringX("\n"); // get next prompt, here or outside
                    } else {
                        int promptPos = dataCollector.indexOf("> ");
                        if (-1 != promptPos) {
                            dataCollector = dataCollector.substring(promptPos + 2);
                            System.out.print(".");
                            sendNext();
                        }
                    }
                    break;
                case AUTORUN:
                    int promptPos = dataCollector.indexOf("> ");
                    if (-1 != promptPos) {
                        completed();
                        serialPort.writeStringX(("dofile('" + target + "')\n"));
                    }
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
