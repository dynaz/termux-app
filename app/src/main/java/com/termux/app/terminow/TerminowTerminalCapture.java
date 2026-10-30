package com.termux.app.terminow;

import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;
import com.termux.view.TerminalView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * In-app Scroll Screenshot: renders the active session's retained terminal buffer to PNG(s) with an
 * offscreen renderer, never using Android's system scrolling screenshot and never scrolling, typing
 * to, or otherwise changing the live terminal. The buffer is snapshotted once (a detached copy), so
 * incoming output cannot shift the captured content, and the capture excludes toolbars, the keyboard
 * and other sessions. Large captures are paged into numbered PNGs with bounded memory (one page
 * bitmap at a time) and a cancelable progress dialog. Everything is processed on-device.
 */
public final class TerminowTerminalCapture {

    /** Keep each page within safe bitmap dimensions; taller captures split into numbered PNGs. */
    private static final int MAX_PAGE_PX = 4000;

    private final TermuxActivity mActivity;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private android.widget.ProgressBar mProgressBar;

    public TerminowTerminalCapture(@NonNull TermuxActivity activity) {
        mActivity = activity;
    }

    /** Entry point from the toolbar: choose what to capture. */
    public void start() {
        TerminalView view = mActivity.getTerminalView();
        if (view == null || mActivity.getCurrentSession() == null) return;
        CharSequence[] options = {
            mActivity.getString(R.string.terminow_capture_visible),
            mActivity.getString(R.string.terminow_capture_range),
            mActivity.getString(R.string.terminow_capture_all),
        };
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_capture_title)
            .setItems(options, (d, which) -> {
                int top = view.captureHistoryTop();
                int bottom = view.captureBottom();
                if (which == 0) {
                    int visTop = Math.max(top, view.captureVisibleTop());
                    capture(Math.min(visTop, bottom), bottom);
                } else if (which == 2) {
                    capture(top, bottom);
                } else {
                    showRangeDialog(top, bottom);
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Let the user adjust the first and last captured lines (1 = oldest retained line). */
    private void showRangeDialog(int top, int bottom) {
        int total = bottom - top + 1;
        LinearLayout box = new LinearLayout(mActivity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, pad, pad, 0);
        TextView note = new TextView(mActivity);
        note.setText(mActivity.getString(R.string.terminow_capture_range_note, total));
        note.setTextColor(mActivity.getColor(R.color.terminow_text_secondary));
        box.addView(note);
        EditText first = lineInput(1);
        EditText last = lineInput(total);
        box.addView(labeled(R.string.terminow_capture_range_first, first));
        box.addView(labeled(R.string.terminow_capture_range_last, last));
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_capture_range)
            .setView(box)
            .setPositiveButton(R.string.terminow_capture_do, (d, w) -> {
                int f = clamp(parse(first, 1), 1, total);
                int l = clamp(parse(last, total), f, total);
                capture(top + (f - 1), top + (l - 1));
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void capture(int firstExternal, int lastExternal) {
        TerminalView view = mActivity.getTerminalView();
        if (view == null) return;
        // Snapshot now, on the main thread, so output cannot shift the content mid-capture.
        TerminalView.CaptureSource src = view.captureSource(firstExternal, lastExternal);
        if (src == null || src.totalRows <= 0) return;

        int pageRows = Math.max(1, MAX_PAGE_PX / Math.max(1, src.lineHeight));
        int pageCount = (src.totalRows + pageRows - 1) / pageRows;

        File dir = new File(mActivity.getCacheDir(), "explorer/captures");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());

        AlertDialog progress = buildProgressDialog(pageCount);
        boolean[] cancelled = {false};
        progress.setOnCancelListener(d -> cancelled[0] = true);
        progress.show();

        List<File> files = new ArrayList<>();
        renderPage(view, src, 0, pageRows, pageCount, dir, stamp, files, cancelled, progress);
    }

    /** Render one page, write its PNG, then post the next — cancelable, one bitmap in memory at a time. */
    private void renderPage(TerminalView view, TerminalView.CaptureSource src, int page, int pageRows,
                            int pageCount, File dir, String stamp, List<File> files,
                            boolean[] cancelled, AlertDialog progress) {
        if (cancelled[0]) { progress.dismiss(); cleanup(files); return; }
        int firstRow = page * pageRows;
        int rowCount = Math.min(pageRows, src.totalRows - firstRow);
        try {
            Bitmap bitmap = view.renderCapturePage(src, firstRow, rowCount);
            String name = pageCount > 1
                ? "terminow-capture-" + stamp + "-" + (page + 1) + ".png"
                : "terminow-capture-" + stamp + ".png";
            File out = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            bitmap.recycle();
            files.add(out);
        } catch (Throwable t) {
            progress.dismiss();
            cleanup(files);
            toast(mActivity.getString(R.string.terminow_capture_failed));
            Logger.logStackTraceWithMessage("TerminowCapture", "capture page failed", t);
            return;
        }
        int done = page + 1;
        setProgress(progress, done);
        if (done < pageCount) {
            mHandler.post(() -> renderPage(view, src, done, pageRows, pageCount, dir, stamp, files, cancelled, progress));
        } else {
            progress.dismiss();
            showResult(files, src.totalRows);
        }
    }

    private void showResult(@NonNull List<File> files, int capturedRows) {
        if (files.isEmpty()) return;
        LinearLayout box = new LinearLayout(mActivity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        box.setPadding(pad, pad, pad, 0);

        ImageView preview = new ImageView(mActivity);
        preview.setAdjustViewBounds(true);
        preview.setImageBitmap(thumbnail(files.get(0)));
        box.addView(preview);

        TextView info = new TextView(mActivity);
        info.setTextColor(mActivity.getColor(R.color.terminow_text_secondary));
        info.setText(files.size() > 1
            ? mActivity.getString(R.string.terminow_capture_pages, files.size(), capturedRows)
            : mActivity.getString(R.string.terminow_capture_lines, capturedRows));
        info.setPadding(0, dp(8), 0, 0);
        box.addView(info);

        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_capture_ready)
            .setView(box)
            .setPositiveButton(R.string.terminow_capture_save, (d, w) -> save(files))
            .setNeutralButton(R.string.terminow_capture_share, (d, w) -> share(files))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void save(@NonNull List<File> files) {
        int saved = 0;
        for (File f : files) if (saveToGallery(f)) saved++;
        toast(mActivity.getString(R.string.terminow_capture_saved, saved));
    }

    private boolean saveToGallery(@NonNull File file) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, file.getName());
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TermiNow");
                Uri uri = mActivity.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return false;
                try (OutputStream os = mActivity.getContentResolver().openOutputStream(uri);
                     java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
                }
                return true;
            } else {
                String saved = MediaStore.Images.Media.insertImage(mActivity.getContentResolver(), file.getAbsolutePath(), file.getName(), null);
                return saved != null;
            }
        } catch (Throwable t) {
            Logger.logStackTraceWithMessage("TerminowCapture", "save failed", t);
            return false;
        }
    }

    private void share(@NonNull List<File> files) {
        try {
            String authority = mActivity.getPackageName() + ".explorer";
            ArrayList<Uri> uris = new ArrayList<>();
            for (File f : files) uris.add(FileProvider.getUriForFile(mActivity, authority, f));
            Intent intent;
            if (uris.size() == 1) {
                intent = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                intent = new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            intent.setType("image/png");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            mActivity.startActivity(Intent.createChooser(intent, mActivity.getString(R.string.terminow_capture_share)));
        } catch (Throwable t) {
            Logger.logStackTraceWithMessage("TerminowCapture", "share failed", t);
            toast(mActivity.getString(R.string.terminow_capture_failed));
        }
    }

    // --- small helpers ---

    private Bitmap thumbnail(@NonNull File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int sample = 1;
        int maxW = dp(280);
        while (bounds.outWidth / sample > maxW * 2) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }

    private void cleanup(@NonNull List<File> files) {
        for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    private AlertDialog buildProgressDialog(int pageCount) {
        mProgressBar = new android.widget.ProgressBar(mActivity, null, android.R.attr.progressBarStyleHorizontal);
        mProgressBar.setMax(Math.max(1, pageCount));
        LinearLayout box = new LinearLayout(mActivity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, pad);
        box.addView(mProgressBar);
        return new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_capture_working)
            .setView(box)
            .setNegativeButton(android.R.string.cancel, (d, w) -> d.cancel())
            .setCancelable(true)
            .create();
    }

    private void setProgress(@NonNull AlertDialog dialog, int done) {
        if (mProgressBar != null) mProgressBar.setProgress(done);
    }

    private EditText lineInput(int value) {
        EditText e = new EditText(mActivity);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(value));
        e.setTextColor(mActivity.getColor(R.color.terminow_text));
        return e;
    }

    private LinearLayout labeled(int labelRes, EditText input) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView label = new TextView(mActivity);
        label.setText(labelRes);
        label.setTextColor(mActivity.getColor(R.color.terminow_text));
        label.setMinWidth(dp(96));
        row.addView(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(input, lp);
        return row;
    }

    private int parse(EditText e, int fallback) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private int dp(float v) {
        return Math.round(v * mActivity.getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        mActivity.showToast(message, true);
    }
}
