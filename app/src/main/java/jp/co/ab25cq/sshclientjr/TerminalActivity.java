package com.sshclientjr;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.view.SshTerminalView;

public final class TerminalActivity extends Activity {
    private static final String EXTRA_HOST = "host";
    private static final String EXTRA_PORT = "port";
    private static final String EXTRA_USERNAME = "username";
    private static final String EXTRA_PASSWORD = "password";
    private static final String EXTRA_PRIVATE_KEY = "private_key";
    private static final String EXTRA_PASSPHRASE = "passphrase";
    private static final long KEY_REPEAT_INITIAL_DELAY_MS = 350L;
    private static final long KEY_REPEAT_INTERVAL_MS = 80L;

    private TextView statusView;
    private TextView sessionView;
    private SshTerminalView terminalView;
    private Button keyboardButton;
    private Button copyButton;
    private Button pasteButton;
    private Button ctrlButton;
    private Button altButton;
    private Button ctrlDButton;
    private Button pageUpButton;
    private Button leftButton;
    private Button downButton;
    private Button upButton;
    private Button rightButton;
    private Button pageDownButton;
    private Button escButton;
    private Button tabButton;
    private Button disconnectButton;

    private SshTerminalSession sshSession;
    private String baseSessionLabel = "";
    private boolean terminalConnected;
    private boolean ctrlLocked;
    private boolean altLocked;
    private boolean imeEnabled;
    private final Handler keyRepeatHandler = new Handler(Looper.getMainLooper());
    private int repeatingKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    private final Runnable keyRepeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (repeatingKeyCode == KeyEvent.KEYCODE_UNKNOWN) {
                return;
            }
            sendKey(repeatingKeyCode);
            keyRepeatHandler.postDelayed(this, KEY_REPEAT_INTERVAL_MS);
        }
    };

    public static Intent newIntent(Context context, String host, int port, String username, String password, String privateKey, String passphrase) {
        Intent intent = new Intent(context, TerminalActivity.class);
        intent.putExtra(EXTRA_HOST, host);
        intent.putExtra(EXTRA_PORT, port);
        intent.putExtra(EXTRA_USERNAME, username);
        intent.putExtra(EXTRA_PASSWORD, password);
        intent.putExtra(EXTRA_PRIVATE_KEY, privateKey);
        intent.putExtra(EXTRA_PASSPHRASE, passphrase);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);
        KeyboardInsetHelper.keepBelowStatusBar(this, findViewById(R.id.terminalTopBar));
        KeyboardInsetHelper.keepAboveKeyboard(this, findViewById(R.id.terminalKeyBar));

        sshSession = new SshTerminalSession(this, new SshTerminalSession.Client() {
            @Override
            public void onConnected() {
                runOnUiThread(() -> {
                    setConnectionState(true);
                    terminalView.requestFocus();
                    terminalView.onScreenUpdated();
                    showTerminalKeyboard();
                });
            }

            @Override
            public void onScreenUpdated() {
                runOnUiThread(() -> terminalView.onScreenUpdated());
            }

            @Override
            public void onTitleChanged(String title) {
                runOnUiThread(() -> {
                    if (TextUtils.isEmpty(title)) {
                        sessionView.setText(baseSessionLabel);
                    } else {
                        sessionView.setText(title + "  " + baseSessionLabel);
                    }
                });
            }

            @Override
            public void onConnectionError(String message) {
                runOnUiThread(() -> {
                    Toast.makeText(TerminalActivity.this, message, Toast.LENGTH_SHORT).show();
                    setConnectionState(false);
                });
            }

            @Override
            public void onDisconnected(String message) {
                runOnUiThread(() -> {
                    Toast.makeText(TerminalActivity.this, message, Toast.LENGTH_SHORT).show();
                    setConnectionState(false);
                });
            }

            @Override
            public void onCopyToClipboard(String text) {
                ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (manager != null) {
                    manager.setPrimaryClip(ClipData.newPlainText("sshclientjr", text));
                    Toast.makeText(TerminalActivity.this, R.string.toast_copied, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public String onRequestPaste() {
                ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (manager == null || manager.getPrimaryClip() == null || manager.getPrimaryClip().getItemCount() == 0) {
                    return null;
                }
                CharSequence text = manager.getPrimaryClip().getItemAt(0).coerceToText(TerminalActivity.this);
                return text == null ? null : text.toString();
            }

            @Override
            public void onBell() {
                // Keep silent behavior for now.
            }
        });

        bindViews();
        connectFromIntent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (terminalConnected) {
            showTerminalKeyboard();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && terminalConnected) {
            showTerminalKeyboard();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRepeatingKey();
        sshSession.release();
    }

    private void bindViews() {
        statusView = findViewById(R.id.statusValue);
        sessionView = findViewById(R.id.sessionValue);
        terminalView = findViewById(R.id.terminalOutput);
        keyboardButton = findViewById(R.id.keyboardButton);
        copyButton = findViewById(R.id.copyButton);
        pasteButton = findViewById(R.id.pasteButton);
        ctrlButton = findViewById(R.id.ctrlButton);
        altButton = findViewById(R.id.altButton);
        ctrlDButton = findViewById(R.id.ctrlDButton);
        pageUpButton = findViewById(R.id.pageUpButton);
        leftButton = findViewById(R.id.leftButton);
        downButton = findViewById(R.id.downButton);
        upButton = findViewById(R.id.upButton);
        rightButton = findViewById(R.id.rightButton);
        pageDownButton = findViewById(R.id.pageDownButton);
        escButton = findViewById(R.id.escButton);
        tabButton = findViewById(R.id.tabButton);
        disconnectButton = findViewById(R.id.disconnectButton);

        terminalView.attachSession(sshSession);
        terminalView.setImeModeEnabled(false);
        terminalView.setModifierListener((ctrlEnabled, altEnabled) -> runOnUiThread(() -> {
            ctrlLocked = ctrlEnabled;
            altLocked = altEnabled;
            updateButtonStates();
        }));

        findViewById(R.id.backButton).setOnClickListener(view -> finish());
        keyboardButton.setOnClickListener(view -> toggleImeMode());
        copyButton.setOnClickListener(view -> copySelection());
        pasteButton.setOnClickListener(view -> pasteClipboard());
        ctrlButton.setOnClickListener(view -> toggleCtrlModifier());
        altButton.setOnClickListener(view -> toggleAltModifier());
        ctrlDButton.setOnClickListener(view -> sshSession.sendCtrlD());
        pageUpButton.setOnClickListener(view -> sendKey(KeyEvent.KEYCODE_PAGE_UP));
        setRepeatingKeyListener(leftButton, KeyEvent.KEYCODE_DPAD_LEFT);
        setRepeatingKeyListener(downButton, KeyEvent.KEYCODE_DPAD_DOWN);
        setRepeatingKeyListener(upButton, KeyEvent.KEYCODE_DPAD_UP);
        setRepeatingKeyListener(rightButton, KeyEvent.KEYCODE_DPAD_RIGHT);
        pageDownButton.setOnClickListener(view -> sendKey(KeyEvent.KEYCODE_PAGE_DOWN));
        escButton.setOnClickListener(view -> sshSession.sendEscape());
        tabButton.setOnClickListener(view -> sshSession.sendTab());
        disconnectButton.setOnClickListener(view -> sshSession.disconnect());
        updateButtonStates();
        setConnectionState(false);
    }

    private void connectFromIntent() {
        Intent intent = getIntent();
        String host = intent.getStringExtra(EXTRA_HOST);
        int port = intent.getIntExtra(EXTRA_PORT, 22);
        String username = intent.getStringExtra(EXTRA_USERNAME);
        String password = intent.getStringExtra(EXTRA_PASSWORD);
        String privateKey = intent.getStringExtra(EXTRA_PRIVATE_KEY);
        String passphrase = intent.getStringExtra(EXTRA_PASSPHRASE);

        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(username) ||
                (TextUtils.isEmpty(password) && TextUtils.isEmpty(privateKey))) {
            Toast.makeText(this, R.string.toast_missing_connection_info, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        baseSessionLabel = username + "@" + host + ":" + port;
        sessionView.setText(baseSessionLabel);
        statusView.setText(R.string.status_connecting);
        sshSession.connect(host, port, username, password, privateKey, passphrase);
    }

    private void setConnectionState(boolean connected) {
        terminalConnected = connected;
        statusView.setText(connected ? R.string.status_connected : R.string.status_disconnected);
        keyboardButton.setEnabled(connected);
        copyButton.setEnabled(connected);
        pasteButton.setEnabled(connected);
        ctrlButton.setEnabled(connected);
        altButton.setEnabled(connected);
        ctrlDButton.setEnabled(connected);
        pageUpButton.setEnabled(connected);
        leftButton.setEnabled(connected);
        downButton.setEnabled(connected);
        upButton.setEnabled(connected);
        rightButton.setEnabled(connected);
        pageDownButton.setEnabled(connected);
        escButton.setEnabled(connected);
        tabButton.setEnabled(connected);
        disconnectButton.setEnabled(connected);
    }

    private void toggleCtrlModifier() {
        terminalView.setCtrlModifier(!ctrlLocked);
    }

    private void toggleAltModifier() {
        terminalView.setAltModifier(!altLocked);
    }

    private void toggleImeMode() {
        imeEnabled = !imeEnabled;
        terminalView.setImeModeEnabled(imeEnabled);
        showTerminalKeyboard();
        updateButtonStates();
    }

    private void showTerminalKeyboard() {
        terminalView.setImeModeEnabled(imeEnabled);
        terminalView.requestFocus();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) {
            imm.restartInput(terminalView);
        }
        terminalView.showKeyboard();
        keyRepeatHandler.postDelayed(() -> {
            if (terminalConnected) {
                terminalView.requestFocus();
                terminalView.showKeyboard();
            }
        }, 180L);
        keyRepeatHandler.postDelayed(() -> {
            if (terminalConnected) {
                terminalView.requestFocus();
                terminalView.showKeyboard();
            }
        }, 600L);
    }

    private void updateButtonStates() {
        keyboardButton.setText(imeEnabled ? "IME*" : "IME");
        ctrlButton.setText(ctrlLocked ? "Ctrl*" : "Ctrl");
        altButton.setText(altLocked ? "Alt*" : "Alt");
    }

    private void sendKey(int keyCode) {
        terminalView.handleKeyCode(keyCode, 0);
    }

    private void setRepeatingKeyListener(Button button, int keyCode) {
        button.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!view.isEnabled()) {
                        return true;
                    }
                    startRepeatingKey(keyCode);
                    return true;
                case MotionEvent.ACTION_UP:
                    view.performClick();
                    stopRepeatingKey();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    stopRepeatingKey();
                    return true;
                default:
                    return true;
            }
        });
    }

    private void startRepeatingKey(int keyCode) {
        stopRepeatingKey();
        repeatingKeyCode = keyCode;
        sendKey(keyCode);
        keyRepeatHandler.postDelayed(keyRepeatRunnable, KEY_REPEAT_INITIAL_DELAY_MS);
    }

    private void stopRepeatingKey() {
        repeatingKeyCode = KeyEvent.KEYCODE_UNKNOWN;
        keyRepeatHandler.removeCallbacks(keyRepeatRunnable);
    }

    private void copySelection() {
        String selectedText = terminalView.getSelectedText();
        if (TextUtils.isEmpty(selectedText)) {
            Toast.makeText(this, R.string.toast_select_range, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(ClipData.newPlainText("sshclientjr", selectedText));
            terminalView.clearSelection();
            Toast.makeText(this, R.string.toast_selection_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void pasteClipboard() {
        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager == null || manager.getPrimaryClip() == null || manager.getPrimaryClip().getItemCount() == 0) {
            Toast.makeText(this, R.string.toast_no_clipboard_text, Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence text = manager.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (text == null) {
            Toast.makeText(this, R.string.toast_no_clipboard_text, Toast.LENGTH_SHORT).show();
            return;
        }
        terminalView.pasteText(text.toString());
    }
}
