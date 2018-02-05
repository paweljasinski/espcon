package ch.aerodigital.espcon;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import jssc.SerialPort;
import jssc.SerialPortEvent;
import jssc.SerialPortEventListener;
import jssc.SerialPortException;
import jssc.SerialPortList;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.jline.reader.EndOfFileException;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 *
 *
 */
public class App {

    public static String serialPortDevice = null;
    public static Integer baud = 230400;
    public static boolean portOpen = false;
    public static SerialPort serialPort;
    public static Terminal terminal;
    public static boolean waitForMarker;
    public Timer timer;

    private final PortReader portReader;

    private static final String MARKER = "~~~END~~~";

    public static void main(String[] args) {

        CommandLineParser parser = new DefaultParser();
        Options options = new Options();
        options.addOption("h", "help", false, "print help message and exit");
        options.addOption("v", "verbose", false, "be extra verbose");
        options.addOption("d", "debug", false, "print debugging information");
        options.addOption("p", "port", true, "serial port device");
        options.addOption("l", "list", false, "list available serial ports");
        options.addOption(Option.builder("b")
                .desc("serial port baud rate, default = " + baud)
                .longOpt("baud")
                .hasArg()
                .argName("BAUD")
                .type(Integer.class)
                .build());
        try {
            // parse the command line arguments
            CommandLine line = parser.parse(options, args);
            if (line.hasOption("help")) {
                HelpFormatter formatter = new HelpFormatter();
                formatter.printHelp("espcom", options);
                System.exit(0);
            }
            if (line.hasOption("list")) {
                String[] portNames = SerialPortList.getPortNames();
                if (portNames.length > 0) {
                    System.out.println("available serial ports:");

                    for (String port : portNames) {
                        System.out.println(port);
                    }
                } else {
                    System.out.println("no serial ports detected");
                }
                System.exit(0);
            }
            if (line.hasOption("block-size")) {
                System.out.println(line.getOptionValue("block-size"));
            }
            if (line.hasOption("port")) {
                serialPortDevice = line.getOptionValue("port");
            }
        } catch (ParseException exp) {
            System.out.println("Unexpected exception:" + exp.getMessage());
        }

        try {
            App app = new App();
            app.run();
        } catch (IOException ex) {
            System.out.println(ex);
        }
    }

    public App() {
        this.fileReadBuffer = new byte[FILE_UPLOAD_PACKET_SIZE];
        this.portReader = new PortReader();
    }

