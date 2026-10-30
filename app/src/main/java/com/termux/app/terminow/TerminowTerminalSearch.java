package com.termux.app.terminow;

import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.view.KeyboardUtils;
import com.termux.view.TerminalView;

/**
 * Drives the compact terminal history search bar. It only reads the active session's retained
 * scrollback through {@link TerminalView}'s search API — it never writes to the terminal, touches a
 * running process, or changes the session. Search state lives in the view and is reset when the
 * session changes, so one session's search never shows in another. Only retained history is
 * searchable, which the bar's count and hint make clear.
 */
public final class TerminowTerminalSearch {

    private static final long DEBOUNCE_MS = 120;

    private final TermuxActivity mActivity;
    private final View mBar;
    private final EditText mInput;
    private final TextView mCount;
    private final TextView mCaseToggle;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mCaseSensitive = false;

    private final Runnable mRunSearch = this::runSearch;

    public TerminowTerminalSearch(@NonNull TermuxActivity activity, @NonNull View bar) {
        mActivity = activity;
        mBar = bar;
        mInput = bar.findViewById(R.id.terminow_search_input);
        mCount = bar.findViewById(R.id.terminow_search_count);
        mCaseToggle = bar.findViewById(R.id.terminow_search_case);

        mInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                // Debounce so typing stays responsive on a large history.
                mHandler.removeCallbacks(mRunSearch);
                mHandler.postDelayed(mRunSearch, DEBOUNCE_MS);
            }
        });
        mInput.setOnEditorActionListener((v, actionId, event) -> { next(); return true; });

        bar.findViewById(R.id.terminow_search_prev).setOnClickListener(v -> previous());
        bar.findViewById(R.id.terminow_search_next).setOnClickListener(v -> next());
        bar.findViewById(R.id.terminow_search_close).setOnClickListener(v -> hide());
        mCaseToggle.setOnClickListener(v -> toggleCase());
        updateCaseStyle();
        showCount(0, 0);
    }

    /** Toggle the bar: show + focus when hidden, close when shown. */
    public void toggle() {
        if (mBar.getVisibility() == View.VISIBLE) hide(); else show();
    }

    public void show() {
        TerminalView terminal = mActivity.getTerminalView();
        if (terminal == null) return;
        terminal.setSearchResultListener(this::showCount);
        mBar.setVisibility(View.VISIBLE);
        mInput.requestFocus();
        KeyboardUtils.showSoftKeyboard(mActivity, mInput);
        if (mInput.getText().length() > 0) runSearch();
    }

    public void hide() {
        mHandler.removeCallbacks(mRunSearch);
        mBar.setVisibility(View.GONE);
        TerminalView terminal = mActivity.getTerminalView();
        if (terminal != null) {
            terminal.clearSearch();
            terminal.setSearchResultListener(null);
            KeyboardUtils.hideSoftKeyboard(mActivity, mInput);
            terminal.requestFocus();
        }
    }

    public boolean isShowing() {
        return mBar.getVisibility() == View.VISIBLE;
    }

    private void runSearch() {
        TerminalView terminal = mActivity.getTerminalView();
        if (terminal == null) return;
        terminal.runSearch(mInput.getText().toString(), mCaseSensitive);
    }

    private void next() {
        TerminalView terminal = mActivity.getTerminalView();
        if (terminal != null) terminal.searchNext();
    }

    private void previous() {
        TerminalView terminal = mActivity.getTerminalView();
        if (terminal != null) terminal.searchPrevious();
    }

    private void toggleCase() {
        mCaseSensitive = !mCaseSensitive;
        updateCaseStyle();
        runSearch();
    }

    private void updateCaseStyle() {
        mCaseToggle.setTypeface(Typeface.DEFAULT, mCaseSensitive ? Typeface.BOLD : Typeface.NORMAL);
        mCaseToggle.setTextColor(mActivity.getColor(mCaseSensitive ? R.color.terminow_accent : R.color.terminow_text_secondary));
    }

    private void showCount(int currentOneBased, int total) {
        mCount.setText(total == 0
            ? mActivity.getString(R.string.terminow_terminal_search_none)
            : mActivity.getString(R.string.terminow_terminal_search_count, currentOneBased, total));
    }
}
