package com.sshclientjr;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class FileEditorActivity extends Activity {
    private static final String EXTRA_HOST = "host";
    private static final String EXTRA_PORT = "port";
    private static final String EXTRA_USERNAME = "username";
    private static final String EXTRA_PASSWORD = "password";
    private static final String EXTRA_PRIVATE_KEY = "private_key";
    private static final String EXTRA_PASSPHRASE = "passphrase";
    private static final String EXTRA_REMOTE_PATH = "remote_path";
    private static final String EXTRA_LOCAL_PATH = "local_path";
    private static final String EXTRA_LOCAL_URI = "local_uri";
    private static final String EXTRA_DISPLAY_PATH = "display_path";
    private static final long KEY_REPEAT_INITIAL_DELAY_MS = 350L;
    private static final long KEY_REPEAT_INTERVAL_MS = 80L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler keyRepeatHandler = new Handler(Looper.getMainLooper());

    private TextView pathView;
    private EditText editorInput;
    private Button searchButton;
    private Button saveButton;
    private Button closeButton;
    private Button keyboardButton;
    private Button escButton;
    private Button tabButton;
    private Button copyButton;
    private Button pasteButton;
    private Button ctrlButton;
    private Button altButton;
    private Button pageUpButton;
    private Button leftButton;
    private Button downButton;
    private Button upButton;
    private Button rightButton;
    private Button pageDownButton;

    private String host;
    private int port;
    private String username;
    private String password;
    private String privateKey;
    private String passphrase;
    private String remotePath;
    private String localPath;
    private String localUri;
    private String displayPath;
    private String lastSearchQuery;
    private int changedStart;
    private int changedBefore;
    private int changedCount;
    private boolean applyingAutoIndent;
    private boolean applyingEditorShortcut;
    private boolean ctrlLocked;
    private boolean altLocked;
    private boolean imeEnabled;
    private boolean emacsCtrlXPrefix;
    private int repeatingKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    private final Runnable keyRepeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (repeatingKeyCode == KeyEvent.KEYCODE_UNKNOWN) {
                return;
            }
            sendEditorKey(repeatingKeyCode);
            keyRepeatHandler.postDelayed(this, KEY_REPEAT_INTERVAL_MS);
        }
    };

    public static Intent newRemoteIntent(Context context, String host, int port, String username, String password, String privateKey, String passphrase, String remotePath, String localPath) {
        Intent intent = new Intent(context, FileEditorActivity.class);
        intent.putExtra(EXTRA_HOST, host);
        intent.putExtra(EXTRA_PORT, port);
        intent.putExtra(EXTRA_USERNAME, username);
        intent.putExtra(EXTRA_PASSWORD, password);
        intent.putExtra(EXTRA_PRIVATE_KEY, privateKey);
        intent.putExtra(EXTRA_PASSPHRASE, passphrase);
        intent.putExtra(EXTRA_REMOTE_PATH, remotePath);
        intent.putExtra(EXTRA_LOCAL_PATH, localPath);
        return intent;
    }

    public static Intent newLocalIntent(Context context, String localPath) {
        Intent intent = new Intent(context, FileEditorActivity.class);
        intent.putExtra(EXTRA_LOCAL_PATH, localPath);
        return intent;
    }

    public static Intent newLocalDocumentIntent(Context context, String localUri, String displayPath) {
        Intent intent = new Intent(context, FileEditorActivity.class);
        intent.putExtra(EXTRA_LOCAL_URI, localUri);
        intent.putExtra(EXTRA_DISPLAY_PATH, displayPath);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_editor);
        KeyboardInsetHelper.keepBelowStatusBar(this, findViewById(R.id.editorTopBar));
        KeyboardInsetHelper.keepAboveKeyboard(this, findViewById(R.id.editorKeyBar));

        readIntent();
        bindViews();
        loadFile();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRepeatingKey();
        executor.shutdownNow();
    }

    private void readIntent() {
        Intent intent = getIntent();
        host = intent.getStringExtra(EXTRA_HOST);
        port = intent.getIntExtra(EXTRA_PORT, 22);
        username = intent.getStringExtra(EXTRA_USERNAME);
        password = intent.getStringExtra(EXTRA_PASSWORD);
        privateKey = intent.getStringExtra(EXTRA_PRIVATE_KEY);
        passphrase = intent.getStringExtra(EXTRA_PASSPHRASE);
        remotePath = intent.getStringExtra(EXTRA_REMOTE_PATH);
        localPath = intent.getStringExtra(EXTRA_LOCAL_PATH);
        localUri = intent.getStringExtra(EXTRA_LOCAL_URI);
        displayPath = intent.getStringExtra(EXTRA_DISPLAY_PATH);
    }

    private void bindViews() {
        pathView = findViewById(R.id.editorPathValue);
        editorInput = findViewById(R.id.editorInput);
        searchButton = findViewById(R.id.searchButton);
        saveButton = findViewById(R.id.saveButton);
        closeButton = findViewById(R.id.editorCloseButton);
        keyboardButton = findViewById(R.id.editorKeyboardButton);
        escButton = findViewById(R.id.editorEscButton);
        tabButton = findViewById(R.id.editorTabButton);
        copyButton = findViewById(R.id.editorCopyButton);
        pasteButton = findViewById(R.id.editorPasteButton);
        ctrlButton = findViewById(R.id.editorCtrlButton);
        altButton = findViewById(R.id.editorAltButton);
        pageUpButton = findViewById(R.id.editorPageUpButton);
        leftButton = findViewById(R.id.editorLeftButton);
        downButton = findViewById(R.id.editorDownButton);
        upButton = findViewById(R.id.editorUpButton);
        rightButton = findViewById(R.id.editorRightButton);
        pageDownButton = findViewById(R.id.editorPageDownButton);

        editorInput.setFilters(new InputFilter[]{this::filterEditorShortcutInput});
        applyEditorImeMode(false);
        editorInput.setLongClickable(true);
        editorInput.setCustomSelectionActionModeCallback(createEditorActionModeCallback());
        editorInput.setCustomInsertionActionModeCallback(createEditorActionModeCallback());
        editorInput.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            boolean ctrl = ctrlLocked || event.isCtrlPressed();
            boolean alt = altLocked || event.isAltPressed();
            if (handleEditorShortcutKey(keyCode, ctrl, alt)) {
                clearOneShotModifiers();
                return true;
            }
            return false;
        });
        editorInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                changedStart = start;
                changedBefore = before;
                changedCount = count;
            }

            @Override
            public void afterTextChanged(Editable editable) {
                if (handleEditorShortcutInsertion(editable)) {
                    return;
                }
                applyAutoIndent(editable);
            }
        });
        pathView.setText(getDisplayPath());
        saveButton.setText(TextUtils.isEmpty(remotePath) ? R.string.action_save : R.string.action_save_upload);
        searchButton.setOnClickListener(view -> showSearchDialog(false));
        saveButton.setOnClickListener(view -> saveFile());
        closeButton.setOnClickListener(view -> finish());
        keyboardButton.setOnClickListener(view -> toggleEditorIme());
        escButton.setOnClickListener(view -> sendEditorKey(KeyEvent.KEYCODE_ESCAPE));
        tabButton.setOnClickListener(view -> sendEditorKey(KeyEvent.KEYCODE_TAB));
        copyButton.setOnClickListener(view -> editorInput.onTextContextMenuItem(android.R.id.copy));
        pasteButton.setOnClickListener(view -> editorInput.onTextContextMenuItem(android.R.id.paste));
        ctrlButton.setOnClickListener(view -> {
            ctrlLocked = !ctrlLocked;
            updateModifierButtons();
        });
        altButton.setOnClickListener(view -> {
            altLocked = !altLocked;
            updateModifierButtons();
        });
        pageUpButton.setOnClickListener(view -> sendEditorKey(KeyEvent.KEYCODE_PAGE_UP));
        setRepeatingKeyListener(leftButton, KeyEvent.KEYCODE_DPAD_LEFT);
        setRepeatingKeyListener(downButton, KeyEvent.KEYCODE_DPAD_DOWN);
        setRepeatingKeyListener(upButton, KeyEvent.KEYCODE_DPAD_UP);
        setRepeatingKeyListener(rightButton, KeyEvent.KEYCODE_DPAD_RIGHT);
        pageDownButton.setOnClickListener(view -> sendEditorKey(KeyEvent.KEYCODE_PAGE_DOWN));
        updateModifierButtons();
    }

    private void showEditorKeyboard() {
        editorInput.requestFocus();
        applyEditorImeMode(true);
    }

    private void showEditorKeyboardWithoutChangingMode() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) {
            imm.showSoftInput(editorInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideEditorKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) {
            imm.hideSoftInputFromWindow(editorInput.getWindowToken(), 0);
        }
    }

    private void toggleEditorIme() {
        imeEnabled = !imeEnabled;
        applyEditorImeMode(true);
        updateModifierButtons();
    }

    private void applyEditorImeMode(boolean showKeyboard) {
        int selectionStart = editorInput.getSelectionStart();
        int selectionEnd = editorInput.getSelectionEnd();
        editorInput.setShowSoftInputOnFocus(true);
        editorInput.setInputType(buildEditorInputType());
        int length = editorInput.length();
        if (selectionStart >= 0 && selectionEnd >= 0) {
            editorInput.setSelection(Math.min(selectionStart, length), Math.min(selectionEnd, length));
        }
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) {
            imm.restartInput(editorInput);
        }
        if (showKeyboard) {
            editorInput.requestFocus();
            showEditorKeyboardWithoutChangingMode();
        }
    }

    private int buildEditorInputType() {
        if (imeEnabled) {
            return InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_NORMAL
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        }
        return InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
    }

    private void updateModifierButtons() {
        keyboardButton.setText(imeEnabled ? "IME*" : "IME");
        if (emacsCtrlXPrefix) {
            ctrlButton.setText(ctrlLocked ? "C-x*" : "C-x");
        } else {
            ctrlButton.setText(ctrlLocked ? "Ctrl*" : "Ctrl");
        }
        altButton.setText(altLocked ? "Alt*" : "Alt");
    }

    private CharSequence filterEditorShortcutInput(CharSequence source, int start, int end, Spanned dest, int dstart, int dend) {
        if (applyingEditorShortcut || applyingAutoIndent || altLocked || (!ctrlLocked && !emacsCtrlXPrefix)) {
            return null;
        }
        boolean handled = false;
        for (int i = start; i < end; i++) {
            char character = source.charAt(i);
            if (!isAsciiLetter(character)) {
                return handled ? "" : null;
            }
            int keyCode = asciiLetterToKeyCode(character);
            if (!isEmacsCtrlShortcutKey(keyCode) || (!ctrlLocked && emacsCtrlXPrefix && !isCtrlXShortcutKey(keyCode))) {
                return handled ? "" : null;
            }
            handled = true;
            editorInput.post(() -> {
                applyingEditorShortcut = true;
                try {
                    handleEditorShortcutKey(keyCode, ctrlLocked || isCtrlXShortcutKey(keyCode), false);
                    clearOneShotModifiers();
                } finally {
                    applyingEditorShortcut = false;
                }
            });
        }
        return handled ? "" : null;
    }

    private void sendEditorKey(int keyCode) {
        editorInput.requestFocus();
        if (handleEditorShortcutKey(keyCode, ctrlLocked, altLocked)) {
            clearOneShotModifiers();
            return;
        }
        if (keyCode == KeyEvent.KEYCODE_TAB && !ctrlLocked && !altLocked) {
            replaceSelection("\t");
            return;
        }
        int metaState = 0;
        if (ctrlLocked) {
            metaState |= KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        }
        if (altLocked) {
            metaState |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
        }
        long eventTime = System.currentTimeMillis();
        editorInput.dispatchKeyEvent(new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0, metaState));
        editorInput.dispatchKeyEvent(new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0, metaState));
        clearOneShotModifiers();
    }

    private void replaceSelection(String text) {
        int start = Math.max(0, editorInput.getSelectionStart());
        int end = Math.max(0, editorInput.getSelectionEnd());
        int selectionStart = Math.min(start, end);
        int selectionEnd = Math.max(start, end);
        editorInput.getText().replace(selectionStart, selectionEnd, text);
        editorInput.setSelection(selectionStart + text.length());
    }

    private boolean handleEditorShortcutInsertion(Editable editable) {
        if (applyingEditorShortcut || applyingAutoIndent || altLocked || (!ctrlLocked && !emacsCtrlXPrefix) || changedCount != 1) {
            return false;
        }
        if (changedStart < 0 || changedStart >= editable.length()) {
            return false;
        }
        char character = editable.charAt(changedStart);
        if (!isAsciiLetter(character)) {
            return false;
        }
        int keyCode = asciiLetterToKeyCode(character);
        if (!isEmacsCtrlShortcutKey(keyCode)) {
            clearOneShotModifiers();
            return false;
        }
        if (!ctrlLocked && emacsCtrlXPrefix && !isCtrlXShortcutKey(keyCode)) {
            return false;
        }
        applyingEditorShortcut = true;
        try {
            editable.delete(changedStart, changedStart + 1);
            editorInput.setSelection(Math.min(changedStart, editable.length()));
            handleEditorShortcutKey(keyCode, ctrlLocked || isCtrlXShortcutKey(keyCode), false);
            clearOneShotModifiers();
        } finally {
            applyingEditorShortcut = false;
        }
        return true;
    }

    private boolean handleEditorShortcutKey(int keyCode, boolean ctrl, boolean alt) {
        if (emacsCtrlXPrefix) {
            if (ctrl && keyCode == KeyEvent.KEYCODE_C) {
                emacsCtrlXPrefix = false;
                updateModifierButtons();
                finish();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_K) {
                emacsCtrlXPrefix = false;
                updateModifierButtons();
                finish();
                return true;
            }
            if (ctrl && keyCode == KeyEvent.KEYCODE_S) {
                emacsCtrlXPrefix = false;
                updateModifierButtons();
                saveFile();
                return true;
            }
            emacsCtrlXPrefix = false;
            updateModifierButtons();
        }
        if (alt) {
            if (keyCode == KeyEvent.KEYCODE_W) {
                copySelectionToClipboard();
                return true;
            }
            return false;
        }
        if (!ctrl) {
            return false;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_A:
                moveToLineStart();
                return true;
            case KeyEvent.KEYCODE_E:
                moveToLineEnd();
                return true;
            case KeyEvent.KEYCODE_B:
                moveCursorHorizontally(false);
                return true;
            case KeyEvent.KEYCODE_F:
                moveCursorHorizontally(true);
                return true;
            case KeyEvent.KEYCODE_P:
                dispatchEditorKey(KeyEvent.KEYCODE_DPAD_UP, 0);
                return true;
            case KeyEvent.KEYCODE_N:
                dispatchEditorKey(KeyEvent.KEYCODE_DPAD_DOWN, 0);
                return true;
            case KeyEvent.KEYCODE_D:
                deleteForward();
                return true;
            case KeyEvent.KEYCODE_H:
                deleteBackward();
                return true;
            case KeyEvent.KEYCODE_K:
                killToLineEnd();
                return true;
            case KeyEvent.KEYCODE_Y:
                pasteFromClipboard();
                return true;
            case KeyEvent.KEYCODE_W:
                cutSelectionToClipboard();
                return true;
            case KeyEvent.KEYCODE_C:
                copySelectionToClipboard();
                return true;
            case KeyEvent.KEYCODE_S:
                searchForwardEmacsStyle();
                return true;
            case KeyEvent.KEYCODE_R:
                searchBackwardEmacsStyle();
                return true;
            case KeyEvent.KEYCODE_X:
                emacsCtrlXPrefix = true;
                updateModifierButtons();
                return true;
            default:
                return false;
        }
    }

    private boolean isEmacsCtrlShortcutKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_A:
            case KeyEvent.KEYCODE_E:
            case KeyEvent.KEYCODE_B:
            case KeyEvent.KEYCODE_F:
            case KeyEvent.KEYCODE_P:
            case KeyEvent.KEYCODE_N:
            case KeyEvent.KEYCODE_D:
            case KeyEvent.KEYCODE_H:
            case KeyEvent.KEYCODE_K:
            case KeyEvent.KEYCODE_Y:
            case KeyEvent.KEYCODE_W:
            case KeyEvent.KEYCODE_C:
            case KeyEvent.KEYCODE_S:
            case KeyEvent.KEYCODE_R:
            case KeyEvent.KEYCODE_X:
                return true;
            default:
                return false;
        }
    }

    private boolean isCtrlXShortcutKey(int keyCode) {
        return emacsCtrlXPrefix
                && (keyCode == KeyEvent.KEYCODE_C || keyCode == KeyEvent.KEYCODE_S || keyCode == KeyEvent.KEYCODE_K);
    }

    private boolean isAsciiLetter(char character) {
        return (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z');
    }

    private int asciiLetterToKeyCode(char character) {
        return KeyEvent.KEYCODE_A + (Character.toLowerCase(character) - 'a');
    }

    private void moveToLineStart() {
        Editable editable = editorInput.getText();
        int cursor = Math.max(0, editorInput.getSelectionEnd());
        int lineStart = findLineStart(editable, cursor);
        editorInput.setSelection(lineStart);
    }

    private void moveToLineEnd() {
        Editable editable = editorInput.getText();
        int cursor = Math.max(0, editorInput.getSelectionEnd());
        int lineEnd = findLineEnd(editable, cursor);
        editorInput.setSelection(lineEnd);
    }

    private int findLineStart(Editable editable, int cursor) {
        int index = Math.min(cursor, editable.length()) - 1;
        while (index >= 0 && editable.charAt(index) != '\n') {
            index--;
        }
        return index + 1;
    }

    private int findLineEnd(Editable editable, int cursor) {
        int index = Math.min(cursor, editable.length());
        while (index < editable.length() && editable.charAt(index) != '\n') {
            index++;
        }
        return index;
    }

    private void moveCursorHorizontally(boolean forward) {
        int start = editorInput.getSelectionStart();
        int end = editorInput.getSelectionEnd();
        if (start != end) {
            editorInput.setSelection(forward ? Math.max(start, end) : Math.min(start, end));
            return;
        }
        Editable editable = editorInput.getText();
        int cursor = Math.max(0, end);
        if (forward && cursor < editable.length()) {
            editorInput.setSelection(cursor + Character.charCount(Character.codePointAt(editable, cursor)));
        } else if (!forward && cursor > 0) {
            editorInput.setSelection(cursor - Character.charCount(Character.codePointBefore(editable, cursor)));
        }
    }

    private void deleteForward() {
        Editable editable = editorInput.getText();
        int start = Math.max(0, editorInput.getSelectionStart());
        int end = Math.max(0, editorInput.getSelectionEnd());
        int selectionStart = Math.min(start, end);
        int selectionEnd = Math.max(start, end);
        if (selectionStart != selectionEnd) {
            editable.delete(selectionStart, selectionEnd);
            return;
        }
        if (selectionStart < editable.length()) {
            int deleteEnd = selectionStart + Character.charCount(Character.codePointAt(editable, selectionStart));
            editable.delete(selectionStart, deleteEnd);
        }
    }

    private void deleteBackward() {
        Editable editable = editorInput.getText();
        int start = Math.max(0, editorInput.getSelectionStart());
        int end = Math.max(0, editorInput.getSelectionEnd());
        int selectionStart = Math.min(start, end);
        int selectionEnd = Math.max(start, end);
        if (selectionStart != selectionEnd) {
            editable.delete(selectionStart, selectionEnd);
            return;
        }
        if (selectionStart > 0) {
            int deleteStart = selectionStart - Character.charCount(Character.codePointBefore(editable, selectionStart));
            editable.delete(deleteStart, selectionStart);
        }
    }

    private void killToLineEnd() {
        Editable editable = editorInput.getText();
        int cursor = Math.max(0, editorInput.getSelectionEnd());
        int lineEnd = findLineEnd(editable, cursor);
        int deleteEnd = lineEnd;
        if (deleteEnd == cursor && deleteEnd < editable.length() && editable.charAt(deleteEnd) == '\n') {
            deleteEnd++;
        }
        if (deleteEnd <= cursor) {
            return;
        }
        copyTextToClipboard(editable.subSequence(cursor, deleteEnd).toString());
        editable.delete(cursor, deleteEnd);
    }

    private void copySelectionToClipboard() {
        int start = editorInput.getSelectionStart();
        int end = editorInput.getSelectionEnd();
        if (start == end) {
            return;
        }
        int selectionStart = Math.min(start, end);
        int selectionEnd = Math.max(start, end);
        copyTextToClipboard(editorInput.getText().subSequence(selectionStart, selectionEnd).toString());
    }

    private void cutSelectionToClipboard() {
        int start = editorInput.getSelectionStart();
        int end = editorInput.getSelectionEnd();
        if (start == end) {
            return;
        }
        int selectionStart = Math.min(start, end);
        int selectionEnd = Math.max(start, end);
        Editable editable = editorInput.getText();
        copyTextToClipboard(editable.subSequence(selectionStart, selectionEnd).toString());
        editable.delete(selectionStart, selectionEnd);
    }

    private void pasteFromClipboard() {
        editorInput.onTextContextMenuItem(android.R.id.paste);
    }

    private void copyTextToClipboard(String text) {
        android.content.ClipboardManager manager = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(android.content.ClipData.newPlainText("sshclientjr", text));
        }
    }

    private void dispatchEditorKey(int keyCode, int metaState) {
        long eventTime = System.currentTimeMillis();
        editorInput.dispatchKeyEvent(new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0, metaState));
        editorInput.dispatchKeyEvent(new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0, metaState));
    }

    private void clearOneShotModifiers() {
        if (!ctrlLocked && !altLocked) {
            return;
        }
        ctrlLocked = false;
        altLocked = false;
        updateModifierButtons();
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
        sendEditorKey(keyCode);
        keyRepeatHandler.postDelayed(keyRepeatRunnable, KEY_REPEAT_INITIAL_DELAY_MS);
    }

    private void stopRepeatingKey() {
        repeatingKeyCode = KeyEvent.KEYCODE_UNKNOWN;
        keyRepeatHandler.removeCallbacks(keyRepeatRunnable);
    }

    private ActionMode.Callback createEditorActionModeCallback() {
        return new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                ensureEditorContextMenu(menu);
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                ensureEditorContextMenu(menu);
                return true;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                int itemId = item.getItemId();
                if (itemId == android.R.id.selectAll
                        || itemId == android.R.id.cut
                        || itemId == android.R.id.copy
                        || itemId == android.R.id.paste) {
                    boolean handled = editorInput.onTextContextMenuItem(itemId);
                    if (handled && itemId != android.R.id.selectAll) {
                        mode.finish();
                    }
                    return handled;
                }
                return false;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
            }
        };
    }

    private void ensureEditorContextMenu(Menu menu) {
        addEditorContextMenuItem(menu, android.R.id.selectAll, 10, getString(R.string.action_select_all));
        addEditorContextMenuItem(menu, android.R.id.cut, 20, getString(R.string.action_cut));
        addEditorContextMenuItem(menu, android.R.id.copy, 30, getString(R.string.action_copy));
        addEditorContextMenuItem(menu, android.R.id.paste, 40, getString(R.string.action_paste));
    }

    private void addEditorContextMenuItem(Menu menu, int itemId, int order, String title) {
        if (menu.findItem(itemId) != null) {
            return;
        }
        menu.add(0, itemId, order, title).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
    }

    private void applyAutoIndent(Editable editable) {
        if (applyingAutoIndent || changedBefore != 0 || changedCount != 1 || changedStart < 0 || changedStart >= editable.length()) {
            return;
        }
        if (editable.charAt(changedStart) != '\n') {
            return;
        }
        String indent = getLineIndentBeforeNewline(editable, changedStart);
        if (TextUtils.isEmpty(indent)) {
            return;
        }
        applyingAutoIndent = true;
        try {
            int insertAt = changedStart + 1;
            editable.insert(insertAt, indent);
            editorInput.setSelection(insertAt + indent.length());
        } finally {
            applyingAutoIndent = false;
        }
    }

    private String getLineIndentBeforeNewline(Editable editable, int newlineIndex) {
        int lineStart = newlineIndex - 1;
        while (lineStart >= 0 && editable.charAt(lineStart) != '\n') {
            lineStart--;
        }
        lineStart++;
        int index = lineStart;
        while (index < newlineIndex) {
            char character = editable.charAt(index);
            if (character != ' ' && character != '\t') {
                break;
            }
            index++;
        }
        return editable.subSequence(lineStart, index).toString();
    }

    private void showSearchDialog(boolean backward) {
        EditText searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setText(lastSearchQuery);
        searchInput.setSelectAllOnFocus(true);
        searchInput.setHint(R.string.editor_search_hint);
        searchInput.setPadding(32, 16, 32, 16);
        new AlertDialog.Builder(this)
                .setTitle(backward ? R.string.editor_search_backward_title : R.string.editor_search_title)
                .setView(searchInput)
                .setPositiveButton(R.string.action_search, (dialog, which) -> {
                    if (backward) {
                        searchPrevious(searchInput.getText().toString());
                    } else {
                        searchNext(searchInput.getText().toString());
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void searchForwardEmacsStyle() {
        if (TextUtils.isEmpty(lastSearchQuery)) {
            showSearchDialog(false);
            return;
        }
        searchNext(lastSearchQuery);
    }

    private void searchBackwardEmacsStyle() {
        if (TextUtils.isEmpty(lastSearchQuery)) {
            showSearchDialog(true);
            return;
        }
        searchPrevious(lastSearchQuery);
    }

    private void searchNext(String query) {
        if (TextUtils.isEmpty(query)) {
            Toast.makeText(this, R.string.toast_search_query_required, Toast.LENGTH_SHORT).show();
            return;
        }
        String text = editorInput.getText().toString();
        int start = query.equals(lastSearchQuery) ? editorInput.getSelectionEnd() : 0;
        int index = text.indexOf(query, Math.max(0, start));
        if (index < 0 && start > 0) {
            index = text.indexOf(query);
        }
        lastSearchQuery = query;
        if (index < 0) {
            Toast.makeText(this, getString(R.string.toast_not_found, query), Toast.LENGTH_SHORT).show();
            return;
        }
        editorInput.requestFocus();
        editorInput.setSelection(index, index + query.length());
        Toast.makeText(this, getString(R.string.toast_search_found, query), Toast.LENGTH_SHORT).show();
    }

    private void searchPrevious(String query) {
        if (TextUtils.isEmpty(query)) {
            Toast.makeText(this, R.string.toast_search_query_required, Toast.LENGTH_SHORT).show();
            return;
        }
        String text = editorInput.getText().toString();
        int start = query.equals(lastSearchQuery) ? editorInput.getSelectionStart() - 1 : editorInput.getSelectionStart();
        int index = text.lastIndexOf(query, Math.max(0, start));
        if (index < 0 && start < text.length() - 1) {
            index = text.lastIndexOf(query);
        }
        lastSearchQuery = query;
        if (index < 0) {
            Toast.makeText(this, getString(R.string.toast_not_found, query), Toast.LENGTH_SHORT).show();
            return;
        }
        editorInput.requestFocus();
        editorInput.setSelection(index, index + query.length());
        Toast.makeText(this, getString(R.string.toast_search_backward_found, query), Toast.LENGTH_SHORT).show();
    }

    private void loadFile() {
        executor.execute(() -> {
            try {
                String text = TextUtils.isEmpty(localUri)
                        ? readTextFile(new File(localPath))
                        : readTextUri(Uri.parse(localUri));
                runOnUiThread(() -> editorInput.setText(text));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.toast_file_open_failed, e.getMessage()), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void saveFile() {
        String text = editorInput.getText().toString();
        saveButton.setEnabled(false);
        executor.execute(() -> {
            try {
                if (TextUtils.isEmpty(localUri)) {
                    writeTextFile(new File(localPath), text);
                } else {
                    writeTextUri(Uri.parse(localUri), text);
                }
                if (!TextUtils.isEmpty(remotePath)) {
                    uploadToRemote();
                }
                runOnUiThread(() -> {
                    saveButton.setEnabled(true);
                    Toast.makeText(this, TextUtils.isEmpty(remotePath) ? R.string.toast_saved : R.string.toast_saved_uploaded, Toast.LENGTH_SHORT).show();
                    if (!TextUtils.isEmpty(remotePath)) {
                        finish();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    saveButton.setEnabled(true);
                    Toast.makeText(this, getString(R.string.toast_save_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void uploadToRemote() throws Exception {
        Session session = null;
        ChannelSftp channel = null;
        try {
            session = SshSessionFactory.connect(host, port, username, password, privateKey, passphrase);
            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(5_000);
            channel.put(localPath, remotePath);
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
            if (session != null) {
                session.disconnect();
            }
        }
    }

    private String getDisplayPath() {
        if (!TextUtils.isEmpty(remotePath)) {
            return remotePath;
        }
        if (!TextUtils.isEmpty(displayPath)) {
            return displayPath;
        }
        if (!TextUtils.isEmpty(localUri)) {
            return localUri;
        }
        return localPath;
    }

    private String readTextFile(File file) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        FileInputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
        } finally {
            input.close();
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private void writeTextFile(File file, String text) throws Exception {
        FileOutputStream output = new FileOutputStream(file);
        try {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            output.close();
        }
    }

    private String readTextUri(Uri uri) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InputStream input = getContentResolver().openInputStream(uri);
        if (input == null) {
            throw new java.io.FileNotFoundException("not readable");
        }
        try {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
        } finally {
            input.close();
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private void writeTextUri(Uri uri, String text) throws Exception {
        OutputStream output = getContentResolver().openOutputStream(uri, "wt");
        if (output == null) {
            throw new java.io.FileNotFoundException("not writable");
        }
        try {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            output.close();
        }
    }
}
