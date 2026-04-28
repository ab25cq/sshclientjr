package com.sshclientjr;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
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

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView pathView;
    private EditText editorInput;
    private Button saveButton;
    private Button closeButton;

    private String host;
    private int port;
    private String username;
    private String password;
    private String privateKey;
    private String passphrase;
    private String remotePath;
    private String localPath;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_editor);

        readIntent();
        bindViews();
        loadFile();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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
    }

    private void bindViews() {
        pathView = findViewById(R.id.editorPathValue);
        editorInput = findViewById(R.id.editorInput);
        saveButton = findViewById(R.id.saveButton);
        closeButton = findViewById(R.id.editorCloseButton);

        editorInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        pathView.setText(TextUtils.isEmpty(remotePath) ? localPath : remotePath);
        saveButton.setText(TextUtils.isEmpty(remotePath) ? R.string.action_save : R.string.action_save_upload);
        saveButton.setOnClickListener(view -> saveFile());
        closeButton.setOnClickListener(view -> finish());
    }

    private void loadFile() {
        executor.execute(() -> {
            try {
                String text = readTextFile(new File(localPath));
                runOnUiThread(() -> editorInput.setText(text));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "ファイルを開けません: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void saveFile() {
        String text = editorInput.getText().toString();
        saveButton.setEnabled(false);
        executor.execute(() -> {
            try {
                writeTextFile(new File(localPath), text);
                if (!TextUtils.isEmpty(remotePath)) {
                    uploadToRemote();
                }
                runOnUiThread(() -> {
                    saveButton.setEnabled(true);
                    Toast.makeText(this, TextUtils.isEmpty(remotePath) ? "保存しました。" : "保存してアップロードしました。", Toast.LENGTH_SHORT).show();
                    if (!TextUtils.isEmpty(remotePath)) {
                        finish();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    saveButton.setEnabled(true);
                    Toast.makeText(this, "保存に失敗しました: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
}
