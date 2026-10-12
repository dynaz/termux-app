package com.termux.app.terminow;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.BuildConfig;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The MCP Servers page of the home screen: profiles of remote MCP servers, a connection test
 * from the app, and adding them to Claude Code in TermiNow or in an installed proot-distro
 * container.
 *
 * Four states are kept apart on each card, since one does not imply another: the profile is
 * saved, the app could connect to the server, Claude Code was configured, and the server is
 * enabled in it. A test from the app says nothing about whether Claude Code in another
 * environment can reach the server.
 */
public class TerminowMcpPage {

    private static final int MAX_HEADERS = 10;

    private final TermuxActivity mActivity;
    private final TerminowStore mStore;
    private final LinearLayout mContainer;
    private final TerminowMcpTargets mTargets;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private List<TerminowMcpProfile> mProfiles = new ArrayList<>();
    /** The tests that are running, by profile id. */
    private final Map<String, TerminowMcpClient> mTests = new HashMap<>();
    /** The profiles whose Claude Code configuration is being checked or changed. */
    private final Set<String> mBusy = new HashSet<>();
    private String mQuery = "";

    public TerminowMcpPage(@NonNull TermuxActivity activity, @NonNull TerminowStore store, @NonNull LinearLayout container) {
        mActivity = activity;
        mStore = store;
        mContainer = container;
        mTargets = new TerminowMcpTargets(new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH), new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
    }

    // ---------------------------------------------------------------------------------------
    // The page
    // ---------------------------------------------------------------------------------------

    public void update(@NonNull String query) {
        mQuery = query;
        mProfiles = mStore.getMcpProfiles();
        render();
    }

    private void render() {
        mContainer.removeAllViews();
        List<TerminowMcpProfile> shown = new ArrayList<>();
        for (TerminowMcpProfile profile : mProfiles) {
            if (mQuery.isEmpty() || profile.name.toLowerCase(Locale.ROOT).contains(mQuery) ||
                profile.url.toLowerCase(Locale.ROOT).contains(mQuery))
                shown.add(profile);
        }

        if (mProfiles.isEmpty()) {
            addEmptyState();
        } else if (shown.isEmpty()) {
            TextView none = text(mActivity.getString(R.string.terminow_no_results), 14, R.color.terminow_text_secondary);
            none.setPadding(0, dp(24), 0, dp(24));
            none.setGravity(Gravity.CENTER);
            mContainer.addView(none, matchWrap());
        } else {
            // Two columns on wide screens, like an unfolded foldable
            int widthDp = Math.round(mActivity.getResources().getConfiguration().screenWidthDp);
            int columns = widthDp >= 760 ? 2 : 1;
            LinearLayout row = null;
            for (int i = 0; i < shown.size(); i++) {
                if (i % columns == 0) {
                    row = new LinearLayout(mActivity);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    mContainer.addView(row, matchWrap());
                }
                View card = createCard(shown.get(i));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                params.setMarginEnd(columns > 1 && i % columns == 0 ? dp(10) : 0);
                params.bottomMargin = dp(10);
                row.addView(card, params);
            }
            // Keep the last card of an odd count at the width of a column
            if (columns > 1 && shown.size() % columns != 0 && row != null)
                row.addView(new View(mActivity), new LinearLayout.LayoutParams(0, 1, 1));
        }
        addSettingsCard();
    }

    private void addEmptyState() {
        LinearLayout card = card();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(R.drawable.ic_terminow_mcp);
        icon.setImageTintList(ColorStateList.valueOf(color(R.color.terminow_panel_accent)));
        card.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = text(mActivity.getString(R.string.terminow_mcp_empty_title), 16, R.color.terminow_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(10), 0, dp(6));
        card.addView(title);
        TextView body = text(mActivity.getString(R.string.terminow_mcp_empty_body), 14, R.color.terminow_text_secondary);
        body.setGravity(Gravity.CENTER);
        card.addView(body, matchWrap());
        TextView add = actionButton(mActivity.getString(R.string.terminow_mcp_add), true);
        add.setOnClickListener(v -> showProfileDialog(null));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        params.topMargin = dp(14);
        card.addView(add, params);
        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.bottomMargin = dp(10);
        mContainer.addView(card, cardParams);
    }

