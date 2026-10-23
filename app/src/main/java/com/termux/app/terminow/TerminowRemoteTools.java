package com.termux.app.terminow;

import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminow.files.TerminowSftpFs;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Runs the tool scripts of {@link TerminowToolInstaller} on a saved SSH host, over a background
 * exec channel of the host's own connection. It reuses the credentials, key and known_hosts of
 * the host, the same ones the SFTP File Manager uses, and never types into the terminal of the
 * tab, so an install can not collide with an editor, Claude Code or a password prompt running
 * there. One connection per host is kept and shared by detection and installing; it is closed
 * when nothing uses it any more.
 */
final class TerminowRemoteTools {

    /** The key that scopes the jobs of a host, apart from local environments. */
    @NonNull
    static String aliasOf(@NonNull TerminowStore.Host host) {
        return "ssh:" + host.getKey();
    }

    private final TermuxActivity mActivity;
    private final TerminowStore mStore;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Map<String, TerminowSftpFs> mConnections = new HashMap<>();

    TerminowRemoteTools(@NonNull TermuxActivity activity, @NonNull TerminowStore store) {
        mActivity = activity;
        mStore = store;
    }

    /** The connection of a host, reused across detection and installs, made on demand. */
    @NonNull
    synchronized TerminowSftpFs connectionFor(@NonNull TerminowStore.Host login) {
        String alias = aliasOf(login);
        TerminowSftpFs fs = mConnections.get(alias);
        if (fs == null) {
            fs = new TerminowSftpFs(new File(TermuxConstants.TERMUX_HOME_DIR_PATH), "tools:" + alias, login.getTitle(),
                login.address, login.port, login.username, login.password, login.keyPath, new Prompts());
            mConnections.put(alias, fs);
        }
        return fs;
    }

    /** A runner that runs a script on the host, as the user it logs in as, over an exec channel. */
    @NonNull
    TerminowToolInstaller.Runner runnerFor(@NonNull TerminowStore.Host login) {
        TerminowSftpFs fs = connectionFor(login);
        return (alias, user, script, args) -> fs.exec(remoteCommand(script, args));
    }

    /**
     * The command to run on the host: the script with a login shell, its first argument {@code
     * terminow-setup} so the tool is {@code $1}, like {@link TerminowClaudeSetup#buildCommand}
     * runs it locally. Everything is quoted for the remote shell.
     */
    @NonNull
    static String remoteCommand(@NonNull String script, @NonNull String... args) {
        StringBuilder command = new StringBuilder("/bin/sh -c ");
        command.append(TerminowStore.shellQuote(script));
        command.append(' ').append(TerminowStore.shellQuote("terminow-setup"));
        for (String arg : args) command.append(' ').append(TerminowStore.shellQuote(arg));
        return command.toString();
    }

    /** Close the connection of a host when no install of it is running. */
    synchronized void closeIdle(@NonNull TerminowStore.Host host) {
        String alias = aliasOf(host);
        if (TerminowToolInstaller.runningIn(alias) != null) return;
        TerminowSftpFs fs = mConnections.remove(alias);
        if (fs != null) {
            TerminowSftpFs closing = fs;
            new Thread(closing::close, "TermiNow close tools ssh").start();
        }
    }

    /** Close every connection that no install is using, for when the sheet is closed. */
    synchronized void closeAllIdle() {
        for (Map.Entry<String, TerminowSftpFs> entry : new HashMap<>(mConnections).entrySet()) {
            if (TerminowToolInstaller.runningIn(entry.getKey()) != null) continue;
            mConnections.remove(entry.getKey());
            TerminowSftpFs fs = entry.getValue();
            new Thread(fs::close, "TermiNow close tools ssh").start();
        }
    }

    /** The questions of a connection, on the main thread, blocking the worker until answered. */
    private final class Prompts implements TerminowSftpFs.Prompts {
        @Override
        public boolean confirmHostKey(@NonNull String host, @NonNull String keyType, @NonNull String fingerprint) {
            AtomicReference<Boolean> answer = new AtomicReference<>(false);
            waitForUi(latch -> dialog()
                .setTitle(R.string.terminow_explorer_host_key_title)
                .setMessage(mActivity.getString(R.string.terminow_explorer_host_key_message, host, keyType, fingerprint))
                .setCancelable(false)
                .setPositiveButton(R.string.terminow_explorer_host_key_accept, (d, w) -> {
                    answer.set(true);
                    latch.countDown();
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> latch.countDown())
                .show());
            return answer.get();
        }

        @Nullable
        @Override
        public String askPassword(@NonNull String prompt) {
            return askSecret(prompt);
        }

        @Nullable
        @Override
        public String askPassphrase(@NonNull String keyName) {
            return askSecret(mActivity.getString(R.string.terminow_explorer_passphrase, keyName));
        }

        @Nullable
        @Override
        public String savedPassphrase(@NonNull String keyName) {
            String passphrase = mStore.getKeyPassphrase(keyName);
            return passphrase.isEmpty() ? null : passphrase;
        }

        @Nullable
        private String askSecret(@NonNull String prompt) {
            AtomicReference<String> answer = new AtomicReference<>(null);
            waitForUi(latch -> {
                EditText field = new EditText(mActivity);
                field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                LinearLayout box = new LinearLayout(mActivity);
                box.setPadding(dp(20), dp(4), dp(20), 0);
                box.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                dialog()
                    .setTitle(prompt)
                    .setView(box)
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        answer.set(field.getText().toString());
                        latch.countDown();
                    })
                    .setNegativeButton(android.R.string.cancel, (d, w) -> latch.countDown())
                    .show();
            });
            return answer.get();
        }
    }

    @NonNull
    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog);
    }

    private void waitForUi(@NonNull Consumer<CountDownLatch> show) {
        CountDownLatch latch = new CountDownLatch(1);
        mHandler.post(() -> {
            if (mActivity.isFinishing() || mActivity.isDestroyed()) {
                latch.countDown();
                return;
            }
            show.accept(latch);
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }
}
