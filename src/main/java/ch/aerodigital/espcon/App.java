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

    private static String serialPortDevice = null;
    private static Integer baud = 230400;
    public static SerialPort serialPort;

    private Terminal terminal;
    private History history;
    private LineReader reader;

    private final InteractiveSerialPortSink portReader;
    private final CommonSerialPortEventSink commonSerialEventSink;

    private static final String MARKER = "~~~END~~~";

    public enum State {
        DISCONNECTED,
        SYNC,
        REPL,
        WAIT_FOR_MARKER
    }

    State state;

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
            if (line.hasOption("port")) {
                serialPortDevice = line.getOptionValue("port");
            }
            if (line.hasOption("baud")) {
                String baudAsString = line.getOptionValue("baud");
                try {
                    baud = Integer.parseInt(baudAsString);
                } catch (NumberFormatException ex) {
                    System.out.println("Unable to interpret baud argument: " + baudAsString);
                }
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
        commonSerialEventSink = new CommonSerialPortEventSink();
        portReader = new InteractiveSerialPortSink(commonSerialEventSink);
        state = State.DISCONNECTED;
    }

    public void run() throws IOException {
        terminal = TerminalBuilder.builder().system(true).build();
        history = new DefaultHistory();
        reader = LineReaderBuilder.builder().terminal(terminal).history(history).build();
        reader.setVariable(LineReader.HISTORY_FILE, ".cmd-history");

        boolean terminate = false;
        while (!terminate) {
            try {
                openPort();
                state = State.REPL;
                terminate = repl();
            } catch (SerialPortException ex) {
                System.out.println(ex);
                reader.readLine("press ENTER to try again ... ");
            } catch (EndOfFileException ex) {
                try {
                    serialPort.closePort();
                } catch (SerialPortException ex2) {
                    System.out.println("troulbe closing serial port " + ex2);
                }
                break;
            }
        }
    }

    private boolean repl() {
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
                    serialPort.writeString(line + "\r\n");
                }
                prompt = waitForPrompt();
            } catch (SerialPortException ex) {
                System.out.println("unable to send serial: " + ex);
            } catch (UserInterruptException e) {
                // can do someting good here
            }
        }
    }

    private class CommonSerialPortEventSink implements SerialPortEventListener {
        public void serialEvent(SerialPortEvent event) {
            if (event.isBREAK()) {
                System.out.println("BREAK");
            } else if (event.isCTS()) {
                System.out.println("CTS:" + event.getEventValue());
            } else if (event.isDSR()) {
                System.out.println("DSR:" + event.getEventValue());
            } else if (event.isERR()) {
                System.out.println(String.format("ERR: 0x04X", event.getEventValue()));
            } else if (event.isRING()) {
                System.out.println("RING:" + event.getEventValue());
            } else if (event.isRLSD()) {
                System.out.println("RLSD:" + event.getEventValue());
            } else if (event.isRXCHAR()) {
                System.out.println("RXCHAR:" + event.getEventValue());
            } else if (event.isRXFLAG()) {
                System.out.println("RXFLAG:" + event.getEventValue());
            } else if (event.isTXEMPTY()) {
                System.out.println("TXEMPTY:" + event.getEventValue());
            }
        }
    }

    private class InteractiveSerialPortSink implements SerialPortEventListener {

        private SerialPortEventListener next;

        public InteractiveSerialPortSink(SerialPortEventListener next) {
            this.next = next;
        }

        private final StringBuilder lineBuffer = new StringBuilder(1024);

        public void serialEvent(SerialPortEvent event) {
            if (!event.isRXCHAR() || event.getEventValue() <= 0) {
                next.serialEvent(event);
                return;
            }
            try {
                String data = serialPort.readString(event.getEventValue());
                lineBuffer.append(data);
                dump("dd ", data);
                dump("lb", lineBuffer.toString());
                String[] lines = lineBuffer.toString().split("\r\n", -1);
                String line;
                // process all but last line
                for (int idx = 0; idx < lines.length - 1; idx++) {
                    line = lines[idx];
                    dump("l" + idx, line);
                    // ignore all prompts followed by a new line
                    if (!line.equals("> ") && !line.equals(">> ")) {
                        if (line.equals(MARKER)) {
                            state = State.REPL;
                        } else {
                            System.out.println(line);
                        }
                    }
                }
                // deal with the last one
                line = lines[lines.length - 1];
                dump("ll", line);
                // input ends with new line
                if (line.length() == 0) {
                    lineBuffer.setLength(0);
                    return;
                }
                // from here, only not empty last line, not followed with newline
                if (line.startsWith("> ") || line.startsWith(">> ")) {
                    if (state != State.WAIT_FOR_MARKER) {
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
        }
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
            throw new EndOfFileException("terminated by user");
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
                state = State.WAIT_FOR_MARKER;
                serialPort.writeString(cmd);
            } catch (SerialPortException ex) {
                System.out.println("exception in ls: " + ex);
            }
        } else if (command.startsWith("--cat")) {
            String args[] = command.split("\\s+");
            if (args.length != 2) {
                throw new InvalidCommandException("expect exactly one parameter to --cat");
            }
            viewFile(args[1]);
        } else if (command.startsWith("--upload")) {
            FileUploadCommandExecutor ce = new FileUploadCommandExecutor(command);
            ce.setRestoreEventListener(portReader);
            ce.setPromptQueue(promptQueue);
            ce.setNextSerialPortEventListener(commonSerialEventSink);
            ce.start();
        } else if (command.startsWith("--save")) {
            TextFileUploadCommandExecutor ce = new TextFileUploadCommandExecutor(command);
            ce.setRestoreEventListener(portReader);
            ce.setPromptQueue(promptQueue);
            ce.setNextSerialPortEventListener(commonSerialEventSink);
            ce.setAutoRun(false);
            ce.start();
        } else if (command.equals("--globals")) {
            try {
                String lua = "for k,v in pairs(_G) do print(k,v) end\n";
                serialPort.writeString(lua);
            } catch (SerialPortException ex) {
                System.out.println("Exception when writing to serial port: " + ex);
            }

        } else {
            throw new InvalidCommandException("not a valid command");
        }
    }

    private void viewFile(String filename) {
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
        state = State.WAIT_FOR_MARKER;
        try {
            serialPort.writeString(cmd);
        } catch (SerialPortException ex) {
            System.out.println("exception in cat: " + ex);
        }

    }

    public void openPort() throws SerialPortException {
        if (null != serialPort && serialPort.isOpened()) {
            try {
                serialPort.closePort();
            } catch (SerialPortException ex) {
                System.out.println("exception when closing serial port: " + ex);
            }
        }
        System.out.println("About to open port " + serialPortDevice + ", baud " + baud + ", 8N1");
        serialPort = new SerialPort(serialPortDevice);
        serialPort.openPort();
        serialPort.setParams(baud, SerialPort.DATABITS_8,
                SerialPort.STOPBITS_1, SerialPort.PARITY_NONE, false, false);
        serialPort.setEventsMask(SerialPort.MASK_BREAK | SerialPort.MASK_CTS | SerialPort.MASK_DSR |
                SerialPort.MASK_ERR | SerialPort.MASK_RING | SerialPort.MASK_RLSD | SerialPort.MASK_RXCHAR |
                SerialPort.MASK_RXFLAG | SerialPort.MASK_TXEMPTY);
        serialPort.addEventListener(portReader);
    }

    private void dump(String p, String s) {
        if (false) {
            System.out.print(p + " ");
            for (int i = 0; i < s.length(); i++) {
                System.out.print(String.format("%02X ", (int) s.charAt(i)));
            }
            System.out.println();
        }
    }
}
