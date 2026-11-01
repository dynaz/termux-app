package com.termux.app.terminow;

import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The Packet capture screen: two bounded workflows over the shared tools — a live {@code tcpdump}
 * capture that is hard-limited in time and size and written to a {@code .pcap} file in the Linux
 * environment, and {@code tshark} analysis (protocol summary, a bounded packet list, and export) of
 * a capture already on disk, plus a display-gated launch of the Wireshark GUI.
 *
 * <p>Everything this runs is built and validated by {@link TerminowCapture}, so no user value can
 * inject a shell command and no capture or analysis can load a whole file into memory or run without
 * bounds. Nothing is uploaded anywhere: captures stay in the environment and no capture content is
 * ever sent to AI or a third-party service. An in-app packet viewer is intentionally not included in
 * this round (see {@code terminow_cap_deferred}); analysis is through TShark and Wireshark.</p>
 */
public final class TerminowCaptureSheet {

    private static final int PACKET_LIST_LIMIT = 500;
    private static final int MAX_LOG_CHARS = 120_000;
    private static final Pattern USER = Pattern.compile("[a-z_][a-z0-9_-]*");

    private final TermuxActivity mActivity;
    private final File mPrefixDir;
    private final TerminowToolInstaller.Runner mRunner;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    @Nullable private Dialog mDialog;
    @Nullable private TextView mLogView;
    @Nullable private ScrollView mLogScroll;
    @Nullable private TextView mStatus;
    @Nullable private LinearLayout mForm;
    private final StringBuilder mLog = new StringBuilder();

    private String mAlias = "";
    private String mUser = "root";
    private boolean mCaptureMode = true;
    private String mExportFormat = "fields";
    @Nullable private String mLastCapture;

    /** A running capture or analysis process, or null; only one runs at a time. */
    @Nullable private volatile Process mRun;
    private volatile boolean mBusy;
    /** null = not checked yet; true/false once the environment has been probed. */
    @Nullable private Boolean mHasTcpdump, mHasTshark, mHasWireshark, mHasDisplay;

    // Capture form fields (kept so a mode switch preserves typed values).
    private String mIface = "any", mFilter = "", mDuration = "60", mSize = "10", mAnalyzeFile = "", mDisplayFilter = "";
    // Live references to the inputs currently on screen, so typed-but-unsaved values survive a rebuild.
    @Nullable private EditText mIfaceField, mFilterField, mDurationField, mSizeField, mFileField, mDisplayFilterField;

    public TerminowCaptureSheet(@NonNull TermuxActivity activity, @NonNull File prefixDir) {
        mActivity = activity;
        mPrefixDir = prefixDir;
        TerminowClaudeSetup setup = new TerminowClaudeSetup(activity, new TerminowMcpTargets(prefixDir,
            new File(TermuxConstants.TERMUX_HOME_DIR_PATH)));
        mRunner = (alias, user, script, args) -> setup.start(TerminowToolInstaller.command(prefixDir, alias, user, script, args));
    }

    /** Open for an environment as a user (root unless the environment names another). */
    public void show(@NonNull String alias, @NonNull String user) {
        mAlias = alias;
        mUser = USER.matcher(user).matches() ? user : "root";
        open();
    }

    // ---------------------------------------------------------------------------------------
    // Layout

