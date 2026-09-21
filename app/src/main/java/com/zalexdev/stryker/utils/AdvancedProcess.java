package com.zalexdev.stryker.utils;

import android.app.Activity;
import android.content.Context;

import com.zalexdev.stryker.engine.GuestExec;
import com.zalexdev.stryker.logger.LogTool;
import com.zalexdev.stryker.logger.Logger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;

public abstract class AdvancedProcess {

    public static final String MACHINE_PREFIX = "STRYKER:";

    public Activity activity;
    public Context context;
    public Process process;
    public Core core;
    public InputStream output;
    public InputStream error;
    public OutputStream input;
    public String cmd;
    public String tool;
    public boolean chroot;
    public boolean success = false;
    public ArrayList<String> outputList = new ArrayList<>();
    public Logger logger;
    public boolean running = true;
    public boolean noLog = false;

    private final boolean rootless;
    private volatile GuestExec.Session guestSession;
    private volatile boolean killed;

    public AdvancedProcess(Activity activity, Context context, String command, boolean chroot) {
        this.activity = activity;
        this.context = context;
        core = new Core(context);
        this.cmd = command;
        this.tool = LogTool.classify(command);
        this.chroot = chroot;
        this.rootless = chroot && core.isRootless();
        this.logger = new Logger();
        execute();
    }

    public AdvancedProcess(Activity activity, Context context, String command, boolean chroot, boolean inMainThread) {
        this.activity = activity;
        this.context = context;
        core = new Core(context);
        this.cmd = command;
        this.tool = LogTool.classify(command);
        this.chroot = chroot;
        this.rootless = chroot && core.isRootless();
        this.logger = new Logger();
        if (inMainThread)
            executeInMainThread();
        else
            execute();
    }

    public AdvancedProcess setNoLog(boolean noLog) {
        this.noLog = noLog;
        return this;
    }

    private void start() {
        if (killed) {
            running = false;
            return;
        }
        if (rootless) {
            startRootless();
            return;
        }
        process = core.generateSuProcess();
        if (killed) {
            try { process.destroy(); } catch (Exception ignored) {}
            running = false;
            return;
        }
        output = process.getInputStream();
        error = process.getErrorStream();
        input = process.getOutputStream();
        activity.runOnUiThread(this::onPrepare);
        sendCommand(cmd);
        logger.writeLine("Command: " + cmd, 1, tool);
        BufferedReader reader = new BufferedReader(new InputStreamReader(output));
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                String finalLine = line;
                if (!finalLine.startsWith(MACHINE_PREFIX)) {
                    activity.runOnUiThread(() -> onNewLine(finalLine));
                }
                if (!noLog) {
                    logger.writeLine(line, 2, tool);
                }else{
                }
                if (line.contains("JOBFINISHED")) {
                    process.destroy();
                }
                outputList.add(line);
            }
        } catch (Exception ignored) {

        }
        BufferedReader errorReader = new BufferedReader(new InputStreamReader(error));
        try {
            while ((line = errorReader.readLine()) != null) {
                line = line.trim();
                if (!noLog) {
                    logger.writeLine(line, 3, tool);
                }else{
                }
                outputList.add("[E] " + line);
            }
        } catch (Exception ignored) {

        }
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        process.destroy();

        activity.runOnUiThread(() -> onFinished(outputList));
        running = false;
    }

    private void startRootless() {
        activity.runOnUiThread(this::onPrepare);
        logger.writeLine("Rootless command: " + cmd, 1, tool);
        int exitCode = -1;
        try {
            if (!killed) {
                guestSession = core.rootless().openStream(cmd);
                String line;
                while (!killed && (line = guestSession.reader.readLine()) != null) {
                    if (line.startsWith(GuestExec.Session.SENTINEL)) {
                        try { exitCode = Integer.parseInt(line.substring(GuestExec.Session.SENTINEL.length()).trim()); }
                        catch (NumberFormatException ignored) {}
                        break;
                    }
                    line = line.trim();
                    String finalLine = line;
                    if (!finalLine.startsWith(MACHINE_PREFIX)) {
                        activity.runOnUiThread(() -> onNewLine(finalLine));
                    }
                    if (!noLog) {
                        logger.writeLine(line, 2, tool);
                    }
                    if (line.contains("JOBFINISHED")) {
                        break;
                    }
                    outputList.add(line);
                }
            }
        } catch (Exception e) {
            logger.writeLine("Rootless exec failed (VM not reachable?): " + e.getMessage(), 3, tool);
        } finally {
            if (guestSession != null) guestSession.close();
        }
        // Always log the exit status, even when noLog suppresses per-line output, so the
        // airodump/mdk4 failure (exit code + trailing stderr) is diagnosable from the log.
        boolean failed = exitCode != 0;
        logger.writeLine("Rootless finished: exit=" + exitCode + " lines=" + outputList.size() + " cmd=" + cmd, failed ? 3 : 1, tool);
        if (failed) {
            int from = Math.max(0, outputList.size() - 25);
            for (int i = from; i < outputList.size(); i++) {
                logger.writeLine("[out] " + outputList.get(i), 3, tool);
            }
        }
        activity.runOnUiThread(() -> onFinished(outputList));
        running = false;
    }

    public void execute() {
        new Thread(this::start).start();
    }

    public void executeInMainThread() {
        start();
    }

    public abstract void onFinished(ArrayList<String> outputList);

    public abstract void onNewLine(String line);

    public AdvancedProcess sendCommand(String command) {
        if (rootless) {
            return this;
        }
        try {
            if (chroot) {
                input.write((Core.EXECUTE + "'" + Core.SHELL + "'" + "\n").getBytes());
                input.write((command + "\n").getBytes());
                input.write(("exit\n").getBytes());
                input.write(("exit\n").getBytes());
            } else {
                input.write((command +"\n").getBytes());
                input.write(("exit\n").getBytes());
            }
            input.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return this;
    }

    public void kill() {
        killed = true;
        try {
            if (rootless) {
                if (guestSession != null) guestSession.close();
            } else if (process != null) {
                process.destroy();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        running = false;
    }

    protected void onPrepare() {

    }

    public abstract void onEvent(String line);

    public boolean isSuccess() {
        return success;
    }

    public boolean isRunning() {
        return running;
    }
}
