package com.sshclientjr;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
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
    private static final int REQUEST_STORAGE_PERMISSION = 1001;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView statusView;
    private TextView localPathView;
    private TextView remotePathView;
    private ListView localListView;
    private ListView remoteListView;
    private EditText remoteCommandInput;
    private Button runCommandButton;
    private Button refreshButton;
    private Button closeButton;

    private ArrayAdapter<FileRow> localAdapter;
    private ArrayAdapter<FileRow> remoteAdapter;
    private final List<FileRow> localRows = new ArrayList<>();
    private final List<FileRow> remoteRows = new ArrayList<>();

    private String host;
    private int port;
    private String username;
    private String password;
    private String privateKey;
    private String passphrase;

    private File localRoot;
    private File localDirectory;
    private String remoteDirectory = ".";
    private Session sshSession;
    private ChannelSftp sftp;
    private volatile boolean connected;
    private boolean storageAccessRequested;

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
        requestStorageAccessIfNeeded();
        initializeLocalDirectory();
        loadLocalDirectory(localDirectory);
        connectSftp();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (localRoot == null) {
            initializeLocalDirectory();
        } else if (hasStorageAccess() && !isDownloadsDirectory(localRoot)) {
            initializeLocalDirectory();
        }
        loadLocalDirectory(localDirectory);
        if (connected) {
            refreshRemoteDirectory();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            initializeLocalDirectory();
            loadLocalDirectory(localDirectory);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnectSftp();
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
    }

    private void bindViews() {
        statusView = findViewById(R.id.filerStatus);
        localPathView = findViewById(R.id.localPathValue);
        remotePathView = findViewById(R.id.remotePathValue);
        localListView = findViewById(R.id.localList);
        remoteListView = findViewById(R.id.remoteList);
        remoteCommandInput = findViewById(R.id.remoteCommandInput);
        runCommandButton = findViewById(R.id.runCommandButton);
        refreshButton = findViewById(R.id.refreshButton);
        closeButton = findViewById(R.id.closeButton);

        localAdapter = new ArrayAdapter<>(this, R.layout.list_item_file, R.id.fileItemText, localRows);
        remoteAdapter = new ArrayAdapter<>(this, R.layout.list_item_file, R.id.fileItemText, remoteRows);
        localListView.setAdapter(localAdapter);
        remoteListView.setAdapter(remoteAdapter);

        localListView.setOnItemClickListener((parent, view, position, id) -> openLocalRow(localRows.get(position)));
        localListView.setOnItemLongClickListener((parent, view, position, id) -> {
            openLocalFileWithAndroid(localRows.get(position));
            return true;
        });
        remoteListView.setOnItemClickListener((parent, view, position, id) -> openRemoteRow(remoteRows.get(position)));
        remoteListView.setOnItemLongClickListener((parent, view, position, id) -> {
            showRemoteActionDialog(remoteRows.get(position));
            return true;
        });
        refreshButton.setOnClickListener(view -> {
            loadLocalDirectory(localDirectory);
            refreshRemoteDirectory();
        });
        runCommandButton.setOnClickListener(view -> runRemoteCommand());
        closeButton.setOnClickListener(view -> finish());
    }

    private void initializeLocalDirectory() {
        localRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (localRoot == null || (!localRoot.exists() && !localRoot.mkdirs()) || !localRoot.isDirectory()) {
            localRoot = getFilesDir();
            Toast.makeText(this, "Downloadsを読めないためアプリ内フォルダを表示します。", Toast.LENGTH_LONG).show();
        }
        if (!localRoot.exists()) {
            localRoot.mkdirs();
        }
        localDirectory = localRoot;
    }

    private void requestStorageAccessIfNeeded() {
        if (hasStorageAccess() || storageAccessRequested) {
            return;
        }
        storageAccessRequested = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            requestPermissions(
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_STORAGE_PERMISSION
            );
        }
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private boolean isDownloadsDirectory(File file) {
        File downloadsDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (file == null || downloadsDirectory == null) {
            return false;
        }
        try {
            return file.getCanonicalPath().equals(downloadsDirectory.getCanonicalPath());
        } catch (Exception e) {
            return false;
        }
    }

    private void connectSftp() {
        statusView.setText("SSH接続中...");
        executor.execute(() -> {
            try {
                setStatus("SSH接続中...");
                Session session = SshSessionFactory.connect(host, port, username, password, privateKey, passphrase);
                setStatus("SFTP開始中...");
                ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
                channel.connect(5_000);
                sshSession = session;
                sftp = channel;
                setStatus("ディレクトリ取得中...");
                remoteDirectory = channel.pwd();
                connected = true;
                List<FileRow> rows = loadRemoteRows(channel, remoteDirectory);
                runOnUiThread(() -> {
                    statusView.setText(R.string.status_connected);
                    remoteRows.clear();
                    remoteRows.addAll(rows);
                    remotePathView.setText(remoteDirectory);
                    remoteAdapter.notifyDataSetChanged();
                });
            } catch (Exception e) {
                disconnectSftp();
                runOnUiThread(() -> {
                    statusView.setText(R.string.status_disconnected);
                    Toast.makeText(this, "SFTP接続に失敗しました: " + buildErrorMessage(e), Toast.LENGTH_LONG).show();
                });
            }
        });
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

    private void loadLocalDirectory(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return;
        }
        localDirectory = directory;
        List<FileRow> rows = new ArrayList<>();
        File parent = directory.getParentFile();
        if (parent != null && isInsideLocalRoot(parent) && !directory.equals(localRoot)) {
            rows.add(FileRow.parent(parent.getAbsolutePath()));
        }

        File[] files = directory.listFiles();
        if (files != null) {
            List<File> sortedFiles = new ArrayList<>();
            Collections.addAll(sortedFiles, files);
            Collections.sort(sortedFiles, (first, second) -> {
                if (first.isDirectory() != second.isDirectory()) {
                    return first.isDirectory() ? -1 : 1;
                }
                return first.getName().compareToIgnoreCase(second.getName());
            });
            for (File file : sortedFiles) {
                rows.add(FileRow.local(file));
            }
        }

        localRows.clear();
        localRows.addAll(rows);
        localPathView.setText(directory.getAbsolutePath());
        localAdapter.notifyDataSetChanged();
    }

    private boolean isInsideLocalRoot(File file) {
        try {
            String rootPath = localRoot.getCanonicalPath();
            String filePath = file.getCanonicalPath();
            return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshRemoteDirectory() {
        executor.execute(() -> {
            ChannelSftp channel = sftp;
            if (channel == null || !channel.isConnected()) {
                return;
            }
            try {
                setStatus("ディレクトリ取得中...");
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
                    Toast.makeText(this, "リモート一覧を取得できません: " + buildErrorMessage(e), Toast.LENGTH_LONG).show();
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
        if (row.directory) {
            loadLocalDirectory(new File(row.path));
        } else {
            openLocalFileWithAndroid(row);
        }
    }

    private void openLocalFileWithAndroid(FileRow row) {
        if (row.parent || row.directory) {
            return;
        }
        File file = new File(row.path);
        if (!file.isFile()) {
            Toast.makeText(this, "ファイルを開けません。", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = LocalFileContentProvider.uriForFile(file);
        String mimeType = LocalFileContentProvider.guessMimeType(file);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mimeType);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.action_open_android)));
        } catch (Exception e) {
            Toast.makeText(this, "開けるアプリがありません: " + mimeType, Toast.LENGTH_LONG).show();
        }
    }

    private void openRemoteRow(FileRow row) {
        if (row.directory) {
            remoteDirectory = row.path;
            refreshRemoteDirectory();
        } else {
            editRemoteFile(row);
        }
    }

    private void editRemoteFile(FileRow row) {
        executor.execute(() -> {
            ChannelSftp channel = sftp;
            if (channel == null || !channel.isConnected()) {
                return;
            }
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
                runOnUiThread(() -> Toast.makeText(this, "編集用ダウンロードに失敗しました: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void showRemoteActionDialog(FileRow row) {
        if (row.parent) {
            return;
        }
        String[] actions = row.directory
                ? new String[]{getString(R.string.action_delete)}
                : new String[]{getString(R.string.action_download), getString(R.string.action_delete)};
        new AlertDialog.Builder(this)
                .setTitle(row.name)
                .setItems(actions, (dialog, which) -> {
                    String action = actions[which];
                    if (getString(R.string.action_download).equals(action)) {
                        downloadRemoteFile(row);
                    } else {
                        deleteRemoteRow(row);
                    }
                })
                .show();
    }

    private void downloadRemoteFile(FileRow row) {
        executor.execute(() -> {
            ChannelSftp channel = sftp;
            if (channel == null || !channel.isConnected()) {
                return;
            }
            try {
                File targetFile = new File(localDirectory, row.name);
                channel.get(row.path, targetFile.getAbsolutePath());
                runOnUiThread(() -> {
                    loadLocalDirectory(localDirectory);
                    Toast.makeText(this, "ダウンロードしました: " + targetFile.getName(), Toast.LENGTH_SHORT).show();
                });
            } catch (SftpException e) {
                runOnUiThread(() -> Toast.makeText(this, "ダウンロードに失敗しました: " + e.getMessage(), Toast.LENGTH_SHORT).show());
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
                if (row.directory) {
                    channel.rmdir(row.path);
                } else {
                    channel.rm(row.path);
                }
                runOnUiThread(() -> {
                    Toast.makeText(this, "削除しました: " + row.name, Toast.LENGTH_SHORT).show();
                    refreshRemoteDirectory();
                });
            } catch (SftpException e) {
                runOnUiThread(() -> Toast.makeText(this, "削除に失敗しました: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void runRemoteCommand() {
        String command = remoteCommandInput.getText().toString().trim();
        if (TextUtils.isEmpty(command)) {
            Toast.makeText(this, "実行するコマンドを入力してください。", Toast.LENGTH_SHORT).show();
            return;
        }
        Session session = sshSession;
        if (session == null || !session.isConnected()) {
            Toast.makeText(this, "サーバーに接続していません。", Toast.LENGTH_SHORT).show();
            return;
        }

        runCommandButton.setEnabled(false);
        setStatus("コマンド実行中...");
        executor.execute(() -> {
            ChannelExec channel = null;
            try {
                channel = (ChannelExec) session.openChannel("exec");
                ByteArrayOutputStream errorOutput = new ByteArrayOutputStream();
                channel.setCommand("cd " + shellQuote(remoteDirectory) + " && " + command);
                channel.setInputStream(null);
                channel.setErrStream(errorOutput);
                InputStream inputStream = channel.getInputStream();
                channel.connect(5_000);
                String output = readCommandOutput(channel, inputStream, errorOutput);
                int exitStatus = channel.getExitStatus();
                runOnUiThread(() -> {
                    runCommandButton.setEnabled(true);
                    statusView.setText(R.string.status_connected);
                    showCommandResult(command, exitStatus, output);
                    refreshRemoteDirectory();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    runCommandButton.setEnabled(true);
                    statusView.setText(R.string.status_connected);
                    Toast.makeText(this, "コマンド実行に失敗しました: " + buildErrorMessage(e), Toast.LENGTH_LONG).show();
                });
            } finally {
                if (channel != null) {
                    channel.disconnect();
                }
            }
        });
    }

    private String readCommandOutput(ChannelExec channel, InputStream inputStream, ByteArrayOutputStream errorOutput) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (!channel.isClosed()) {
            while (inputStream.available() > 0) {
                int count = inputStream.read(buffer, 0, Math.min(buffer.length, inputStream.available()));
                if (count < 0) {
                    break;
                }
                output.write(buffer, 0, count);
            }
            Thread.sleep(50L);
        }
        while (inputStream.available() > 0) {
            int count = inputStream.read(buffer, 0, Math.min(buffer.length, inputStream.available()));
            if (count < 0) {
                break;
            }
            output.write(buffer, 0, count);
        }

        String stdout = new String(output.toByteArray(), StandardCharsets.UTF_8);
        String stderr = new String(errorOutput.toByteArray(), StandardCharsets.UTF_8);
        if (TextUtils.isEmpty(stderr)) {
            return stdout;
        }
        if (TextUtils.isEmpty(stdout)) {
            return stderr;
        }
        return stdout + "\n" + stderr;
    }

    private void showCommandResult(String command, int exitStatus, String output) {
        TextView outputView = new TextView(this);
        outputView.setText(TextUtils.isEmpty(output) ? "(出力なし)" : output);
        outputView.setTextIsSelectable(true);
        outputView.setPadding(24, 16, 24, 16);
        new AlertDialog.Builder(this)
                .setTitle(command + "  exit=" + exitStatus)
                .setView(outputView)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
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

        private FileRow(String name, String path, boolean directory, boolean parent, long size) {
            this.name = name;
            this.path = path;
            this.directory = directory;
            this.parent = parent;
            this.size = size;
        }

        private static FileRow parent(String path) {
            return new FileRow("..", path, true, true, 0L);
        }

        private static FileRow local(File file) {
            return new FileRow(file.getName(), file.getAbsolutePath(), file.isDirectory(), false, file.length());
        }

        private static FileRow remote(String name, String path, boolean directory, long size) {
            return new FileRow(name, path, directory, false, size);
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