    @NonNull
    private View createCard(@NonNull TerminowMcpProfile profile) {
        LinearLayout card = card();

        LinearLayout titleRow = new LinearLayout(mActivity);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(R.drawable.ic_terminow_mcp);
        icon.setBackgroundResource(R.drawable.terminow_host_icon);
        icon.getBackground().mutate().setTint(color(R.color.terminow_host_group));
        icon.setPadding(dp(8), dp(8), dp(8), dp(8));
        titleRow.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView name = text(profile.name, 16, R.color.terminow_text);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        nameParams.setMarginStart(dp(12));
        titleRow.addView(name, nameParams);
        titleRow.addView(chip(profile.isSupported() ? "HTTP" : profile.transport.toUpperCase(Locale.ROOT)));
        card.addView(titleRow);

        TextView endpoint = text(profile.isSupported() ? profile.url : mActivity.getString(R.string.terminow_mcp_stdio_unsupported),
            13, R.color.terminow_text_secondary);
        endpoint.setSingleLine(true);
        endpoint.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        endpoint.setPadding(0, dp(10), 0, 0);
        card.addView(endpoint, matchWrap());
        TextView target = text(getTargetLabel(profile) + " · " + getScopeLabel(profile), 13, R.color.terminow_text_secondary);
        target.setSingleLine(true);
        target.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(target, matchWrap());

        // The four states, each on its own line
        boolean secretsMissing = (TerminowMcpProfile.AUTH_BEARER.equals(profile.authType) && profile.bearerToken.isEmpty()) ||
            (TerminowMcpProfile.AUTH_HEADERS.equals(profile.authType) && hasEmptyHeader(profile));
        addStatus(card, secretsMissing ? Status.WARN : Status.OK, secretsMissing ?
            mActivity.getString(R.string.terminow_mcp_state_saved_no_secret) : mActivity.getString(R.string.terminow_mcp_state_saved));

        if (mTests.containsKey(profile.id)) {
            addStatus(card, Status.BUSY, mActivity.getString(R.string.terminow_mcp_state_testing));
        } else if (profile.testTime == 0) {
            addStatus(card, Status.NONE, mActivity.getString(R.string.terminow_mcp_state_not_tested));
        } else if (profile.testOk) {
            addStatus(card, Status.OK, mActivity.getString(R.string.terminow_mcp_state_test_passed,
                profile.testTools.size(), formatTime(profile.testTime)));
        } else if (TerminowMcpClient.Failure.OAUTH_REQUIRED.name().equals(profile.testFailure) ||
            TerminowMcpClient.Failure.AUTH_FAILED.name().equals(profile.testFailure)) {
            addStatus(card, Status.WARN, mActivity.getString(R.string.terminow_mcp_state_auth_required, formatTime(profile.testTime)));
        } else {
            addStatus(card, Status.ERROR, mActivity.getString(R.string.terminow_mcp_state_test_failed, formatTime(profile.testTime)));
        }

        TerminowMcpProfile.Applied applied = profile.getApplied();
        if (mBusy.contains(profile.id)) {
            addStatus(card, Status.BUSY, mActivity.getString(R.string.terminow_mcp_state_checking_claude));
        } else if (applied == null) {
            addStatus(card, Status.NONE, mActivity.getString(R.string.terminow_mcp_state_not_configured));
        } else {
            addStatus(card, applied.verified || !applied.enabled ? Status.OK : Status.WARN,
                mActivity.getString(applied.verified || !applied.enabled ? R.string.terminow_mcp_state_configured : R.string.terminow_mcp_state_configured_unverified,
                    applied.destination, formatTime(applied.time)));
        }
        if (applied != null && !mBusy.contains(profile.id))
            addStatus(card, applied.enabled ? Status.OK : Status.NONE, mActivity.getString(applied.enabled ?
                R.string.terminow_mcp_state_enabled : R.string.terminow_mcp_state_disabled));

        TextView note = text(mActivity.getString(R.string.terminow_mcp_test_note), 12, R.color.terminow_text_secondary);
        note.setPadding(0, dp(8), 0, 0);
        card.addView(note, matchWrap());

        // Actions
        LinearLayout actions = new LinearLayout(mActivity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(12), 0, 0);
        boolean testing = mTests.containsKey(profile.id);
        TextView test = actionButton(mActivity.getString(testing ? R.string.terminow_mcp_cancel_test : R.string.terminow_mcp_test), false);
        test.setOnClickListener(v -> {
            if (mTests.containsKey(profile.id)) cancelTest(profile);
            else runTest(profile);
        });
        test.setEnabled(profile.isSupported());
        TextView configure = actionButton(mActivity.getString(R.string.terminow_mcp_configure), true);
        configure.setOnClickListener(v -> startConfigure(profile));
        configure.setEnabled(profile.isSupported() && !mBusy.contains(profile.id));
        configure.setAlpha(configure.isEnabled() ? 1f : 0.5f);
        TextView more = actionButton("⋯", false);
        more.setOnClickListener(v -> showMoreActions(profile));
        actions.addView(test, actionParams());
        actions.addView(configure, actionParams());
        actions.addView(more, actionParams());
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(mActivity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(actions);
        card.addView(scroll, matchWrap());

        card.setOnClickListener(v -> showDetails(profile));
        return card;
    }

    private static boolean hasEmptyHeader(@NonNull TerminowMcpProfile profile) {
        if (profile.headers.isEmpty()) return true;
        for (TerminowMcpProfile.Header header : profile.headers) if (header.value.isEmpty()) return true;
        return false;
    }

    private void addSettingsCard() {
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(mActivity.getString(R.string.terminow_mcp_local_http), 15, R.color.terminow_text);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch toggle = new Switch(mActivity);
        toggle.setChecked(mStore.isMcpLocalHttpAllowed());
        toggle.setThumbTintList(ContextCompat.getColorStateList(mActivity, R.color.terminow_panel_switch_thumb));
        toggle.setTrackTintList(ContextCompat.getColorStateList(mActivity, R.color.terminow_panel_switch_track));
        toggle.setOnCheckedChangeListener((button, checked) -> mStore.setMcpLocalHttpAllowed(checked));
        row.addView(toggle);
        card.addView(row, matchWrap());
        card.addView(text(mActivity.getString(R.string.terminow_mcp_local_http_summary), 12, R.color.terminow_text_secondary), matchWrap());

        TextView unsupported = text(mActivity.getString(R.string.terminow_mcp_not_in_release), 12, R.color.terminow_text_secondary);
        unsupported.setPadding(0, dp(12), 0, 0);
        card.addView(unsupported, matchWrap());
        mContainer.addView(card, matchWrap());
    }

    // ---------------------------------------------------------------------------------------
    // Adding and editing profiles
    // ---------------------------------------------------------------------------------------

    /** Show the form to add a profile, or to edit {@code existing}. */
    public void showProfileDialog(@Nullable TerminowMcpProfile existing) {
        mProfiles = mStore.getMcpProfiles();
        TerminowMcpProfile profile = existing != null ? findProfile(existing.id) : null;

        ScrollView scroll = new ScrollView(mActivity);
        LinearLayout form = new LinearLayout(mActivity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(8), dp(22), dp(8));
        scroll.addView(form);

        EditText nameView = field(form, R.string.terminow_mcp_name, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        nameView.setHint("github");
        EditText urlView = field(form, R.string.terminow_mcp_url, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlView.setHint("https://mcp.example.com/mcp");

        label(form, R.string.terminow_mcp_auth);
        RadioGroup authGroup = new RadioGroup(mActivity);
        RadioButton authNone = radio(authGroup, R.string.terminow_mcp_auth_none);
        RadioButton authBearer = radio(authGroup, R.string.terminow_mcp_auth_bearer);
        RadioButton authHeaders = radio(authGroup, R.string.terminow_mcp_auth_headers);
        form.addView(authGroup);

        EditText tokenView = new EditText(mActivity);
        styleField(tokenView, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenView.setHint(R.string.terminow_mcp_token_hint);
        form.addView(tokenView, matchWrap());

        LinearLayout headersView = new LinearLayout(mActivity);
        headersView.setOrientation(LinearLayout.VERTICAL);
        form.addView(headersView, matchWrap());
        List<EditText[]> headerRows = new ArrayList<>();
        TextView addHeader = link(form, R.string.terminow_mcp_add_header);
        addHeader.setOnClickListener(v -> {
            if (headerRows.size() < MAX_HEADERS) addHeaderRow(headersView, headerRows, "", "", false);
        });

        TextView oauthNote = text(mActivity.getString(R.string.terminow_mcp_oauth_note), 12, R.color.terminow_text_secondary);
        oauthNote.setPadding(0, dp(6), 0, 0);
        form.addView(oauthNote, matchWrap());

        // Target environment
        label(form, R.string.terminow_mcp_target);
        List<TerminowMcpTargets.Container> containers = mTargets.getContainers();
        EditText targetView = picker(form);
        EditText userView = picker(form);
        userView.setVisibility(View.GONE);
        TextView sshNote = text(mActivity.getString(containers.isEmpty() ? R.string.terminow_mcp_target_no_containers :
            R.string.terminow_mcp_target_ssh_note), 12, R.color.terminow_text_secondary);
        form.addView(sshNote, matchWrap());
        final String[] targetValue = {TerminowMcpTargets.TARGET_TERMINOW};
        final String[] userValue = {"root"};

        label(form, R.string.terminow_mcp_scope);
        EditText scopeView = picker(form);
        final String[] scopeValue = {TerminowMcpProfile.SCOPE_USER};
        EditText projectView = field(form, R.string.terminow_mcp_project_dir, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        View projectLabel = form.getChildAt(form.getChildCount() - 2);

        Runnable refreshTarget = () -> {
            TerminowMcpTargets.Container container = findContainer(containers, targetValue[0]);
            if (container == null) {
                targetValue[0] = TerminowMcpTargets.TARGET_TERMINOW;
                targetView.setText(R.string.terminow_mcp_target_terminow);
                userView.setVisibility(View.GONE);
                projectView.setHint(TermuxConstants.TERMUX_HOME_DIR_PATH + "/project");
            } else {
                targetView.setText(container.label + " (" + container.name + ")");
                userView.setVisibility(View.VISIBLE);
                userView.setText(mActivity.getString(R.string.terminow_mcp_target_user, userValue[0]));
                projectView.setHint(TerminowMcpTargets.getGuestHome(container, userValue[0]) + "/project");
            }
            scopeView.setText(getScopeText(scopeValue[0]));
            boolean project = !TerminowMcpProfile.SCOPE_USER.equals(scopeValue[0]);
            projectView.setVisibility(project ? View.VISIBLE : View.GONE);
            projectLabel.setVisibility(project ? View.VISIBLE : View.GONE);
        };
        targetView.setOnClickListener(v -> {
            List<CharSequence> names = new ArrayList<>();
            names.add(mActivity.getString(R.string.terminow_mcp_target_terminow));
            for (TerminowMcpTargets.Container container : containers) names.add(container.label + " (" + container.name + ")");
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_mcp_target)
                .setItems(names.toArray(new CharSequence[0]), (d, which) -> {
                    targetValue[0] = which == 0 ? TerminowMcpTargets.TARGET_TERMINOW : containers.get(which - 1).name;
                    userValue[0] = "root";
                    refreshTarget.run();
                }).show();
        });
        userView.setOnClickListener(v -> {
            TerminowMcpTargets.Container container = findContainer(containers, targetValue[0]);
            if (container == null) return;
            List<String> users = TerminowMcpTargets.getUsers(container);
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_mcp_target_user_title)
                .setItems(users.toArray(new CharSequence[0]), (d, which) -> {
                    userValue[0] = users.get(which);
                    refreshTarget.run();
                }).show();
        });
        scopeView.setOnClickListener(v -> {
            String[] scopes = {TerminowMcpProfile.SCOPE_USER, TerminowMcpProfile.SCOPE_LOCAL, TerminowMcpProfile.SCOPE_PROJECT};
            CharSequence[] names = new CharSequence[scopes.length];
            for (int i = 0; i < scopes.length; i++) names[i] = getScopeText(scopes[i]);
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_mcp_scope)
                .setItems(names, (d, which) -> {
                    scopeValue[0] = scopes[which];
                    refreshTarget.run();
                }).show();
        });

        Runnable refreshAuth = () -> {
            int checked = authGroup.getCheckedRadioButtonId();
            tokenView.setVisibility(checked == authBearer.getId() ? View.VISIBLE : View.GONE);
            boolean headers = checked == authHeaders.getId();
            headersView.setVisibility(headers ? View.VISIBLE : View.GONE);
            addHeader.setVisibility(headers ? View.VISIBLE : View.GONE);
            if (headers && headerRows.isEmpty()) addHeaderRow(headersView, headerRows, "", "", false);
        };
        authGroup.setOnCheckedChangeListener((group, id) -> refreshAuth.run());

        if (profile != null) {
            nameView.setText(profile.name);
            urlView.setText(profile.url);
            if (!profile.bearerToken.isEmpty()) tokenView.setHint(R.string.terminow_mcp_secret_saved);
            for (TerminowMcpProfile.Header header : profile.headers)
                addHeaderRow(headersView, headerRows, header.name, header.value, true);
            targetValue[0] = profile.target;
            userValue[0] = profile.containerUser;
            scopeValue[0] = profile.scope;
            projectView.setText(profile.projectDir);
        }
        authGroup.check(profile == null || TerminowMcpProfile.AUTH_NONE.equals(profile.authType) ? authNone.getId() :
            TerminowMcpProfile.AUTH_BEARER.equals(profile.authType) ? authBearer.getId() : authHeaders.getId());
        refreshAuth.run();
        refreshTarget.run();

        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(profile == null ? R.string.terminow_mcp_add : R.string.terminow_mcp_edit)
            .setView(scroll)
            .setPositiveButton(R.string.terminow_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = nameView.getText().toString().trim();
            String url = urlView.getText().toString().trim();
            if (!TerminowMcpProfile.isValidName(name)) {
                nameView.setError(mActivity.getString(R.string.terminow_mcp_name_invalid));
                return;
            }
            if (TerminowMcpProfile.isNameTaken(mProfiles, name, profile != null ? profile.id : null)) {
                nameView.setError(mActivity.getString(R.string.terminow_mcp_name_taken));
                return;
            }
            String urlError = TerminowMcpClient.validateUrl(url, mStore.isMcpLocalHttpAllowed());
            if (urlError != null) {
                urlView.setError(urlError);
                return;
            }

            int checked = authGroup.getCheckedRadioButtonId();
            String authType = checked == authBearer.getId() ? TerminowMcpProfile.AUTH_BEARER :
                checked == authHeaders.getId() ? TerminowMcpProfile.AUTH_HEADERS : TerminowMcpProfile.AUTH_NONE;
            String token = tokenView.getText().toString().trim();
            if (token.isEmpty() && profile != null) token = profile.bearerToken;
            if (TerminowMcpProfile.AUTH_BEARER.equals(authType)) {
                if (token.toLowerCase(Locale.ROOT).startsWith("bearer ")) token = token.substring(7).trim();
                String error = token.isEmpty() ? mActivity.getString(R.string.terminow_mcp_token_needed) :
                    TerminowMcpProfile.validateHeader("Authorization", "Bearer " + token);
                if (error != null) {
                    tokenView.setError(error);
                    return;
                }
            }
            List<TerminowMcpProfile.Header> headers = new ArrayList<>();
            Set<String> headerNames = new HashSet<>();
            if (TerminowMcpProfile.AUTH_HEADERS.equals(authType)) {
                for (EditText[] row : headerRows) {
                    String headerName = row[0].getText().toString().trim();
                    String value = row[1].getText().toString().trim();
                    // An unchanged secret field keeps the saved value
                    if (value.isEmpty() && row[1].getTag() instanceof String) value = (String) row[1].getTag();
                    if (headerName.isEmpty() && value.isEmpty()) continue;
                    String error = TerminowMcpProfile.validateHeader(headerName, value);
                    if (error == null && !headerNames.add(headerName.toLowerCase(Locale.ROOT)))
                        error = mActivity.getString(R.string.terminow_mcp_header_twice, headerName);
                    if (error != null) {
                        row[0].setError(error);
                        return;
                    }
                    headers.add(new TerminowMcpProfile.Header(headerName, value));
                }
                if (headers.isEmpty()) {
                    mActivity.showToast(mActivity.getString(R.string.terminow_mcp_headers_needed), true);
                    return;
                }
            }

            String scope = scopeValue[0];
            String projectDir = projectView.getText().toString().trim();
            while (projectDir.length() > 1 && projectDir.endsWith("/")) projectDir = projectDir.substring(0, projectDir.length() - 1);
            if (!TerminowMcpProfile.SCOPE_USER.equals(scope) && (!projectDir.startsWith("/") || projectDir.contains("/../") || projectDir.endsWith("/.."))) {
                projectView.setError(mActivity.getString(R.string.terminow_mcp_project_invalid));
                return;
            }
            if (TerminowMcpProfile.SCOPE_PROJECT.equals(scope) && !TerminowMcpProfile.AUTH_NONE.equals(authType)) {
                mActivity.showToast(mActivity.getString(R.string.terminow_mcp_shared_no_secrets), true);
                return;
            }

            TerminowMcpProfile target = profile != null ? profile : new TerminowMcpProfile();
            boolean connectionChanged = !url.equals(target.url) || !authType.equals(target.authType) ||
                !token.equals(target.bearerToken) || !sameHeaders(headers, target.headers);
            target.name = name;
            target.url = url;
            target.authType = authType;
            target.bearerToken = TerminowMcpProfile.AUTH_BEARER.equals(authType) ? token : "";
            target.headers.clear();
            target.headers.addAll(headers);
            target.target = targetValue[0];
            target.containerUser = userValue[0];
            target.scope = scope;
            target.projectDir = TerminowMcpProfile.SCOPE_USER.equals(scope) ? "" : projectDir;
            // A test is only valid for what was tested
            if (connectionChanged) target.clearTest();
            if (profile == null) mProfiles.add(target);
            mStore.setMcpProfiles(mProfiles);
            dialog.dismiss();
            render();
            if (profile != null && profile.getApplied() != null && profile.getApplied().enabled && connectionChanged)
                mActivity.showToast(mActivity.getString(R.string.terminow_mcp_reconfigure_hint), true);
        }));
        dialog.show();
    }

