/*
 *
 */
package ch.aerodigital.espcon;

import static ch.aerodigital.espcon.App.serialPort;
import java.util.ArrayList;
import jssc.SerialPortEvent;
import jssc.SerialPortEventListener;

/**
 *
 * @author Pawel Jasinski
 */
public class HexDumpCommandExecutor extends AbstractCommandExecutor {

    private String filename;

    private enum State {
        IDLE,
        LUA_TRANSFER,
        DUMP_TRANSFER,
    }
    private State state;
    private ArrayList<String> sendBuffer;
    private int sendIndex;

    public HexDumpCommandExecutor(String command) throws InvalidCommandException {
        String args[] = command.split("\\s+");
        if (args.length != 2) {
            throw new InvalidCommandException("hexdump needs 1 argument");
        }
        filename = args[1];
        state = State.IDLE;
    }

    public void start() throws InvalidCommandException {
        String cmd = ""
                + "_dump=function()\n"
                + "  local buf\n"
                + "  local j=0\n"
                + "  if file.open('" + filename + "','r') then\n"
                + "  print('--HexDump start')\n"
                + "  repeat\n"
                + "     buf=file.read(1024)\n"
                + "     if buf~=nil then\n"
                + "     local n \n"
                + "     if #buf==1024 then\n"
                + "        n=(#buf/16)*16\n"
                + "     else\n"
                + "        n=(#buf/16+1)*16\n"
                + "     end\n"
                + "     for i=1,n do\n"
                + "         j=j+1\n"
                + "         if (i-1)%16==0 then\n"
                + "            uart.write(0,string.format('%08X  ',j-1))\n"
                + "         end\n"
                + "         uart.write(0,i>#buf and'   'or string.format('%02X ',buf:byte(i)))\n"
                + "         if i%8==0 then uart.write(0,' ')end\n"
                + "         if i%16==0 then uart.write(0,buf:sub(i-16+1, i):gsub('%c','.'),'\\n')end\n"
                + "         if i%128==0 then tmr.wdclr()end\n"
                + "     end\n"
                + "     end\n"
                + "  until(buf==nil)\n"
                + "  file.close()\n"
                + "  print('\\r--HexDump done')\n"
                + "  else\n"
                + "  print('\\r--HexDump error')\n"
                + "  end\n"
                + "end\n"
                + "_dump()\n"
                + "_dump=nil\n";
        sendBuffer = Util.cmdPrep(cmd);
        serialPort.removeEventListenerX();
        serialPort.addEventListenerX(new SerialPortSink(nextSerialPortEventListener));
        sendIndex = 0;
        state = State.LUA_TRANSFER;
        sendNext();
    }

    private void sendNext() {
        if (sendIndex < sendBuffer.size()) {
            serialPort.writeStringX(sendBuffer.get(sendIndex));
            sendIndex++;
        }
        if (sendIndex == sendBuffer.size()) {
            serialPort.writeStringX("\n");
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
                    writer.println("unexpected data when in idle: " + dataCollector);
                    break;
                case LUA_TRANSFER:
                    int startMarkerPos = dataCollector.indexOf("--HexDump start");
                    if (-1 != startMarkerPos) {
                        dataCollector = dataCollector.substring(startMarkerPos + 15);
                        state = State.DUMP_TRANSFER;
                        break;
                    }
                    int errMarkerPos = dataCollector.indexOf("--HexDump error");
                    if (-1 != errMarkerPos) {
                        completed();
                        serialPort.writeStringX("\n");
                        break;
                    }
                    int promptPos = dataCollector.indexOf("> ");
                    if (-1 != promptPos) {
                        dataCollector = dataCollector.substring(promptPos + 2);
                        sendNext();
                        // break;
                    }
                    break;
                case DUMP_TRANSFER:
                    int endMarkerPos = dataCollector.indexOf("--HexDump done");
                    if (-1 != endMarkerPos) {
                        dataCollector = dataCollector.substring(endMarkerPos + 15);
                        completed();
                        break;
                    }
                    writer.print(dataCollector);
                    writer.flush();
                    dataCollector = "";
                    break;
                default:
                    break;
            }
        }
    }
}
