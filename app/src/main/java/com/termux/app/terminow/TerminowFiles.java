package com.termux.app.terminow;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Move files between shared storage and the home directory of the app with the Android Storage
 * Access Framework, which needs no storage permission. The user picks the files in the system
 * file picker, which gives content URIs, never file paths, so they are read and written with
 * the content resolver. Programs of the terminal, like sftp, need real files, so imported files
 * are copied into `~/Imports`.
 */
public class TerminowFiles {

    public static final int REQUEST_IMPORT_FILES = 2010;
    public static final int REQUEST_EXPORT_FILE = 2011;

    /** The folder in the home directory that files are imported into. */
    public static final String IMPORTS_DIR_NAME = "Imports";

    private static final String LOG_TAG = "TerminowFiles";

    private final TermuxActivity mActivity;
    /** The file of the home directory to write to the document that the user creates. */
    @Nullable
    private File mFileToExport;
    /** The SFTP session to offer to upload the imported files in, {@code null} if none. */
    @Nullable
    private TerminalSession mSftpSession;

    public TerminowFiles(@NonNull TermuxActivity activity) {
        mActivity = activity;
    }

    @NonNull
    public static File getHomeDir() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
    }

    /**
     * Show the system file picker to copy files from shared storage or other apps into
     * `~/Imports`. If an SFTP session is given, the files can then be uploaded in it.
     */
    public void importFiles(@Nullable TerminalSession sftpSession) {
        mSftpSession = sftpSession;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            mActivity.startActivityForResult(intent, REQUEST_IMPORT_FILES);
        } catch (Exception e) {
            mActivity.showToast(mActivity.getString(R.string.terminow_files_failed, String.valueOf(e.getMessage())), true);
        }
    }

    /** Let the user pick a file of the home directory and then where to save it with the system file picker. */
    public void exportFile() {
        showFolder(getHomeDir());
    }

    private void showFolder(@NonNull File folder) {
        File home = getHomeDir();
        File[] files = folder.listFiles();
        List<File> entries = new ArrayList<>();
        if (files != null) entries.addAll(Arrays.asList(files));
        entries.sort((a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        List<CharSequence> names = new ArrayList<>();
        boolean canGoUp = !folder.getAbsolutePath().equals(home.getAbsolutePath());
        if (canGoUp) names.add("..");
        for (File entry : entries)
            names.add(entry.getName() + (entry.isDirectory() ? "/" : ""));

        String relative = folder.getAbsolutePath().substring(home.getAbsolutePath().length());
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle("~" + relative)
            .setItems(names.toArray(new CharSequence[0]), (dialog, which) -> {
                if (canGoUp && which == 0) {
                    File parent = folder.getParentFile();
                    showFolder(parent != null ? parent : home);
                    return;
                }
                File entry = entries.get(canGoUp ? which - 1 : which);
                if (entry.isDirectory())
                    showFolder(entry);
                else
                    createDocument(entry);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void createDocument(@NonNull File file) {
        mFileToExport = file;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, file.getName());
        try {
            mActivity.startActivityForResult(intent, REQUEST_EXPORT_FILE);
        } catch (Exception e) {
            mActivity.showToast(mActivity.getString(R.string.terminow_files_failed, String.valueOf(e.getMessage())), true);
        }
    }

    /** Handle the result of the file pickers. Returns {@code true} if it was for them. */
    public boolean onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode != REQUEST_IMPORT_FILES && requestCode != REQUEST_EXPORT_FILE) return false;
        if (resultCode != Activity.RESULT_OK || data == null) return true;

        if (requestCode == REQUEST_EXPORT_FILE) {
            Uri uri = data.getData();
            File file = mFileToExport;
            mFileToExport = null;
            if (uri == null || file == null) return true;
            new Thread(() -> {
                String message;
                try (InputStream in = new FileInputStream(file);
                     OutputStream out = mActivity.getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IOException("The file can not be written");
                    copy(in, out);
                    message = mActivity.getString(R.string.terminow_files_exported, file.getName());
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to export \"" + file + "\"", e);
                    message = mActivity.getString(R.string.terminow_files_failed, String.valueOf(e.getMessage()));
                }
                String finalMessage = message;
                mActivity.runOnUiThread(() -> mActivity.showToast(finalMessage, true));
            }).start();
            return true;
        }

        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++)
                uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        TerminalSession sftpSession = mSftpSession;
        mSftpSession = null;
        if (uris.isEmpty()) return true;

        new Thread(() -> {
            List<File> imported = new ArrayList<>();
            String error = null;
            for (Uri uri : uris) {
                try {
                    imported.add(importUri(uri));
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to import \"" + uri + "\"", e);
                    error = e.getMessage();
                }
            }
            String finalError = error;
            mActivity.runOnUiThread(() -> showImported(imported, finalError, sftpSession));
        }).start();
        return true;
    }

    /** Copy the content of a URI into `~/Imports`, with a file name that is safe to type in a shell. */
    @NonNull
    private File importUri(@NonNull Uri uri) throws IOException {
        String name = null;
        try (Cursor cursor = mActivity.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0))
                name = cursor.getString(0);
        }
        File dir = new File(getHomeDir(), IMPORTS_DIR_NAME);
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new IOException("Failed to create \"" + dir + "\"");

        File file = new File(dir, getSafeName(name));
        String base = file.getName();
        int dot = base.lastIndexOf('.');
        for (int i = 2; file.exists(); i++)
            file = new File(dir, dot > 0 ? base.substring(0, dot) + "_" + i + base.substring(dot) : base + "_" + i);

        try (InputStream in = mActivity.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(file)) {
            if (in == null) throw new IOException("The file can not be read");
            copy(in, out);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            throw e;
        }
        return file;
    }

    /** A file name with only characters that need no quoting in a shell or in sftp. */
    @NonNull
    static String getSafeName(@Nullable String name) {
        String safe = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^[.-]+", "");
        if (safe.isEmpty()) safe = "file";
        return safe.length() > 120 ? safe.substring(safe.length() - 120) : safe;
    }

    private void showImported(@NonNull List<File> imported, @Nullable String error, @Nullable TerminalSession sftpSession) {
        if (imported.isEmpty()) {
            mActivity.showToast(mActivity.getString(R.string.terminow_files_failed, String.valueOf(error)), true);
            return;
        }

        StringBuilder message = new StringBuilder();
        for (File file : imported)
            message.append("~/").append(IMPORTS_DIR_NAME).append("/").append(file.getName()).append("\n");
        if (error != null)
            message.append("\n").append(mActivity.getString(R.string.terminow_files_failed, error));

        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(mActivity.getResources().getQuantityString(R.plurals.terminow_files_imported, imported.size(), imported.size()))
            .setMessage(message.toString().trim())
            .setPositiveButton(android.R.string.ok, null);
        if (sftpSession != null && sftpSession.isRunning()) {
            // Only when the user asks for it, and with names that need no quoting
            builder.setNeutralButton(R.string.terminow_files_upload, (dialog, which) -> {
                for (File file : imported)
                    sftpSession.write("put " + file.getAbsolutePath() + "\r");
            });
        }
        builder.show();
    }

    private static void copy(@NonNull InputStream in, @NonNull OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1)
            out.write(buffer, 0, read);
    }

    /**
     * Explain how shared storage is used without the all files access permission, for the
     * `termux-setup-storage` command. Android 10 and higher do not let apps that target
     * them read or write shared storage by path without that permission.
     */
    public void showStorageAccessInfo() {
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_storage_title)
            .setMessage(mActivity.getString(R.string.terminow_storage_message, Build.VERSION.RELEASE))
            .setPositiveButton(R.string.terminow_files_import, (dialog, which) -> importFiles(null))
            .setNeutralButton(R.string.terminow_files_export, (dialog, which) -> exportFile())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

}