    private static boolean sameHeaders(@NonNull List<TerminowMcpProfile.Header> a, @NonNull List<TerminowMcpProfile.Header> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++)
            if (!a.get(i).name.equals(b.get(i).name) || !a.get(i).value.equals(b.get(i).value)) return false;
        return true;
    }

    private void addHeaderRow(@NonNull LinearLayout parent, @NonNull List<EditText[]> rows, @NonNull String name,
                              @NonNull String value, boolean saved) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        EditText nameView = new EditText(mActivity);
        styleField(nameView, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        nameView.setHint(R.string.terminow_mcp_header_name);
        nameView.setText(name);
        EditText valueView = new EditText(mActivity);
        styleField(valueView, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        // Saved secrets are never put back into a field, the field only says that one is saved
        if (saved && !value.isEmpty()) {
            valueView.setHint(R.string.terminow_mcp_secret_saved);
            valueView.setTag(value);
        } else {
            valueView.setHint(R.string.terminow_mcp_header_value);
        }
        TextView remove = new TextView(mActivity);
        remove.setText("✕");
        remove.setTextColor(color(R.color.terminow_text_secondary));
        remove.setPadding(dp(10), dp(8), dp(4), dp(8));
        EditText[] views = {nameView, valueView};
        remove.setOnClickListener(v -> {
            parent.removeView(row);
            rows.remove(views);
        });
        row.addView(nameView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f);
        valueParams.setMarginStart(dp(8));
        row.addView(valueView, valueParams);
        row.addView(remove);
        parent.addView(row, matchWrap());
        rows.add(views);
    }

    // ---------------------------------------------------------------------------------------
    // Details, deleting and the other actions
    // ---------------------------------------------------------------------------------------

    private void showMoreActions(@NonNull TerminowMcpProfile profile) {
        TerminowMcpProfile.Applied applied = profile.getApplied();
        List<CharSequence> actions = new ArrayList<>();
        List<Runnable> handlers = new ArrayList<>();
        actions.add(mActivity.getString(R.string.terminow_edit));
        handlers.add(() -> showProfileDialog(profile));
        actions.add(mActivity.getString(R.string.terminow_mcp_details));
        handlers.add(() -> showDetails(profile));
        if (applied != null && applied.enabled && !mBusy.contains(profile.id)) {
            actions.add(mActivity.getString(R.string.terminow_mcp_disable));
            handlers.add(() -> startDisable(profile));
        }
        actions.add(mActivity.getString(R.string.terminow_mcp_manual));
        handlers.add(() -> showManualSetup(profile, null));
        actions.add(mActivity.getString(R.string.terminow_delete));
        handlers.add(() -> confirmDelete(profile));
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(profile.name)
            .setItems(actions.toArray(new CharSequence[0]), (d, which) -> handlers.get(which).run())
            .show();
    }

    private void showDetails(@NonNull TerminowMcpProfile profile) {
        StringBuilder text = new StringBuilder();
        text.append(mActivity.getString(R.string.terminow_mcp_url)).append(": ").append(profile.url).append('\n');
        text.append(mActivity.getString(R.string.terminow_mcp_auth)).append(": ").append(getAuthLabel(profile)).append('\n');
        for (Map.Entry<String, String> header : profile.getRequestHeaders().entrySet())
            text.append("  ").append(header.getKey()).append(": ").append(TerminowMcpRedactor.mask(header.getValue())).append('\n');
        text.append(mActivity.getString(R.string.terminow_mcp_target)).append(": ").append(getTargetLabel(profile)).append('\n');
        text.append(mActivity.getString(R.string.terminow_mcp_scope)).append(": ").append(getScopeText(profile.scope));
        if (!profile.projectDir.isEmpty()) text.append(" — ").append(profile.projectDir);
        text.append("\n\n");

        if (profile.testTime == 0) {
            text.append(mActivity.getString(R.string.terminow_mcp_state_not_tested));
        } else if (profile.testOk) {
            text.append(mActivity.getString(R.string.terminow_mcp_details_test_ok, formatTime(profile.testTime), profile.testServer, profile.testProtocol));
            text.append('\n');
            for (String tool : profile.testTools) text.append("• ").append(tool).append('\n');
        } else {
            text.append(mActivity.getString(R.string.terminow_mcp_details_test_failed, formatTime(profile.testTime))).append('\n').append(profile.testMessage);
        }
        TerminowMcpProfile.Applied applied = profile.getApplied();
        text.append("\n\n");
        if (applied == null) {
            text.append(mActivity.getString(R.string.terminow_mcp_state_not_configured));
        } else {
            text.append(mActivity.getString(R.string.terminow_mcp_details_applied, applied.destination, getScopeText(applied.scope),
                applied.claudeVersion, formatTime(applied.time)));
            text.append('\n').append(mActivity.getString(applied.enabled ? R.string.terminow_mcp_state_enabled : R.string.terminow_mcp_state_disabled));
            if (applied.storesSecret && applied.enabled) text.append('\n').append(mActivity.getString(R.string.terminow_mcp_secret_in_file_short));
            if (!applied.verifyOutput.isEmpty()) text.append("\n\nclaude mcp get:\n").append(applied.verifyOutput);
        }
        showMessage(profile.name, text.toString().trim());
    }

    private void confirmDelete(@NonNull TerminowMcpProfile profile) {
        boolean stillConfigured = false;
        for (TerminowMcpProfile.Applied applied : profile.applied.values()) stillConfigured |= applied.enabled;
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setMessage(mActivity.getString(stillConfigured ? R.string.terminow_mcp_delete_configured : R.string.terminow_mcp_delete_confirm, profile.name))
            .setPositiveButton(R.string.terminow_delete, (d, w) -> {
                mProfiles = mStore.getMcpProfiles();
                TerminowMcpProfile stored = findProfile(profile.id);
                if (stored != null) mProfiles.remove(stored);
                mStore.setMcpProfiles(mProfiles);
                render();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ---------------------------------------------------------------------------------------
    // Connection test
    // ---------------------------------------------------------------------------------------

    private void runTest(@NonNull TerminowMcpProfile profile) {
        String urlError = TerminowMcpClient.validateUrl(profile.url, mStore.isMcpLocalHttpAllowed());
        if (urlError != null) {
            showMessage(profile.name, urlError);
            return;
        }
        TerminowMcpClient client;
        try {
            client = new TerminowMcpClient(new URL(profile.url), profile.getRequestHeaders(), BuildConfig.VERSION_NAME);
        } catch (MalformedURLException e) {
            showMessage(profile.name, String.valueOf(e.getMessage()));
            return;
        }
        String id = profile.id;
        mTests.put(id, client);
        render();
        new Thread(() -> {
            TerminowMcpClient.Result result = client.test();
            mHandler.post(() -> {
                if (mTests.get(id) != client) return;
                mTests.remove(id);
                mProfiles = mStore.getMcpProfiles();
                TerminowMcpProfile stored = findProfile(id);
                if (stored == null) {
                    render();
                    return;
                }
                if (result.failure != TerminowMcpClient.Failure.CANCELLED) {
                    stored.testTime = System.currentTimeMillis();
                    stored.testOk = result.ok;
                    stored.testFailure = result.failure != null ? result.failure.name() : "";
                    stored.testMessage = result.message;
                    stored.testServer = (result.serverName + " " + result.serverVersion).trim();
                    stored.testProtocol = result.protocolVersion;
                    stored.testTools.clear();
                    for (TerminowMcpClient.Tool tool : result.tools) stored.testTools.add(tool.name);
                    mStore.setMcpProfiles(mProfiles);
                }
                render();
                showTestResult(stored, result);
            });
        }).start();
    }

    private void cancelTest(@NonNull TerminowMcpProfile profile) {
        TerminowMcpClient client = mTests.get(profile.id);
        if (client != null) new Thread(client::cancel).start();
    }

    private void showTestResult(@NonNull TerminowMcpProfile profile, @NonNull TerminowMcpClient.Result result) {
        if (result.failure == TerminowMcpClient.Failure.CANCELLED) return;
        StringBuilder text = new StringBuilder();
        if (result.ok) {
            text.append(mActivity.getString(R.string.terminow_mcp_result_ok_summary,
                result.serverName.isEmpty() ? "?" : result.serverName, result.serverVersion, result.protocolVersion,
                mActivity.getString("modern".equals(result.era) ? R.string.terminow_mcp_era_modern : R.string.terminow_mcp_era_legacy)));
            text.append("\n\n");
            if (!result.hasTools) {
                text.append(mActivity.getString(R.string.terminow_mcp_result_no_tools));
            } else {
                text.append(mActivity.getResources().getQuantityString(R.plurals.terminow_mcp_tools, result.tools.size(), result.tools.size()));
                if (result.toolsTruncated) text.append(" +");
                text.append('\n');
                for (TerminowMcpClient.Tool tool : result.tools) {
                    text.append("\n• ").append(tool.name);
                    if (!tool.description.isEmpty()) {
                        String description = tool.description.trim().replace('\n', ' ');
                        text.append(" — ").append(description.length() > 160 ? description.substring(0, 160) + "…" : description);
                    }
                }
            }
            text.append("\n\n").append(mActivity.getString(R.string.terminow_mcp_result_ok_note, getTargetLabel(profile)));
        } else if (result.failure == TerminowMcpClient.Failure.OAUTH_REQUIRED) {
            text.append(mActivity.getString(R.string.terminow_mcp_result_oauth));
            if (!result.resourceMetadata.isEmpty()) text.append("\n\n").append(result.resourceMetadata);
        } else if (result.failure == TerminowMcpClient.Failure.AUTH_FAILED) {
            text.append(result.message).append("\n\n").append(mActivity.getString(R.string.terminow_mcp_result_auth_failed));
        } else {
            text.append(result.message);
        }
        int title = result.ok ? R.string.terminow_mcp_result_ok :
            result.failure == TerminowMcpClient.Failure.OAUTH_REQUIRED || result.failure == TerminowMcpClient.Failure.AUTH_FAILED ?
                R.string.terminow_mcp_result_auth : R.string.terminow_mcp_result_failed;
        showMessage(mActivity.getString(title), text.toString());
    }

    // ---------------------------------------------------------------------------------------
    // Configuring Claude Code
    // ---------------------------------------------------------------------------------------

    /** Everything that is checked before the preview, and what is applied after it. */
    private static class ConfigureContext {
        TerminowMcpProfile profile;
        boolean remove;
        String targetLabel = "";
        TerminowClaudeSetup.Detection detection;
        @Nullable TerminowMcpTargets.Container container;
        /** The file to change, in the app, and as a path in the environment. */
        @Nullable File file;
        String guestPath = "";
        @Nullable JSONObject entry;
        @Nullable TerminowClaudeConfig.Plan plan;
        @Nullable String problem;
        int runningClaude;
        @Nullable String lastFingerprint;
    }

    private void startConfigure(@NonNull TerminowMcpProfile profile) {
        prepare(profile, false);
    }

    private void startDisable(@NonNull TerminowMcpProfile profile) {
        prepare(profile, true);
    }

    /** Detect Claude Code and plan the change in the background, then show the preview. */
    private void prepare(@NonNull TerminowMcpProfile profile, boolean remove) {
        if (!profile.isSupported()) {
            showMessage(profile.name, mActivity.getString(R.string.terminow_mcp_stdio_unsupported));
            return;
        }
        String id = profile.id;
        mBusy.add(id);
        render();
        AlertDialog progress = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setMessage(mActivity.getString(R.string.terminow_mcp_checking, getTargetLabel(profile)))
            .setCancelable(false)
            .show();
        new Thread(() -> {
            ConfigureContext context = plan(profile, remove, false);
            mHandler.post(() -> {
                progress.dismiss();
                mBusy.remove(id);
                render();
                showPreview(context);
            });
        }).start();
    }

    /** Runs in the background. */
    @NonNull
    private ConfigureContext plan(@NonNull TerminowMcpProfile profile, boolean remove, boolean confirmedConflict) {
        ConfigureContext context = new ConfigureContext();
        context.profile = profile;
        context.remove = remove;
        context.targetLabel = getTargetLabel(profile);
        boolean terminow = TerminowMcpTargets.TARGET_TERMINOW.equals(profile.target);
        if (!terminow) {
            context.container = mTargets.getContainer(profile.target);
            if (context.container == null) {
                context.problem = mActivity.getString(R.string.terminow_mcp_container_missing, profile.target);
                return context;
            }
        }

        context.detection = new TerminowClaudeSetup(mActivity, mTargets).detect(profile.target, profile.containerUser);
        if (context.detection.error != null) {
            context.problem = context.detection.error;
            return context;
        }
        if (!remove && !context.detection.isInstalled()) {
            context.problem = mActivity.getString(R.string.terminow_mcp_claude_missing, context.targetLabel);
            return context;
        }
        if (!remove && !context.detection.supportsHttp) {
            context.problem = mActivity.getString(R.string.terminow_mcp_claude_no_http, context.detection.version);
            return context;
        }

        // Where the configuration is, as a path in the environment
        String scope = profile.scope;
        TerminowMcpProfile.Applied applied = profile.getApplied();
        if (remove && applied != null) scope = applied.scope;
        String projectDir = remove && applied != null ? applied.projectDir : profile.projectDir;
        String guestDir = TerminowMcpProfile.SCOPE_PROJECT.equals(scope) ? projectDir : context.detection.getUserConfigDir();
        String fileName = TerminowMcpProfile.SCOPE_PROJECT.equals(scope) ? TerminowClaudeConfig.PROJECT_CONFIG_NAME : TerminowClaudeConfig.USER_CONFIG_NAME;
        context.guestPath = guestDir + "/" + fileName;

        if (!TerminowMcpProfile.SCOPE_USER.equals(scope)) {
            File dir = terminow ? mTargets.resolveTerminowPath(projectDir) : TerminowMcpTargets.resolveGuestPath(context.container, projectDir);
            if (dir == null || !dir.isDirectory()) {
                context.problem = mActivity.getString(R.string.terminow_mcp_project_missing, projectDir, context.targetLabel);
                return context;
            }
        }
        context.file = terminow ? mTargets.resolveTerminowPath(context.guestPath) : TerminowMcpTargets.resolveGuestPath(context.container, context.guestPath);
        if (context.file == null || context.file.getParentFile() == null || !context.file.getParentFile().isDirectory()) {
            context.problem = mActivity.getString(R.string.terminow_mcp_path_unusable, context.guestPath);
            return context;
        }

        // Only an entry that TermiNow wrote to the same place is updated without asking
        if (applied != null && applied.destination.equals(context.guestPath) && applied.scope.equals(scope) &&
            applied.projectDir.equals(projectDir))
            context.lastFingerprint = applied.fingerprint;

        try {
            String existing = TerminowClaudeConfig.read(context.file);
            if (remove) {
                context.plan = TerminowClaudeConfig.planRemove(existing, scope, projectDir, profile.name, context.lastFingerprint, confirmedConflict);
            } else {
                context.entry = TerminowClaudeConfig.buildHttpEntry(profile.url, profile.getRequestHeaders());
                context.plan = TerminowClaudeConfig.planAdd(existing, scope, projectDir, profile.name, context.entry,
                    context.lastFingerprint, confirmedConflict);
            }
        } catch (TerminowClaudeConfig.ConfigException | JSONException e) {
            context.problem = String.valueOf(e.getMessage());
            return context;
        }
        if (!TerminowMcpProfile.SCOPE_PROJECT.equals(scope))
            context.runningClaude = TerminowClaudeSetup.countRunningClaude();
        return context;
    }

    private void showPreview(@NonNull ConfigureContext context) {
        TerminowMcpProfile profile = context.profile;
        StringBuilder text = new StringBuilder();
        text.append(mActivity.getString(R.string.terminow_mcp_preview_environment, context.targetLabel)).append('\n');
        if (context.detection != null && context.detection.ran) {
            text.append(context.detection.isInstalled() ?
                mActivity.getString(R.string.terminow_mcp_preview_claude, context.detection.version, context.detection.claudePath) :
                mActivity.getString(R.string.terminow_mcp_preview_claude_missing)).append('\n');
        }
        text.append(mActivity.getString(R.string.terminow_mcp_preview_profile, profile.name, profile.url)).append('\n');
        String scope = context.remove && profile.getApplied() != null ? profile.getApplied().scope : profile.scope;
        text.append(mActivity.getString(R.string.terminow_mcp_preview_scope, getScopeText(scope),
            context.guestPath.isEmpty() ? "—" : context.guestPath)).append("\n\n");

        if (context.problem != null) {
            text.append(context.problem).append("\n\n").append(mActivity.getString(R.string.terminow_mcp_preview_manual_hint));
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(context.remove ? R.string.terminow_mcp_disable : R.string.terminow_mcp_configure)
                .setMessage(text.toString())
                .setPositiveButton(R.string.terminow_mcp_manual, (d, w) -> showManualSetup(profile, context))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return;
        }

        TerminowClaudeConfig.Plan plan = context.plan;
        String entryText = context.plan != null && context.plan.existingEntry != null ?
            new TerminowMcpRedactor(profile.getSecrets()).redact(context.plan.existingEntry.toString()) : "";
        boolean canApply = true;
        int positive = R.string.terminow_mcp_apply;
        switch (plan.action) {
            case ADD: text.append(mActivity.getString(R.string.terminow_mcp_preview_add)); break;
            case UPDATE: text.append(mActivity.getString(R.string.terminow_mcp_preview_update)); break;
            case UNCHANGED:
                text.append(mActivity.getString(R.string.terminow_mcp_preview_unchanged));
                positive = R.string.terminow_mcp_verify;
                break;
            case REMOVE: text.append(mActivity.getString(R.string.terminow_mcp_preview_remove)); break;
            case NOT_PRESENT:
                text.append(mActivity.getString(R.string.terminow_mcp_preview_not_present));
                positive = R.string.terminow_mcp_mark_disabled;
                break;
            case CONFLICT:
                text.append(mActivity.getString(context.remove ? R.string.terminow_mcp_preview_conflict_remove :
                    R.string.terminow_mcp_preview_conflict, profile.name, entryText));
                positive = context.remove ? R.string.terminow_mcp_remove_anyway : R.string.terminow_mcp_replace;
                break;
        }
        if (!plan.otherServers.isEmpty())
            text.append("\n\n").append(mActivity.getString(R.string.terminow_mcp_preview_others, TextUtils.join(", ", plan.otherServers)));
        if (plan.action != TerminowClaudeConfig.Action.UNCHANGED && plan.action != TerminowClaudeConfig.Action.NOT_PRESENT)
            text.append("\n\n").append(mActivity.getString(R.string.terminow_mcp_preview_backup));
        if (!context.remove && profile.hasSecrets())
            text.append("\n\n").append(mActivity.getString(R.string.terminow_mcp_preview_secret, context.guestPath));
        if (context.runningClaude > 0 && plan.newText != null) {
            text.append("\n\n").append(mActivity.getResources().getQuantityString(R.plurals.terminow_mcp_preview_running,
                context.runningClaude, context.runningClaude));
            if (plan.action != TerminowClaudeConfig.Action.CONFLICT) positive = R.string.terminow_mcp_apply_anyway;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(context.remove ? R.string.terminow_mcp_preview_title_disable : R.string.terminow_mcp_preview_title)
            .setMessage(text.toString())
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.terminow_mcp_manual, (d, w) -> showManualSetup(profile, context));
        if (canApply)
            builder.setPositiveButton(positive, (d, w) -> apply(context, plan.action == TerminowClaudeConfig.Action.CONFLICT));
        builder.show();
    }

    /** Apply the previewed change in the background, then verify it with Claude Code. */
    private void apply(@NonNull ConfigureContext preview, boolean confirmedConflict) {
        String id = preview.profile.id;
        mBusy.add(id);
        render();
        AlertDialog progress = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setMessage(mActivity.getString(R.string.terminow_mcp_applying))
            .setCancelable(false)
            .show();
        new Thread(() -> {
            String error = null;
            ConfigureContext context = null;
            TerminowClaudeSetup.ProcessResult verify = null;
            File backup = null;
            try {
                // Plan again from the file as it is now: it may have changed since the preview
                context = plan(preview.profile, preview.remove, confirmedConflict);
                if (context.problem != null) throw new TerminowClaudeConfig.ConfigException(context.problem);
                TerminowClaudeConfig.Plan plan = context.plan;
                if (plan.action != preview.plan.action && !(confirmedConflict && preview.plan.action == TerminowClaudeConfig.Action.CONFLICT))
                    throw new TerminowClaudeConfig.ConfigException(mActivity.getString(R.string.terminow_mcp_changed_since_preview));
                if (plan.newText != null) {
                    backup = TerminowClaudeConfig.backup(context.file, new Date());
                    TerminowClaudeConfig.writeAtomically(context.file, plan.newText);
                }
                if (plan.action != TerminowClaudeConfig.Action.NOT_PRESENT || !preview.remove) {
                    String scope = preview.remove && preview.profile.getApplied() != null ? preview.profile.getApplied().scope : preview.profile.scope;
                    String dir = TerminowMcpProfile.SCOPE_USER.equals(scope) ? "" :
                        (preview.remove && preview.profile.getApplied() != null ? preview.profile.getApplied().projectDir : preview.profile.projectDir);
                    verify = new TerminowClaudeSetup(mActivity, mTargets).verify(preview.profile.target, preview.profile.containerUser, dir, preview.profile.name);
                }
            } catch (TerminowClaudeConfig.ConfigException e) {
                error = e.getMessage();
            }
            String finalError = error;
            ConfigureContext finalContext = context;
            TerminowClaudeSetup.ProcessResult finalVerify = verify;
            File finalBackup = backup;
            mHandler.post(() -> {
                progress.dismiss();
                mBusy.remove(id);
                onApplied(preview, finalContext, finalError, finalVerify, finalBackup);
            });
        }).start();
    }

    private void onApplied(@NonNull ConfigureContext preview, @Nullable ConfigureContext context, @Nullable String error,
                           @Nullable TerminowClaudeSetup.ProcessResult verify, @Nullable File backup) {
        mProfiles = mStore.getMcpProfiles();
        TerminowMcpProfile stored = findProfile(preview.profile.id);
        if (error != null || context == null || stored == null) {
            render();
            showMessage(mActivity.getString(R.string.terminow_mcp_apply_failed), String.valueOf(error));
            return;
        }
        TerminowMcpRedactor redactor = new TerminowMcpRedactor(stored.getSecrets());
        String output = verify == null ? "" : redactor.redact((verify.stdout + "\n" + verify.stderr).trim());
        if (output.length() > 3000) output = output.substring(0, 3000) + "…";
        boolean found = verify != null && verify.exitCode == 0 && output.contains(stored.name);

        TerminowMcpProfile.Applied applied = stored.getApplied();
        if (applied == null) {
            applied = new TerminowMcpProfile.Applied();
            stored.applied.put(stored.getTargetKey(), applied);
        }
        if (!preview.remove) {
            applied.target = stored.target;
            applied.scope = stored.scope;
            applied.projectDir = stored.projectDir;
            applied.destination = context.guestPath;
            applied.fingerprint = context.entry != null ? TerminowClaudeConfig.fingerprint(context.entry) : "";
            applied.enabled = true;
            applied.storesSecret = stored.hasSecrets();
        } else {
            applied.enabled = false;
            applied.storesSecret = false;
        }
        applied.time = System.currentTimeMillis();
        applied.claudeVersion = context.detection != null ? context.detection.version : "";
        applied.verified = preview.remove ? !found : found;
        applied.verifyOutput = output;
        mStore.setMcpProfiles(mProfiles);
        render();

        StringBuilder text = new StringBuilder();
        TerminowClaudeConfig.Action action = context.plan != null ? context.plan.action : null;
        if (preview.remove) {
            text.append(mActivity.getString(action == TerminowClaudeConfig.Action.NOT_PRESENT ?
                R.string.terminow_mcp_done_disabled_absent : R.string.terminow_mcp_done_disabled, context.guestPath));
            if (found) text.append("\n\n").append(mActivity.getString(R.string.terminow_mcp_done_still_listed));
        } else {
            text.append(mActivity.getString(action == TerminowClaudeConfig.Action.UNCHANGED ?
                R.string.terminow_mcp_done_unchanged : R.string.terminow_mcp_done_written, context.guestPath));
            text.append("\n\n").append(mActivity.getString(found ? R.string.terminow_mcp_done_verified : R.string.terminow_mcp_done_not_verified));
            if (TerminowMcpProfile.SCOPE_PROJECT.equals(stored.scope))
                text.append("\n\n").append(mActivity.getString(R.string.terminow_mcp_done_project_approval));
            text.append("\n\n").append(mActivity.getString(R.string.terminow_mcp_done_restart));
        }
        if (backup != null) text.append("\n\n").append(mActivity.getString(R.string.terminow_mcp_done_backup, backup.getName()));
        if (!output.isEmpty()) text.append("\n\nclaude mcp get ").append(stored.name).append(":\n").append(output);
        if (verify != null && verify.timedOut) text.append("\n\n").append(mActivity.getString(R.string.terminow_mcp_verify_timeout));
        showMessage(mActivity.getString(preview.remove ? R.string.terminow_mcp_disable : R.string.terminow_mcp_configure), text.toString());
    }

    /**
     * Commands to run yourself, for an environment that TermiNow can not configure. They never
     * contain the secrets: the token is read into a variable without echo or history.
     */
    private void showManualSetup(@NonNull TerminowMcpProfile profile, @Nullable ConfigureContext context) {
        StringBuilder commands = new StringBuilder();
        if (!TerminowMcpTargets.TARGET_TERMINOW.equals(profile.target))
            commands.append("proot-distro login ").append(profile.target).append(" --user ").append(profile.containerUser).append('\n');
        if (!TerminowMcpProfile.SCOPE_USER.equals(profile.scope))
            commands.append("cd ").append(shellQuote(profile.projectDir)).append('\n');
        List<String> variables = new ArrayList<>();
        StringBuilder headerArgs = new StringBuilder();
        if (TerminowMcpProfile.AUTH_BEARER.equals(profile.authType)) {
            variables.add("MCP_TOKEN");
            headerArgs.append(" \\\n  --header \"Authorization: Bearer $MCP_TOKEN\"");
        } else if (TerminowMcpProfile.AUTH_HEADERS.equals(profile.authType)) {
            for (int i = 0; i < profile.headers.size(); i++) {
                String variable = "MCP_HEADER_" + (i + 1);
                variables.add(variable);
                headerArgs.append(" \\\n  --header \"").append(profile.headers.get(i).name).append(": $").append(variable).append('"');
            }
        }
        for (String variable : variables)
            commands.append("read -rs ").append(variable).append("   # paste the value, it is not shown\n");
        commands.append("claude mcp add --transport http --scope ").append(profile.scope).append(' ')
            .append(profile.name).append(' ').append(shellQuote(profile.url)).append(headerArgs).append('\n');
        if (!variables.isEmpty()) commands.append("unset ").append(TextUtils.join(" ", variables)).append('\n');
        commands.append("claude mcp get ").append(profile.name).append('\n');

        String text = mActivity.getString(R.string.terminow_mcp_manual_intro, getTargetLabel(profile)) + "\n\n" + commands +
            (variables.isEmpty() ? "" : "\n" + mActivity.getString(R.string.terminow_mcp_manual_secret_note));
        TextView view = text(text, 13, R.color.terminow_text);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setPadding(dp(22), dp(12), dp(22), dp(4));
        ScrollView scroll = new ScrollView(mActivity);
        scroll.addView(view);
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_mcp_manual_title)
            .setView(scroll)
            .setPositiveButton(R.string.terminow_mcp_copy_commands, (d, w) -> {
                ClipboardManager clipboard = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("claude mcp", commands.toString()));
                    mActivity.showToast(mActivity.getString(R.string.terminow_mcp_commands_copied), false);
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    @NonNull
    static String shellQuote(@NonNull String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    // ---------------------------------------------------------------------------------------
    // Labels
    // ---------------------------------------------------------------------------------------

    @NonNull
    private String getTargetLabel(@NonNull TerminowMcpProfile profile) {
        if (TerminowMcpTargets.TARGET_TERMINOW.equals(profile.target))
            return mActivity.getString(R.string.terminow_mcp_target_terminow);
        TerminowMcpTargets.Container container = mTargets.getContainer(profile.target);
        String label = container != null ? container.label : profile.target;
        return mActivity.getString(R.string.terminow_mcp_target_container, label, profile.target, profile.containerUser);
    }

    @NonNull
    private String getScopeLabel(@NonNull TerminowMcpProfile profile) {
        String scope = getScopeShort(profile.scope);
        return profile.projectDir.isEmpty() ? scope : scope + " " + profile.projectDir;
    }

    @NonNull
    private String getScopeShort(@NonNull String scope) {
        return mActivity.getString(TerminowMcpProfile.SCOPE_LOCAL.equals(scope) ? R.string.terminow_mcp_scope_local_short :
            TerminowMcpProfile.SCOPE_PROJECT.equals(scope) ? R.string.terminow_mcp_scope_project_short : R.string.terminow_mcp_scope_user_short);
    }

    @NonNull
    private String getScopeText(@NonNull String scope) {
        return mActivity.getString(TerminowMcpProfile.SCOPE_LOCAL.equals(scope) ? R.string.terminow_mcp_scope_local :
            TerminowMcpProfile.SCOPE_PROJECT.equals(scope) ? R.string.terminow_mcp_scope_project : R.string.terminow_mcp_scope_user);
    }

    @NonNull
    private String getAuthLabel(@NonNull TerminowMcpProfile profile) {
        return mActivity.getString(TerminowMcpProfile.AUTH_BEARER.equals(profile.authType) ? R.string.terminow_mcp_auth_bearer :
            TerminowMcpProfile.AUTH_HEADERS.equals(profile.authType) ? R.string.terminow_mcp_auth_headers : R.string.terminow_mcp_auth_none);
    }

    @NonNull
    private static String formatTime(long time) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(time));
    }

    @Nullable
    private TerminowMcpProfile findProfile(@NonNull String id) {
        for (TerminowMcpProfile profile : mProfiles) if (profile.id.equals(id)) return profile;
        return null;
    }

    @Nullable
    private static TerminowMcpTargets.Container findContainer(@NonNull List<TerminowMcpTargets.Container> containers, @NonNull String name) {
        for (TerminowMcpTargets.Container container : containers) if (container.name.equals(name)) return container;
        return null;
    }

    // ---------------------------------------------------------------------------------------
    // Views
    // ---------------------------------------------------------------------------------------

    private enum Status { OK, WARN, ERROR, BUSY, NONE }

    private void addStatus(@NonNull LinearLayout card, @NonNull Status status, @NonNull String text) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, 0);
        View dot = new View(mActivity);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        int colorRes = status == Status.OK ? R.color.terminow_panel_accent : status == Status.WARN ? R.color.terminow_mcp_warn :
            status == Status.ERROR ? R.color.terminow_host_ssh : status == Status.BUSY ? R.color.terminow_accent : R.color.terminow_text_secondary;
        shape.setColor(color(colorRes));
        dot.setBackground(shape);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotParams.setMarginEnd(dp(10));
        row.addView(dot, dotParams);
        TextView label = text(text, 13, status == Status.NONE ? R.color.terminow_text_secondary : R.color.terminow_text);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(row, matchWrap());
    }

    @NonNull
    private LinearLayout card() {
        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.terminow_card);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        return card;
    }

    @NonNull
    private TextView chip(@NonNull String text) {
        TextView chip = text(text, 11, R.color.terminow_text);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(10));
        shape.setColor(color(R.color.terminow_field));
        chip.setBackground(shape);
        chip.setPadding(dp(8), dp(3), dp(8), dp(3));
        return chip;
    }

    @NonNull
    private TextView actionButton(@NonNull String text, boolean primary) {
        TextView button = text(text, 12, primary ? R.color.terminow_bar_home : R.color.terminow_text);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setAllCaps(true);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(dp(48));
        button.setPadding(dp(16), 0, dp(16), 0);
        if (primary) {
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(dp(18));
            shape.setColor(color(R.color.terminow_panel_accent));
            button.setBackground(shape);
        } else {
            button.setBackgroundResource(R.drawable.terminow_action);
        }
        return button;
    }

    @NonNull
    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        params.setMarginEnd(dp(8));
        return params;
    }

    @NonNull
    private TextView text(@NonNull String text, int sp, int colorRes) {
        TextView view = new TextView(mActivity);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color(colorRes));
        return view;
    }

    private void label(@NonNull LinearLayout form, int textRes) {
        TextView label = text(mActivity.getString(textRes), 12, R.color.terminow_text_secondary);
        label.setPadding(0, dp(14), 0, dp(2));
        form.addView(label);
    }

    @NonNull
    private EditText field(@NonNull LinearLayout form, int labelRes, int inputType) {
        label(form, labelRes);
        EditText field = new EditText(mActivity);
        styleField(field, inputType);
        form.addView(field, matchWrap());
        return field;
    }

    @NonNull
    private EditText picker(@NonNull LinearLayout form) {
        EditText field = new EditText(mActivity);
        styleField(field, InputType.TYPE_NULL);
        field.setFocusable(false);
        field.setCursorVisible(false);
        form.addView(field, matchWrap());
        return field;
    }

    private void styleField(@NonNull EditText field, int inputType) {
        field.setInputType(inputType);
        field.setSingleLine(true);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        field.setTextColor(color(R.color.terminow_text));
        field.setHintTextColor(color(R.color.terminow_text_secondary));
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setBackgroundTintList(ColorStateList.valueOf(color(R.color.terminow_panel_accent)));
    }

    @NonNull
    private RadioButton radio(@NonNull RadioGroup group, int textRes) {
        RadioButton button = new RadioButton(mActivity);
        button.setId(View.generateViewId());
        button.setText(textRes);
        button.setTextColor(color(R.color.terminow_text));
        button.setButtonTintList(ColorStateList.valueOf(color(R.color.terminow_panel_accent)));
        group.addView(button);
        return button;
    }

    @NonNull
    private TextView link(@NonNull LinearLayout form, int textRes) {
        TextView link = text(mActivity.getString(textRes), 14, R.color.terminow_panel_accent);
        link.setTypeface(Typeface.DEFAULT_BOLD);
        link.setPadding(0, dp(8), 0, dp(4));
        form.addView(link);
        return link;
    }

    private void showMessage(@NonNull String title, @NonNull String message) {
        if (mActivity.isFinishing()) return;
        TextView view = text(message, 14, R.color.terminow_text);
        view.setTextIsSelectable(true);
        view.setPadding(dp(22), dp(12), dp(22), dp(4));
        ScrollView scroll = new ScrollView(mActivity);
        scroll.addView(view);
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    @NonNull
    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int color(int res) {
        return ContextCompat.getColor(mActivity, res);
    }

    private int dp(float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, mActivity.getResources().getDisplayMetrics()));
    }

}
