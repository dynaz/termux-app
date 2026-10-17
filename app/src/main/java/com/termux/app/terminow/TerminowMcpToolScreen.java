package com.termux.app.terminow;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.Base64;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminow.mcp.McpException;
import com.termux.app.terminow.mcp.McpSchemaForm;
import com.termux.app.terminow.mcp.McpSession;
import com.termux.app.terminow.mcp.McpTool;
import com.termux.app.terminow.mcp.McpToolResult;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Run one tool: a form from its input schema, a confirmation with the exact arguments, progress
 * with Cancel, and the result.
 */
final class TerminowMcpToolScreen {

    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_TIMEOUT_SECONDS = 3600;
    /** Longer text is cut in the view, and copied whole. */
    private static final int MAX_SHOWN_CHARS = 100_000;
    private static final int MAX_IMAGE_SIDE = 2048;

    /** The input of one field of the form. */
    private static final class Input {
        final McpSchemaForm.Field field;
        final View view;
        final TextView error;

        Input(@NonNull McpSchemaForm.Field field, @NonNull View view, @NonNull TextView error) {
            this.field = field;
            this.view = view;
            this.error = error;
        }

        @NonNull
        String value() {
            if (view instanceof Switch) return field.booleanText(((Switch) view).isChecked());
            if (view instanceof Spinner) {
                int index = ((Spinner) view).getSelectedItemPosition() - (field.required ? 0 : 1);
                return index >= 0 && index < field.enumValues.size() ? field.enumValues.get(index) : "";
            }
            return ((EditText) view).getText().toString();
        }
    }

    private final TerminowMcpPage mPage;
    private final TermuxActivity mActivity;
    private final TerminowMcpProfile mProfile;
    private final McpTool mTool;
    private final McpSchemaForm mForm;
    private final List<Input> mInputs = new ArrayList<>();

    private TerminowMcpToolScreen(@NonNull TerminowMcpPage page, @NonNull TerminowMcpProfile profile, @NonNull McpTool tool) {
        mPage = page;
        mActivity = page.getActivity();
        mProfile = profile;
        mTool = tool;
        mForm = McpSchemaForm.parse(tool.getInputSchema());
    }

    static void show(@NonNull TerminowMcpPage page, @NonNull TerminowMcpProfile profile, @NonNull McpTool tool) {
        new TerminowMcpToolScreen(page, profile, tool).show();
    }

