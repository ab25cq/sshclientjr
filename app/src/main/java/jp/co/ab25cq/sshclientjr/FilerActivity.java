package com.sshclientjr;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.text.InputType;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;
import com.termux.view.SshTerminalView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class FilerActivity extends Activity {
    private static final String EXTRA_HOST = "host";
    private static final String EXTRA_PORT = "port";
    private static final String EXTRA_USERNAME = "username";
    private static final String EXTRA_PASSWORD = "password";
    private static final String EXTRA_PRIVATE_KEY = "private_key";
    private static final String EXTRA_PASSPHRASE = "passphrase";
    private static final String PREFERENCES_NAME = "sshclientjr";
    private static final String KEY_LOCAL_TREE_URI = "filer_local_tree_uri";
    private static final String KEY_REMOTE_COMMAND_HISTORY = "filer_remote_command_history";
    private static final String KEY_DETACHED_SHELLS = "filer_detached_shells";
    private static final String PREFERRED_LOCAL_DOCUMENT_ID = "primary:Documents";
    private static final String PREFERRED_LOCAL_DOCUMENT_URI = "content://com.android.externalstorage.documents/document/primary%3ADocuments";
    private static final int REQUEST_LOCAL_TREE = 1002;
    private static final long FILE_PROGRESS_THRESHOLD_BYTES = 1024L * 1024L;
    private static final long PROGRESS_UPDATE_INTERVAL_MS = 100L;
    private static final long KEY_REPEAT_INITIAL_DELAY_MS = 350L;
    private static final long KEY_REPEAT_INTERVAL_MS = 80L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService attachDiscoveryExecutor = Executors.newSingleThreadExecutor();

    private TextView statusView;
    private TextView localPathView;
    private TextView remotePathView;
    private TextView transferProgressText;
    private ProgressBar transferProgressBar;
    private View transferProgressPanel;
    private View filerPanel;
    private View shellPanel;
    private FrameLayout shellViewContainer;
    private LinearLayout shellTabsContainer;
    private ListView localListView;
    private ListView remoteListView;
    private EditText remoteCommandInput;
    private Button runCommandButton;
    private Button refreshButton;
    private Button reconnectButton;
    private Button closeButton;
    private Button filerTabButton;
    private Button localPermissionButton;
    private Button localNewFileButton;
    private Button remoteNewFileButton;
    private Button shellKeyboardButton;
    private Button shellCopyButton;
    private Button shellPasteButton;
    private Button shellCtrlButton;
    private Button shellDetachButton;
    private Button shellAttachButton;
    private Button shellAltButton;
    private Button shellCtrlDButton;
    private Button shellPageUpButton;
    private Button shellLeftButton;
    private Button shellDownButton;
    private Button shellUpButton;
    private Button shellRightButton;
    private Button shellPageDownButton;
    private Button shellEscButton;
    private Button shellTabKeyButton;

    private ArrayAdapter<FileRow> localAdapter;
    private ArrayAdapter<FileRow> remoteAdapter;
    private final List<FileRow> localRows = new ArrayList<>();
    private final List<FileRow> remoteRows = new ArrayList<>();
    private final List<String> remoteCommandHistory = new ArrayList<>();
    private final List<DetachedShell> detachedShells = new ArrayList<>();

    private String host;
    private int port;
    private String username;
    private String password;
    private String privateKey;
    private String passphrase;

    private Uri localTreeUri;
    private String localRootDocumentId;
    private String localDirectoryDocumentId;
    private String remoteDirectory = ".";
    private Session sshSession;
    private ChannelSftp sftp;
    private volatile boolean connected;
    private boolean commandImeEnabled;
    private final List<ShellTab> shellTabs = new ArrayList<>();
    private ShellTab activeShellTab;
    private int shellTabCounter;
    private PopupWindow commandHistoryPopup;
    private final Handler keyRepeatHandler = new Handler(Looper.getMainLooper());
    private int repeatingKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    private final Runnable keyRepeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (repeatingKeyCode == KeyEvent.KEYCODE_UNKNOWN) {
                return;
            }
            sendShellKey(repeatingKeyCode);
            keyRepeatHandler.postDelayed(this, KEY_REPEAT_INTERVAL_MS);
        }
    };

    public static Intent newIntent(Context context, String host, int port, String username, String password, String privateKey, String passphrase) {
        Intent intent = new Intent(context, FilerActivity.class);
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
        setContentView(R.layout.activity_filer);

        readIntent();
        bindViews();
        KeyboardInsetHelper.keepBelowStatusBar(this, findViewById(R.id.filerTopBar));
        KeyboardInsetHelper.keepAboveKeyboard(this, findViewById(R.id.filerShellKeyBar), 300);
        loadRemoteCommandHistory();
        loadDetachedShells();
        setShellKeyButtonsEnabled(false);
        updateShellKeyButtonStates();
        initializeLocalDirectory();
        connectSftp();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadLocalDirectory();
        if (connected) {
            refreshRemoteDirectory();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_LOCAL_TREE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri treeUri = data.getData();
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0 || (flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
                Toast.makeText(this, text("Read/write permission is required. Select the Documents folder again.", "読み書き権限が必要です。Documentsフォルダを選び直してください。"), Toast.LENGTH_LONG).show();
                return;
            }
            if (!isPreferredLocalTree(treeUri)) {
                Toast.makeText(this, text("The local start folder is Documents. Select the Documents folder.", "ローカル初期フォルダはDocumentsにします。Documentsフォルダを選んでください。"), Toast.LENGTH_LONG).show();
                return;
            }
            try {
                getContentResolver().takePersistableUriPermission(treeUri, flags);
            } catch (SecurityException e) {
                Toast.makeText(this, text("Cannot save folder permission: ", "フォルダの読み書き権限を保存できません: ") + buildErrorMessage(e), Toast.LENGTH_LONG).show();
                return;
            }
            getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LOCAL_TREE_URI, treeUri.toString())
                    .apply();
            localTreeUri = treeUri;
            localRootDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
            localDirectoryDocumentId = localRootDocumentId;
            loadLocalDirectory();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRepeatingShellKey();
        if (commandHistoryPopup != null) {
            commandHistoryPopup.dismiss();
            commandHistoryPopup = null;
        }
        for (ShellTab tab : shellTabs) {
            saveDetachedShell(tab);
            tab.session.release();
        }
        shellTabs.clear();
        activeShellTab = null;
        disconnectSftp();
        executor.shutdownNow();
        attachDiscoveryExecutor.shutdownNow();
    }

    private void readIntent() {
        Intent intent = getIntent();
        host = intent.getStringExtra(EXTRA_HOST);
        port = intent.getIntExtra(EXTRA_PORT, 22);
        username = intent.getStringExtra(EXTRA_USERNAME);
        password = intent.getStringExtra(EXTRA_PASSWORD);
        privateKey = intent.getStringExtra(EXTRA_PRIVATE_KEY);
        passphrase = intent.getStringExtra(EXTRA_PASSPHRASE);
    }

    private void bindViews() {
        statusView = findViewById(R.id.filerStatus);
        localPathView = findViewById(R.id.localPathValue);
        remotePathView = findViewById(R.id.remotePathValue);
        transferProgressPanel = findViewById(R.id.transferProgressPanel);
        transferProgressText = findViewById(R.id.transferProgressText);
        transferProgressBar = findViewById(R.id.transferProgressBar);
        filerPanel = findViewById(R.id.filerPanel);
        shellPanel = findViewById(R.id.shellPanel);
        shellViewContainer = findViewById(R.id.shellViewContainer);
        shellTabsContainer = findViewById(R.id.shellTabsContainer);
        localListView = findViewById(R.id.localList);
        remoteListView = findViewById(R.id.remoteList);
        remoteCommandInput = findViewById(R.id.remoteCommandInput);
        runCommandButton = findViewById(R.id.runCommandButton);
        refreshButton = findViewById(R.id.refreshButton);
        reconnectButton = findViewById(R.id.reconnectButton);
        closeButton = findViewById(R.id.closeButton);
        filerTabButton = findViewById(R.id.filerTabButton);
        localPermissionButton = findViewById(R.id.localPermissionButton);
        localNewFileButton = findViewById(R.id.localNewFileButton);
        remoteNewFileButton = findViewById(R.id.remoteNewFileButton);
        shellKeyboardButton = findViewById(R.id.filerShellKeyboardButton);
        shellCopyButton = findViewById(R.id.filerShellCopyButton);
        shellPasteButton = findViewById(R.id.filerShellPasteButton);
        shellCtrlButton = findViewById(R.id.filerShellCtrlButton);
        shellDetachButton = findViewById(R.id.filerShellDetachButton);
        shellAttachButton = findViewById(R.id.filerShellAttachButton);
        shellAltButton = findViewById(R.id.filerShellAltButton);
        shellCtrlDButton = findViewById(R.id.filerShellCtrlDButton);
        shellPageUpButton = findViewById(R.id.filerShellPageUpButton);
        shellLeftButton = findViewById(R.id.filerShellLeftButton);
        shellDownButton = findViewById(R.id.filerShellDownButton);
        shellUpButton = findViewById(R.id.filerShellUpButton);
        shellRightButton = findViewById(R.id.filerShellRightButton);
        shellPageDownButton = findViewById(R.id.filerShellPageDownButton);
        shellEscButton = findViewById(R.id.filerShellEscButton);
        shellTabKeyButton = findViewById(R.id.filerShellTabKeyButton);

        localAdapter = new ArrayAdapter<>(this, R.layout.list_item_file, R.id.fileItemText, localRows);
        remoteAdapter = new ArrayAdapter<>(this, R.layout.list_item_file, R.id.fileItemText, remoteRows);
        localListView.setAdapter(localAdapter);
        remoteListView.setAdapter(remoteAdapter);

        localListView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= localRows.size()) {
                return;
            }
            openLocalRow(localRows.get(position));
        });
        localListView.setOnItemLongClickListener((parent, view, position, id) -> {
            FileRow row = localRows.get(position);
            if (!row.parent) {
                showLocalActionDialog(row);
            }
            return true;
        });
        remoteListView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= remoteRows.size()) {
                return;
            }
            openRemoteRow(remoteRows.get(position));
        });
        remoteListView.setOnItemLongClickListener((parent, view, position, id) -> {
            showRemoteActionDialog(remoteRows.get(position));
            return true;
        });
        refreshButton.setOnClickListener(view -> {
            loadLocalDirectory();
            refreshRemoteDirectory();
        });
        reconnectButton.setOnClickListener(view -> reconnectSftp());
        runCommandButton.setOnClickListener(view -> runRemoteCommand());
        remoteCommandInput.setOnClickListener(view -> showRemoteCommandHistoryMenu());
        configureCommandInputIme(remoteCommandInput);
        remoteCommandInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus && shellPanel.getVisibility() != View.VISIBLE) {
                updateShellKeyButtonStates();
            }
        });
        remoteCommandInput.setOnEditorActionListener((textView, actionId, keyEvent) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                runRemoteCommand();
                return true;
            }
            if (keyEvent != null && keyEvent.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                if (keyEvent.getAction() == KeyEvent.ACTION_UP) {
                    runRemoteCommand();
                }
                return true;
            }
            return false;
        });
        closeButton.setOnClickListener(view -> finish());
        filerTabButton.setOnClickListener(view -> showFilerTab());
        localPermissionButton.setOnClickListener(view -> showLocalDirectoryPermissionDialog());
        localNewFileButton.setOnClickListener(view -> showCreateLocalMenu());
        remoteNewFileButton.setOnClickListener(view -> showCreateRemoteMenu());
        shellKeyboardButton.setOnClickListener(view -> toggleShellImeMode());
        shellCopyButton.setOnClickListener(view -> copyShellSelection());
        shellPasteButton.setOnClickListener(view -> pasteShellClipboard());
        shellCtrlButton.setOnClickListener(view -> {
            ShellTab tab = activeShellTab;
            if (tab != null) tab.view.setCtrlModifier(!tab.ctrlLocked);
        });
        shellDetachButton.setOnClickListener(view -> detachActiveShellTab());
        shellAttachButton.setOnClickListener(view -> showAttachDetachedShellDialog());
        shellAltButton.setOnClickListener(view -> {
            ShellTab tab = activeShellTab;
            if (tab != null) tab.view.setAltModifier(!tab.altLocked);
        });
        shellCtrlDButton.setOnClickListener(view -> {
            ShellTab tab = activeShellTab;
            if (tab != null) tab.session.sendCtrlD();
        });
        shellPageUpButton.setOnClickListener(view -> sendShellKey(KeyEvent.KEYCODE_PAGE_UP));
        setRepeatingShellKeyListener(shellLeftButton, KeyEvent.KEYCODE_DPAD_LEFT);
        setRepeatingShellKeyListener(shellDownButton, KeyEvent.KEYCODE_DPAD_DOWN);
        setRepeatingShellKeyListener(shellUpButton, KeyEvent.KEYCODE_DPAD_UP);
        setRepeatingShellKeyListener(shellRightButton, KeyEvent.KEYCODE_DPAD_RIGHT);
        shellPageDownButton.setOnClickListener(view -> sendShellKey(KeyEvent.KEYCODE_PAGE_DOWN));
        shellEscButton.setOnClickListener(view -> {
            ShellTab tab = activeShellTab;
            if (tab != null) tab.session.sendEscape();
        });
        shellTabKeyButton.setOnClickListener(view -> {
            ShellTab tab = activeShellTab;
            if (tab != null) tab.session.sendTab();
        });
        setShellKeyButtonsEnabled(false);
        updateShellKeyButtonStates();
        showFilerTab();
    }

    private void initializeLocalDirectory() {
        SharedPreferences preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE);
        String savedTreeUri = preferences.getString(KEY_LOCAL_TREE_URI, null);
        if (!TextUtils.isEmpty(savedTreeUri)) {
            localTreeUri = Uri.parse(savedTreeUri);
            if (!isPreferredLocalTree(localTreeUri) || !hasPersistedLocalPermission(localTreeUri)) {
                clearLocalDirectoryAccess();
                localPathView.setText(text("Documents folder permission is required. Tap Allow.", "Documentsフォルダの許可が必要です。許可ボタンを押してください"));
                return;
            }
            localRootDocumentId = DocumentsContract.getTreeDocumentId(localTreeUri);
            if (TextUtils.isEmpty(localDirectoryDocumentId)) {
                localDirectoryDocumentId = localRootDocumentId;
            }
            loadLocalDirectory();
        } else {
            localPathView.setText(text("Documents folder permission is required. Tap Allow.", "Documentsフォルダの許可が必要です。許可ボタンを押してください"));
        }
    }

    private void showLocalDirectoryPermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle(text("Documents folder permission", "Documentsフォルダの許可"))
                .setMessage(text("Grant read/write access to the Android Documents folder for filer mode. On the next screen, select Documents and tap Use this folder.", "ファイラーモードのローカル初期ディレクトリとして、AndroidのDocumentsフォルダへの読み書き権限を許可してください。次の画面でDocumentsフォルダを選んで「このフォルダを使用」を押してください。"))
                .setPositiveButton(text("Allow", "許可する"), (dialog, which) -> requestLocalDirectoryAccess())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showCreateLocalMenu() {
        if (localTreeUri == null || TextUtils.isEmpty(localDirectoryDocumentId)) {
            Toast.makeText(this, text("Grant Documents folder read/write permission first.", "先にDocumentsフォルダの読み書き権限を許可してください。"), Toast.LENGTH_SHORT).show();
            return;
        }
        String[] actions = new String[]{getString(R.string.action_file), getString(R.string.action_directory)};
        new AlertDialog.Builder(this)
                .setTitle(text("Create locally", "ローカルに新規作成"))
                .setItems(actions, (dialog, which) -> showCreateLocalDialog(which == 0))
                .show();
    }

    private void loadRemoteCommandHistory() {
        remoteCommandHistory.clear();
        String historyJson = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                .getString(KEY_REMOTE_COMMAND_HISTORY, "[]");
        try {
            JSONArray historyArray = new JSONArray(historyJson);
            for (int i = 0; i < historyArray.length(); i++) {
                String command = historyArray.optString(i);
                if (!TextUtils.isEmpty(command) && !remoteCommandHistory.contains(command)) {
                    remoteCommandHistory.add(command);
                }
            }
        } catch (Exception ignored) {
            remoteCommandHistory.clear();
        }
    }

    private void addRemoteCommandHistory(String command) {
        String normalizedCommand = command == null ? "" : command.trim();
        if (TextUtils.isEmpty(normalizedCommand)) {
            return;
        }
        remoteCommandHistory.remove(normalizedCommand);
        remoteCommandHistory.add(0, normalizedCommand);
        while (remoteCommandHistory.size() > 20) {
            remoteCommandHistory.remove(remoteCommandHistory.size() - 1);
        }
        JSONArray historyArray = new JSONArray();
        for (String historyCommand : remoteCommandHistory) {
            historyArray.put(historyCommand);
        }
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_REMOTE_COMMAND_HISTORY, historyArray.toString())
                .apply();
    }

    private void showRemoteCommandHistoryMenu() {
        if (remoteCommandHistory.isEmpty()) {
            return;
        }
        if (commandHistoryPopup != null && commandHistoryPopup.isShowing()) {
            commandHistoryPopup.dismiss();
            return;
        }

        ListView historyList = new ListView(this);
        historyList.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, remoteCommandHistory));
        historyList.setDividerHeight(1);
        int width = Math.max(remoteCommandInput.getWidth(), dp(220));
        int height = Math.min(dp(280), dp(48) * remoteCommandHistory.size());
        commandHistoryPopup = new PopupWindow(historyList, width, height, true);
        commandHistoryPopup.setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        commandHistoryPopup.setOutsideTouchable(true);
        historyList.setOnItemClickListener((parent, view, position, id) -> {
            String command = remoteCommandHistory.get(position);
            commandHistoryPopup.dismiss();
            remoteCommandInput.setText(command);
            remoteCommandInput.setSelection(command.length());
            runRemoteCommand();
        });
        commandHistoryPopup.showAsDropDown(remoteCommandInput);
    }

    private void loadDetachedShells() {
        detachedShells.clear();
        String shellsJson = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                .getString(KEY_DETACHED_SHELLS, "[]");
        try {
            JSONArray shellsArray = new JSONArray(shellsJson);
            for (int i = 0; i < shellsArray.length(); i++) {
                JSONObject object = shellsArray.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                DetachedShell shell = DetachedShell.fromJson(object);
                if (shell == null || !matchesCurrentConnection(shell) || containsDetachedShell(shell.tmuxSessionName)) {
                    continue;
                }
                detachedShells.add(shell);
            }
        } catch (Exception ignored) {
            detachedShells.clear();
        }
    }

    private void saveDetachedShell(ShellTab tab) {
        if (tab == null || TextUtils.isEmpty(tab.tmuxSessionName)) {
            return;
        }
        DetachedShell shell = new DetachedShell(
                host,
                port,
                username,
                tab.tmuxSessionName,
                tab.title,
                tab.remoteDirectory
        );
        detachedShells.removeIf(existing -> shell.tmuxSessionName.equals(existing.tmuxSessionName));
        detachedShells.add(0, shell);
        while (detachedShells.size() > 30) {
            detachedShells.remove(detachedShells.size() - 1);
        }
        saveDetachedShells();
        setShellKeyButtonsEnabled(activeShellTab != null && activeShellTab.connected);
        updateShellKeyButtonStates();
    }

    private void saveDetachedShells() {
        JSONArray shellsArray = new JSONArray();
        for (DetachedShell shell : detachedShells) {
            shellsArray.put(shell.toJson());
        }
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_DETACHED_SHELLS, shellsArray.toString())
                .apply();
    }

    private boolean matchesCurrentConnection(DetachedShell shell) {
        return shell.port == port
                && TextUtils.equals(shell.host, host)
                && TextUtils.equals(shell.username, username);
    }

    private boolean containsDetachedShell(String tmuxSessionName) {
        for (DetachedShell shell : detachedShells) {
            if (TextUtils.equals(shell.tmuxSessionName, tmuxSessionName)) {
                return true;
            }
        }
        return false;
    }

    private void showCreateLocalDialog(boolean file) {
        EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setHint(file ? text("File name", "ファイル名") : text("Directory name", "ディレクトリ名"));
        nameInput.setPadding(32, 16, 32, 16);
        new AlertDialog.Builder(this)
                .setTitle(file ? text("Create local file", "ローカルファイルを新規作成") : text("Create local directory", "ローカルディレクトリを新規作成"))
                .setView(nameInput)
                .setPositiveButton(text("Create", "作成"), (dialog, which) -> {
                    if (file) {
                        createLocalFile(nameInput.getText().toString());
                    } else {
                        createLocalDirectory(nameInput.getText().toString());
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void createLocalFile(String rawName) {
        String name = validateNewName(rawName);
        if (name == null) {
            return;
        }
        if (findLocalChild(localDirectoryDocumentId, name) != null) {
            Toast.makeText(this, text("An item with the same name already exists.", "同じ名前のファイルが既にあります。"), Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(localTreeUri, localDirectoryDocumentId);
            Uri fileUri = DocumentsContract.createDocument(getContentResolver(), parentUri, "text/plain", name);
            if (fileUri == null) {
                Toast.makeText(this, text("Cannot create the file.", "ファイルを作成できません。"), Toast.LENGTH_SHORT).show();
                return;
            }
            loadLocalDirectory();
            Toast.makeText(this, text("Created: ", "作成しました: ") + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, text("Create failed: ", "作成に失敗しました: ") + buildErrorMessage(e), Toast.LENGTH_LONG).show();
        }
    }

    private void createLocalDirectory(String rawName) {
        String name = validateNewName(rawName);
        if (name == null) {
            return;
        }
        if (findLocalChild(localDirectoryDocumentId, name) != null) {
            Toast.makeText(this, text("An item with the same name already exists.", "同じ名前のファイルが既にあります。"), Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(localTreeUri, localDirectoryDocumentId);
            Uri directoryUri = DocumentsContract.createDocument(getContentResolver(), parentUri, Document.MIME_TYPE_DIR, name);
            if (directoryUri == null) {
                Toast.makeText(this, text("Cannot create the directory.", "ディレクトリを作成できません。"), Toast.LENGTH_SHORT).show();
                return;
            }
            loadLocalDirectory();
            Toast.makeText(this, text("Created: ", "作成しました: ") + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, text("Create failed: ", "作成に失敗しました: ") + buildErrorMessage(e), Toast.LENGTH_LONG).show();
        }
    }

    private void requestLocalDirectoryAccess() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(PREFERRED_LOCAL_DOCUMENT_URI));
        }
        startActivityForResult(intent, REQUEST_LOCAL_TREE);
    }

    private boolean isPreferredLocalTree(Uri treeUri) {
        try {
            return PREFERRED_LOCAL_DOCUMENT_ID.equals(DocumentsContract.getTreeDocumentId(treeUri));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasPersistedLocalPermission(Uri treeUri) {
        for (UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
            if (permission.getUri().equals(treeUri) && permission.isReadPermission() && permission.isWritePermission()) {
                return true;
            }
        }
        return false;
    }

    private void clearLocalDirectoryAccess() {
        localTreeUri = null;
        localRootDocumentId = null;
        localDirectoryDocumentId = null;
        localRows.clear();
        if (localAdapter != null) {
            localAdapter.notifyDataSetChanged();
        }
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                .edit()
                .remove(KEY_LOCAL_TREE_URI)
                .apply();
    }

    private void connectSftp() {
        statusView.setText(text("Connecting SSH...", "SSH接続中..."));
        executor.execute(() -> {
            connectSftpInternal();
        });
    }

    private void reconnectSftp() {
        statusView.setText(text("Reconnecting SSH...", "SSH再接続中..."));
        executor.execute(() -> {
            disconnectSftp();
            runOnUiThread(() -> {
                remoteRows.clear();
                remotePathView.setText("");
                remoteAdapter.notifyDataSetChanged();
            });
            connectSftpInternal();
        });
    }

    private void connectSftpInternal() {
            try {
                setStatus(text("Connecting SSH...", "SSH接続中..."));
                Session session = SshSessionFactory.connect(host, port, username, password, privateKey, passphrase);
                setStatus(text("Starting SFTP...", "SFTP開始中..."));
                ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
                channel.connect(5_000);
                sshSession = session;
                sftp = channel;
                setStatus(text("Loading directory...", "ディレクトリ取得中..."));
                remoteDirectory = channel.pwd();
                connected = true;
                List<FileRow> rows = loadRemoteRows(channel, remoteDirectory);
                runOnUiThread(() -> {
                    statusView.setText(R.string.status_connected);
                    remoteRows.clear();
                    remoteRows.addAll(rows);
                    remotePathView.setText(remoteDirectory);
                    remoteAdapter.notifyDataSetChanged();
                    discoverDetachedShells(false);
                });
            } catch (Exception e) {
                disconnectSftp();
                runOnUiThread(() -> {
                    statusView.setText(R.string.status_disconnected);
                    Toast.makeText(this, text("SFTP connection failed: ", "SFTP接続に失敗しました: ") + buildErrorMessage(e), Toast.LENGTH_LONG).show();
                });
            }
    }

    private void setStatus(String message) {
        runOnUiThread(() -> statusView.setText(message));
    }

    private void disconnectSftp() {
        connected = false;
        if (sftp != null) {
            sftp.disconnect();
            sftp = null;
        }
        if (sshSession != null) {
            sshSession.disconnect();
            sshSession = null;
        }
    }

    private ShellTab createShellTab(String command) {
        ShellTab tab = new ShellTab();
        tab.id = ++shellTabCounter;
        tab.tmuxSessionName = buildTmuxSessionName(tab.id);
        tab.remoteDirectory = remoteDirectory;
        tab.title = buildShellTabTitle(tab.id, tab.remoteDirectory);
        tab.pendingCommand = buildShellCommand(command);
        setupShellTab(tab);
        return tab;
    }

    private ShellTab createAttachedShellTab(DetachedShell detachedShell) {
        ShellTab tab = new ShellTab();
        tab.id = ++shellTabCounter;
        tab.tmuxSessionName = detachedShell.tmuxSessionName;
        tab.remoteDirectory = detachedShell.remoteDirectory;
        tab.title = buildShellTabTitle(tab.id, tab.remoteDirectory);
        setupShellTab(tab);
        return tab;
    }

    private void setupShellTab(ShellTab tab) {
        tab.view = new SshTerminalView(this);
        tab.view.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        tab.view.setVisibility(View.GONE);
        tab.view.setImeModeEnabled(tab.imeEnabled);

        tab.button = new Button(this);
        tab.button.setText(tab.title);
        tab.button.setTextSize(11f);
        tab.button.setMinHeight(0);
        tab.button.setMinWidth(0);
        tab.button.setPadding(dp(10), 0, dp(10), 0);
        tab.button.setBackgroundResource(R.drawable.button_secondary);
        tab.button.setTextColor(getColor(R.color.button_text_light));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        buttonParams.setMarginEnd(dp(6));
        tab.button.setLayoutParams(buttonParams);
        tab.button.setOnClickListener(view -> showShellTab(tab));
        tab.button.setOnLongClickListener(view -> {
            showCloseShellTabDialog(tab);
            return true;
        });

        tab.session = new SshTerminalSession(this, new SshTerminalSession.Client() {
            @Override
            public void onConnected() {
                runOnUiThread(() -> {
                    tab.connected = true;
                    tab.detached = false;
                    if (activeShellTab == tab) {
                        setShellKeyButtonsEnabled(true);
                        if (shellPanel.getVisibility() == View.VISIBLE) {
                            tab.view.requestFocus();
                            tab.view.onScreenUpdated();
                        }
                    }
                    sendPendingShellCommand(tab);
                });
            }

            @Override
            public void onScreenUpdated() {
                runOnUiThread(() -> tab.view.onScreenUpdated());
            }

            @Override
            public void onTitleChanged(String title) {
            }

            @Override
            public void onConnectionError(String message) {
                runOnUiThread(() -> {
                    tab.connected = false;
                    if (activeShellTab == tab) {
                        setShellKeyButtonsEnabled(false);
                    }
                    Toast.makeText(FilerActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onDisconnected(String message) {
                runOnUiThread(() -> {
                    tab.connected = false;
                    if (!tab.closed && !tab.detached) {
                        removeShellTab(tab, false);
                    } else if (activeShellTab == tab) {
                        setShellKeyButtonsEnabled(false);
                        updateShellKeyButtonStates();
                    }
                    Toast.makeText(FilerActivity.this, message, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onCopyToClipboard(String text) {
                ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (manager != null) {
                    manager.setPrimaryClip(ClipData.newPlainText("sshclientjr", text));
                    Toast.makeText(FilerActivity.this, R.string.toast_copied, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public String onRequestPaste() {
                ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (manager == null || manager.getPrimaryClip() == null || manager.getPrimaryClip().getItemCount() == 0) {
                    return null;
                }
                CharSequence text = manager.getPrimaryClip().getItemAt(0).coerceToText(FilerActivity.this);
                return text == null ? null : text.toString();
            }

            @Override
            public void onBell() {
            }
        });
        tab.view.attachSession(tab.session);
        tab.view.setModifierListener((ctrlEnabled, altEnabled) -> runOnUiThread(() -> {
            tab.ctrlLocked = ctrlEnabled;
            tab.altLocked = altEnabled;
            updateShellKeyButtonStates();
        }));
        tab.view.setKeyboardListener(imeModeEnabled -> runOnUiThread(() -> {
            if (activeShellTab != tab || shellPanel.getVisibility() != View.VISIBLE) {
                return;
            }
            KeyboardInsetHelper.setManualKeyboardVisible(this, findViewById(R.id.filerShellKeyBar), true, 300);
        }));
        shellTabs.add(tab);
        shellTabsContainer.addView(tab.button);
        shellViewContainer.addView(tab.view);
    }

    private void showCloseShellTabDialog(ShellTab tab) {
        new AlertDialog.Builder(this)
                .setTitle(text("Close shell tab", "シェルタブを閉じる"))
                .setMessage(text("Close ", "") + tab.title + text("? The server-side tmux session will remain attachable.", " を閉じますか？サーバー側のtmuxセッションは残してAttachできるようにします。"))
                .setPositiveButton(text("Close", "閉じる"), (dialog, which) -> detachAndRemoveShellTab(tab))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void detachAndRemoveShellTab(ShellTab tab) {
        if (tab == null || tab.closed) {
            return;
        }
        saveDetachedShell(tab);
        removeShellTab(tab, true);
        Toast.makeText(this, text("Detached: ", "デタッチしました: ") + tab.title, Toast.LENGTH_SHORT).show();
    }

    private void removeShellTab(ShellTab tab, boolean releaseSession) {
        if (tab == null || tab.closed) {
            return;
        }
        int index = shellTabs.indexOf(tab);
        if (index < 0) {
            return;
        }

        tab.closed = true;
        if (releaseSession) {
            tab.session.release();
        }
        shellTabs.remove(index);
        shellTabsContainer.removeView(tab.button);
        shellViewContainer.removeView(tab.view);

        if (activeShellTab == tab) {
            activeShellTab = null;
            if (shellTabs.isEmpty()) {
                showFilerTab();
            } else {
                activeShellTab = shellTabs.get(Math.min(index, shellTabs.size() - 1));
                if (shellPanel.getVisibility() == View.VISIBLE) {
                    showShellTab(activeShellTab);
                } else {
                    updateShellTabButtons();
                    setShellKeyButtonsEnabled(activeShellTab.connected);
                    updateShellKeyButtonStates();
                }
            }
        } else {
            updateShellTabButtons();
        }
    }

    private void showFilerTab() {
        filerPanel.setVisibility(View.VISIBLE);
        shellPanel.setVisibility(View.GONE);
        KeyboardInsetHelper.setManualKeyboardVisible(this, findViewById(R.id.filerShellKeyBar), false, 300);
        filerTabButton.setBackgroundResource(R.drawable.button_primary);
        filerTabButton.setTextColor(getColor(R.color.button_text));
        updateShellTabButtons();
        setShellKeyButtonsEnabled(activeShellTab != null && activeShellTab.connected);
        updateShellKeyButtonStates();
    }

    private void showShellTab() {
        if (activeShellTab == null) {
            if (shellTabs.isEmpty()) {
                showFilerTab();
                return;
            }
            activeShellTab = shellTabs.get(shellTabs.size() - 1);
        }
        showShellTab(activeShellTab);
    }

    private void showShellTab(ShellTab tab) {
        if (tab == null) {
            showFilerTab();
            return;
        }
        activeShellTab = tab;
        filerPanel.setVisibility(View.GONE);
        shellPanel.setVisibility(View.VISIBLE);
        filerTabButton.setBackgroundResource(R.drawable.button_secondary);
        filerTabButton.setTextColor(getColor(R.color.button_text_light));
        for (ShellTab shellTab : shellTabs) {
            shellTab.view.setVisibility(shellTab == tab ? View.VISIBLE : View.GONE);
        }
        updateShellTabButtons();
        setShellKeyButtonsEnabled(tab.connected);
        updateShellKeyButtonStates();
        KeyboardInsetHelper.setManualKeyboardVisible(this, findViewById(R.id.filerShellKeyBar), tab.imeEnabled, 300);
        tab.view.requestFocus();
        tab.view.onScreenUpdated();
    }

    private void updateShellTabButtons() {
        for (ShellTab tab : shellTabs) {
            boolean selected = shellPanel.getVisibility() == View.VISIBLE && activeShellTab == tab;
            tab.button.setBackgroundResource(selected ? R.drawable.button_primary : R.drawable.button_secondary);
            tab.button.setTextColor(getColor(selected ? R.color.button_text : R.color.button_text_light));
        }
    }

    private void setShellKeyButtonsEnabled(boolean enabled) {
        ShellTab tab = activeShellTab;
        boolean filerMode = shellPanel.getVisibility() != View.VISIBLE;
        shellKeyboardButton.setEnabled(enabled || filerMode);
        shellCopyButton.setEnabled(enabled);
        shellPasteButton.setEnabled(enabled);
        shellCtrlButton.setEnabled(enabled);
        shellDetachButton.setEnabled(enabled);
        shellAttachButton.setEnabled(!detachedShells.isEmpty());
        shellAltButton.setEnabled(enabled);
        shellCtrlDButton.setEnabled(enabled);
        shellPageUpButton.setEnabled(enabled);
        shellLeftButton.setEnabled(enabled);
        shellDownButton.setEnabled(enabled);
        shellUpButton.setEnabled(enabled);
        shellRightButton.setEnabled(enabled);
        shellPageDownButton.setEnabled(enabled);
        shellEscButton.setEnabled(enabled);
        shellTabKeyButton.setEnabled(enabled);
        if (!enabled) {
            stopRepeatingShellKey();
        }
    }

    private void toggleShellImeMode() {
        if (shellPanel.getVisibility() != View.VISIBLE) {
            toggleCommandImeMode(remoteCommandInput);
            return;
        }
        ShellTab tab = activeShellTab;
        if (tab == null) {
            return;
        }
        tab.imeEnabled = !tab.imeEnabled;
        tab.view.setImeModeEnabled(tab.imeEnabled);
        KeyboardInsetHelper.setManualKeyboardVisible(this, findViewById(R.id.filerShellKeyBar), tab.imeEnabled, 300);
        tab.view.requestFocus();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) {
            imm.restartInput(tab.view);
            imm.showSoftInput(tab.view, InputMethodManager.SHOW_IMPLICIT);
        }
        updateShellKeyButtonStates();
    }

    private void updateShellKeyButtonStates() {
        ShellTab tab = activeShellTab;
        if (shellPanel.getVisibility() == View.VISIBLE) {
            shellKeyboardButton.setText(tab != null && tab.imeEnabled ? "IME*" : "IME");
        } else {
            shellKeyboardButton.setText(commandImeEnabled ? "IME*" : "IME");
        }
        shellCtrlButton.setText(tab != null && tab.ctrlLocked ? "Ctrl*" : "Ctrl");
        shellAltButton.setText(tab != null && tab.altLocked ? "Alt*" : "Alt");
    }

    private void sendShellKey(int keyCode) {
        ShellTab tab = activeShellTab;
        if (tab != null) {
            tab.view.handleKeyCode(keyCode, 0);
        }
    }

    private void setRepeatingShellKeyListener(Button button, int keyCode) {
        button.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!view.isEnabled()) {
                        return true;
                    }
                    startRepeatingShellKey(keyCode);
                    return true;
                case MotionEvent.ACTION_UP:
                    view.performClick();
                    stopRepeatingShellKey();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    stopRepeatingShellKey();
                    return true;
                default:
                    return true;
            }
        });
    }

    private void startRepeatingShellKey(int keyCode) {
        stopRepeatingShellKey();
        repeatingKeyCode = keyCode;
        sendShellKey(keyCode);
        keyRepeatHandler.postDelayed(keyRepeatRunnable, KEY_REPEAT_INITIAL_DELAY_MS);
    }

    private void stopRepeatingShellKey() {
        repeatingKeyCode = KeyEvent.KEYCODE_UNKNOWN;
        keyRepeatHandler.removeCallbacks(keyRepeatRunnable);
    }

    private void copyShellSelection() {
        ShellTab tab = activeShellTab;
        if (tab == null) {
            return;
        }
        String selectedText = tab.view.getSelectedText();
        if (TextUtils.isEmpty(selectedText)) {
            Toast.makeText(this, R.string.toast_select_range, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(ClipData.newPlainText("sshclientjr", selectedText));
            tab.view.clearSelection();
            Toast.makeText(this, R.string.toast_selection_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void pasteShellClipboard() {
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
        ShellTab tab = activeShellTab;
        if (tab != null) {
            tab.view.pasteText(text.toString());
        }
    }

    private void detachActiveShellTab() {
        ShellTab tab = activeShellTab;
        if (tab == null || !tab.connected) {
            return;
        }
        detachAndRemoveShellTab(tab);
    }

    private void showAttachDetachedShellDialog() {
        discoverDetachedShells(true);
    }

    private void discoverDetachedShells(boolean showDialog) {
        if (showDialog) {
            setStatus(text("Loading attach candidates...", "Attach候補取得中..."));
        }
        attachDiscoveryExecutor.execute(() -> {
            List<DetachedShell> discovered = new ArrayList<>();
            Exception discoverError = null;
            Session session = null;
            ChannelExec channel = null;
            try {
                session = SshSessionFactory.connect(host, port, username, password, privateKey, passphrase);
                channel = (ChannelExec) session.openChannel("exec");
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ByteArrayOutputStream errorOutput = new ByteArrayOutputStream();
                channel.setCommand(buildDiscoverDetachedShellsCommand());
                channel.setErrStream(errorOutput);
                InputStream input = channel.getInputStream();
                channel.connect(5_000);
                readCommandOutput(channel, input, output);
                discovered = parseDiscoveredDetachedShells(output.toString(StandardCharsets.UTF_8.name()));
            } catch (Exception e) {
                discoverError = e;
            } finally {
                if (channel != null) {
                    channel.disconnect();
                }
                if (session != null) {
                    session.disconnect();
                }
            }

            boolean discoverySucceeded = discoverError == null;
            List<DetachedShell> finalDiscovered = discovered;
            Exception finalDiscoverError = discoverError;
            runOnUiThread(() -> {
                statusView.setText(connected ? getString(R.string.status_connected) : getString(R.string.status_disconnected));
                if (discoverySucceeded) {
                    reconcileDetachedShells(finalDiscovered);
                    saveDetachedShells();
                    setShellKeyButtonsEnabled(activeShellTab != null && activeShellTab.connected);
                    updateShellKeyButtonStates();
                } else if (showDialog) {
                    Toast.makeText(this, text("Cannot load shell candidates from the server: ", "サーバー上のシェル候補を取得できません: ") + buildErrorMessage(finalDiscoverError), Toast.LENGTH_LONG).show();
                    return;
                }
                if (showDialog) {
                    showAttachDetachedShellList();
                }
            });
        });
    }

    private void showAttachDetachedShellList() {
        if (detachedShells.isEmpty()) {
            Toast.makeText(this, text("No attachable shells.", "アタッチできるシェルがありません。"), Toast.LENGTH_SHORT).show();
            return;
        }
        if (detachedShells.size() == 1) {
            attachDetachedShell(detachedShells.get(0));
            return;
        }
        String[] labels = new String[detachedShells.size()];
        for (int i = 0; i < detachedShells.size(); i++) {
            labels[i] = detachedShells.get(i).getDisplayLabel();
        }
        new AlertDialog.Builder(this)
                .setTitle(text("Shell to attach", "アタッチするシェル"))
                .setItems(labels, (dialog, which) -> attachDetachedShell(detachedShells.get(which)))
                .show();
    }

    private void reconcileDetachedShells(List<DetachedShell> discovered) {
        Set<String> liveSessionNames = new HashSet<>();
        for (DetachedShell shell : discovered) {
            if (shell == null || !matchesCurrentConnection(shell) || TextUtils.isEmpty(shell.tmuxSessionName)) {
                continue;
            }
            liveSessionNames.add(shell.tmuxSessionName);
        }
        detachedShells.removeIf(shell -> matchesCurrentConnection(shell) && !liveSessionNames.contains(shell.tmuxSessionName));
        for (DetachedShell shell : discovered) {
            if (shell == null || !matchesCurrentConnection(shell) || TextUtils.isEmpty(shell.tmuxSessionName)) {
                continue;
            }
            detachedShells.removeIf(existing -> TextUtils.equals(existing.tmuxSessionName, shell.tmuxSessionName));
            detachedShells.add(shell);
        }
    }

    private List<DetachedShell> parseDiscoveredDetachedShells(String output) {
        List<DetachedShell> shells = new ArrayList<>();
        if (TextUtils.isEmpty(output)) {
            return shells;
        }
        String[] lines = output.split("\\r?\\n");
        for (String line : lines) {
            if (TextUtils.isEmpty(line)) {
                continue;
            }
            String[] parts = line.split("\t", 3);
            String sessionName = parts[0].trim();
            if (TextUtils.isEmpty(sessionName) || !sessionName.startsWith("sshclientjr_")) {
                continue;
            }
            if (containsDetachedShell(shells, sessionName)) {
                continue;
            }
            String directory = parts.length > 2 && !TextUtils.isEmpty(parts[2]) ? parts[2].trim() : "?";
            String title = buildShellTabTitle(0, directory);
            shells.add(new DetachedShell(host, port, username, sessionName, title, directory));
        }
        return shells;
    }

    private boolean containsDetachedShell(List<DetachedShell> shells, String tmuxSessionName) {
        for (DetachedShell shell : shells) {
            if (TextUtils.equals(shell.tmuxSessionName, tmuxSessionName)) {
                return true;
            }
        }
        return false;
    }

    private void readCommandOutput(ChannelExec channel, InputStream input, ByteArrayOutputStream output) throws Exception {
        byte[] buffer = new byte[4096];
        while (true) {
            while (input.available() > 0) {
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                output.write(buffer, 0, read);
            }
            if (channel.isClosed()) {
                while (input.available() > 0) {
                    int read = input.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    output.write(buffer, 0, read);
                }
                return;
            }
            Thread.sleep(50L);
        }
    }

    private void attachDetachedShell(DetachedShell detachedShell) {
        if (detachedShell == null) {
            return;
        }
        detachedShells.remove(detachedShell);
        saveDetachedShells();
        ShellTab tab = createAttachedShellTab(detachedShell);
        showShellTab(tab);
        setStatus(text("Reconnecting shell...", "シェル再接続中..."));
        tab.session.connectWithCommand(host, port, username, password, privateKey, passphrase, buildTmuxAttachCommand(tab));
    }

    private void loadLocalDirectory() {
        if (localTreeUri == null || TextUtils.isEmpty(localDirectoryDocumentId)) {
            return;
        }
        List<FileRow> rows = new ArrayList<>();
        String parentDocumentId = getParentDocumentId(localDirectoryDocumentId);
        if (!TextUtils.isEmpty(parentDocumentId) && !localDirectoryDocumentId.equals(localRootDocumentId)) {
            rows.add(FileRow.parent(parentDocumentId));
        }

        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(localTreeUri, localDirectoryDocumentId);
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(
                    childrenUri,
                    new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE},
                    null,
                    null,
                    null
            );
            if (cursor != null) {
                int idIndex = cursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID);
                int nameIndex = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME);
                int mimeIndex = cursor.getColumnIndex(Document.COLUMN_MIME_TYPE);
                int sizeIndex = cursor.getColumnIndex(Document.COLUMN_SIZE);
                while (cursor.moveToNext()) {
                    String documentId = cursor.getString(idIndex);
                    String name = cursor.getString(nameIndex);
                    String mimeType = cursor.getString(mimeIndex);
                    long size = cursor.isNull(sizeIndex) ? 0L : cursor.getLong(sizeIndex);
                    rows.add(FileRow.localDocument(name, documentId, Document.MIME_TYPE_DIR.equals(mimeType), mimeType, size));
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, text("Cannot load local list: ", "ローカル一覧を取得できません: ") + buildErrorMessage(e), Toast.LENGTH_LONG).show();
            if (e instanceof SecurityException) {
                clearLocalDirectoryAccess();
                localPathView.setText(text("Documents folder permission is required. Tap Allow.", "Documentsフォルダの許可が必要です。許可ボタンを押してください"));
                return;
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        Collections.sort(rows, FileRow.COMPARATOR);

        localRows.clear();
        localRows.addAll(rows);
        localPathView.setText(localDirectoryDocumentId);
        localAdapter.notifyDataSetChanged();
    }

    private void refreshRemoteDirectory() {
        executor.execute(() -> {
            ChannelSftp channel = sftp;
            if (channel == null || !channel.isConnected()) {
                return;
            }
            try {
                setStatus(text("Loading directory...", "ディレクトリ取得中..."));
                List<FileRow> rows = loadRemoteRows(channel, remoteDirectory);
                runOnUiThread(() -> {
                    statusView.setText(R.string.status_connected);
                    remoteRows.clear();
                    remoteRows.addAll(rows);
                    remotePathView.setText(remoteDirectory);
                    remoteAdapter.notifyDataSetChanged();
                });
            } catch (SftpException e) {
                runOnUiThread(() -> {
                    statusView.setText(R.string.status_connected);
                    Toast.makeText(this, text("Cannot load remote list: ", "リモート一覧を取得できません: ") + buildErrorMessage(e), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private List<FileRow> loadRemoteRows(ChannelSftp channel, String directory) throws SftpException {
        Vector<?> entries = channel.ls(directory);
        List<FileRow> rows = new ArrayList<>();
        if (!"/".equals(directory)) {
            rows.add(FileRow.parent(parentRemotePath(directory)));
        }
        for (Object object : entries) {
            ChannelSftp.LsEntry entry = (ChannelSftp.LsEntry) object;
            String name = entry.getFilename();
            if (".".equals(name) || "..".equals(name)) {
                continue;
            }
            SftpATTRS attrs = entry.getAttrs();
            rows.add(FileRow.remote(name, joinRemotePath(directory, name), attrs.isDir(), attrs.getSize()));
        }
        Collections.sort(rows, FileRow.COMPARATOR);
        return rows;
    }

    private void openLocalRow(FileRow row) {
        if (row == null) {
            return;
        }
        if (row.directory || canOpenLocalDirectory(row.path)) {
            localDirectoryDocumentId = row.path;
            loadLocalDirectory();
        } else {
            openLocalFileInEditor(row);
        }
    }

    private boolean canOpenLocalDirectory(String documentId) {
        if (TextUtils.isEmpty(documentId) || localTreeUri == null) {
            return false;
        }
        Cursor cursor = null;
        try {
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(localTreeUri, documentId);
            cursor = getContentResolver().query(
                    childrenUri,
                    new String[]{Document.COLUMN_DOCUMENT_ID},
                    null,
                    null,
                    null
            );
            return cursor != null;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private void openLocalFileInEditor(FileRow row) {
        if (row.parent || row.directory) {
            return;
        }
        Uri uri = buildLocalDocumentUri(row.path);
        if (uri == null) {
            Toast.makeText(this, text("Cannot open the file.", "ファイルを開けません。"), Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(FileEditorActivity.newLocalDocumentIntent(this, uri.toString(), row.name));
    }

    private void openLocalFileWithAndroid(FileRow row) {
        if (row.parent || row.directory) {
            return;
        }
        Uri uri = buildLocalDocumentUri(row.path);
        if (uri == null) {
            Toast.makeText(this, text("Cannot open the file.", "ファイルを開けません。"), Toast.LENGTH_SHORT).show();
            return;
        }
        String mimeType = TextUtils.isEmpty(row.mimeType) ? "application/octet-stream" : row.mimeType;
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mimeType);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.action_open_android)));
        } catch (Exception e) {
            Toast.makeText(this, text("No app can open this type: ", "開けるアプリがありません: ") + mimeType, Toast.LENGTH_LONG).show();
        }
    }

    private void showLocalActionDialog(FileRow row) {
        if (row.parent) {
            return;
        }
        String[] actions = row.directory
                ? new String[]{getString(R.string.action_copy_file_name), getString(R.string.action_upload), getString(R.string.action_delete)}
                : new String[]{getString(R.string.action_open_android), getString(R.string.action_copy_file_name), getString(R.string.action_upload), getString(R.string.action_delete)};
        new AlertDialog.Builder(this)
                .setTitle(row.name)
                .setItems(actions, (dialog, which) -> {
                    String action = actions[which];
                    if (getString(R.string.action_open_android).equals(action)) {
                        openLocalFileWithAndroid(row);
                    } else if (getString(R.string.action_copy_file_name).equals(action)) {
                        copyFileName(row);
                    } else if (getString(R.string.action_upload).equals(action)) {
                        uploadLocalRow(row);
                    } else {
                        confirmDeleteLocalFile(row);
                    }
                })
                .show();
    }

    private void confirmDeleteLocalFile(FileRow row) {
        new AlertDialog.Builder(this)
                .setTitle(row.name)
                .setMessage(text("Delete this local item?", "このローカルファイルを削除しますか？"))
                .setPositiveButton(R.string.action_delete, (dialog, which) -> deleteLocalFile(row))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteLocalFile(FileRow row) {
        Uri uri = buildLocalDocumentUri(row.path);
        if (uri == null) {
            Toast.makeText(this, text("Cannot open the item to delete.", "削除するファイルを開けません。"), Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            if (DocumentsContract.deleteDocument(getContentResolver(), uri)) {
                loadLocalDirectory();
                Toast.makeText(this, text("Deleted: ", "削除しました: ") + row.name, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, text("Could not delete: ", "削除できませんでした: ") + row.name, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, text("Delete failed: ", "削除に失敗しました: ") + buildErrorMessage(e), Toast.LENGTH_LONG).show();
        }
    }

    private void openRemoteRow(FileRow row) {
        if (row == null) {
            return;
        }
        if (row.directory) {
            setStatus(text("Opening directory...", "ディレクトリを開いています..."));
            openRemoteDirectory(row.path);
        } else {
            setStatus(text("Opening file...", "ファイルを開いています..."));
            openRemoteDirectoryOrFile(row);
        }
    }

    private void openRemoteDirectoryOrFile(FileRow row) {
        executor.execute(() -> {
            ChannelSftp channel = sftp;
            if (channel == null || !channel.isConnected()) {
                runOnUiThread(() -> {
                    statusView.setText(R.string.status_disconnected);
                    Toast.makeText(this, text("Not connected to the server.", "サーバーに接続していません。"), Toast.LENGTH_SHORT).show();
                });
                return;
            }
            try {
                SftpATTRS attrs = channel.stat(row.path);
                if (attrs.isDir()) {
                    openRemoteDirectoryOnExecutor(channel, row.path);
                    return;
                }
            } catch (SftpException ignored) {
                // If stat cannot prove this is a directory, fall back to file editing.
            }
            editRemoteFileOnExecutor(channel, row);
        });
    }

    private void openRemoteDirectory(String targetDirectory) {
        executor.execute(() -> {
            ChannelSftp channel = sftp;
            if (channel == null || !channel.isConnected()) {
                runOnUiThread(() -> {
                    statusView.setText(R.string.status_disconnected);
                    Toast.makeText(this, text("Not connected to the server.", "サーバーに接続していません。"), Toast.LENGTH_SHORT).show();
                });
                return;
            }
            openRemoteDirectoryOnExecutor(channel, targetDirectory);
        });
    }

    private void openRemoteDirectoryOnExecutor(ChannelSftp channel, String targetDirectory) {
        try {
            setStatus(text("Loading directory...", "ディレクトリ取得中..."));
            List<FileRow> rows = loadRemoteRows(channel, targetDirectory);
            runOnUiThread(() -> {
                remoteDirectory = targetDirectory;
                statusView.setText(R.string.status_connected);
                remoteRows.clear();
                remoteRows.addAll(rows);
                remotePathView.setText(remoteDirectory);
                remoteAdapter.notifyDataSetChanged();
            });
        } catch (SftpException e) {
            runOnUiThread(() -> {
                statusView.setText(R.string.status_connected);
                Toast.makeText(this, text("Cannot enter directory: ", "ディレクトリに入れません: ") + buildErrorMessage(e), Toast.LENGTH_LONG).show();
            });
        }
    }

    private void editRemoteFile(FileRow row) {
        executor.execute(() -> {
            ChannelSftp channel = sftp;
            if (channel == null || !channel.isConnected()) {
                return;
            }
            editRemoteFileOnExecutor(channel, row);
        });
    }

    private void editRemoteFileOnExecutor(ChannelSftp channel, FileRow row) {
        try {
            File editDirectory = new File(getCacheDir(), "remote-edit");
            if (!editDirectory.exists()) {
                editDirectory.mkdirs();
            }
            File localFile = new File(editDirectory, sanitizeFileName(row.name));
            channel.get(row.path, localFile.getAbsolutePath());
            runOnUiThread(() -> startActivity(FileEditorActivity.newRemoteIntent(
                    this,
                    host,
                    port,
                    username,
                    password,
                    privateKey,
                    passphrase,
                    row.path,
                    localFile.getAbsolutePath()
            )));
        } catch (SftpException e) {
            runOnUiThread(() -> Toast.makeText(this, text("Failed to download for editing: ", "編集用ダウンロードに失敗しました: ") + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    private void showRemoteActionDialog(FileRow row) {
        if (row.parent) {
            return;
        }
        String[] actions = row.directory
                ? new String[]{getString(R.string.action_copy_file_name), getString(R.string.action_download), getString(R.string.action_delete)}
                : new String[]{getString(R.string.action_copy_file_name), getString(R.string.action_download), text("Run with command", "コマンドで実行"), getString(R.string.action_delete)};
        new AlertDialog.Builder(this)
                .setTitle(row.name)
                .setItems(actions, (dialog, which) -> {
                    String action = actions[which];
                    if (getString(R.string.action_copy_file_name).equals(action)) {
                        copyFileName(row);
                    } else if (getString(R.string.action_download).equals(action)) {
                        downloadRemoteFile(row);
                    } else if (text("Run with command", "コマンドで実行").equals(action)) {
                        showRunRemoteFileCommandDialog(row);
                    } else {
                        deleteRemoteRow(row);
                    }
                })
                .show();
    }

    private void copyFileName(FileRow row) {
        if (row == null || TextUtils.isEmpty(row.name)) {
            return;
        }
        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager == null) {
            return;
        }
        manager.setPrimaryClip(ClipData.newPlainText("sshclientjr-file-name", row.name));
        Toast.makeText(this, R.string.toast_file_name_copied, Toast.LENGTH_SHORT).show();
    }

    private void showRunRemoteFileCommandDialog(FileRow row) {
        if (row.directory || row.parent) {
            return;
        }
        EditText commandInput = new EditText(this);
        commandInput.setSingleLine(true);
        commandInput.setHint(text("Example: sh / python3 / node", "例: sh / python3 / node"));
        configureCommandInputIme(commandInput);
        commandInput.setPadding(32, 16, 32, 16);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(text("Run ", "") + row.name + text(" with command", " をコマンドで実行"))
                .setView(commandInput)
                .setPositiveButton(R.string.action_run, (clickedDialog, which) -> runRemoteFileWithCommand(row, commandInput.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        commandInput.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP)) {
                runRemoteFileWithCommand(row, commandInput.getText().toString());
                dialog.dismiss();
                return true;
            }
            return false;
        });
        dialog.setOnShowListener(view -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
            commandInput.requestFocus();
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) {
                imm.showSoftInput(commandInput, InputMethodManager.SHOW_IMPLICIT);
            }
            commandInput.postDelayed(() -> {
                commandInput.requestFocus();
                InputMethodManager delayedImm = getSystemService(InputMethodManager.class);
                if (delayedImm != null) {
                    delayedImm.showSoftInput(commandInput, InputMethodManager.SHOW_IMPLICIT);
                }
            }, 120L);
        });
        dialog.show();
    }

    private void configureCommandInputIme(EditText input) {
        if (commandImeEnabled) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
        } else {
            input.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        }
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
    }

    private void toggleCommandImeMode(EditText input) {
        commandImeEnabled = !commandImeEnabled;
        int selection = Math.max(0, input.getSelectionStart());
        configureCommandInputIme(input);
        input.setSelection(Math.min(selection, input.getText().length()));
        input.requestFocus();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) {
            imm.restartInput(input);
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }
        updateShellKeyButtonStates();
    }

    private void runRemoteFileWithCommand(FileRow row, String rawCommand) {
        String command = rawCommand == null ? "" : rawCommand.trim();
        if (TextUtils.isEmpty(command)) {
            Toast.makeText(this, text("Enter a command name.", "コマンド名を入力してください。"), Toast.LENGTH_SHORT).show();
            return;
        }
        startShellCommand(command + " " + shellQuote(row.path));
    }

    private void downloadRemoteFile(FileRow row) {
        executor.execute(() -> {
            ChannelSftp channel = sftp;
            if (channel == null || !channel.isConnected()) {
                return;
            }
            if (localTreeUri == null || TextUtils.isEmpty(localDirectoryDocumentId)) {
                runOnUiThread(() -> Toast.makeText(this, text("Select the local Documents folder.", "ローカルのDocumentsフォルダを選択してください。"), Toast.LENGTH_SHORT).show());
                return;
            }
            try {
                setStatus(text("Downloading...", "ダウンロード中..."));
                if (row.directory) {
                    TransferProgress progress = new TransferProgress(text("Download", "ダウンロード"), Math.max(1, countRemoteTreeItems(channel, row.path)));
                    showTransferProgress(progress);
                    String targetDirectoryDocumentId = ensureLocalDirectory(localDirectoryDocumentId, row.name);
                    incrementTransferProgress(progress);
                    downloadRemoteDirectory(channel, row.path, targetDirectoryDocumentId, progress);
                } else {
                    TransferProgress progress = row.size >= FILE_PROGRESS_THRESHOLD_BYTES
                            ? TransferProgress.bytes(text("Download", "ダウンロード"), row.name, row.size)
                            : null;
                    if (progress != null) {
                        showTransferProgress(progress);
                    }
                    downloadRemoteFileToLocal(channel, row.path, localDirectoryDocumentId, row.name, progress);
                }
                runOnUiThread(() -> {
                    statusView.setText(R.string.status_connected);
                    hideTransferProgress();
                    loadLocalDirectory();
                    Toast.makeText(this, text("Downloaded: ", "ダウンロードしました: ") + row.name, Toast.LENGTH_SHORT).show();
                });
            } catch (SftpException e) {
                runOnUiThread(() -> {
                    statusView.setText(R.string.status_connected);
                    hideTransferProgress();
                    Toast.makeText(this, text("Download failed: ", "ダウンロードに失敗しました: ") + buildErrorMessage(e), Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusView.setText(R.string.status_connected);
                    hideTransferProgress();
                    Toast.makeText(this, text("Download failed: ", "ダウンロードに失敗しました: ") + buildErrorMessage(e), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void downloadRemoteDirectory(ChannelSftp channel, String remotePath, String localDirectoryDocumentId, TransferProgress progress) throws Exception {
        Vector<?> entries = channel.ls(remotePath);
        for (Object object : entries) {
            ChannelSftp.LsEntry entry = (ChannelSftp.LsEntry) object;
            String name = entry.getFilename();
            if (".".equals(name) || "..".equals(name)) {
                continue;
            }
            String childRemotePath = joinRemotePath(remotePath, name);
            if (entry.getAttrs().isDir()) {
                String childLocalDocumentId = ensureLocalDirectory(localDirectoryDocumentId, name);
                incrementTransferProgress(progress);
                downloadRemoteDirectory(channel, childRemotePath, childLocalDocumentId, progress);
            } else {
                downloadRemoteFileToLocal(channel, childRemotePath, localDirectoryDocumentId, name, null);
                incrementTransferProgress(progress);
            }
        }
    }

    private int countRemoteTreeItems(ChannelSftp channel, String remotePath) throws SftpException {
        int count = 1;
        Vector<?> entries = channel.ls(remotePath);
        for (Object object : entries) {
            ChannelSftp.LsEntry entry = (ChannelSftp.LsEntry) object;
            String name = entry.getFilename();
            if (".".equals(name) || "..".equals(name)) {
                continue;
            }
            if (entry.getAttrs().isDir()) {
                count += countRemoteTreeItems(channel, joinRemotePath(remotePath, name));
            } else {
                count++;
            }
        }
        return count;
    }

    private void downloadRemoteFileToLocal(ChannelSftp channel, String remotePath, String localParentDocumentId, String name, TransferProgress progress) throws Exception {
        Uri targetUri = ensureLocalFile(localParentDocumentId, name);
        OutputStream outputStream = getContentResolver().openOutputStream(targetUri, "wt");
        if (outputStream == null) {
            throw new SftpException(ChannelSftp.SSH_FX_FAILURE, "local file is not writable");
        }
        try {
            channel.get(remotePath, outputStream, progress == null ? null : createSftpProgressMonitor(progress));
        } finally {
            closeQuietly(outputStream);
        }
    }

    private Uri ensureLocalFile(String parentDocumentId, String name) throws Exception {
        LocalDocumentInfo existing = findLocalChild(parentDocumentId, name);
        if (existing != null) {
            if (existing.directory) {
                throw new SftpException(ChannelSftp.SSH_FX_FAILURE, "local directory already exists: " + name);
            }
            return buildLocalDocumentUri(existing.documentId);
        }
        Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(localTreeUri, parentDocumentId);
        Uri targetUri = DocumentsContract.createDocument(getContentResolver(), parentUri, "application/octet-stream", name);
        if (targetUri == null) {
            throw new SftpException(ChannelSftp.SSH_FX_FAILURE, "local file cannot be created");
        }
        return targetUri;
    }

    private String ensureLocalDirectory(String parentDocumentId, String name) throws Exception {
        LocalDocumentInfo existing = findLocalChild(parentDocumentId, name);
        if (existing != null) {
            if (!existing.directory) {
                throw new SftpException(ChannelSftp.SSH_FX_FAILURE, "local file already exists: " + name);
            }
            return existing.documentId;
        }
        Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(localTreeUri, parentDocumentId);
        Uri directoryUri = DocumentsContract.createDocument(getContentResolver(), parentUri, Document.MIME_TYPE_DIR, name);
        if (directoryUri == null) {
            throw new SftpException(ChannelSftp.SSH_FX_FAILURE, "local directory cannot be created");
        }
        return DocumentsContract.getDocumentId(directoryUri);
    }

    private void uploadLocalRow(FileRow row) {
        executor.execute(() -> {
            ChannelSftp channel = sftp;
            if (channel == null || !channel.isConnected()) {
                runOnUiThread(() -> Toast.makeText(this, text("Not connected to the server.", "サーバーに接続していません。"), Toast.LENGTH_SHORT).show());
                return;
            }
            try {
                String targetPath = joinRemotePath(remoteDirectory, row.name);
                setStatus(text("Uploading...", "アップロード中..."));
                if (row.directory) {
                    TransferProgress progress = new TransferProgress(text("Upload", "アップロード"), Math.max(1, countLocalTreeItems(row.path)));
                    showTransferProgress(progress);
                    ensureRemoteDirectory(channel, targetPath);
                    incrementTransferProgress(progress);
                    uploadLocalDirectory(channel, row.path, targetPath, progress);
                } else {
                    TransferProgress progress = row.size >= FILE_PROGRESS_THRESHOLD_BYTES
                            ? TransferProgress.bytes(text("Upload", "アップロード"), row.name, row.size)
                            : null;
                    if (progress != null) {
                        showTransferProgress(progress);
                    }
                    uploadLocalFileToRemote(channel, row.path, targetPath, progress);
                }
                runOnUiThread(() -> {
                    statusView.setText(R.string.status_connected);
                    hideTransferProgress();
                    Toast.makeText(this, text("Uploaded: ", "アップロードしました: ") + row.name, Toast.LENGTH_SHORT).show();
                    refreshRemoteDirectory();
                });
            } catch (SftpException e) {
                runOnUiThread(() -> {
                    statusView.setText(R.string.status_connected);
                    hideTransferProgress();
                    Toast.makeText(this, text("Upload failed: ", "アップロードに失敗しました: ") + buildErrorMessage(e), Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusView.setText(R.string.status_connected);
                    hideTransferProgress();
                    Toast.makeText(this, text("Upload failed: ", "アップロードに失敗しました: ") + buildErrorMessage(e), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void uploadLocalDirectory(ChannelSftp channel, String localDirectoryDocumentId, String remoteDirectoryPath, TransferProgress progress) throws Exception {
        List<FileRow> children = loadLocalChildRows(localDirectoryDocumentId);
        for (FileRow child : children) {
            String remoteChildPath = joinRemotePath(remoteDirectoryPath, child.name);
            if (child.directory) {
                ensureRemoteDirectory(channel, remoteChildPath);
                incrementTransferProgress(progress);
                uploadLocalDirectory(channel, child.path, remoteChildPath, progress);
            } else {
                uploadLocalFileToRemote(channel, child.path, remoteChildPath, null);
                incrementTransferProgress(progress);
            }
        }
    }

    private int countLocalTreeItems(String localDirectoryDocumentId) throws Exception {
        int count = 1;
        for (FileRow child : loadLocalChildRows(localDirectoryDocumentId)) {
            if (child.directory) {
                count += countLocalTreeItems(child.path);
            } else {
                count++;
            }
        }
        return count;
    }

    private void uploadLocalFileToRemote(ChannelSftp channel, String localDocumentId, String remotePath, TransferProgress progress) throws Exception {
        Uri sourceUri = buildLocalDocumentUri(localDocumentId);
        if (sourceUri == null) {
            throw new SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "local file is not readable");
        }
        InputStream inputStream = getContentResolver().openInputStream(sourceUri);
        if (inputStream == null) {
            throw new SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "local file is not readable");
        }
        try {
            channel.put(inputStream, remotePath, progress == null ? null : createSftpProgressMonitor(progress));
        } finally {
            closeQuietly(inputStream);
        }
    }

    private void ensureRemoteDirectory(ChannelSftp channel, String remotePath) throws SftpException {
        try {
            SftpATTRS attrs = channel.lstat(remotePath);
            if (!attrs.isDir()) {
                throw new SftpException(ChannelSftp.SSH_FX_FAILURE, "remote file already exists: " + remotePath);
            }
        } catch (SftpException e) {
            if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                throw e;
            }
            channel.mkdir(remotePath);
        }
    }

    private void showTransferProgress(TransferProgress progress) {
        runOnUiThread(() -> {
            transferProgressPanel.setVisibility(View.VISIBLE);
            transferProgressBar.setMax(getTransferProgressMax(progress));
            transferProgressBar.setProgress(getTransferProgressValue(progress));
            transferProgressText.setText(buildTransferProgressText(progress));
        });
    }

    private void incrementTransferProgress(TransferProgress progress) {
        progress.done = Math.min(progress.total, progress.done + 1);
        runOnUiThread(() -> {
            transferProgressBar.setMax(getTransferProgressMax(progress));
            transferProgressBar.setProgress(getTransferProgressValue(progress));
            transferProgressText.setText(buildTransferProgressText(progress));
        });
    }

    private void addByteTransferProgress(TransferProgress progress, long bytes) {
        progress.done = Math.min(progress.total, progress.done + Math.max(0L, bytes));
        long now = System.currentTimeMillis();
        if (progress.done < progress.total && now - progress.lastUiUpdateMs < PROGRESS_UPDATE_INTERVAL_MS) {
            return;
        }
        progress.lastUiUpdateMs = now;
        runOnUiThread(() -> {
            transferProgressBar.setMax(getTransferProgressMax(progress));
            transferProgressBar.setProgress(getTransferProgressValue(progress));
            transferProgressText.setText(buildTransferProgressText(progress));
        });
    }

    private void hideTransferProgress() {
        transferProgressPanel.setVisibility(View.GONE);
        transferProgressBar.setProgress(0);
        transferProgressText.setText("");
    }

    private int getTransferProgressMax(TransferProgress progress) {
        return progress.bytes ? 100 : (int) Math.min(Integer.MAX_VALUE, progress.total);
    }

    private int getTransferProgressValue(TransferProgress progress) {
        if (!progress.bytes) {
            return (int) Math.min(Integer.MAX_VALUE, progress.done);
        }
        if (progress.total <= 0L) {
            return 0;
        }
        return (int) Math.min(100L, (progress.done * 100L) / progress.total);
    }

    private String buildTransferProgressText(TransferProgress progress) {
        if (progress.bytes) {
            return progress.label + " " + progress.name + " " + formatBytes(progress.done) + "/" + formatBytes(progress.total);
        }
        return progress.label + " " + progress.done + "/" + progress.total;
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(java.util.Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0));
        }
        if (bytes >= 1024L) {
            return String.format(java.util.Locale.US, "%.1fKB", bytes / 1024.0);
        }
        return bytes + "B";
    }

    private SftpProgressMonitor createSftpProgressMonitor(TransferProgress progress) {
        return new SftpProgressMonitor() {
            @Override
            public void init(int op, String src, String dest, long max) {
            }

            @Override
            public boolean count(long count) {
                addByteTransferProgress(progress, count);
                return true;
            }

            @Override
            public void end() {
                progress.done = progress.total;
                addByteTransferProgress(progress, 0L);
            }
        };
    }

    private void showCreateRemoteMenu() {
        if (!connected || sftp == null || !sftp.isConnected()) {
            Toast.makeText(this, text("Not connected to the server.", "サーバーに接続していません。"), Toast.LENGTH_SHORT).show();
            return;
        }
        String[] actions = new String[]{getString(R.string.action_file), getString(R.string.action_directory)};
        new AlertDialog.Builder(this)
                .setTitle(text("Create on server", "サーバーに新規作成"))
                .setItems(actions, (dialog, which) -> showCreateRemoteDialog(which == 0))
                .show();
    }

    private void showCreateRemoteDialog(boolean file) {
        EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setHint(file ? text("File name", "ファイル名") : text("Directory name", "ディレクトリ名"));
        nameInput.setPadding(32, 16, 32, 16);
        new AlertDialog.Builder(this)
                .setTitle(file ? text("Create server file", "サーバーファイルを新規作成") : text("Create server directory", "サーバーディレクトリを新規作成"))
                .setView(nameInput)
                .setPositiveButton(text("Create", "作成"), (dialog, which) -> createRemoteRow(nameInput.getText().toString(), file))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void createRemoteRow(String rawName, boolean file) {
        String name = validateNewName(rawName);
        if (name == null) {
            return;
        }
        executor.execute(() -> {
            ChannelSftp channel = sftp;
            if (channel == null || !channel.isConnected()) {
                runOnUiThread(() -> Toast.makeText(this, text("Not connected to the server.", "サーバーに接続していません。"), Toast.LENGTH_SHORT).show());
                return;
            }
            String targetPath = joinRemotePath(remoteDirectory, name);
            try {
                try {
                    channel.lstat(targetPath);
                    runOnUiThread(() -> Toast.makeText(this, text("An item with the same name already exists.", "同じ名前のファイルが既にあります。"), Toast.LENGTH_SHORT).show());
                    return;
                } catch (SftpException e) {
                    if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                        throw e;
                    }
                }
                setStatus(text("Creating...", "作成中..."));
                if (file) {
                    channel.put(new ByteArrayInputStream(new byte[0]), targetPath);
                } else {
                    channel.mkdir(targetPath);
                }
                runOnUiThread(() -> {
                    statusView.setText(R.string.status_connected);
                    Toast.makeText(this, text("Created: ", "作成しました: ") + name, Toast.LENGTH_SHORT).show();
                    refreshRemoteDirectory();
                });
            } catch (SftpException e) {
                runOnUiThread(() -> {
                    statusView.setText(R.string.status_connected);
                    Toast.makeText(this, text("Create failed: ", "作成に失敗しました: ") + buildErrorMessage(e), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void deleteRemoteRow(FileRow row) {
        executor.execute(() -> {
            ChannelSftp channel = sftp;
            if (channel == null || !channel.isConnected()) {
                return;
            }
            try {
                setStatus(text("Deleting...", "削除中..."));
                if (row.directory) {
                    deleteRemoteDirectoryRecursive(channel, row.path);
                } else {
                    channel.rm(row.path);
                }
                runOnUiThread(() -> {
                    statusView.setText(R.string.status_connected);
                    Toast.makeText(this, text("Deleted: ", "削除しました: ") + row.name, Toast.LENGTH_SHORT).show();
                    refreshRemoteDirectory();
                });
            } catch (SftpException e) {
                runOnUiThread(() -> {
                    statusView.setText(R.string.status_connected);
                    Toast.makeText(this, text("Delete failed: ", "削除に失敗しました: ") + buildErrorMessage(e), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void deleteRemoteDirectoryRecursive(ChannelSftp channel, String remotePath) throws SftpException {
        Vector<?> entries = channel.ls(remotePath);
        for (Object object : entries) {
            ChannelSftp.LsEntry entry = (ChannelSftp.LsEntry) object;
            String name = entry.getFilename();
            if (".".equals(name) || "..".equals(name)) {
                continue;
            }
            String childPath = joinRemotePath(remotePath, name);
            if (entry.getAttrs().isDir()) {
                deleteRemoteDirectoryRecursive(channel, childPath);
            } else {
                channel.rm(childPath);
            }
        }
        channel.rmdir(remotePath);
    }

    private void runRemoteCommand() {
        String command = remoteCommandInput.getText().toString().trim();
        if (TextUtils.isEmpty(command)) {
            Toast.makeText(this, text("Enter a command to run.", "実行するコマンドを入力してください。"), Toast.LENGTH_SHORT).show();
            return;
        }
        addRemoteCommandHistory(command);
        if (commandHistoryPopup != null) {
            commandHistoryPopup.dismiss();
        }
        startShellCommand(command);
        remoteCommandInput.setText("");
    }

    private void startShellCommand(String command) {
        ShellTab tab = createShellTab(command);
        showShellTab(tab);
        setStatus(text("Connecting shell...", "シェル接続中..."));
        tab.session.connectWithCommand(host, port, username, password, privateKey, passphrase, buildTmuxAttachCommand(tab));
    }

    private String buildShellCommand(String command) {
        return "cd " + shellQuote(remoteDirectory) + "\n" + command + "\n";
    }

    private String buildTmuxSessionName(int id) {
        return "sshclientjr_" + System.currentTimeMillis() + "_" + id;
    }

    private String buildDiscoverDetachedShellsCommand() {
        return "export LANG=ja_JP.UTF-8 2>/dev/null || export LANG=C.UTF-8 2>/dev/null; "
                + "if command -v tmux >/dev/null 2>&1; then "
                + "tmux list-sessions -F '#{session_name}' 2>/dev/null | while IFS= read -r name; do "
                + "case \"$name\" in sshclientjr_*) "
                + "dir=$(tmux display-message -p -t \"$name:\" '#{pane_current_path}' 2>/dev/null); "
                + "[ -n \"$dir\" ] || dir='?'; "
                + "printf '%s\\t%s\\t%s\\n' \"$name\" \"$name\" \"$dir\";; "
                + "esac; "
                + "done; "
                + "fi; "
                + "if command -v screen >/dev/null 2>&1; then "
                + "screen -ls 2>/dev/null | sed -n 's/^[[:space:]]*[0-9][0-9]*\\.\\(sshclientjr_[^[:space:]]*\\).*/\\1/p' | while IFS= read -r name; do "
                + "printf '%s\\t%s\\t%s\\n' \"$name\" \"$name\" '?'; "
                + "done; "
                + "fi";
    }

    private String buildTmuxAttachCommand(ShellTab tab) {
        String sessionName = shellQuote(tab.tmuxSessionName);
        String noMultiplexerMessage = shellQuote(text(
                "tmux/screen was not found, so detached sessions cannot be preserved.",
                "tmux/screen が見つからないためデタッチ維持はできません。"
        ));
        return "SSHCLIENTJR_SESSION=" + sessionName + "; "
                + "export LANG=ja_JP.UTF-8 2>/dev/null || export LANG=C.UTF-8 2>/dev/null; "
                + "export LC_CTYPE=\"$LANG\"; "
                + "stty iutf8 2>/dev/null; "
                + "if command -v tmux >/dev/null 2>&1 && tmux has-session -t \"$SSHCLIENTJR_SESSION\" 2>/dev/null; then "
                + "tmux set-environment -g LANG \"$LANG\" 2>/dev/null; "
                + "tmux set-environment -g LC_CTYPE \"$LC_CTYPE\" 2>/dev/null; "
                + "tmux set-option -g mouse on 2>/dev/null; "
                + "exec tmux -u attach-session -t \"$SSHCLIENTJR_SESSION\"; "
                + "elif command -v screen >/dev/null 2>&1 && screen -list | grep -F \".$SSHCLIENTJR_SESSION\" >/dev/null 2>&1; then "
                + "exec screen -U -xRR \"$SSHCLIENTJR_SESSION\"; "
                + "elif command -v tmux >/dev/null 2>&1; then "
                + "tmux start-server 2>/dev/null; "
                + "tmux set-environment -g LANG \"$LANG\" 2>/dev/null; "
                + "tmux set-environment -g LC_CTYPE \"$LC_CTYPE\" 2>/dev/null; "
                + "tmux set-option -g mouse on 2>/dev/null; "
                + "exec tmux -u new-session -s \"$SSHCLIENTJR_SESSION\"; "
                + "elif command -v screen >/dev/null 2>&1; then "
                + "exec screen -U -S \"$SSHCLIENTJR_SESSION\"; "
                + "elif command -v bash >/dev/null 2>&1; then "
                + "printf '%s\\n' " + noMultiplexerMessage + " >&2; "
                + "exec bash --noprofile --norc -i; "
                + "else "
                + "printf '%s\\n' " + noMultiplexerMessage + " >&2; "
                + "exec sh -i; "
                + "fi";
    }

    private static String buildShellTabTitle(int id, String directory) {
        String label = directoryName(directory);
        if (TextUtils.isEmpty(label)) {
            label = "?";
        }
        if (label.length() > 14) {
            label = label.substring(0, 14) + "...";
        }
        return label;
    }

    private static String directoryName(String directory) {
        String value = directory == null ? "" : directory.trim();
        if (TextUtils.isEmpty(value) || "?".equals(value)) {
            return value;
        }
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if ("/".equals(value)) {
            return "/";
        }
        int slashIndex = value.lastIndexOf('/');
        if (slashIndex >= 0 && slashIndex + 1 < value.length()) {
            return value.substring(slashIndex + 1);
        }
        return value;
    }

    private void sendPendingShellCommand(ShellTab tab) {
        String command = tab.pendingCommand;
        if (TextUtils.isEmpty(command)) {
            return;
        }
        tab.pendingCommand = null;
        byte[] bytes = command.getBytes(StandardCharsets.UTF_8);
        tab.session.write(bytes, 0, bytes.length);
        statusView.setText(R.string.status_connected);
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private Uri buildLocalDocumentUri(String documentId) {
        if (localTreeUri == null || TextUtils.isEmpty(documentId)) {
            return null;
        }
        return DocumentsContract.buildDocumentUriUsingTree(localTreeUri, documentId);
    }

    private Uri findLocalChildUri(String name) {
        LocalDocumentInfo document = findLocalChild(localDirectoryDocumentId, name);
        if (document == null || document.directory) {
            return null;
        }
        return buildLocalDocumentUri(document.documentId);
    }

    private List<FileRow> loadLocalChildRows(String parentDocumentId) throws Exception {
        if (localTreeUri == null || TextUtils.isEmpty(parentDocumentId)) {
            return Collections.emptyList();
        }
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(localTreeUri, parentDocumentId);
        Cursor cursor = null;
        List<FileRow> rows = new ArrayList<>();
        try {
            cursor = getContentResolver().query(
                    childrenUri,
                    new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE},
                    null,
                    null,
                    null
            );
            if (cursor == null) {
                return rows;
            }
            int idIndex = cursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID);
            int nameIndex = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndex(Document.COLUMN_MIME_TYPE);
            int sizeIndex = cursor.getColumnIndex(Document.COLUMN_SIZE);
            while (cursor.moveToNext()) {
                String documentId = cursor.getString(idIndex);
                String name = cursor.getString(nameIndex);
                String mimeType = cursor.getString(mimeIndex);
                long size = cursor.isNull(sizeIndex) ? 0L : cursor.getLong(sizeIndex);
                rows.add(FileRow.localDocument(name, documentId, Document.MIME_TYPE_DIR.equals(mimeType), mimeType, size));
            }
            return rows;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private LocalDocumentInfo findLocalChild(String parentDocumentId, String name) {
        if (localTreeUri == null || TextUtils.isEmpty(parentDocumentId)) {
            return null;
        }
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(localTreeUri, parentDocumentId);
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(
                    childrenUri,
                    new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE},
                    null,
                    null,
                    null
            );
            if (cursor == null) {
                return null;
            }
            int idIndex = cursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID);
            int nameIndex = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndex(Document.COLUMN_MIME_TYPE);
            while (cursor.moveToNext()) {
                String displayName = cursor.getString(nameIndex);
                String mimeType = cursor.getString(mimeIndex);
                if (name.equals(displayName)) {
                    return new LocalDocumentInfo(cursor.getString(idIndex), Document.MIME_TYPE_DIR.equals(mimeType));
                }
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    private String getParentDocumentId(String documentId) {
        if (TextUtils.isEmpty(documentId)) {
            return null;
        }
        int slashIndex = documentId.lastIndexOf('/');
        if (slashIndex <= 0) {
            return null;
        }
        return documentId.substring(0, slashIndex);
    }

    private void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private String joinRemotePath(String parent, String name) {
        if (TextUtils.isEmpty(parent) || ".".equals(parent)) {
            return name;
        }
        if ("/".equals(parent)) {
            return "/" + name;
        }
        return parent + "/" + name;
    }

    private String parentRemotePath(String path) {
        if (TextUtils.isEmpty(path) || "/".equals(path)) {
            return "/";
        }
        int slashIndex = path.lastIndexOf('/');
        if (slashIndex <= 0) {
            return "/";
        }
        return path.substring(0, slashIndex);
    }

    private String sanitizeFileName(String name) {
        return name.replace('/', '_').replace('\\', '_');
    }

    private String buildErrorMessage(Exception exception) {
        String message = exception.getMessage();
        if (TextUtils.isEmpty(message)) {
            return exception.getClass().getSimpleName();
        }
        return message;
    }

    private String text(String english, String japanese) {
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        return "ja".equals(locale.getLanguage()) ? japanese : english;
    }

    private String validateNewName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (TextUtils.isEmpty(name)) {
            Toast.makeText(this, text("Enter a name.", "名前を入力してください。"), Toast.LENGTH_SHORT).show();
            return null;
        }
        if (name.contains("/") || name.contains("\\")) {
            Toast.makeText(this, text("Names cannot contain / or \\.", "名前に / や \\ は使えません。"), Toast.LENGTH_SHORT).show();
            return null;
        }
        return name;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class ShellTab {
        private int id;
        private String tmuxSessionName;
        private String title;
        private String remoteDirectory;
        private Button button;
        private SshTerminalView view;
        private SshTerminalSession session;
        private boolean connected;
        private boolean ctrlLocked;
        private boolean altLocked;
        private boolean imeEnabled;
        private boolean detached;
        private boolean closed;
        private String pendingCommand;
    }

    private static final class DetachedShell {
        private final String host;
        private final int port;
        private final String username;
        private final String tmuxSessionName;
        private final String title;
        private final String remoteDirectory;

        private DetachedShell(String host, int port, String username, String tmuxSessionName, String title, String remoteDirectory) {
            this.host = host;
            this.port = port;
            this.username = username;
            this.tmuxSessionName = tmuxSessionName;
            this.title = TextUtils.isEmpty(title) ? tmuxSessionName : title;
            this.remoteDirectory = TextUtils.isEmpty(remoteDirectory) ? "?" : remoteDirectory;
        }

        private JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("host", host);
                object.put("port", port);
                object.put("username", username);
                object.put("tmuxSessionName", tmuxSessionName);
                object.put("title", title);
                object.put("remoteDirectory", remoteDirectory);
            } catch (Exception ignored) {
            }
            return object;
        }

        private String getDisplayLabel() {
            return buildShellTabTitle(0, remoteDirectory) + "  " + remoteDirectory;
        }

        private static DetachedShell fromJson(JSONObject object) {
            String host = object.optString("host", "");
            int port = object.optInt("port", 22);
            String username = object.optString("username", "");
            String tmuxSessionName = object.optString("tmuxSessionName", "");
            String title = object.optString("title", tmuxSessionName);
            String remoteDirectory = object.optString("remoteDirectory", "?");
            if (TextUtils.isEmpty(host) || TextUtils.isEmpty(username) || TextUtils.isEmpty(tmuxSessionName)) {
                return null;
            }
            return new DetachedShell(host, port, username, tmuxSessionName, title, remoteDirectory);
        }
    }

    private static final class LocalDocumentInfo {
        private final String documentId;
        private final boolean directory;

        private LocalDocumentInfo(String documentId, boolean directory) {
            this.documentId = documentId;
            this.directory = directory;
        }
    }

    private static final class TransferProgress {
        private final String label;
        private final String name;
        private final long total;
        private final boolean bytes;
        private long done;
        private long lastUiUpdateMs;

        private TransferProgress(String label, int total) {
            this.label = label;
            this.name = "";
            this.total = Math.max(1, total);
            this.bytes = false;
        }

        private TransferProgress(String label, String name, long total, boolean bytes) {
            this.label = label;
            this.name = name;
            this.total = Math.max(1L, total);
            this.bytes = bytes;
        }

        private static TransferProgress bytes(String label, String name, long total) {
            return new TransferProgress(label, name, total, true);
        }
    }

    private static final class FileRow {
        private static final Comparator<FileRow> COMPARATOR = (first, second) -> {
            if (first.parent != second.parent) {
                return first.parent ? -1 : 1;
            }
            if (first.directory != second.directory) {
                return first.directory ? -1 : 1;
            }
            return first.name.compareToIgnoreCase(second.name);
        };

        private final String name;
        private final String path;
        private final boolean directory;
        private final boolean parent;
        private final long size;
        private final String mimeType;

        private FileRow(String name, String path, boolean directory, boolean parent, long size, String mimeType) {
            this.name = name;
            this.path = path;
            this.directory = directory;
            this.parent = parent;
            this.size = size;
            this.mimeType = mimeType;
        }

        private static FileRow parent(String path) {
            return new FileRow("..", path, true, true, 0L, Document.MIME_TYPE_DIR);
        }

        private static FileRow localDocument(String name, String documentId, boolean directory, String mimeType, long size) {
            return new FileRow(name, documentId, directory, false, size, mimeType);
        }

        private static FileRow remote(String name, String path, boolean directory, long size) {
            return new FileRow(name, path, directory, false, size, directory ? Document.MIME_TYPE_DIR : "application/octet-stream");
        }

        @Override
        public String toString() {
            if (parent) {
                return "../";
            }
            if (directory) {
                return name + "/";
            }
            return name;
        }
    }
}
