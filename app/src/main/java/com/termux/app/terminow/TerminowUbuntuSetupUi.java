package com.termux.app.terminow;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.terminow.linux.TerminowLinuxCatalog;
import com.termux.app.terminow.linux.TerminowLinuxEnv;
import com.termux.app.terminow.linux.TerminowLinuxInstaller;
import com.termux.app.terminow.linux.TerminowUbuntuSetup;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screen of the automatic Ubuntu setup, see {@link TerminowUbuntuSetup}: its stages, the
 * download, the details of every step, and Retry, Copy log and Open Ubuntu.
 *
 * The job belongs to the process of the app, not to the screen: closing the screen or a new
 * activity shows the job that runs again, and a second tap never starts a second job.
 */
public class TerminowUbuntuSetupUi {

    /** Opens an installed environment in a terminal tab, with its user, folder and theme. */
    public interface Opener {
        void open(@NonNull String alias);
    }

    private static final String PREFERENCES = "terminow_ubuntu_setup";
    private static final String KEY_STATE = "state";
    private static final long MAX_LOG_BYTES = 512 * 1024;

    @Nullable private static TerminowUbuntuSetup sSetup;
    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();
    private static boolean sRecovered;
    /** The Linux screen of the current activity, which makes installers. */
    @Nullable private static volatile TerminowLinuxUi sLinux;
    @Nullable private static volatile TerminowLinuxInstaller sInstaller;

    private final Activity mActivity;
    private final TerminowLinuxUi mLinux;
    private final Opener mOpener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public TerminowUbuntuSetupUi(@NonNull Activity activity, @NonNull TerminowLinuxUi linux, @NonNull Opener opener) {
        mActivity = activity;
        mLinux = linux;
        mOpener = opener;
        sLinux = linux;
    }

