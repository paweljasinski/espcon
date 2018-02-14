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

/**
 *
 * @author Pawel Jasinski
 */
public class TurboTextFileUploadCommandExecutor extends AbstractCommandExecutor {

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
        serialPort.pushEventListener(new SerialPortSink());
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
            switch (state) {
                case IDLE:
                    writer.println("unexpected data when in idle: " + dataCollector);
                    break;
                case BUF_TRANSFER:
                    int endPos = dataCollector.indexOf("\r\n--Done--\r\n> ");
                    if (-1 != endPos) {
                        writer.println(); // after dots
                        if (autoRun) {
                            dataCollector = dataCollector.substring(endPos + 12);
                            state = State.AUTORUN;
                        } else {
                            serialPort.popEventListener();
                        }
                        serialPort.writeStringX("\n"); // get next prompt, here or outside
                    } else {
                        int promptPos = dataCollector.indexOf("> ");
                        if (-1 != promptPos) {
                            dataCollector = dataCollector.substring(promptPos + 2);
                            writer.print(".");
                            writer.flush();
                            sendNext();
                        }
                    }
                    break;
                case AUTORUN:
                    int promptPos = dataCollector.indexOf("> ");
                    if (-1 != promptPos) {
                        serialPort.popEventListener();
                        serialPort.writeStringX(("dofile('" + target + "')\n"));
                    }
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