    private void show() {
        LinearLayout form = new LinearLayout(mActivity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(mPage.dp(22), mPage.dp(4), mPage.dp(22), mPage.dp(8));
        if (!mTool.title.isEmpty() && !mTool.title.equals(mTool.name)) {
            TextView name = mPage.text(mTool.name, 12, R.color.terminow_text_secondary);
            name.setTypeface(Typeface.MONOSPACE);
            form.addView(name, TerminowMcpPage.matchWrap());
        }
        LinearLayout badges = new LinearLayout(mActivity);
        TerminowMcpPage.addToolBadges(mPage, badges, mTool);
        if (badges.getChildCount() > 0) form.addView(badges, TerminowMcpPage.matchWrap());
        if (!mTool.description.isEmpty()) {
            TextView description = mPage.text(mTool.description.trim(), 14, R.color.terminow_text);
            description.setTextIsSelectable(true);
            description.setPadding(0, mPage.dp(6), 0, 0);
            form.addView(description, TerminowMcpPage.matchWrap());
        }
        if (mForm.getFields().isEmpty()) {
            TextView none = mPage.text(mActivity.getString(R.string.terminow_mcp_tool_no_arguments), 13, R.color.terminow_text_secondary);
            none.setPadding(0, mPage.dp(12), 0, 0);
            form.addView(none, TerminowMcpPage.matchWrap());
        }
        for (McpSchemaForm.Field field : mForm.getFields()) addField(form, field);

        mPage.label(form, R.string.terminow_mcp_tool_timeout);
        EditText timeout = new EditText(mActivity);
        mPage.styleField(timeout, InputType.TYPE_CLASS_NUMBER);
        timeout.setText(String.valueOf(DEFAULT_TIMEOUT_SECONDS));
        form.addView(timeout, TerminowMcpPage.matchWrap());

        ScrollView scroll = new ScrollView(mActivity);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(mTool.getDisplayName())
            .setView(scroll)
            .setPositiveButton(R.string.terminow_mcp_tool_run, null)
            .setNegativeButton(R.string.terminow_mcp_tool_close, null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            for (Input input : mInputs) input.error.setVisibility(View.GONE);
            Map<String, String> values = new HashMap<>();
            for (Input input : mInputs) values.put(input.field.name, input.value());
            JSONObject arguments;
            try {
                arguments = mForm.buildArguments(values);
            } catch (McpSchemaForm.InvalidArguments e) {
                for (Input input : mInputs) {
                    String error = e.errors.get(input.field.name);
                    if (error == null) continue;
                    input.error.setText(error);
                    input.error.setVisibility(View.VISIBLE);
                }
                return;
            }
            int seconds;
            try {
                seconds = Integer.parseInt(timeout.getText().toString().trim());
            } catch (NumberFormatException e) {
                seconds = 0;
            }
            if (seconds < 1 || seconds > MAX_TIMEOUT_SECONDS) {
                timeout.setError(mActivity.getString(R.string.terminow_mcp_tool_timeout_invalid, MAX_TIMEOUT_SECONDS));
                return;
            }
            confirm(arguments, seconds * 1000L);
        }));
        dialog.show();
    }

    private void addField(@NonNull LinearLayout form, @NonNull McpSchemaForm.Field field) {
        TextView label = mPage.text(field.getLabel() + (field.required ? " *" : ""), 12, R.color.terminow_text_secondary);
        label.setPadding(0, mPage.dp(14), 0, mPage.dp(2));
        form.addView(label, TerminowMcpPage.matchWrap());
        if (!field.description.isEmpty()) {
            TextView description = mPage.text(field.description, 12, R.color.terminow_text_secondary);
            form.addView(description, TerminowMcpPage.matchWrap());
        }
        View view;
        switch (field.kind) {
            case BOOLEAN: {
                Switch toggle = new Switch(mActivity);
                toggle.setChecked("true".equals(field.defaultText));
                toggle.setThumbTintList(ContextCompat.getColorStateList(mActivity, R.color.terminow_panel_switch_thumb));
                toggle.setTrackTintList(ContextCompat.getColorStateList(mActivity, R.color.terminow_panel_switch_track));
                view = toggle;
                break;
            }
            case ENUM: {
                List<String> items = new ArrayList<>();
                if (!field.required) items.add(mActivity.getString(R.string.terminow_mcp_tool_not_set));
                items.addAll(field.enumValues);
                Spinner spinner = new Spinner(mActivity);
                ArrayAdapter<String> adapter = new ArrayAdapter<>(mActivity, android.R.layout.simple_spinner_item, items);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinner.setAdapter(adapter);
                int defaultIndex = field.enumValues.indexOf(field.defaultText);
                if (defaultIndex >= 0) spinner.setSelection(defaultIndex + (field.required ? 0 : 1));
                view = spinner;
                break;
            }
            default: {
                EditText text = new EditText(mActivity);
                int inputType = field.kind == McpSchemaForm.Kind.INTEGER ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED :
                    field.kind == McpSchemaForm.Kind.NUMBER ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL :
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
                mPage.styleField(text, inputType);
                if (field.kind == McpSchemaForm.Kind.STRING || field.kind == McpSchemaForm.Kind.JSON) {
                    text.setSingleLine(false);
                    text.setMaxLines(field.kind == McpSchemaForm.Kind.JSON ? 12 : 6);
                }
                if (field.kind == McpSchemaForm.Kind.JSON) {
                    text.setTypeface(Typeface.MONOSPACE);
                    text.setMinLines(3);
                    text.setHint(field.defaultText.isEmpty() ? "JSON" : field.defaultText);
                } else if (!field.defaultText.isEmpty()) {
                    text.setHint(mActivity.getString(R.string.terminow_mcp_tool_default, field.defaultText));
                }
                view = text;
            }
        }
        form.addView(view, TerminowMcpPage.matchWrap());
        TextView error = mPage.text("", 12, R.color.terminow_host_ssh);
        error.setVisibility(View.GONE);
        form.addView(error, TerminowMcpPage.matchWrap());
        mInputs.add(new Input(field, view, error));
    }

    /** The server, the tool and the exact arguments, with stored secrets hidden, before anything is sent. */
    private void confirm(@NonNull JSONObject arguments, long timeoutMs) {
        String shown;
        try {
            shown = ((JSONObject) McpSchemaForm.redactSecrets(arguments, mProfile.getSecrets())).toString(2);
        } catch (JSONException e) {
            shown = String.valueOf(e.getMessage());
        }
        LinearLayout box = new LinearLayout(mActivity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(mPage.dp(22), mPage.dp(8), mPage.dp(22), mPage.dp(4));
        box.addView(mPage.text(mActivity.getString(R.string.terminow_mcp_confirm_server, mProfile.name), 14, R.color.terminow_text),
            TerminowMcpPage.matchWrap());
        box.addView(mPage.text(mActivity.getString(R.string.terminow_mcp_confirm_tool, mTool.name), 14, R.color.terminow_text),
            TerminowMcpPage.matchWrap());
        TextView label = mPage.text(mActivity.getString(R.string.terminow_mcp_confirm_arguments), 12, R.color.terminow_text_secondary);
        label.setPadding(0, mPage.dp(10), 0, mPage.dp(2));
        box.addView(label, TerminowMcpPage.matchWrap());
        TextView json = mPage.text(shown, 13, R.color.terminow_text);
        json.setTypeface(Typeface.MONOSPACE);
        json.setTextIsSelectable(true);
        box.addView(json, TerminowMcpPage.matchWrap());
        TextView warning = mPage.text(mActivity.getString(mTool.isDestructive() ? R.string.terminow_mcp_confirm_destructive :
            mTool.isReadOnly() ? R.string.terminow_mcp_confirm_read_only : R.string.terminow_mcp_confirm_note), 13,
            mTool.isDestructive() ? R.color.terminow_host_ssh : R.color.terminow_text_secondary);
        if (mTool.isDestructive()) warning.setTypeface(Typeface.DEFAULT_BOLD);
        warning.setPadding(0, mPage.dp(12), 0, 0);
        box.addView(warning, TerminowMcpPage.matchWrap());
        ScrollView scroll = new ScrollView(mActivity);
        scroll.addView(box);
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_mcp_confirm_title)
            .setView(scroll)
            .setPositiveButton(R.string.terminow_mcp_tool_run, (d, w) -> mPage.requirePremium(() -> run(arguments, timeoutMs)))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void run(@NonNull JSONObject arguments, long timeoutMs) {
        McpSession session = mPage.getSessions().get(mProfile.id);
        if (session == null || session.getSnapshot().state != McpSession.State.READY) {
            mPage.showMessage(mTool.getDisplayName(), mActivity.getString(R.string.terminow_mcp_server_connect_first));
            return;
        }
        McpSession.CancelHandle cancel = new McpSession.CancelHandle();
        AlertDialog progress = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setMessage(mActivity.getString(R.string.terminow_mcp_tool_running, mTool.getDisplayName()))
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel, (d, w) -> new Thread(cancel::cancel).start())
            .show();
        new Thread(() -> {
            McpToolResult result = null;
            McpException failure = null;
            try {
                result = session.callTool(mTool.name, arguments, timeoutMs, cancel);
            } catch (McpException e) {
                failure = e;
            }
            McpToolResult finalResult = result;
            McpException finalFailure = failure;
            mActivity.runOnUiThread(() -> {
                progress.dismiss();
                if (mActivity.isFinishing()) return;
                if (finalResult != null) {
                    showResult(finalResult);
                } else if (finalFailure.kind == McpException.Kind.CANCELLED && cancel.isCancelled()) {
                    mActivity.showToast(mActivity.getString(R.string.terminow_mcp_tool_cancelled), false);
                } else {
                    String[] explained = TerminowMcpPage.explain(mActivity, finalFailure.kind);
                    mPage.showMessage(explained[0], (explained[1].isEmpty() ? "" : explained[1] + "\n\n") + finalFailure.getMessage());
                }
            });
        }).start();
    }

    private void showResult(@NonNull McpToolResult result) {
        LinearLayout box = new LinearLayout(mActivity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(mPage.dp(22), mPage.dp(8), mPage.dp(22), mPage.dp(4));
        StringBuilder copyText = new StringBuilder();
        if (result.isError) {
            TextView banner = mPage.text(mActivity.getString(R.string.terminow_mcp_result_is_error), 14, R.color.terminow_text);
            banner.setTypeface(Typeface.DEFAULT_BOLD);
            banner.setPadding(mPage.dp(12), mPage.dp(10), mPage.dp(12), mPage.dp(10));
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(mPage.dp(8));
            shape.setColor(mPage.color(R.color.terminow_host_ssh) & 0x55FFFFFF);
            banner.setBackground(shape);
            LinearLayout.LayoutParams params = TerminowMcpPage.matchWrap();
            params.bottomMargin = mPage.dp(10);
            box.addView(banner, params);
        }
        if (result.content.isEmpty() && result.structuredContent == null) {
            box.addView(mPage.text(mActivity.getString(R.string.terminow_mcp_result_empty), 13, R.color.terminow_text_secondary),
                TerminowMcpPage.matchWrap());
        }
        for (McpToolResult.Content content : result.content) addContent(box, content, copyText);
        if (result.structuredContent != null) {
            String structured = prettyJson(result.structuredContent);
            box.addView(heading(R.string.terminow_mcp_result_structured), TerminowMcpPage.matchWrap());
            box.addView(monospace(structured), TerminowMcpPage.matchWrap());
            if (copyText.length() == 0) copyText.append(structured);
        }

        String raw = prettyJson(result.raw);
        TextView rawToggle = mPage.text(mActivity.getString(R.string.terminow_mcp_result_show_raw), 14, R.color.terminow_panel_accent);
        rawToggle.setTypeface(Typeface.DEFAULT_BOLD);
        rawToggle.setPadding(0, mPage.dp(14), 0, mPage.dp(4));
        TextView rawView = monospace(raw);
        rawView.setVisibility(View.GONE);
        rawToggle.setOnClickListener(v -> {
            boolean show = rawView.getVisibility() != View.VISIBLE;
            rawView.setVisibility(show ? View.VISIBLE : View.GONE);
            rawToggle.setText(show ? R.string.terminow_mcp_result_hide_raw : R.string.terminow_mcp_result_show_raw);
        });
        box.addView(rawToggle, TerminowMcpPage.matchWrap());
        box.addView(rawView, TerminowMcpPage.matchWrap());

        ScrollView scroll = new ScrollView(mActivity);
        scroll.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(mActivity.getString(R.string.terminow_mcp_result_title, mTool.getDisplayName()))
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.terminow_mcp_result_copy, null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            String text = rawView.getVisibility() == View.VISIBLE || copyText.length() == 0 ? raw : copyText.toString();
            ClipboardManager clipboard = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) return;
            clipboard.setPrimaryClip(ClipData.newPlainText(mTool.name, text));
            mActivity.showToast(mActivity.getString(R.string.terminow_mcp_result_copied), false);
        }));
        dialog.show();
    }

    private void addContent(@NonNull LinearLayout box, @NonNull McpToolResult.Content content, @NonNull StringBuilder copyText) {
        if (content instanceof McpToolResult.Text) {
            String text = ((McpToolResult.Text) content).text;
            box.addView(monospace(text), spaced());
            if (copyText.length() > 0) copyText.append("\n\n");
            copyText.append(text);
        } else if (content instanceof McpToolResult.Image) {
            McpToolResult.Image image = (McpToolResult.Image) content;
            Bitmap bitmap = decodeImage(image.data);
            if (bitmap == null) {
                box.addView(mPage.text(mActivity.getString(R.string.terminow_mcp_result_image_failed, image.mimeType), 13,
                    R.color.terminow_text_secondary), spaced());
                return;
            }
            ImageView view = new ImageView(mActivity);
            view.setImageBitmap(bitmap);
            view.setAdjustViewBounds(true);
            view.setMaxHeight(mPage.dp(360));
            view.setScaleType(ImageView.ScaleType.FIT_START);
            view.setContentDescription(image.mimeType);
            box.addView(view, spaced());
        } else if (content instanceof McpToolResult.Audio) {
            McpToolResult.Audio audio = (McpToolResult.Audio) content;
            box.addView(resourceRow(mActivity.getString(R.string.terminow_mcp_result_audio, audio.mimeType, audio.data.length() * 3 / 4 / 1024), ""),
                spaced());
        } else if (content instanceof McpToolResult.ResourceLink) {
            McpToolResult.ResourceLink link = (McpToolResult.ResourceLink) content;
            box.addView(resourceRow(link.name.isEmpty() ? mActivity.getString(R.string.terminow_mcp_result_resource) : link.name, link.uri), spaced());
            if (copyText.length() > 0) copyText.append("\n\n");
            copyText.append(link.uri);
        } else if (content instanceof McpToolResult.EmbeddedResource) {
            McpToolResult.EmbeddedResource resource = (McpToolResult.EmbeddedResource) content;
            box.addView(resourceRow(mActivity.getString(R.string.terminow_mcp_result_resource) +
                (resource.mimeType.isEmpty() ? "" : " · " + resource.mimeType), resource.uri), spaced());
            if (resource.text != null) {
                box.addView(monospace(resource.text), TerminowMcpPage.matchWrap());
                if (copyText.length() > 0) copyText.append("\n\n");
                copyText.append(resource.text);
            } else if (resource.blob != null) {
                box.addView(mPage.text(mActivity.getString(R.string.terminow_mcp_result_binary, resource.blob.length() * 3 / 4), 12,
                    R.color.terminow_text_secondary), TerminowMcpPage.matchWrap());
            }
        } else {
            McpToolResult.Other other = (McpToolResult.Other) content;
            box.addView(resourceRow(other.type, ""), spaced());
            box.addView(monospace(other.json), TerminowMcpPage.matchWrap());
        }
    }

    @Nullable
    private static Bitmap decodeImage(@NonNull String base64) {
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (bounds.outWidth / options.inSampleSize > MAX_IMAGE_SIDE || bounds.outHeight / options.inSampleSize > MAX_IMAGE_SIDE)
                options.inSampleSize *= 2;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (IllegalArgumentException | OutOfMemoryError e) {
            return null;
        }
    }

    @NonNull
    private View resourceRow(@NonNull String title, @NonNull String uri) {
        LinearLayout row = mPage.card();
        TextView name = mPage.text(title, 14, R.color.terminow_text);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(name, TerminowMcpPage.matchWrap());
        if (!uri.isEmpty()) {
            TextView link = mPage.text(uri, 12, R.color.terminow_text_secondary);
            link.setTypeface(Typeface.MONOSPACE);
            link.setTextIsSelectable(true);
            row.addView(link, TerminowMcpPage.matchWrap());
        }
        return row;
    }

    @NonNull
    private TextView heading(int textRes) {
        TextView view = mPage.text(mActivity.getString(textRes), 12, R.color.terminow_text_secondary);
        view.setPadding(0, mPage.dp(14), 0, mPage.dp(2));
        return view;
    }

    @NonNull
    private TextView monospace(@NonNull String text) {
        String shown = text.length() > MAX_SHOWN_CHARS ?
            text.substring(0, MAX_SHOWN_CHARS) + "\n" + mActivity.getString(R.string.terminow_mcp_result_cut, text.length() - MAX_SHOWN_CHARS) : text;
        TextView view = mPage.text(shown, 13, R.color.terminow_text);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        return view;
    }

    @NonNull
    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams params = TerminowMcpPage.matchWrap();
        params.bottomMargin = mPage.dp(8);
        return params;
    }

    @NonNull
    private static String prettyJson(@NonNull JSONObject json) {
        try {
            return json.toString(2);
        } catch (JSONException e) {
            return json.toString();
        }
    }

}
