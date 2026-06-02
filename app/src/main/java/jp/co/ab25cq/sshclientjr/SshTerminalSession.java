package com.sshclientjr;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalSessionClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SshTerminalSession extends TerminalOutput {
    interface Client {
        void onConnected();
        void onScreenUpdated();
        void onTitleChanged(String title);
        void onConnectionError(String message);
        void onDisconnected(String message);
        void onCopyToClipboard(String text);
        String onRequestPaste();
        void onBell();
    }

    private static final int DEFAULT_COLUMNS = 80;
    private static final int DEFAULT_ROWS = 24;
    private static final int DEFAULT_CELL_WIDTH = 8;
    private static final int DEFAULT_CELL_HEIGHT = 16;
    private static final String CLEAN_INTERACTIVE_SHELL_COMMAND =
            "if command -v bash >/dev/null 2>&1; then exec bash --noprofile --norc -i; else exec sh -i; fi";
    private static final String ENABLE_TMUX_MOUSE_COMMAND =
            "if command -v tmux >/dev/null 2>&1; then "
                    + "tmux start-server 2>/dev/null; "
                    + "tmux set-option -g mouse on 2>/dev/null; "
                    + "tmux list-sessions -F '#{session_name}' 2>/dev/null | while IFS= read -r session_name; do "
                    + "tmux set-option -t \"$session_name\" mouse on 2>/dev/null; "
                    + "done; "
                    + "fi";

    private final Client client;
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean disconnectNotified = new AtomicBoolean(true);
    private final Object lock = new Object();
    private final byte[] utf8InputBuffer = new byte[5];
    private final TerminalSessionClient emulatorClient = new EmulatorCallbacks();

    private Session sshSession;
    private Channel channel;
    private OutputStream outputStream;
    private Thread readerThread;
    private volatile boolean disconnectRequested;

    private TerminalEmulator emulator;
    private int columns = DEFAULT_COLUMNS;
    private int rows = DEFAULT_ROWS;
    private int cellWidth = DEFAULT_CELL_WIDTH;
    private int cellHeight = DEFAULT_CELL_HEIGHT;
    private String title = "";

    public SshTerminalSession(Context context, Client client) {
        this.context = context.getApplicationContext();
        this.client = client;
        emulator = new TerminalEmulator(this, columns, rows, null, emulatorClient);
    }

    public void connect(String host, int port, String username, String password, String privateKey, String passphrase) {
        connectInternal(host, port, username, password, privateKey, passphrase, null);
    }

    public void connectWithoutStartupFiles(String host, int port, String username, String password, String privateKey, String passphrase) {
        connectWithCommand(host, port, username, password, privateKey, passphrase, CLEAN_INTERACTIVE_SHELL_COMMAND);
    }

    public void connectWithCommand(String host, int port, String username, String password, String privateKey, String passphrase, String command) {
        connectInternal(host, port, username, password, privateKey, passphrase, command);
    }

    private void connectInternal(String host, int port, String username, String password, String privateKey, String passphrase, String execCommand) {
        executor.execute(() -> {
            closeActiveConnection();
            disconnectRequested = false;
            disconnectNotified.set(true);

            Session localSession = null;
            Channel localChannel = null;
            OutputStream localOutput = null;
            try {
                localSession = SshSessionFactory.connect(host, port, username, password, privateKey, passphrase);
                enableTmuxMouse(localSession);
                if (execCommand != null) {
                    ChannelExec execChannel = (ChannelExec) localSession.openChannel("exec");
                    execChannel.setCommand(execCommand);
                    execChannel.setPty(true);
                    localChannel = execChannel;
                } else {
                    localChannel = (ChannelShell) localSession.openChannel("shell");
                }
                setPtyType(localChannel);
                InputStream inputStream = localChannel.getInputStream();
                localOutput = localChannel.getOutputStream();
                localChannel.connect(5_000);

                synchronized (lock) {
                    sshSession = localSession;
                    channel = localChannel;
                    outputStream = localOutput;
                }

                disconnectNotified.set(false);
                mainHandler.post(client::onConnected);
                startReaderLoop(inputStream);
            } catch (Exception e) {
                closeOutput(localOutput);
                disconnectSession(localChannel, localSession);
                mainHandler.post(() -> client.onConnectionError(buildConnectionErrorMessage(e)));
            }
        });
    }

    private void enableTmuxMouse(Session session) {
        ChannelExec tmuxChannel = null;
        try {
            tmuxChannel = (ChannelExec) session.openChannel("exec");
            tmuxChannel.setCommand(ENABLE_TMUX_MOUSE_COMMAND);
            tmuxChannel.setInputStream(null);
            tmuxChannel.setOutputStream(null);
            tmuxChannel.setErrStream(null);
            tmuxChannel.connect(2_000);
            long deadline = System.currentTimeMillis() + 1_200L;
            while (!tmuxChannel.isClosed() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
        } catch (Exception ignored) {
            // tmux is optional. A failure here must not prevent opening the SSH terminal.
        } finally {
            if (tmuxChannel != null) {
                tmuxChannel.disconnect();
            }
        }
    }

    public void updateSize(int newColumns, int newRows, int newCellWidth, int newCellHeight) {
        columns = Math.max(4, newColumns);
        rows = Math.max(4, newRows);
        cellWidth = Math.max(1, newCellWidth);
        cellHeight = Math.max(1, newCellHeight);
        if (emulator == null) {
            emulator = new TerminalEmulator(this, columns, rows, null, emulatorClient);
        } else {
            emulator.resize(columns, rows);
        }
        executor.execute(() -> {
            synchronized (lock) {
                if (channel != null && channel.isConnected()) {
                    setPtySize(channel);
                }
            }
        });
    }

    public TerminalEmulator getEmulator() {
        return emulator;
    }

    public String getTitle() {
        return title;
    }

    public void sendCtrlC() {
        write(new byte[]{3}, 0, 1);
    }

    public void sendCtrlD() {
        write(new byte[]{4}, 0, 1);
    }

    public void sendEscape() {
        write(new byte[]{27}, 0, 1);
    }

    public void sendTab() {
        write(new byte[]{9}, 0, 1);
    }

    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) utf8InputBuffer[bufferPosition++] = 27;
        if (codePoint <= 0b1111111) {
            utf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= 0b11111111111) {
            utf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            utf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= 0b1111111111111111) {
            utf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            utf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            utf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else {
            utf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            utf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            utf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            utf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(utf8InputBuffer, 0, bufferPosition);
    }

    public void disconnect() {
        executor.execute(() -> {
            disconnectRequested = true;
            closeActiveConnection();
            notifyDisconnected(context.getString(R.string.toast_disconnected));
        });
    }

    public void release() {
        disconnectRequested = true;
        closeActiveConnection();
        executor.shutdownNow();
    }

    @Override
    public void write(byte[] data, int offset, int count) {
        byte[] payload = Arrays.copyOfRange(data, offset, offset + count);
        executor.execute(() -> {
            OutputStream currentOutput;
            synchronized (lock) {
                currentOutput = outputStream;
            }
            if (currentOutput == null) {
                mainHandler.post(() -> client.onConnectionError(context.getString(R.string.toast_send_not_connected)));
                return;
            }
            try {
                currentOutput.write(payload);
                currentOutput.flush();
            } catch (IOException e) {
                mainHandler.post(() -> client.onConnectionError(context.getString(R.string.toast_send_failed, e.getMessage())));
            }
        });
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        title = newTitle == null ? "" : newTitle;
        mainHandler.post(() -> client.onTitleChanged(title));
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mainHandler.post(() -> client.onCopyToClipboard(text));
    }

    @Override
    public void onPasteTextFromClipboard() {
        mainHandler.post(() -> {
            String text = client.onRequestPaste();
            if (text != null && emulator != null) {
                emulator.paste(text);
            }
        });
    }

    @Override
    public void onBell() {
        mainHandler.post(client::onBell);
    }

    @Override
    public void onColorsChanged() {
        mainHandler.post(client::onScreenUpdated);
    }

    private void startReaderLoop(InputStream inputStream) {
        readerThread = new Thread(() -> {
            byte[] buffer = new byte[4096];
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    int count = inputStream.read(buffer);
                    if (count < 0) {
                        break;
                    }
                    if (count > 0) {
                        byte[] payload = Arrays.copyOf(buffer, count);
                        mainHandler.post(() -> {
                            if (emulator != null) {
                                emulator.append(payload, payload.length);
                                client.onScreenUpdated();
                            }
                        });
                    }
                }
            } catch (IOException e) {
                if (!disconnectRequested) {
                    mainHandler.post(() -> client.onConnectionError(context.getString(R.string.toast_receive_failed, e.getMessage())));
                }
            } finally {
                closeActiveConnection();
                if (disconnectRequested) {
                    notifyDisconnected(context.getString(R.string.toast_disconnected));
                } else {
                    notifyDisconnected(context.getString(R.string.toast_server_disconnected));
                }
            }
        }, "ssh-terminal-reader");
        readerThread.start();
    }

    private void closeActiveConnection() {
        Thread localReaderThread;
        Channel localChannel;
        Session localSession;
        OutputStream localOutput;
        synchronized (lock) {
            localReaderThread = readerThread;
            localChannel = channel;
            localSession = sshSession;
            localOutput = outputStream;
            readerThread = null;
            channel = null;
            sshSession = null;
            outputStream = null;
        }

        if (localReaderThread != null && localReaderThread != Thread.currentThread()) {
            localReaderThread.interrupt();
        }

        closeOutput(localOutput);
        disconnectSession(localChannel, localSession);
    }

    private void closeOutput(OutputStream stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (IOException ignored) {
            // Ignore cleanup failures.
        }
    }

    private void disconnectSession(Channel localChannel, Session localSession) {
        if (localChannel != null) {
            localChannel.disconnect();
        }
        if (localSession != null) {
            localSession.disconnect();
        }
    }

    private void setPtyType(Channel targetChannel) {
        int width = columns * cellWidth;
        int height = rows * cellHeight;
        if (targetChannel instanceof ChannelShell) {
            ((ChannelShell) targetChannel).setPtyType("xterm-256color", columns, rows, width, height);
        } else if (targetChannel instanceof ChannelExec) {
            ((ChannelExec) targetChannel).setPtyType("xterm-256color", columns, rows, width, height);
        }
    }

    private void setPtySize(Channel targetChannel) {
        int width = columns * cellWidth;
        int height = rows * cellHeight;
        if (targetChannel instanceof ChannelShell) {
            ((ChannelShell) targetChannel).setPtySize(columns, rows, width, height);
        } else if (targetChannel instanceof ChannelExec) {
            ((ChannelExec) targetChannel).setPtySize(columns, rows, width, height);
        }
    }

    private void notifyDisconnected(String message) {
        if (disconnectNotified.compareAndSet(false, true)) {
            mainHandler.post(() -> client.onDisconnected(message));
        }
    }

    private String buildConnectionErrorMessage(Exception exception) {
        if (exception instanceof JSchException && exception.getMessage() != null) {
            return context.getString(R.string.toast_connection_failed, exception.getMessage());
        }
        return context.getString(R.string.toast_connection_failed, exception.getClass().getSimpleName());
    }

    private final class EmulatorCallbacks implements TerminalSessionClient {
        @Override
        public void onTextChanged(com.termux.terminal.TerminalSession changedSession) {}

        @Override
        public void onTitleChanged(com.termux.terminal.TerminalSession changedSession) {}

        @Override
        public void onSessionFinished(com.termux.terminal.TerminalSession finishedSession) {}

        @Override
        public void onCopyTextToClipboard(com.termux.terminal.TerminalSession session, String text) {
            mainHandler.post(() -> client.onCopyToClipboard(text));
        }

        @Override
        public void onPasteTextFromClipboard(com.termux.terminal.TerminalSession session) {
            SshTerminalSession.this.onPasteTextFromClipboard();
        }

        @Override
        public void onBell(com.termux.terminal.TerminalSession session) {
            mainHandler.post(client::onBell);
        }

        @Override
        public void onColorsChanged(com.termux.terminal.TerminalSession session) {
            mainHandler.post(client::onScreenUpdated);
        }

        @Override
        public void onTerminalCursorStateChange(boolean state) {
            mainHandler.post(client::onScreenUpdated);
        }

        @Override
        public Integer getTerminalCursorStyle() {
            return TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK;
        }

        @Override
        public void logError(String tag, String message) {
            Log.e(tag, message);
        }

        @Override
        public void logWarn(String tag, String message) {
            Log.w(tag, message);
        }

        @Override
        public void logInfo(String tag, String message) {
            Log.i(tag, message);
        }

        @Override
        public void logDebug(String tag, String message) {
            Log.d(tag, message);
        }

        @Override
        public void logVerbose(String tag, String message) {
            Log.v(tag, message);
        }

        @Override
        public void logStackTraceWithMessage(String tag, String message, Exception e) {
            Log.e(tag, message, e);
        }

        @Override
        public void logStackTrace(String tag, Exception e) {
            Log.e(tag, "stacktrace", e);
        }
    }
}