    private void open() {
        if (mDialog != null && mDialog.isShowing()) mDialog.dismiss();
        Dialog dialog = new Dialog(mActivity, R.style.TerminowPanelDialog);
        mDialog = dialog;

        LinearLayout rootView = new LinearLayout(mActivity);
        rootView.setOrientation(LinearLayout.VERTICAL);
        rootView.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        rootView.setBackgroundColor(color(R.color.terminow_panel_scrim));
        rootView.setOnClickListener(v -> dialog.dismiss());

        LinearLayout sheet = new LinearLayout(mActivity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClickable(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color(R.color.terminow_panel));
        float radius = dp(24);
        background.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        sheet.setBackground(background);
        sheet.setPadding(dp(16), dp(8), dp(16), dp(16));

        View handle = new View(mActivity);
        GradientDrawable handleShape = new GradientDrawable();
        handleShape.setColor(color(R.color.terminow_text_secondary));
        handleShape.setCornerRadius(dp(2));
        handle.setBackground(handleShape);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(36), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(10);
        sheet.addView(handle, handleParams);

        LinearLayout titleRow = new LinearLayout(mActivity);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(mActivity.getString(R.string.terminow_cap_title), 20, R.color.terminow_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView info = text("ⓘ", 20, R.color.terminow_accent);
        info.setPadding(dp(10), dp(4), dp(10), dp(4));
        info.setContentDescription(mActivity.getString(R.string.terminow_cap_info_title));
        info.setOnClickListener(v -> showInfo());
        titleRow.addView(info);
        sheet.addView(titleRow, matchWrap());

        sheet.addView(modeChips(), matchWrap());

        ScrollView formScroll = new ScrollView(mActivity);
        formScroll.setVerticalScrollBarEnabled(false);
        LinearLayout form = new LinearLayout(mActivity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(0, dp(8), 0, 0);
        formScroll.addView(form);
        mForm = form;
        sheet.addView(formScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        rootView.addView(sheet, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rootView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int cap = mActivity.getResources().getConfiguration().screenWidthDp >= 720 ? dp(900) : dp(640);
            int width = Math.min(r - l, cap);
            int maxHeight = Math.round((b - t) * 0.92f);
            ViewGroup.LayoutParams params = sheet.getLayoutParams();
            ViewGroup.LayoutParams scrollParams = formScroll.getLayoutParams();
            int scrollMax = maxHeight - dp(90);
            boolean tooTall = form.getHeight() > scrollMax;
            int scrollHeight = tooTall ? scrollMax : ViewGroup.LayoutParams.WRAP_CONTENT;
            if (params.width != width || scrollParams.height != scrollHeight) {
                params.width = width;
                scrollParams.height = scrollHeight;
                sheet.post(() -> { sheet.setLayoutParams(params); formScroll.setLayoutParams(scrollParams); });
            }
        });

        dialog.setContentView(rootView);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.setOnDismissListener(d -> {
            // Leaving never stops a running capture — it is time/size bounded and finishes writing its
            // file. Only the Stop action ends it. We just stop updating a view that is gone.
            if (mDialog == dialog) { mDialog = null; mLogView = null; mLogScroll = null; mStatus = null; mForm = null; }
        });
        renderForm();
        dialog.show();
        probeEnvironment();
    }

    private HorizontalScrollView modeChips() {
        HorizontalScrollView scroll = new HorizontalScrollView(mActivity);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(chip(mActivity.getString(R.string.terminow_cap_mode_capture), true));
        row.addView(chip(mActivity.getString(R.string.terminow_cap_mode_analyze), false));
        scroll.addView(row);
        return scroll;
    }

    private TextView chip(@NonNull String label, boolean captureChip) {
        boolean selected = mCaptureMode == captureChip;
        TextView chip = text(label, 13, selected ? R.color.terminow_bar_home : R.color.terminow_text);
        chip.setPadding(dp(16), dp(7), dp(16), dp(7));
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(16));
        shape.setColor(color(selected ? R.color.terminow_panel_accent : R.color.terminow_panel_button));
        chip.setBackground(shape);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> {
            if (mCaptureMode == captureChip) return;
            saveInputs();
            mCaptureMode = captureChip;
            renderForm();
        });
        return chip;
    }

    // ---------------------------------------------------------------------------------------
    // The form (rebuilt on mode switch / state change)

    private void renderForm() {
        LinearLayout form = mForm;
        if (form == null) return;
        saveInputs();   // keep anything the user typed before the views are thrown away
        form.removeAllViews();
        mLogView = null;
        mLogScroll = null;
        mStatus = null;
        mIfaceField = mFilterField = mDurationField = mSizeField = mFileField = mDisplayFilterField = null;

        if (mCaptureMode) renderCapture(form); else renderAnalyze(form);

        // Shared status line + log, present in both modes.
        TextView status = text("", 13, R.color.terminow_text_secondary);
        status.setPadding(0, dp(6), 0, dp(4));
        mStatus = status;
        form.addView(status, matchWrap());

        LinearLayout logActions = new LinearLayout(mActivity);
        logActions.setOrientation(LinearLayout.HORIZONTAL);
        logActions.setGravity(Gravity.END);
        logActions.addView(textButton(mActivity.getString(R.string.terminow_cap_clear), false, () -> { clearLog(); render(); }));
        form.addView(logActions, matchWrap());

        ScrollView logScroll = new ScrollView(mActivity);
        GradientDrawable logBg = new GradientDrawable();
        logBg.setColor(color(R.color.terminow_panel_card));
        logBg.setCornerRadius(dp(10));
        logScroll.setBackground(logBg);
        logScroll.setPadding(dp(10), dp(8), dp(10), dp(8));
        TextView logView = new TextView(mActivity);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(color(R.color.terminow_text_secondary));
        logView.setTextIsSelectable(true);
        logScroll.addView(logView);
        mLogView = logView;
        mLogScroll = logScroll;
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170));
        logLp.topMargin = dp(2);
        form.addView(logScroll, logLp);

