package ch.aerodigital.espcon;

import java.io.IOException;
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

    public void run() throws IOException {
        openPort();

        terminal = TerminalBuilder.builder().system(true).build();

        History history;
        history = new DefaultHistory();
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).history(history).build();
        reader.setVariable(LineReader.HISTORY_FILE, ".cmd-history");
        String prompt = "press ENTER to sync";
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
                try {
                    serialPort.closePort();
                } catch (SerialPortException ex) {

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

    private final BlockingQueue promptQueue = new ArrayBlockingQueue(10);

    private String waitForPrompt() {
        try {
            return (String) promptQueue.poll(1500, TimeUnit.MILLISECONDS);
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
                        + "    print(l..\" : \"..v)\n"
                        + "  end\n"
                        + "end\n"
                        + "_dir()\n"
                        + "_dir=nil\n"
                        + "print(\"" + MARKER + "\")\n";
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
        } else {
            throw new InvalidCommandException("not a valid command");
        }
    }

    private void ViewFile(String filename) {
        String cmd = ""
                + "_view=function()\n"
                + "  local _line, _f\n"
                + "  _f=file.open(\"" + filename + "\",\"r\")\n"
                + "  repeat _line = _f:readline()\n"
                + "    if (_line ~= nil) then\n"
                + "      print(_line:sub(1,-2))\n"
                + "    end\n"
                + "  until _line == nil\n"
                + "  _f:close()\n"
                + "end\n"
                + "_view()\n"
                + "_view=nil\n"
                + "print(\"" + MARKER + "\")\n";
        waitForMarker = true;
        try {
            serialPort.writeString(cmd);
        } catch (SerialPortException ex) {
            System.out.println("exception in cat: " + ex);
        }

    }

    public boolean openPort() {
        int nSpeed = baud;
        boolean success = false;
        if (portOpen) {
            try {
                serialPort.closePort();
            } catch (Exception ex) {
                System.out.println("exception when closing serial port: " + ex);
            }
        } else {
            System.out.println("Try to open port " + serialPortDevice + ", baud " + Integer.toString(nSpeed) + ", 8N1");
        }
        serialPort = new SerialPort(serialPortDevice);
        portOpen = false;
        try {
            success = serialPort.openPort();
            if (!success) {
                System.out.println("ERROR opening serial port " + serialPortDevice);
                return success;
            }
            SetSerialPortParams();
            serialPort.addEventListener(new PortReader(), SerialPort.MASK_RXCHAR + SerialPort.MASK_CTS);
        } catch (SerialPortException ex) {
            System.out.println("failed to open port " + ex);
            success = false;
        }
        portOpen = success;
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
        } catch (Exception e) {
            System.out.println(e.toString());
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
