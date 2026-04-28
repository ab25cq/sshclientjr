package com.sshclientjr;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    private static final String PREFERENCES_NAME = "sshclientjr";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_PRIVATE_KEY = "private_key";
    private static final String KEY_KEY_PASSPHRASE = "key_passphrase";
    private static final String KEY_CONNECTION_HISTORY = "connection_history";
    private static final int MAX_HISTORY_SIZE = 10;

    private Spinner historySpinner;
    private EditText hostInput;
    private EditText portInput;
    private EditText usernameInput;
    private EditText passwordInput;
    private EditText privateKeyInput;
    private EditText passphraseInput;
    private CheckBox filerModeCheckBox;
    private Button connectButton;

    private SharedPreferences preferences;
    private ArrayAdapter<String> historyAdapter;
    private final List<ConnectionHistoryEntry> historyEntries = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE);
        bindViews();
        restoreSavedConnection();
        loadHistory();
    }

    private void bindViews() {
        historySpinner = findViewById(R.id.historySpinner);
        hostInput = findViewById(R.id.hostInput);
        portInput = findViewById(R.id.portInput);
        usernameInput = findViewById(R.id.usernameInput);
        passwordInput = findViewById(R.id.passwordInput);
        privateKeyInput = findViewById(R.id.privateKeyInput);
        passphraseInput = findViewById(R.id.passphraseInput);
        filerModeCheckBox = findViewById(R.id.filerModeCheckBox);
        connectButton = findViewById(R.id.connectButton);

        historyAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        historyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        historySpinner.setAdapter(historyAdapter);
        historySpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                if (position <= 0 || position - 1 >= historyEntries.size()) {
                    return;
                }
                applyHistoryEntry(historyEntries.get(position - 1));
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        connectButton.setOnClickListener(view -> connect());
        passphraseInput.setOnEditorActionListener((textView, actionId, keyEvent) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                connect();
                return true;
            }
            return false;
        });
    }

    private void restoreSavedConnection() {
        hostInput.setText(preferences.getString(KEY_HOST, ""));
        portInput.setText(preferences.getString(KEY_PORT, "22"));
        usernameInput.setText(preferences.getString(KEY_USERNAME, ""));
        passwordInput.setText(preferences.getString(KEY_PASSWORD, ""));
        privateKeyInput.setText(preferences.getString(KEY_PRIVATE_KEY, ""));
        passphraseInput.setText(preferences.getString(KEY_KEY_PASSPHRASE, ""));
    }

    private void loadHistory() {
        historyEntries.clear();
        historyAdapter.clear();
        historyAdapter.add(getString(R.string.history_prompt));

        String historyJson = preferences.getString(KEY_CONNECTION_HISTORY, "[]");
        try {
            JSONArray historyArray = new JSONArray(historyJson);
            for (int i = 0; i < historyArray.length(); i++) {
                JSONObject object = historyArray.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                ConnectionHistoryEntry entry = ConnectionHistoryEntry.fromJson(object);
                if (entry != null) {
                    historyEntries.add(entry);
                    historyAdapter.add(entry.getDisplayLabel());
                }
            }
        } catch (JSONException ignored) {
            historyEntries.clear();
        }
        historyAdapter.notifyDataSetChanged();
        historySpinner.setSelection(0);
    }

    private void applyHistoryEntry(ConnectionHistoryEntry entry) {
        hostInput.setText(entry.host);
        portInput.setText(entry.port);
        usernameInput.setText(entry.username);
        passwordInput.setText(entry.password);
        privateKeyInput.setText(entry.privateKey);
        passphraseInput.setText(entry.passphrase);
    }

    private void saveHistoryEntry(ConnectionHistoryEntry entry) {
        List<ConnectionHistoryEntry> updatedEntries = new ArrayList<>();
        updatedEntries.add(entry);
        for (ConnectionHistoryEntry existingEntry : historyEntries) {
            if (!existingEntry.sameTarget(entry)) {
                updatedEntries.add(existingEntry);
            }
            if (updatedEntries.size() >= MAX_HISTORY_SIZE) {
                break;
            }
        }

        JSONArray historyArray = new JSONArray();
        for (ConnectionHistoryEntry updatedEntry : updatedEntries) {
            historyArray.put(updatedEntry.toJson());
        }

        preferences.edit().putString(KEY_CONNECTION_HISTORY, historyArray.toString()).apply();
        loadHistory();
    }

    private void connect() {
        String host = hostInput.getText().toString().trim();
        String portText = portInput.getText().toString().trim();
        String username = usernameInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        String privateKey = privateKeyInput.getText().toString().trim();
        String passphrase = passphraseInput.getText().toString();

        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(portText) || TextUtils.isEmpty(username)) {
            Toast.makeText(this, "ホスト、ポート、ユーザー名は必須です。", Toast.LENGTH_SHORT).show();
            return;
        }
        if (TextUtils.isEmpty(password) && TextUtils.isEmpty(privateKey)) {
            Toast.makeText(this, "パスワードか秘密鍵のどちらかを入力してください。", Toast.LENGTH_SHORT).show();
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "ポート番号が不正です。", Toast.LENGTH_SHORT).show();
            return;
        }

        preferences.edit()
                .putString(KEY_HOST, host)
                .putString(KEY_PORT, portText)
                .putString(KEY_USERNAME, username)
                .putString(KEY_PASSWORD, password)
                .putString(KEY_PRIVATE_KEY, privateKey)
                .putString(KEY_KEY_PASSPHRASE, passphrase)
                .apply();
        saveHistoryEntry(new ConnectionHistoryEntry(host, portText, username, password, privateKey, passphrase));

        Intent intent = filerModeCheckBox.isChecked()
                ? FilerActivity.newIntent(this, host, port, username, password, privateKey, passphrase)
                : TerminalActivity.newIntent(this, host, port, username, password, privateKey, passphrase);
        startActivity(intent);
    }

    private static final class ConnectionHistoryEntry {
        private final String host;
        private final String port;
        private final String username;
        private final String password;
        private final String privateKey;
        private final String passphrase;

        private ConnectionHistoryEntry(String host, String port, String username, String password, String privateKey, String passphrase) {
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
            this.privateKey = privateKey;
            this.passphrase = passphrase;
        }

        private String getDisplayLabel() {
            return username + "@" + host + ":" + port;
        }

        private boolean sameTarget(ConnectionHistoryEntry other) {
            return host.equals(other.host) && port.equals(other.port) && username.equals(other.username);
        }

        private JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put(KEY_HOST, host);
                object.put(KEY_PORT, port);
                object.put(KEY_USERNAME, username);
                object.put(KEY_PASSWORD, password);
                object.put(KEY_PRIVATE_KEY, privateKey);
                object.put(KEY_KEY_PASSPHRASE, passphrase);
            } catch (JSONException ignored) {
            }
            return object;
        }

        private static ConnectionHistoryEntry fromJson(JSONObject object) {
            String host = object.optString(KEY_HOST, "");
            String port = object.optString(KEY_PORT, "22");
            String username = object.optString(KEY_USERNAME, "");
            if (TextUtils.isEmpty(host) || TextUtils.isEmpty(username)) {
                return null;
            }
            return new ConnectionHistoryEntry(
                    host,
                    port,
                    username,
                    object.optString(KEY_PASSWORD, ""),
                    object.optString(KEY_PRIVATE_KEY, ""),
                    object.optString(KEY_KEY_PASSPHRASE, "")
            );
        }
    }
}
