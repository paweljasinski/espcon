package ch.aerodigital.espcon;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import jssc.SerialPort;
import jssc.SerialPortEvent;
import jssc.SerialPortException;
import jssc.SerialPortList;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.jline.builtins.ScreenTerminal;
import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import static org.jline.keymap.KeyMap.alt;
import static org.jline.keymap.KeyMap.key;
import static org.jline.keymap.KeyMap.translate;
import org.jline.reader.Binding;
import org.jline.reader.EndOfFileException;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Attributes;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.Display;
import org.jline.utils.InfoCmp;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.Log;

/**
 *
 *
 */
public class App {

    private static String serialPortDevice = "/dev/ttyS0";
    public static int baud = 230400;
    public static SerialPortX serialPort;
    public static int echo = 0;
    public static boolean autorun = true;

    private Terminal systemTerminal;
    private History history;
    private LineReader reader;

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
        options.addOption("p", "port", true, "serial port device, default /dev/ttyS0");
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
            String [] remaining = line.getArgs();
            if (remaining.length != 0) {
                System.err.println("Unexpected argument(s): " + String.join(",", remaining));
                System.exit(1);
            }
        } catch (ParseException exp) {
            System.out.println("Unexpected exception:" + exp.getMessage());
        }

        try {
            App app = new App();
            app.prepareVirtualTerminal();
        } catch (IOException ex) {
            System.out.println(ex);
        }
        System.exit(0);
    }

    enum BindingEnum {
        Discard, SelfInsert, Mouse
    }

    private KeyMap<Object> createEmptyKeyMap() {
        KeyMap<Object> keyMap = new KeyMap<>();
        keyMap.setUnicode(BindingEnum.SelfInsert);
        keyMap.setNomatch(BindingEnum.SelfInsert);
        keyMap.bind(BindingEnum.Mouse, key(systemTerminal, InfoCmp.Capability.key_mouse));
        return keyMap;
    }

    public App() {
        state = State.DISCONNECTED;
        try {
            systemTerminal = TerminalBuilder.builder().system(true).build();
        } catch (IOException ex) {
            System.out.println("failed to create terminal object " + ex.getMessage());
        }
        display = new Display(systemTerminal, true);
        size.copy(systemTerminal.getSize());
        appKeyMap = createEmptyKeyMap();
        appKeyMap.bind("history-back-page", translate("^[[1;5A"), alt(translate("^[[A"))); // ctrl-up
        appKeyMap.bind("history-forward-page", translate("^[[1;5B"), alt(translate("^[[B"))); // ctrl-down
        appKeyMap.bind("history-back", key(systemTerminal, Capability.key_sr)); // shift-up
        appKeyMap.bind("history-forward", key(systemTerminal, Capability.key_sf)); // shift-down
    }

    private LineDisciplineTerminal console;

    private ScheduledExecutorService executor;

    private ScreenTerminal screenTerminal;

    private String term; // terminal type of the screen terminal

    private final AtomicBoolean running = new AtomicBoolean(true);

    private final AtomicBoolean dirty = new AtomicBoolean(true);
    private final AtomicBoolean resized = new AtomicBoolean(true);

    private final KeyMap<Object> appKeyMap;

    private final Display display;
    private final Size size = new Size(); // this is system terminal size
    // private final screenSize; // this is one row less than system terminal

    private void resize(Terminal.Signal signal) {
        resized.set(true);
        setDirty();
    }

    private void setDirty() {
        synchronized (dirty) {
            dirty.set(true);
            dirty.notifyAll();
        }
    }

    private void interrupt(Terminal.Signal signal) {
        console.raise(signal);
    }

    private void suspend(Terminal.Signal signal) {
        console.raise(signal);
    }

    private OutputStream masterOutput;
    private OutputStream masterInputOutput;


    private class MasterOutputStream extends OutputStream {

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final CharsetDecoder decoder = Charset.defaultCharset().newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);

        @Override
        public synchronized void write(int b) {
            buffer.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            buffer.write(b, off, len);
        }

        @Override
        public synchronized void flush() throws IOException {
            int size = buffer.size();
            if (size > 0) {
                CharBuffer out;
                for (;;) {
                    out = CharBuffer.allocate(size);
                    ByteBuffer in = ByteBuffer.wrap(buffer.toByteArray());
                    CoderResult result = decoder.decode(in, out, false);
                    if (result.isOverflow()) {
                        size *= 2;
                    } else {
                        buffer.reset();
                        buffer.write(in.array(), in.arrayOffset(), in.remaining());
                        break;
                    }
                }
                if (out.position() > 0) {
                    out.flip();
                    screenTerminal.write(out);
                    masterInputOutput.write(screenTerminal.read().getBytes());
                }
            }
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }

    private void prepareVirtualTerminal() throws IOException {
        history = new DefaultHistory();
        Terminal.SignalHandler prevWinchHandler = systemTerminal.handle(Terminal.Signal.WINCH, this::resize);
        Terminal.SignalHandler prevIntHandler = systemTerminal.handle(Terminal.Signal.INT, this::interrupt);
        Terminal.SignalHandler prevSuspHandler = systemTerminal.handle(Terminal.Signal.TSTP, this::suspend);
        Attributes attributes = systemTerminal.enterRawMode();
        systemTerminal.puts(InfoCmp.Capability.enter_ca_mode);
        systemTerminal.puts(InfoCmp.Capability.keypad_xmit);
        // keep mouse as-is for copy/paste of xterm
        // systemTerminal.trackMouse(Terminal.MouseTracking.Any);
        systemTerminal.flush();
        executor = Executors.newSingleThreadScheduledExecutor();
        try {
            screenTerminal = new ScreenTerminal(size.getColumns(), size.getRows() - 1) {
                @Override
                protected void setDirty() {
                    super.setDirty();
                    App.this.setDirty();
                }
            };
            masterOutput = new MasterOutputStream();
            masterInputOutput = new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    console.processInputByte(b);
                }
            };
            Integer colors = systemTerminal.getNumericCapability(InfoCmp.Capability.max_colors);
            term = (colors != null && colors >= 256) ? "screen-256color" : "screen";
            this.console = new LineDisciplineTerminal("espcon", term, masterOutput, null) {
                @Override
                public void close() throws IOException {
                    super.close();
                    // closer.accept(Tmux.VirtualConsole.this);
                }
            };
            this.console.setSize(new Size(size.getColumns(), size.getRows() - 1));
            console.setAttributes(systemTerminal.getAttributes());
            new Thread(this::serialrun, "Interactive").start();
            new Thread(this::inputLoop, "Mux input loop").start();
            // Redraw loop
            redrawLoop();
        } catch (RuntimeException e) {
            throw e;
        } finally {
            executor.shutdown();
            // systemTerminal.trackMouse(Terminal.MouseTracking.Off);
            systemTerminal.puts(InfoCmp.Capability.keypad_local);
            systemTerminal.puts(InfoCmp.Capability.exit_ca_mode);
            systemTerminal.flush();
            systemTerminal.setAttributes(attributes);
            systemTerminal.handle(Terminal.Signal.WINCH, prevWinchHandler);
            systemTerminal.handle(Terminal.Signal.INT, prevIntHandler);
            systemTerminal.handle(Terminal.Signal.TSTP, prevSuspHandler);
        }
    }

    private void inputLoop() {
        try {
            BindingReader keyboardreader = new BindingReader(systemTerminal.reader());
            boolean first = true;
            while (running.get()) {
                Object b;
                if (first) {
                    b = keyboardreader.readBinding(appKeyMap);
                } else if (keyboardreader.peekCharacter(100) >= 0) {
                    b = keyboardreader.readBinding(appKeyMap, null, false);
                } else {
                    b = null;
                }
                //System.out.println("f:" + first + " b:" + b);
                if (b == BindingEnum.SelfInsert) {
                    screenTerminal.historyScrollTerminate();
                    masterInputOutput.write(keyboardreader.getLastBinding().getBytes());
                    first = false;
                } else {
                    if (first) {
                        first = false;
                    } else {
                        masterInputOutput.flush();
                        first = true;
                    }
                    if (b == BindingEnum.Mouse) {
                        MouseEvent event = systemTerminal.readMouseEvent();
                        // System.err.println(event.toString());
                    } else if (b instanceof String || b instanceof String[]) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        ByteArrayOutputStream err = new ByteArrayOutputStream();
                        try (PrintStream pout = new PrintStream(out);
                                PrintStream perr = new PrintStream(err)) {
                            if (b instanceof String) {
                                execute(pout, perr, (String) b);
                            } else {
                                execute(pout, perr, Arrays.asList((String[]) b));
                            }
                        } catch (Exception ex) {
                            console.writer().println("ex:" + ex.getMessage());
                        }
                    }
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                Log.info("Error in input loop", e);
            }
        } finally {
            running.set(false);
            setDirty();
        }
    }

    private boolean prevIsScrolling = false;

    private void redrawLoop() {
        while (running.get()) {
            try {
                synchronized (dirty) {
                    while (running.get() && !dirty.compareAndSet(true, false)) {
                        dirty.wait();
                    }
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            handleResize();
            long[] screen = new long[size.getRows() * size.getColumns()];
            // Fill
            Arrays.fill(screen, 0x00000020L);
            int[] cursor = new int[2];

            // redraw();
            screenTerminal.dump(screen, 0, 0, size.getRows()-1, size.getColumns(), cursor);
            List<AttributedString> lines = new ArrayList<>();
            for (int y = 0; y < size.getRows(); y++) {
                AttributedStringBuilder sb = new AttributedStringBuilder(size.getColumns());
                // TODO: bring back colors/bold etc.
                for (int x = 0; x < size.getColumns(); x++) {
                    long d = screen[y * size.getColumns() + x];
                    int c = (int) (d & 0xffffffffL);
                    sb.append((char) c);
                }
                lines.add(sb.toAttributedString());
            }
            if (screenTerminal.isScrolling && !prevIsScrolling) {
                systemTerminal.puts(Capability.cursor_invisible);
                prevIsScrolling = true;
            } else if (!screenTerminal.isScrolling && prevIsScrolling) {
                systemTerminal.puts(Capability.cursor_visible);
                prevIsScrolling = false;
            }
            display.resize(size.getRows(), size.getColumns());
            display.update(lines, size.cursorPos(cursor[1], cursor[0]));
        }
    }

    private void handleResize() {
        // Re-compute the layout
        if (resized.compareAndSet(true, false)) {
            size.copy(systemTerminal.getSize());
            screenTerminal.setSize(size.getColumns(), size.getRows() - 1);
            console.setSize(new Size(size.getColumns(), size.getRows() - 1));
            // may have to clear display here
        }
    }

    private void execute(PrintStream pout, PrintStream perr, String cmd) {
        switch (cmd) {
            case "history-forward":
                screenTerminal.historyForward();
                break;
            case "history-back":
                screenTerminal.historyBack();
                break;
            case "history-forward-page":
                screenTerminal.historyForwardPage();
                break;
            case "history-back-page":
                screenTerminal.historyBackPage();
                break;
            default:
                throw new UnsupportedOperationException("cmd not implemented: '" + cmd + "'");
        }
    }

    private void execute(PrintStream pout, PrintStream perr, List<String> b) {
        System.out.println("not sure what to expect: " + String.join("", b));
    }

    private void terminate() {
        try {
            serialPort.closePort();
            running.set(false);
            setDirty();
        } catch (SerialPortException ex2) {
            System.out.println("trouble closing serial port " + ex2);
        }
    }

    public void serialrun() {
        reader = LineReaderBuilder.builder().terminal(console).history(history).build();
        reader.setOpt(LineReader.Option.AUTO_FRESH_LINE);
        reader.setVariable(LineReader.HISTORY_FILE, ".cmd-history");
        KeyMap<Binding> keyMap = reader.getKeyMaps().get(LineReader.MAIN);
        keyMap.bind(new Reference(LineReader.UP_LINE_OR_HISTORY), KeyMap.key(console, Capability.key_up));
        keyMap.bind(new Reference(LineReader.DOWN_LINE_OR_HISTORY), KeyMap.key(console, Capability.key_down));
        while (true) {
            try {
                openPort();
                state = State.SYNC;
                repl();
            } catch (SerialPortXException ex) {
                // disconnected device
                try {
                    console.writer().println(ex.getMessage());
                    reader.readLine("press ENTER to try again ... ");
                } catch (UserInterruptException ue) {
                    // ^C when in open/reopen loop
                    running.set(false);
                    setDirty();
                    break;
                }
            } catch (EndOfFileException ex) {
                // either ^D or --quit
                terminate();
                break;
            } catch (Throwable ex) {
                console.writer().println(ex);
                console.writer().flush();
            }
        }
    }

    private String timedDrain(int timeout) {
        String prompt;
        String lastNotNull = null;
        long start = System.currentTimeMillis();
        long remainder = timeout;
        while (remainder > 0) {
            try {
                prompt = (String) promptQueue.poll(remainder, TimeUnit.MILLISECONDS);
                if (prompt != null) {
                    lastNotNull = prompt;
                }
            } catch (InterruptedException ex) {
                console.writer().println("Interrupted in poll: " + ex);
            }
            long elapsed = System.currentTimeMillis() - start;
            remainder = timeout - elapsed;
        }
        return lastNotNull;
    }

    private String waitForPrompt(int timeout, int drainPeriod) {
        String prompt = "";
        try {
            prompt = (String) promptQueue.poll(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            console.writer().println("interrupted " + ex);
        }
        String drainPrompt = timedDrain(drainPeriod);
        return drainPrompt != null ? drainPrompt : prompt;
    }

    private void repl() {
        String prompt;
        while (state == State.SYNC) {
            String line;
            prompt = "press ENTER to sync ... ";
            line = reader.readLine(prompt);
            serialPort.writeStringX(line + "\n");
            prompt = timedDrain(100);
            if (prompt == null) {
                continue;
            }
            if (prompt.equals(">> ")) {
                console.writer().println("aborting >> prompt");
                serialPort.writeStringX("x\n");
                prompt = timedDrain(200);
                if (prompt.equals("> ")) {
                    state = State.REPL;
                }
            } else {
                state = State.REPL;
            }
        }

        // turn echo off
        serialPort.writeStringX("uart.setup(0," + baud + ",8, 0, 1, " + echo + ")\n");
        prompt = timedDrain(25);

        while (true) {
            String line;
            try {
                line = reader.readLine(prompt);
                if (line.startsWith("--")) { // abuse lua comment as internal command prefix
                    try {
                        processCommand(line);
                    } catch (InvalidCommandException ex) {
                        console.writer().println(ex.getMessage());
                        continue; // reuse prompt
                    }
                } else {
                    serialPort.writeStringX(line + "\r\n");
                }
                prompt = waitForPrompt(2000, 25);
            } catch (UserInterruptException e) {
                // can do someting good here
            }
        }
    }

    private class InteractiveSerialPortSink implements SerialPortEventListenerX {

        private final StringBuilder lineBuffer = new StringBuilder(1024);

        @Override
        public boolean serialEvent(SerialPortEvent event) {
            if (!event.isRXCHAR() || event.getEventValue() <= 0) {
                return false;
            }
            String data = serialPort.readStringX(event.getEventValue());
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
                        console.writer().println(line);
                        console.flush();
                    }
                }
            }
            // deal with the last one
            line = lines[lines.length - 1];
            dump("ll", line);
            // input ends with new line
            if (line.length() == 0) {
                lineBuffer.setLength(0);
                return true;
            }
            // from here, only not empty last line, not followed with newline
            if (line.startsWith("> ") || line.startsWith(">> ")) {
                if (state != State.WAIT_FOR_MARKER) {
                    try {
                        promptQueue.put(line);
                    } catch (InterruptedException ex) {
                        console.writer().println("interrupted in put");
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
            return true;
        }
    }

    private final BlockingQueue promptQueue = new ArrayBlockingQueue(10);

    private void processCommand(String command) throws InvalidCommandException {

        if (command.startsWith("--echo")) {
            processEchoCommand(command);
        } else if (command.equals("--quit")) {
            throw new EndOfFileException("terminated by user");
        } else if (command.startsWith("--set") || command.startsWith("--reset")) {
            processSetCommand(command);
        } else if (command.startsWith("--hexdump")) {
            HexDumpCommandExecutor ce = new HexDumpCommandExecutor(command);
            ce.setWriter(console.writer());
            ce.start();
        } else if (command.equals("--ls")) {
            processLsCommand(command);
        } else if (command.startsWith("--cat")) {
            processCatCommand(command);
        } else if (command.startsWith("--upload")) {
            FileUploadCommandExecutor ce = new FileUploadCommandExecutor(command);
            ce.setWriter(console.writer());
            ce.start();
        } else if (command.startsWith("--save")) {
            TextFileUploadCommandExecutor ce = new TextFileUploadCommandExecutor(command);
            ce.setWriter(console.writer());
            ce.setAutoRun(autorun);
            ce.start();
        } else if (command.startsWith("--tsave")) {
            TurboTextFileUploadCommandExecutor ce = new TurboTextFileUploadCommandExecutor(command);
            ce.setWriter(console.writer());
            ce.setAutoRun(autorun);
            ce.start();
        } else if (command.equals("--globals")) {
            serialPort.writeStringX("for k,v in pairs(_G) do print(k,v) end\n");
        } else {
            throw new InvalidCommandException("not a valid command");
        }
    }

    private void processLsCommand(String command) throws InvalidCommandException {
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
        serialPort.writeStringX(cmd);
    }

    private void processEchoCommand(String command) throws InvalidCommandException {
        String args[] = command.split("\\s+");
        int val = 0;
        if (args.length != 2) {
            throw new InvalidCommandException("echo expects exactly one argument");
        }
        switch (args[1]) {
            case "off":
                val = 0;
                break;
            case "on":
                val = 1;
                break;
            default:
                throw new InvalidCommandException("argument of --echo can be either on or off");
        }
        serialPort.writeStringX("uart.setup(0," + baud + ",8, 0, 1, " + val + ")\n");
        echo = val;
    }

    private void processSetCommand(String command) throws InvalidCommandException {
        String args[] = command.split("\\s+");
        if (args.length != 2) {
            throw new InvalidCommandException("set/reset requires exactly one parameter");
        }
        boolean isSet = args[0].equals("--set");
        if (args[1].equals("autorun")) {
            autorun = isSet;
        } else {
            throw new InvalidCommandException("unrecognize set/reset argument: " + args[1]);
        }
        try {
            promptQueue.put("> ");
        } catch (InterruptedException ex) {
            console.writer().println("interrupter in put");
        }
    }

    private void processCatCommand(String command) throws InvalidCommandException {
        String args[];
        args = command.split("\\s+");
        if (args.length != 2) {
            throw new InvalidCommandException("expect exactly one parameter to --cat");
        }
        String cmd = ""
                + "_view=function()\n"
                + "  local _line, _f\n"
                + "  _f=file.open('" + args[1] + "','r')\n"
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
        serialPort.writeStringX(cmd);
    }

    public void openPort() {
        if (null != serialPort && serialPort.isOpened()) {
            try {
                serialPort.closePort();
            } catch (SerialPortException ex) {
                // don't care
            }
        }
        console.writer().println("About to open port " + serialPortDevice + ", baud " + baud + ", 8N1");
        serialPort = new SerialPortX(serialPortDevice);
        serialPort.openPortX();
        serialPort.setParamsX(baud, SerialPort.DATABITS_8,
                SerialPort.STOPBITS_1, SerialPort.PARITY_NONE, false, false);
        serialPort.installCommonEventListener(new CommonEventListener());
        serialPort.pushEventListener(new InteractiveSerialPortSink());
    }

    private class CommonEventListener implements SerialPortEventListenerX {

        @Override
        public boolean serialEvent(SerialPortEvent event) {

            if (event.isBREAK()) {
                System.out.println("BREAK");
                return true;
            }
            if (event.isCTS()) {
                System.out.println("CTS:" + event.getEventValue());
                return true;
            }
            if (event.isDSR()) {
                System.out.println("DSR:" + event.getEventValue());
                return true;
            }
            if (event.isERR()) {
                System.out.println(String.format("ERR: 0x04X", event.getEventValue()));
                return true;
            }
            if (event.isRING()) {
                System.out.println("RING:" + event.getEventValue());
                return true;
            }
            if (event.isRLSD()) {
                System.out.println("RLSD:" + event.getEventValue());
                return true;
            }
            if (event.isRXCHAR()) {
                System.out.println("RXCHAR:" + event.getEventValue());
                return true;
            }
            if (event.isRXFLAG()) {
                System.out.println("RXFLAG:" + event.getEventValue());
                return true;
            }
            if (event.isTXEMPTY()) {
                System.out.println("TXEMPTY:" + event.getEventValue());
                return true;
            }
            return false;
        }
    }

    private void dump(String p, String s) {
        if (false) {
            console.writer().print(p + " ");
            for (int i = 0; i < s.length(); i++) {
                console.writer().print(String.format("%02X ", (int) s.charAt(i)));
            }
            console.writer().println();
        }
    }
}