        TextView deferred = text(mActivity.getString(R.string.terminow_cap_deferred), 11, R.color.terminow_text_secondary);
        deferred.setPadding(0, dp(8), 0, 0);
        form.addView(deferred, matchWrap());

        render();
    }

    private void renderCapture(@NonNull LinearLayout form) {
        if (Boolean.FALSE.equals(mHasTcpdump))
            form.addView(hint(mActivity.getString(R.string.terminow_cap_need_tcpdump)), matchWrap());
        EditText iface = field(R.string.terminow_cap_iface, R.string.terminow_cap_iface_hint, mIface, InputType.TYPE_CLASS_TEXT, form);
        EditText filter = field(R.string.terminow_cap_filter, R.string.terminow_cap_filter_hint, mFilter, InputType.TYPE_CLASS_TEXT, form);
        EditText duration = field(R.string.terminow_cap_duration, 0, mDuration, InputType.TYPE_CLASS_NUMBER, form);
        EditText size = field(R.string.terminow_cap_size, 0, mSize, InputType.TYPE_CLASS_NUMBER, form);
        mIfaceField = iface; mFilterField = filter; mDurationField = duration; mSizeField = size;

        LinearLayout actions = new LinearLayout(mActivity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(6), 0, 0);
        if (mBusy) {
            actions.addView(textButton(mActivity.getString(R.string.terminow_cap_stop), true, this::stopRun));
        } else {
            boolean locked = !TerminowAccountUi.isPremium(new TerminowStore(mActivity));
            actions.addView(textButton(mActivity.getString(locked ? R.string.terminow_cap_start_premium : R.string.terminow_cap_start), true,
                () -> startCapture(iface.getText().toString(), filter.getText().toString(),
                    duration.getText().toString(), size.getText().toString())));
        }
        form.addView(actions, matchWrap());
    }

    private void renderAnalyze(@NonNull LinearLayout form) {
        if (Boolean.FALSE.equals(mHasTshark))
            form.addView(hint(mActivity.getString(R.string.terminow_cap_need_tshark)), matchWrap());
        String prefill = !mAnalyzeFile.isEmpty() ? mAnalyzeFile : (mLastCapture != null ? mLastCapture : "");
        EditText file = field(R.string.terminow_cap_file, R.string.terminow_cap_file_hint, prefill, InputType.TYPE_CLASS_TEXT, form);
        EditText displayFilter = field(R.string.terminow_cap_display_filter, R.string.terminow_cap_display_filter_hint, mDisplayFilter, InputType.TYPE_CLASS_TEXT, form);
        mFileField = file; mDisplayFilterField = displayFilter;

        LinearLayout actions = new LinearLayout(mActivity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.START);
        actions.setPadding(0, dp(6), 0, 0);
        boolean locked = !TerminowAccountUi.isPremium(new TerminowStore(mActivity));
        if (mBusy) {
            actions.addView(textButton(mActivity.getString(R.string.terminow_cap_stop), true, this::stopRun));
        } else {
            actions.addView(textButton(gateLabel(locked, R.string.terminow_cap_summary), true,
                () -> analyzeSummary(file.getText().toString())));
            actions.addView(textButton(mActivity.getString(R.string.terminow_cap_packets, PACKET_LIST_LIMIT), false,
                () -> analyzePackets(file.getText().toString(), displayFilter.getText().toString())));
        }
        form.addView(wrapRow(actions), matchWrap());

        // Export row (format toggle + export button).
        LinearLayout exportRow = new LinearLayout(mActivity);
        exportRow.setOrientation(LinearLayout.HORIZONTAL);
        exportRow.setGravity(Gravity.CENTER_VERTICAL);
        exportRow.setPadding(0, dp(4), 0, 0);
        exportRow.addView(text(mActivity.getString(R.string.terminow_cap_format) + ": ", 12, R.color.terminow_text_secondary));
        exportRow.addView(formatChip("CSV", "fields"));
        exportRow.addView(formatChip("JSON", "json"));
        if (!mBusy) exportRow.addView(textButton(mActivity.getString(R.string.terminow_cap_export), false,
            () -> analyzeExport(file.getText().toString(), displayFilter.getText().toString())));
        form.addView(exportRow, matchWrap());

        // Wireshark launch — only when a display server is present (verified), else say why.
        if (Boolean.TRUE.equals(mHasWireshark)) {
            if (Boolean.TRUE.equals(mHasDisplay)) {
                if (!mBusy) form.addView(textButton(mActivity.getString(R.string.terminow_cap_wireshark), false,
                    () -> launchWireshark(file.getText().toString())), matchWrap());
            } else if (Boolean.FALSE.equals(mHasDisplay)) {
                form.addView(hint(mActivity.getString(R.string.terminow_cap_wireshark_nodisplay)), matchWrap());
            }
        }
    }

    private View wrapRow(@NonNull LinearLayout inner) {
        HorizontalScrollView scroll = new HorizontalScrollView(mActivity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(inner);
        return scroll;
    }

    private TextView formatChip(@NonNull String label, @NonNull String format) {
        boolean selected = mExportFormat.equals(format);
        TextView chip = text(label, 12, selected ? R.color.terminow_bar_home : R.color.terminow_text);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(12));
        shape.setColor(color(selected ? R.color.terminow_panel_accent : R.color.terminow_panel_button));
        chip.setBackground(shape);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> { mExportFormat = format; saveInputs(); renderForm(); });
        return chip;
    }

    private String gateLabel(boolean locked, int labelRes) {
        return locked ? mActivity.getString(labelRes) + " ♛" : mActivity.getString(labelRes);
    }

    // ---------------------------------------------------------------------------------------
    // Actions

    private void startCapture(String iface, String filter, String durationText, String sizeText) {
        saveInputs(iface, filter, durationText, sizeText, mAnalyzeFile, mDisplayFilter);
        if (mBusy) { toast(R.string.terminow_cap_running_other); return; }
        TerminowCapture.Spec spec;
        String outFile;
        try {
            spec = new TerminowCapture.Spec(iface.trim().isEmpty() ? "any" : iface.trim(), filter,
                parseInt(durationText, TerminowCapture.DURATION_DEFAULT), parseInt(sizeText, TerminowCapture.SIZE_DEFAULT_MB), 0);
            if (!spec.isValid()) { toast(R.string.terminow_cap_bad_input); return; }
            outFile = newCapturePath();
            if (!TerminowCapture.isValidCapturePath(outFile)) { toast(R.string.terminow_cap_bad_input); return; }
        } catch (RuntimeException e) {
            toast(R.string.terminow_cap_bad_input);
            return;
        }
        final List<String> argv;
        try {
            argv = TerminowCapture.tcpdumpArgv(spec, outFile);
        } catch (IllegalArgumentException e) {
            toast(R.string.terminow_cap_bad_input);
            return;
        }
        TerminowPremiumGate.require(mActivity, new TerminowStore(mActivity), TerminowPremiumGate.Feature.PACKET_CAPTURE, 0, () -> {
            clearLog();
            setStatus(mActivity.getString(R.string.terminow_cap_running, outFile));
            mLastCapture = outFile;
            // The capture dir is created first (as its own positional arg), then the bounded argv runs.
            runStreaming(captureDir(), argv, code -> {
                if (code == 0) setStatus(mActivity.getString(R.string.terminow_cap_done, outFile));
                else setStatus(mActivity.getString(R.string.terminow_cap_failed));
            });
            render();
        });
    }

    private void analyzeSummary(String file) {
        if (!beginAnalyze(file)) return;
        final List<String> argv;
        try { argv = TerminowCapture.tsharkSummaryArgv(file.trim()); }
        catch (IllegalArgumentException e) { toast(R.string.terminow_cap_bad_file); return; }
        TerminowPremiumGate.require(mActivity, new TerminowStore(mActivity), TerminowPremiumGate.Feature.PACKET_CAPTURE, 0,
            () -> { clearLog(); runStreaming(null, argv, code -> {}); render(); });
    }

    private void analyzePackets(String file, String displayFilter) {
        if (!beginAnalyze(file)) return;
        final List<String> argv;
        try { argv = TerminowCapture.tsharkReadArgv(file.trim(), displayFilter, PACKET_LIST_LIMIT); }
        catch (IllegalArgumentException e) { toast(R.string.terminow_cap_bad_file); return; }
        TerminowPremiumGate.require(mActivity, new TerminowStore(mActivity), TerminowPremiumGate.Feature.PACKET_CAPTURE, 0,
            () -> { clearLog(); runStreaming(null, argv, code -> {}); render(); });
    }

    private void analyzeExport(String file, String displayFilter) {
        if (!beginAnalyze(file)) return;
        String ext = mExportFormat.equals("json") ? ".json" : ".csv";
        String outFile = stripPcapExt(file.trim()) + "-export" + ext;
        final List<String> argv;
        try { argv = TerminowCapture.tsharkExportArgv(file.trim(), mExportFormat, displayFilter); }
        catch (IllegalArgumentException e) { toast(R.string.terminow_cap_bad_file); return; }
        // tshark writes the export to stdout; the run redirects it into outFile (a path we chose).
        TerminowPremiumGate.require(mActivity, new TerminowStore(mActivity), TerminowPremiumGate.Feature.PACKET_CAPTURE, 0, () -> {
            clearLog();
            setStatus(mActivity.getString(R.string.terminow_cap_export_done, outFile));
            runStreamingToFile(argv, outFile, code -> {
                if (code == 0) setStatus(mActivity.getString(R.string.terminow_cap_export_done, outFile));
                else setStatus(mActivity.getString(R.string.terminow_cap_failed));
            });
            render();
        });
    }

    private void launchWireshark(String file) {
        String f = file.trim();
        if (!TerminowCapture.isValidCapturePath(f)) { toast(R.string.terminow_cap_bad_file); return; }
        if (mBusy) { toast(R.string.terminow_cap_running_other); return; }
        // Launch detached so it keeps its own display; the file path is passed as one positional arg.
        runScript("wireshark \"$1\" >/dev/null 2>&1 &\nexit 0\n", code -> {}, f);
    }

    private boolean beginAnalyze(String file) {
        mAnalyzeFile = file.trim();
        if (!TerminowCapture.isValidCapturePath(mAnalyzeFile)) { toast(R.string.terminow_cap_bad_file); return false; }
        if (mBusy) { toast(R.string.terminow_cap_running_other); return false; }
        return true;
    }

    private void stopRun() {
        Process run = mRun;
        if (run != null) run.destroy();
    }

    // ---------------------------------------------------------------------------------------
    // Running — a single process whose stdout+stderr stream into the bounded log

    /** Run a validated argv ({@code exec "$@"}), optionally creating {@code mkdirDir} first. */
    private void runStreaming(@Nullable String mkdirDir, @NonNull List<String> argv, @NonNull OnExit onExit) {
        String script;
        String[] args;
        if (mkdirDir != null) {
            script = "d=\"$1\"; shift; mkdir -p \"$d\" 2>/dev/null; exec \"$@\"\n";
            args = prepend(mkdirDir, argv);
        } else {
            script = "exec \"$@\"\n";
            args = argv.toArray(new String[0]);
        }
        launch(script, args, onExit);
    }

    /** Run a validated argv and redirect its stdout to {@code outFile} (used by export). */
    private void runStreamingToFile(@NonNull List<String> argv, @NonNull String outFile, @NonNull OnExit onExit) {
        // $1 is the output file; the rest ("$@" after shift) is the validated tshark argv. stdout → file.
        String script = "d=\"$1\"; shift; mkdir -p \"$(dirname \"$d\")\" 2>/dev/null; \"$@\" >\"$d\"\n";
        launch(script, prepend(outFile, argv), onExit);
    }

    private void runScript(@NonNull String script, @NonNull OnExit onExit, @NonNull String... args) {
        launch(script, args, onExit);
    }

    private void launch(@NonNull String script, @NonNull String[] args, @NonNull OnExit onExit) {
        if (mBusy) { toast(R.string.terminow_cap_running_other); return; }
        mBusy = true;
        String alias = mAlias, user = mUser;
        mExecutor.execute(() -> {
            int code;
            Process process = null;
            try {
                process = mRunner.start(alias, user, script, args);
                mRun = process;
                final Process p = process;
                Thread err = new Thread(() -> drain(p.getErrorStream()));
                err.setDaemon(true);
                err.start();
                drain(process.getInputStream());   // this thread drains stdout to completion
                err.join(2000);
                code = process.waitFor();
            } catch (IOException | RuntimeException e) {
                appendLine("error: " + e.getMessage());
                code = -1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                code = -1;
            } finally {
                if (process != null && mRun == process) mRun = null;
            }
            final int exit = code;
            mHandler.post(() -> {
                mBusy = false;
                onExit.onExit(exit);
                render();
            });
        });
    }

    private void drain(@NonNull InputStream stream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) appendLine(line);
        } catch (IOException ignored) {
            // the process ended
        }
    }

    private void appendLine(@NonNull String line) {
        synchronized (mLog) {
            mLog.append(line).append('\n');
            if (mLog.length() > MAX_LOG_CHARS) mLog.delete(0, mLog.length() - MAX_LOG_CHARS);
        }
        mHandler.post(this::refreshLog);
    }

    private void refreshLog() {
        TextView view = mLogView;
        ScrollView scroll = mLogScroll;
        if (view == null) return;
        String value;
        synchronized (mLog) { value = mLog.toString(); }
        view.setText(value.isEmpty() ? mActivity.getString(R.string.terminow_cap_log_empty) : value);
        if (scroll != null) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void clearLog() {
        synchronized (mLog) { mLog.setLength(0); }
        refreshLog();
    }

    private void setStatus(@NonNull String message) {
        mHandler.post(() -> { if (mStatus != null) mStatus.setText(message); });
    }

    /** Re-enable/disable action buttons for the current busy state by rebuilding the form. */
    private void render() {
        if (mDialog == null) return;
        // Preserve typed values before the rebuild, then rebuild and restore the log.
        renderForm();
        refreshLog();
    }

    // ---------------------------------------------------------------------------------------
    // Environment probe — installed tools + whether a display server is present (verified)

    private void probeEnvironment() {
        String alias = mAlias, user = mUser;
        mExecutor.execute(() -> {
            Map<String, String> found = TerminowSecurityTools.detect(mRunner, alias, user);
            boolean display = probeDisplay(alias, user);
            mHandler.post(() -> {
                if (!alias.equals(mAlias)) return;
                mHasTcpdump = found.containsKey("tcpdump");
                mHasTshark = found.containsKey("tshark");
                mHasWireshark = found.containsKey("wireshark");
                mHasDisplay = display;
                if (mDialog != null) render();
            });
        });
    }

    private boolean probeDisplay(@NonNull String alias, @NonNull String user) {
        try {
            Process p = mRunner.start(alias, user, "printf %s \"${DISPLAY:-}${WAYLAND_DISPLAY:-}\"\n");
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                int c;
                while ((c = reader.read()) != -1) { out.append((char) c); if (out.length() > 256) break; }
            }
            Thread err = new Thread(() -> drainQuiet(p.getErrorStream()));
            err.setDaemon(true);
            err.start();
            if (!p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) { p.destroy(); return false; }
            return out.toString().trim().length() > 0;
        } catch (IOException | RuntimeException | InterruptedException e) {
            return false;
        }
    }

    private static void drainQuiet(@NonNull InputStream stream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) { /* discard */ }
        } catch (IOException ignored) { }
    }

    private void showInfo() {
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_cap_info_title)
            .setMessage(R.string.terminow_cap_info_message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    // ---------------------------------------------------------------------------------------
    // Helpers

    private interface OnExit { void onExit(int code); }

    private String[] prepend(@NonNull String first, @NonNull List<String> rest) {
        String[] args = new String[rest.size() + 1];
        args[0] = first;
        for (int i = 0; i < rest.size(); i++) args[i + 1] = rest.get(i);
        return args;
    }

    /** The captures directory for the current user inside the environment. */
    private String captureDir() {
        return (mUser.equals("root") ? "/root" : "/home/" + mUser) + "/.terminow/captures";
    }

    private String newCapturePath() {
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new java.util.Date());
        return captureDir() + "/cap-" + stamp + ".pcap";
    }

    private static String stripPcapExt(@NonNull String path) {
        if (path.endsWith(".pcapng")) return path.substring(0, path.length() - 7);
        if (path.endsWith(".pcap")) return path.substring(0, path.length() - 5);
        return path;
    }

    private static int parseInt(@Nullable String value, int fallback) {
        if (value == null) return fallback;
        try { return Integer.parseInt(value.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private void saveInputs() {
        if (mIfaceField != null) mIface = mIfaceField.getText().toString();
        if (mFilterField != null) mFilter = mFilterField.getText().toString();
        if (mDurationField != null) mDuration = mDurationField.getText().toString();
        if (mSizeField != null) mSize = mSizeField.getText().toString();
        if (mFileField != null) mAnalyzeFile = mFileField.getText().toString();
        if (mDisplayFilterField != null) mDisplayFilter = mDisplayFilterField.getText().toString();
    }

    private void saveInputs(String iface, String filter, String duration, String size, String analyzeFile, String displayFilter) {
        mIface = iface; mFilter = filter; mDuration = duration; mSize = size;
        mAnalyzeFile = analyzeFile; mDisplayFilter = displayFilter;
    }

    private void toast(int res) {
        mActivity.showToast(mActivity.getString(res), true);
    }

    private TextView hint(@NonNull String message) {
        TextView view = text(message, 12, R.color.terminow_mcp_warn);
        view.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(R.color.terminow_panel_card));
        bg.setCornerRadius(dp(10));
        view.setBackground(bg);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(8);
        view.setLayoutParams(lp);
        return view;
    }

    private EditText field(int labelRes, int hintRes, @NonNull String value, int inputType, @NonNull LinearLayout parent) {
        TextView label = text(mActivity.getString(labelRes), 12, R.color.terminow_text_secondary);
        label.setPadding(0, dp(6), 0, dp(2));
        parent.addView(label, matchWrap());
        EditText edit = new EditText(mActivity);
        edit.setText(value);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        edit.setTextColor(color(R.color.terminow_text));
        edit.setHintTextColor(color(R.color.terminow_text_secondary));
        edit.setSingleLine(true);
        edit.setInputType(inputType);
        if (hintRes != 0) edit.setHint(hintRes);
        edit.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(R.color.terminow_panel_card));
        bg.setCornerRadius(dp(10));
        edit.setBackground(bg);
        parent.addView(edit, matchWrap());
        return edit;
    }

    private TextView textButton(@NonNull String label, boolean primary, @NonNull Runnable onClick) {
        TextView button = text(label, 13, primary ? R.color.terminow_bar_home : R.color.terminow_text);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(40));
        button.setMinWidth(dp(44));
        button.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(10));
        shape.setColor(color(primary ? R.color.terminow_panel_accent : R.color.terminow_panel_button));
        button.setBackground(shape);
        button.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(8));
        lp.topMargin = dp(4);
        button.setLayoutParams(lp);
        return button;
    }

    private TextView text(@NonNull String value, int sp, int colorRes) {
        TextView view = new TextView(mActivity);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color(colorRes));
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int color(int res) {
        return ContextCompat.getColor(mActivity, res);
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, mActivity.getResources().getDisplayMetrics()));
    }
}
