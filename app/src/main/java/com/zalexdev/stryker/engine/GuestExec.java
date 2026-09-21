package com.zalexdev.stryker.engine;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Client for the in-guest command server (stryker-agentd).
 *
 * <p>Each VM runs its own agent on its own host port, so this is an instance bound to one VM's
 * exec port rather than a process-wide singleton. {@link RootlessEngine} holds one instance per
 * VM. {@link #logToStore} stays static because it is just diagnostic logging with no VM affinity.
 */
public final class GuestExec {

    private static final String TAG = "GuestExec";
    private static final String EXIT_SENTINEL = "__STRYKER_EXIT__";
    private static final String JOB_EOF = "__STRYKER_JOB_EOF__";
    private static final String JOB_DIR = "/tmp";
    private static final java.util.concurrent.atomic.AtomicLong JOB_SEQ =
            new java.util.concurrent.atomic.AtomicLong();
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 90_000;

    private final int hostPort;
    private final String loopback;

    public GuestExec(int hostPort) {
        this(hostPort, RootlessPaths.HOST_LOOPBACK);
    }

    public GuestExec(int hostPort, String loopback) {
        this.hostPort = hostPort;
        this.loopback = loopback;
    }

    public int hostPort() {
        return hostPort;
    }

    // ---- static shims (legacy callers that still assume the vm0 exec port) ----

    private static final GuestExec DEFAULT = new GuestExec(RootlessPaths.HOST_EXEC_PORT);

    public static ArrayList<String> run(String command) {
        return DEFAULT.exec(command);
    }

    public static Session open(String command) throws IOException {
        return DEFAULT.session(command);
    }

    public static Session openJob(String command) throws IOException {
        return DEFAULT.sessionJob(command);
    }

    public static boolean ping(int timeoutMs) {
        return DEFAULT.reachable(timeoutMs);
    }

    private static String wrap(String command) {
        return "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/snap/bin${PATH:+:$PATH}; "
                + "export HOME=/root LANG=C.UTF-8; "
                + command
                + "\nprintf '\\n" + EXIT_SENTINEL + "%s\\n' \"$?\"\n";
    }

    private static String wrapJob(String command, String jobId) {
        String script = JOB_DIR + "/stryker-" + jobId + ".sh";
        String pidFile = JOB_DIR + "/stryker-" + jobId + ".pid";
        return "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/snap/bin${PATH:+:$PATH}; "
                + "export HOME=/root LANG=C.UTF-8; "
                + "cat > " + script + " <<'" + JOB_EOF + "'\n"
                + "echo $$ > " + pidFile + "\n"
                + command + "\n"
                + JOB_EOF + "\n"
                + "if command -v setsid >/dev/null 2>&1; then setsid sh " + script + " & "
                + "else sh " + script + " & fi\n"
                + "__stryker_job=$!\n"
                + "wait $__stryker_job\n"
                + "printf '\\n" + EXIT_SENTINEL + "%s\\n' \"$?\"\n"
                + "rm -f " + script + " " + pidFile + "\n";
    }

    private void killJob(String jobId) {
        final String pidFile = JOB_DIR + "/stryker-" + jobId + ".pid";
        final String cmd =
                "if [ -f " + pidFile + " ]; then __g=$(cat " + pidFile + " 2>/dev/null); "
                        + "if [ -n \"$__g\" ]; then kill -TERM -$__g 2>/dev/null || kill -TERM $__g 2>/dev/null; "
                        + "sleep 1; kill -KILL -$__g 2>/dev/null || kill -KILL $__g 2>/dev/null; fi; fi; "
                        + "rm -f " + JOB_DIR + "/stryker-" + jobId + ".sh " + pidFile;
        new Thread(() -> exec(cmd), "guest-killjob").start();
    }

    public ArrayList<String> exec(String command) {
        ArrayList<String> out = new ArrayList<>();
        Session s = null;
        try {
            s = session(command);
            s.socket.setSoTimeout(READ_TIMEOUT_MS);
            String line;
            while ((line = s.reader.readLine()) != null) {
                if (line.startsWith(EXIT_SENTINEL)) {
                    try { s.exitCode = Integer.parseInt(line.substring(EXIT_SENTINEL.length()).trim()); }
                    catch (NumberFormatException ignored) {}
                    break;
                }
                out.add(line);
            }
        } catch (java.net.SocketTimeoutException te) {
            Log.w(TAG, "run timed out: " + shortCmd(command));
            logToStore("guest command timed out after " + (READ_TIMEOUT_MS / 1000)
                    + "s with no output (hung?) · " + shortCmd(command));
        } catch (IOException e) {
            Log.w(TAG, "run failed: " + e.getMessage());
            logToStore("guest exec failed — VM not reachable on :" + hostPort
                    + " (" + e.getMessage() + ") · " + shortCmd(command));
        } finally {
            if (s != null) s.close();
        }
        return out;
    }

    static void logToStore(String msg) {
        try {
            com.zalexdev.stryker.logger.LogStore st = com.zalexdev.stryker.logger.LogStore.peek();
            if (st != null) st.add(com.zalexdev.stryker.logger.LogEntry.ERR, "guest", msg);
        } catch (Throwable ignored) {}
    }

    private static String shortCmd(String c) {
        if (c == null) return "";
        c = c.replace('\n', ' ').trim();
        return c.length() > 90 ? c.substring(0, 90) + "…" : c;
    }

    public Session session(String command) throws IOException {
        return connect(command, null);
    }

    public Session sessionJob(String command) throws IOException {
        return connect(command, Long.toHexString(System.nanoTime()) + "-" + JOB_SEQ.incrementAndGet());
    }

    private Session connect(String command, String jobId) throws IOException {
        Socket sock = new Socket();
        sock.connect(new InetSocketAddress(loopback, hostPort), CONNECT_TIMEOUT_MS);
        sock.setKeepAlive(true);
        OutputStream os = sock.getOutputStream();
        String payload = jobId == null ? wrap(command) : wrapJob(command, jobId);
        os.write(payload.getBytes(StandardCharsets.UTF_8));
        os.flush();
        return new Session(sock, jobId);
    }

    private static final String PING_MARK = "__STRYKER_PONG__";

    /**
     * A bare TCP connect proves nothing here: QEMU's SLIRP hostfwd listener accepts on
     * 127.0.0.1:<port> from the moment the VM process starts, long before anything inside the guest
     * listens on that port. Readiness therefore has to be a round trip through the guest shell.
     */
    public boolean reachable(int timeoutMs) {
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(loopback, hostPort), timeoutMs);
            sock.setSoTimeout(Math.max(timeoutMs, 400));
            OutputStream os = sock.getOutputStream();
            os.write(("echo " + PING_MARK + "\nexit\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains(PING_MARK)) return true;
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    public final class Session {
        public final Socket socket;
        public final InputStream input;
        public final BufferedReader reader;
        public volatile int exitCode = -1;

        private final String jobId;
        private volatile boolean closed;

        Session(Socket socket, String jobId) throws IOException {
            this.socket = socket;
            this.jobId = jobId;
            this.input = socket.getInputStream();
            this.reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        }

        public static final String SENTINEL = EXIT_SENTINEL;

        public void close() {
            boolean first = !closed;
            closed = true;
            try { socket.close(); } catch (IOException ignored) {}
            if (first && jobId != null) GuestExec.this.killJob(jobId);
        }
    }
}