    public void run() throws IOException {
        openPort();
        terminal = TerminalBuilder.builder().system(true).build();
        History history;
        history = new DefaultHistory();
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).history(history).build();
        reader.setVariable(LineReader.HISTORY_FILE, ".cmd-history");
        String prompt = "press ENTER to sync ... ";
        while (true) {
            String line;
            try {
                line = reader.readLine(prompt);
                if (line.startsWith("--")) { // abuse lua comment as internal command prefix
                    try {
                        processCommand(line);
                    } catch (InvalidCommandException ex) {
                        System.out.println(ex.getMessage());
                        continue; // reuse prompt
                    }
                } else {
                    if (null != serialPort) {
                        serialPort.writeString(line + "\r\n");
                    }
                }
                prompt = waitForPrompt();
            } catch (SerialPortException ex) {
                System.out.println("unable to send serial: " + ex);
            } catch (UserInterruptException e) {
                // Ignore
            } catch (EndOfFileException e) {
                if (timer != null) {
                    timer.cancel();
                }
                try {
                    serialPort.closePort();
                } catch (SerialPortException ex) {
                    System.out.println("troulbe closing serial port " + ex);
                }
                return;
            }
        }
    }

    private class PortReader implements SerialPortEventListener {

        private final StringBuilder lineBuffer = new StringBuilder(1024);

        public void serialEvent(SerialPortEvent event) {

            if (event.isRXCHAR() && event.getEventValue() > 0) {
                try {
                    String data = serialPort.readString(event.getEventValue());
                    lineBuffer.append(data);
                    dump("d ", data);
                    dump("lb", lineBuffer.toString());
                    String[] lines = lineBuffer.toString().split("\r\n", -1);
                    String line;
                    for (int idx = 0; idx < lines.length - 1; idx++) {
                        line = lines[idx];
                        dump("l" + idx, line);
                        if (!line.equals("> ") && !line.equals(">> ")) {
                            if (line.equals(MARKER)) {
                                waitForMarker = false;
                            } else {
                                System.out.println(line);
                            }
                        }
                    }
                    // deal with the last one
                    line = lines[lines.length - 1];
                    dump("ll", line);
                    if (line.length() == 0) {
                        lineBuffer.setLength(0);
                        return;
                    }
                    // from here, only not empty last line
                    if (line.startsWith("> ") || line.startsWith(">> ")) {
                        if (!waitForMarker) {
                            try {
                                promptQueue.put(line);
                            } catch (InterruptedException ex) {
                                System.out.println("interrupter in put");
                            }
                        }
                        lineBuffer.setLength(0);
                    } else {
                        if (lines.length > 1) {
                            // shift the remainder
                            lineBuffer.setLength(0);
                            lineBuffer.append(line);
                        }
                    }

                } catch (SerialPortException ex) {
                    System.out.println(ex.toString());
                }
            } else if (event.isCTS()) {
                System.out.println("cts event");
            } else if (event.isERR()) {
                System.out.println("FileManager: Unknown serial port error received.");
            } else {
                System.out.println("wtf event" + event);
            }
        }
    }


    private class PortFilesUploader implements SerialPortEventListener {
        private String rx_data;

        public PortFilesUploader() {
            rx_data = "";
        }

        public void serialEvent(SerialPortEvent event) {
            String data, crc_parsed;
            int crc_end_marker;
            if (event.isRXCHAR() && event.getEventValue() > 0) {
                try {
                    data = serialPort.readString(event.getEventValue());
                    rx_data = rx_data + data;
                } catch (SerialPortException ex) {
                    System.out.println("exception when receiving data:" + ex);
                }
                if (rx_data.contains("> ")) {
                    rx_data = "";
                }
                crc_end_marker = rx_data.indexOf("~~~CRC-END~~~");
                if (-1 != crc_end_marker) {
                    int start = rx_data.indexOf("~~~CRC-START~~~");
                    crc_parsed = rx_data.substring(start + 15, crc_end_marker);
                    rx_data = rx_data.substring(crc_end_marker + 13);
                    int crc_received = Integer.parseInt(crc_parsed);
                    int crc_expected = CRC(currentChunk);
                    if (crc_expected == crc_received) {
                        System.out.print(".");
                    } else {
                        System.out.print("E");
                    }
                }
                if (rx_data.contains("~~~END~~~")) {
                    try {
                        System.out.println();
                        promptQueue.put("> ");
                    } catch (InterruptedException ex) {
                        System.out.println("intrerrupted when giving prompt " + ex);
                    }
                    try {
                        serialPort.removeEventListener();
                        serialPort.addEventListener(portReader, SerialPort.MASK_RXCHAR + SerialPort.MASK_CTS);
                    } catch (SerialPortException ex) {
                        System.out.println("Unable to restore command line processor");
                    }
                }
            } else if (event.isCTS()) {
                System.out.println("cts event");
            } else if (event.isERR()) {
                System.out.println("FileManager: Unknown serial port error received.");
            } else {
                System.out.println("wtf event" + event);
            }
        }
    }

    private int CRC(byte[] s) {
        int cs = 0;
        int x;
        for (int i = 0; i < s.length; i++) {
            x = s[i] & 0xFF;
            cs = cs + (x * 20) % 19;
        }
        return cs;
    }

    private final BlockingQueue promptQueue = new ArrayBlockingQueue(10);

    private String waitForPrompt() {
        try {
            return (String) promptQueue.poll(2000, TimeUnit.MILLISECONDS); // TODO: This timeout should be extendable
        } catch (InterruptedException ex) {
            System.out.println("interrupted " + ex);
        }
        return "? ";
    }

    private class InvalidCommandException extends Exception {

        public InvalidCommandException(String message) {
            super(message);
        }
    }

    private void processCommand(String command) throws InvalidCommandException {

        if (command.startsWith("--echo")) {
            String args[] = command.split("\\s+");
            int val = 0;
            if (args.length != 2) {
                throw new InvalidCommandException("expecting exactly one argument");
            }
            if (args[1].equals("off")) {
                val = 0;
            } else if (args[1].equals("on")) {
                val = 1;
            } else {
                throw new InvalidCommandException("argument of --echo can be either on or off");
            }
            try {
                serialPort.writeString("uart.setup(0," + baud + ",8, 0, 1, " + val + ")\n");
            } catch (SerialPortException ex) {
                System.out.println("exception in echo off: " + ex);
            }
        } else if (command.equals("--quit")) {
            try {
                serialPort.closePort();
            } catch (SerialPortException ex) {

            }
            System.exit(0);
        } else if (command.equals("--ls")) {
            try {
                String cmd = ""
                        + "_dir=function()\n"
                        + "  local k,v,l\n"
                        + "  for k,v in pairs(file.list()) do\n"
                        + "    l=string.format(\"%-20s\",k)\n"
                        + "    print(l..' : '..v)\n"
                        + "  end\n"
                        + "end\n"
                        + "_dir()\n"
                        + "_dir=nil\n"
                        + "print('~~~'..'END'..'~~~')\n";
                waitForMarker = true;
                serialPort.writeString(cmd);

            } catch (SerialPortException ex) {
                System.out.println("exception in ls: " + ex);
            }
        } else if (command.startsWith("--cat")) {
            String args[] = command.split("\\s+");
            if (args.length != 2) {
                throw new InvalidCommandException("expect exactly one parameter to --cat");
            }
            ViewFile(args[1]);
        } else if (command.startsWith("--upload")) {
            String args[] = command.split("\\s+");
            if (args.length == 1 || args.length > 3) {
                throw new InvalidCommandException("upload needs 1 or 2 arguments");
            }
            if (args.length == 2) {
                uploadFile(args[1]);
            } else {
                uploadFile(args[1], args[2]);
            }
        } else {
            throw new InvalidCommandException("not a valid command");
        }
    }

    private void uploadFile(String src) {
        uploadFile(src, src);
    }

    private static final int FILE_UPLOAD_PACKET_SIZE = 250;

    private static int sendIndex = 0;

    private ArrayList<String> sendBuf;

    private InputStream srcFileIs;

    private final byte[] fileReadBuffer;

    private byte[] currentChunk;

    private void uploadFile(String src, String target) {

        File srcFile = new File(src);

        long nFullPackets = srcFile.length() / FILE_UPLOAD_PACKET_SIZE;
        long lastPacketSize = srcFile.length() % FILE_UPLOAD_PACKET_SIZE;
        final long totalPackets = nFullPackets + (lastPacketSize != 0 ? 1 : 0);
        long regularPacketSize = totalPackets == 1 ? lastPacketSize : FILE_UPLOAD_PACKET_SIZE;

        String cmd = ""
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
        sendBuf = cmdPrep(cmd);
        sendBuf.add("_up(" + totalPackets + "," + regularPacketSize + "," + lastPacketSize + ")");
        try {
            serialPort.removeEventListener();
        } catch (SerialPortException ex) {
            System.out.println("Uploader: Unable to deactivate serial event listener " + ex);
        }
        try {
            serialPort.addEventListener(new PortFilesUploader(), SerialPort.MASK_RXCHAR + SerialPort.MASK_CTS);
        } catch (SerialPortException ex) {
            System.out.println("Uploader: Add EventListener Error. Canceled. " + ex);
            return;
        }
        try {
            srcFileIs = new BufferedInputStream(new FileInputStream(srcFile));
        } catch (FileNotFoundException ex) {
            System.out.println("unable to open src");
            return;
        }
        TimerTask taskPerformer = new TimerTask() {
            public void run() {
                if (sendIndex < sendBuf.size()) {
                    try {
                        serialPort.writeString(sendBuf.get(sendIndex) + "\r");
                    } catch (SerialPortException ex) {
                        System.out.println("failed to send file upload program " + ex);
                    }
                } else if ((sendIndex - sendBuf.size()) < totalPackets) {
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
                    timer.cancel();
                }
                sendIndex++;
            }
        };
        sendIndex = 0;
        timer = new Timer();
        timer.schedule(taskPerformer, 10, 300);
    }

    private ArrayList<String> cmdPrep(String cmd) {
        ArrayList<String> s256 = new ArrayList<String>();
        int i = 0;
        s256.add("");
        for (String subs : cmd.split("\n")) {
            if ((s256.get(i).length() + subs.trim().length()) <= 250) {
                s256.set(i, s256.get(i) + " " + subs.trim());
            } else {
                s256.set(i, s256.get(i) + "\r");
                s256.add(subs);
                i++;
            }
        }
        return s256;
    }


    private void ViewFile(String filename) {
        String cmd = ""
                + "_view=function()\n"
                + "  local _line, _f\n"
                + "  _f=file.open('" + filename + "','r')\n"
                + "  repeat _line = _f:readline()\n"
                + "    if (_line ~= nil) then\n"
                + "      print(_line:sub(1,-2))\n"
                + "    end\n"
                + "  until _line == nil\n"
                + "  _f:close()\n"
                + "end\n"
                + "_view()\n"
                + "_view=nil\n"
                + "print('~~~'..'END'..'~~~')\n";
        waitForMarker = true;
        try {
            serialPort.writeString(cmd);
        } catch (SerialPortException ex) {
            System.out.println("exception in cat: " + ex);
        }

    }

    public boolean openPort() {
        int nSpeed = baud;
        if (portOpen) {
            try {
                serialPort.closePort();
            } catch (SerialPortException ex) {
                System.out.println("exception when closing serial port: " + ex);
            }
        } else {
            System.out.println("Try to open port " + serialPortDevice + ", baud " + Integer.toString(nSpeed) + ", 8N1");
        }
        serialPort = new SerialPort(serialPortDevice);
        portOpen = false;
        try {
            serialPort.openPort();
            SetSerialPortParams();
            serialPort.addEventListener(portReader, SerialPort.MASK_RXCHAR + SerialPort.MASK_CTS);
        } catch (SerialPortException ex) {
            System.out.println("failed to open port " + ex);
        }
        if (portOpen) {
            System.out.println("Open port " + serialPortDevice + " - Success.");
        }
        return portOpen;
    }

    public boolean SetSerialPortParams() {
        boolean success = false;
        try {
            success = serialPort.setParams(baud,
                    SerialPort.DATABITS_8,
                    SerialPort.STOPBITS_1,
                    SerialPort.PARITY_NONE,
                    false,
                    false);
        } catch (SerialPortException ex) {
            System.out.println("unable to configure serial port " + ex);
        }
        if (!success) {
            System.out.println("ERROR setting port " + serialPortDevice + " parameters.");
        }
        return success;
    }

    private void dump(String p, String s) {
        return;
        System.out.print(p + " ");
        for (int i = 0; i < s.length(); i++) {
            System.out.print(String.format("%02X ", (int) s.charAt(i)));
        }
        System.out.println();
    }

}