    /** The one job of the process, made the first time; a setup that the process died in is found. */
    @NonNull
    TerminowUbuntuSetup setup() {
        synchronized (TerminowUbuntuSetupUi.class) {
            if (sSetup == null) {
                Context app = mActivity.getApplicationContext();
                SharedPreferences preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
                File logFile = logFile(app);
                sSetup = new TerminowUbuntuSetup(new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH), mLinux.getRegistry(),
                    (command, output) -> runCommand(app, command, output),
                    new TerminowUbuntuSetup.Installer() {
                        @NonNull
                        @Override
                        public TerminowLinuxEnv install(@NonNull TerminowLinuxCatalog.Entry entry, @NonNull String alias,
                                                        @NonNull TerminowLinuxInstaller.Listener listener) throws IOException {
                            long usable = new StatFs(TermuxConstants.TERMUX_FILES_DIR_PATH).getAvailableBytes();
                            TerminowLinuxInstaller installer = currentLinux().newInstaller();
                            sInstaller = installer;
                            try {
                                return installer.install(entry, alias, TerminowLinuxUi.deviceArch(), usable, listener);
                            } finally {
                                sInstaller = null;
                            }
                        }

                        @NonNull
                        @Override
                        public String freeAlias(@NonNull String distroId) {
                            return currentLinux().newInstaller().freeAlias(distroId);
                        }

                        @Override
                        public void cancel() {
                            TerminowLinuxInstaller installer = sInstaller;
                            if (installer != null) installer.cancel();
                        }
                    },
                    new TerminowUbuntuSetup.StateStore() {
                        @Nullable
                        @Override
                        public String load() {
                            return preferences.getString(KEY_STATE, null);
                        }

                        @Override
                        public void save(@NonNull String state) {
                            preferences.edit().putString(KEY_STATE, state).apply();
                        }
                    },
                    new FileLog(logFile));
            }
            if (!sRecovered) {
                sRecovered = true;
                sSetup.recoverInterrupted();
            }
            return sSetup;
        }
    }

    @NonNull
    private static TerminowLinuxUi currentLinux() {
        TerminowLinuxUi linux = sLinux;
        if (linux == null) throw new IllegalStateException("No Linux screen");
        return linux;
    }

    @NonNull
    static File logFile(@NonNull Context context) {
        return new File(context.getFilesDir(), "terminow-ubuntu-setup.log");
    }

    /** Run a command of the runtime with the environment of the terminal; stdout and stderr go to the log. */
    private static int runCommand(@NonNull Context context, @NonNull List<String> command,
                                  @NonNull TerminowUbuntuSetup.LineSink output) throws IOException {
        Process process = new TerminowClaudeSetup(context, new TerminowMcpTargets(new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH),
            new File(TermuxConstants.TERMUX_HOME_DIR_PATH))).start(command);
        try {
            process.getOutputStream().close();
        } catch (IOException e) {
            // Nothing is written to it
        }
        Thread errors = new Thread(() -> readLines(process.getErrorStream(), output));
        errors.start();
        readLines(process.getInputStream(), output);
        try {
            errors.join();
            return process.waitFor();
        } catch (InterruptedException e) {
            process.destroy();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
    }

    private static void readLines(@NonNull InputStream stream, @NonNull TerminowUbuntuSetup.LineSink output) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Without colors and the redraws of progress lines
                String clean = line.replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", "");
                int carriage = clean.lastIndexOf('\r');
                if (carriage >= 0) clean = clean.substring(carriage + 1);
                if (!clean.trim().isEmpty()) {
                    synchronized (output) {
                        output.line(clean);
                    }
                }
            }
        } catch (IOException e) {
            // The process ended
        }
    }

    /** The log of the last setup in a file, kept below a size, for the screen and to copy. */
    static final class FileLog implements TerminowUbuntuSetup.LogSink {
        private final File mFile;

        FileLog(@NonNull File file) {
            mFile = file;
        }

        @Override
        public synchronized void append(@NonNull String line) {
            try {
                if (mFile.length() > MAX_LOG_BYTES) keepEnd();
                try (Writer writer = new OutputStreamWriter(new FileOutputStream(mFile, true), StandardCharsets.UTF_8)) {
                    writer.write(line);
                    writer.write('\n');
                }
            } catch (IOException e) {
                // The screen still shows it
            }
        }

        @Override
        public synchronized void clear() {
            //noinspection ResultOfMethodCallIgnored
            mFile.delete();
        }

        private void keepEnd() throws IOException {
            byte[] end;
            try (RandomAccessFile file = new RandomAccessFile(mFile, "r")) {
                long keep = MAX_LOG_BYTES / 2;
                file.seek(file.length() - keep);
                end = new byte[(int) keep];
                file.readFully(end);
            }
            try (FileOutputStream out = new FileOutputStream(mFile, false)) {
                out.write("[earlier lines left out]\n".getBytes(StandardCharsets.UTF_8));
                out.write(end);
            }
        }

        @NonNull
        synchronized String read() {
            try {
                return mFile.isFile() ? new String(Files.readAllBytes(mFile.toPath()), StandardCharsets.UTF_8) : "";
            } catch (IOException e) {
                return "";
            }
        }
    }

    /** Whether a setup runs now in this process. */
    public boolean isRunning() {
        return setup().isRunning();
    }

    // ---------------------------------------------------------------------------------------
    // The screen
    // ---------------------------------------------------------------------------------------

    /** Show the setup: the one that runs, the result of the last one, or what a new one does. */
    public void show() {
        TerminowUbuntuSetup setup = setup();
        LinearLayout box = new LinearLayout(mActivity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), dp(8));

        TextView intro = text(mActivity.getString(R.string.terminow_ubuntu_setup_intro), 14, R.color.terminow_text_secondary);
        box.addView(intro, matchWrap());

        LinearLayout steps = new LinearLayout(mActivity);
        steps.setOrientation(LinearLayout.VERTICAL);
        steps.setPadding(0, dp(10), 0, dp(4));
        box.addView(steps, matchWrap());

        ProgressBar bar = new ProgressBar(mActivity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(ColorStateList.valueOf(color(R.color.terminow_panel_accent)));
        box.addView(bar, matchWrap());

        TextView summary = text("", 14, R.color.terminow_text);
        summary.setPadding(0, dp(8), 0, dp(4));
        box.addView(summary, matchWrap());

        TextView detailsToggle = text("", 14, R.color.terminow_panel_accent);
        detailsToggle.setPadding(0, dp(8), 0, dp(8));
        box.addView(detailsToggle, matchWrap());
        TextView details = text("", 11, R.color.terminow_text_secondary);
        details.setTypeface(Typeface.MONOSPACE);
        details.setTextIsSelectable(true);
        ScrollView detailsScroll = new ScrollView(mActivity);
        detailsScroll.addView(details);
        detailsScroll.setVisibility(View.GONE);
        box.addView(detailsScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
        Runnable toggleText = () -> detailsToggle.setText(detailsScroll.getVisibility() == View.VISIBLE ?
            R.string.terminow_ubuntu_setup_hide_details : R.string.terminow_ubuntu_setup_show_details);
        detailsToggle.setOnClickListener(v -> {
            detailsScroll.setVisibility(detailsScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            if (detailsScroll.getVisibility() == View.VISIBLE) {
                details.setText(readLog());
                detailsScroll.post(() -> detailsScroll.fullScroll(View.FOCUS_DOWN));
            }
            toggleText.run();
        });
        toggleText.run();

        LinearLayout buttons = new LinearLayout(mActivity);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, dp(8), 0, 0);
        box.addView(buttons, matchWrap());

        ScrollView scroll = new ScrollView(mActivity);
        scroll.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_ubuntu_setup_title)
            .setView(scroll)
            .create();

        TerminowUbuntuSetup.Listener listener = new TerminowUbuntuSetup.Listener() {
            @Override
            public void onChanged(@NonNull TerminowUbuntuSetup.State state) {
                mHandler.post(() -> render(dialog, setup, state, steps, bar, summary, buttons));
            }

            @Override
            public void onLog(@NonNull String line) {
                mHandler.post(() -> {
                    if (detailsScroll.getVisibility() != View.VISIBLE) return;
                    details.append(line + "\n");
                    detailsScroll.post(() -> detailsScroll.fullScroll(View.FOCUS_DOWN));
                });
            }
        };
        setup.addListener(listener);
        dialog.setOnDismissListener(d -> setup.removeListener(listener));
        render(dialog, setup, setup.getState(), steps, bar, summary, buttons);
        dialog.show();
    }

    private void render(@NonNull AlertDialog dialog, @NonNull TerminowUbuntuSetup setup, @NonNull TerminowUbuntuSetup.State state,
                        @NonNull LinearLayout steps, @NonNull ProgressBar bar, @NonNull TextView summary, @NonNull LinearLayout buttons) {
        boolean running = setup.isRunning();
        TerminowUbuntuSetup.State.Status status = running ? TerminowUbuntuSetup.State.Status.RUNNING : state.status;
        // A running state without a job is a setup that has not been recovered yet: shown as failed
        if (!running && status == TerminowUbuntuSetup.State.Status.RUNNING) status = TerminowUbuntuSetup.State.Status.FAILED;

        steps.removeAllViews();
        TerminowUbuntuSetup.Stage current = status == TerminowUbuntuSetup.State.Status.FAILED ||
            status == TerminowUbuntuSetup.State.Status.CANCELLED ? state.failedStage : state.stage;
        int currentIndex = current != null ? TerminowUbuntuSetup.STEPS.indexOf(current) : -1;
        for (int i = 0; i < TerminowUbuntuSetup.STEPS.size(); i++) {
            TerminowUbuntuSetup.Stage stage = TerminowUbuntuSetup.STEPS.get(i);
            String mark;
            int colorRes;
            if (status == TerminowUbuntuSetup.State.Status.IDLE) {
                mark = "○";
                colorRes = R.color.terminow_text_secondary;
            } else if (status == TerminowUbuntuSetup.State.Status.READY || i < currentIndex) {
                mark = "✓";
                colorRes = R.color.terminow_panel_accent;
            } else if (i == currentIndex) {
                mark = status == TerminowUbuntuSetup.State.Status.RUNNING ? "●" : "✗";
                colorRes = status == TerminowUbuntuSetup.State.Status.RUNNING ? R.color.terminow_text : R.color.terminow_mcp_warn;
            } else {
                mark = "○";
                colorRes = R.color.terminow_text_secondary;
            }
            TextView row = text(mark + "  " + stageLabel(stage), 14, colorRes);
            row.setPadding(0, dp(3), 0, dp(3));
            if (i == currentIndex && status == TerminowUbuntuSetup.State.Status.RUNNING) row.setTypeface(Typeface.DEFAULT_BOLD);
            steps.addView(row, matchWrap());
        }

        bar.setVisibility(status == TerminowUbuntuSetup.State.Status.RUNNING ? View.VISIBLE : View.GONE);
        boolean downloading = state.stage == TerminowUbuntuSetup.Stage.DOWNLOADING && state.total > 0;
        bar.setIndeterminate(!downloading);
        if (downloading) bar.setProgress((int) (state.done * 1000 / state.total));

        StringBuilder text = new StringBuilder();
        switch (status) {
            case IDLE:
                text.append(mActivity.getString(R.string.terminow_ubuntu_setup_idle));
                break;
            case RUNNING:
                text.append(stageLabel(state.stage));
                if (downloading) text.append(" · ").append(android.text.format.Formatter.formatShortFileSize(mActivity, state.done))
                    .append(" / ").append(android.text.format.Formatter.formatShortFileSize(mActivity, state.total));
                text.append("\n").append(mActivity.getString(R.string.terminow_ubuntu_setup_running_note));
                break;
            case READY:
                text.append(mActivity.getString(R.string.terminow_ubuntu_setup_ready, state.version.isEmpty() ? "Ubuntu" : state.version, state.alias));
                break;
            case CANCELLED:
                text.append(mActivity.getString(R.string.terminow_ubuntu_setup_cancelled));
                break;
            default:
                text.append(mActivity.getString(R.string.terminow_ubuntu_setup_failed,
                    state.failedStage != null ? stageLabel(state.failedStage) : "?", state.error));
        }
        for (String warning : state.warnings) text.append("\n\n⚠ ").append(warning);
        summary.setText(text.toString());

        buttons.removeAllViews();
        if (status == TerminowUbuntuSetup.State.Status.RUNNING) {
            buttons.addView(button(R.string.terminow_ubuntu_setup_close, false, dialog::dismiss));
            buttons.addView(button(android.R.string.cancel, false, setup::cancel));
        } else {
            buttons.addView(button(R.string.terminow_ubuntu_setup_copy_log, false, this::copyLog));
            buttons.addView(button(R.string.terminow_ubuntu_setup_close, false, dialog::dismiss));
            if (status == TerminowUbuntuSetup.State.Status.READY) {
                buttons.addView(button(R.string.terminow_ubuntu_setup_open, true, () -> {
                    dialog.dismiss();
                    mOpener.open(state.alias);
                }));
                buttons.addView(button(R.string.terminow_ubuntu_setup_check_again, false, () -> start(setup)), 0);
            } else {
                buttons.addView(button(status == TerminowUbuntuSetup.State.Status.IDLE ? R.string.terminow_ubuntu_setup_start :
                    R.string.terminow_ubuntu_setup_retry, true, () -> start(setup)));
            }
        }
    }

    private void start(@NonNull TerminowUbuntuSetup setup) {
        // A second tap while one runs only shows it
        if (!setup.start()) return;
        sExecutor.execute(setup::run);
    }

    @NonNull
    private String stageLabel(@NonNull TerminowUbuntuSetup.Stage stage) {
        switch (stage) {
            case CHECKING_RUNTIME: return mActivity.getString(R.string.terminow_ubuntu_stage_runtime);
            case INSTALLING_DEPENDENCIES: return mActivity.getString(R.string.terminow_ubuntu_stage_dependencies);
            case CHECKING_PACKAGES: return mActivity.getString(R.string.terminow_ubuntu_stage_packages);
            case DOWNLOADING: return mActivity.getString(R.string.terminow_ubuntu_stage_downloading);
            case CONFIGURING: return mActivity.getString(R.string.terminow_ubuntu_stage_configuring);
            case VERIFYING: return mActivity.getString(R.string.terminow_ubuntu_stage_verifying);
            case READY: return mActivity.getString(R.string.terminow_ubuntu_stage_ready);
            case CANCELLED: return mActivity.getString(R.string.terminow_ubuntu_setup_cancelled);
            default: return mActivity.getString(R.string.terminow_ubuntu_stage_failed);
        }
    }

    @NonNull
    private String readLog() {
        return new FileLog(logFile(mActivity)).read();
    }

    /** The log with the state, which has no secrets: commands, their output and the listed variables. */
    private void copyLog() {
        TerminowUbuntuSetup.State state = setup().getState();
        String text = "TermiNow Ubuntu setup\nstatus: " + state.status + "\nstage: " + state.stage +
            (state.failedStage != null ? "\nfailed stage: " + state.failedStage : "") +
            (state.error.isEmpty() ? "" : "\nerror: " + state.error) + "\n\n" + readLog();
        ClipboardManager clipboard = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("TermiNow Ubuntu setup", text));
        android.widget.Toast.makeText(mActivity, R.string.terminow_ubuntu_setup_log_copied, android.widget.Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------------------------------

    @NonNull
    private TextView button(int label, boolean primary, @NonNull Runnable action) {
        TextView view = text(mActivity.getString(label).toUpperCase(Locale.getDefault()), 14,
            primary ? R.color.terminow_panel_accent : R.color.terminow_text);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setBackgroundResource(R.drawable.terminow_action);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    @NonNull
    private TextView text(@NonNull String value, int sp, int colorRes) {
        TextView view = new TextView(mActivity);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color(colorRes));
        return view;
    }

    private int color(int res) {
        return mActivity.getResources().getColor(res, mActivity.getTheme());
    }

    @NonNull
    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, mActivity.getResources().getDisplayMetrics()));
    }

}
