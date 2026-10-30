package com.termux.app.terminow;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;

/**
 * The host Edit / Duplicate / Delete action menu, used from both the list (one column) and grid
 * (many columns) views of the Hosts page so the two never drift apart. On a tablet or large screen
 * it is an anchored popover beside the host's card, kept within the screen; on a phone it is a
 * compact modal bottom sheet with a drag handle and safe-area padding. Both dismiss on a tap outside
 * or Back, and report dismissal so the caller can clear the card highlight.
 *
 * <p>The menu owns only presentation. The caller supplies the title (host name), the subtitle
 * (connection type) and the three actions; it resolves the host by its stable id when an action
 * runs, and it owns the delete confirmation. No host state is changed here.</p>
 */
public final class TerminowHostActionMenu {

    /** The three actions, invoked after the menu has closed. */
    public interface Callbacks {
        void onEdit();
        void onDuplicate();
        void onDelete();
    }

    /** Popover width on large screens, within the requested 260–300dp range. */
    private static final int POPOVER_WIDTH_DP = 280;
    private static final int LARGE_SCREEN_DP = 600;

    private TerminowHostActionMenu() {}

    public static void show(@NonNull Activity activity, @Nullable View anchor, @NonNull String title,
                            @NonNull String subtitle, @NonNull Callbacks callbacks, @Nullable Runnable onDismiss) {
        View content = activity.getLayoutInflater().inflate(R.layout.view_terminow_host_action_menu, null, false);
        ((TextView) content.findViewById(R.id.terminow_action_title)).setText(title);
        TextView sub = content.findViewById(R.id.terminow_action_subtitle);
        if (subtitle.isEmpty()) sub.setVisibility(View.GONE);
        else sub.setText(subtitle);

        // No anchor (e.g. a keyboard long-press) falls back to the sheet, which needs none.
        boolean large = anchor != null
            && activity.getResources().getConfiguration().screenWidthDp >= LARGE_SCREEN_DP;

        // The window is created below; rows capture it so a tap closes the menu before the action runs.
        final Runnable[] dismiss = new Runnable[1];
        wireRow(content, R.id.terminow_action_edit, callbacks::onEdit, dismiss);
        wireRow(content, R.id.terminow_action_duplicate, callbacks::onDuplicate, dismiss);
        wireRow(content, R.id.terminow_action_delete, callbacks::onDelete, dismiss);

        if (large) showPopover(activity, anchor, content, dismiss, onDismiss);
        else showSheet(activity, content, dismiss, onDismiss);
    }

    /** A row closes the menu, then runs its action on the next loop so the window is already gone. */
    private static void wireRow(@NonNull View content, int rowId, @NonNull Runnable action,
                                @NonNull Runnable[] dismiss) {
        content.findViewById(rowId).setOnClickListener(v -> {
            if (dismiss[0] != null) dismiss[0].run();
            new Handler(Looper.getMainLooper()).post(action);
        });
    }

    private static void showSheet(@NonNull Activity activity, @NonNull View content,
                                  @NonNull Runnable[] dismiss, @Nullable Runnable onDismiss) {
        content.findViewById(R.id.terminow_action_handle).setVisibility(View.VISIBLE);
        content.setBackgroundResource(R.drawable.terminow_action_sheet);
        applyBottomSafeArea(content);

        Dialog dialog = new Dialog(activity, R.style.TerminowSheetDialog);
        dialog.setContentView(content);
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
        }
        dialog.setOnDismissListener(d -> { if (onDismiss != null) onDismiss.run(); });
        dismiss[0] = dialog::dismiss;
        dialog.show();
    }

    /** Pad the sheet above the navigation bar / gesture area, so the last row is never under it. */
    private static void applyBottomSafeArea(@NonNull View content) {
        final int base = dp(content.getContext(), 8);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = Math.max(base, systemBottomInset(insets));
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bottom);
            return insets;
        });
        content.requestApplyInsets();
    }

    @SuppressWarnings("deprecation") // getSystemWindowInsetBottom covers minSdk 24; Type API is API 30+
    private static int systemBottomInset(@NonNull WindowInsets insets) {
        return insets.getSystemWindowInsetBottom();
    }

    private static void showPopover(@NonNull Activity activity, @NonNull View anchor, @NonNull View content,
                                    @NonNull Runnable[] dismiss, @Nullable Runnable onDismiss) {
        content.findViewById(R.id.terminow_action_handle).setVisibility(View.GONE);
        content.setBackgroundResource(R.drawable.terminow_action_popover);

        int width = dp(activity, POPOVER_WIDTH_DP);
        PopupWindow popup = new PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        // A (transparent) background is what lets an outside tap dismiss the popup; elevation is the
        // only shadow, kept restrained.
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(activity, 12));

        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = content.getMeasuredHeight();

        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int margin = dp(activity, 8);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);

        // Prefer just right of the card; if it would overflow, place it to the left; then clamp.
        int x = loc[0] + anchor.getWidth() + margin;
        if (x + width > dm.widthPixels - margin) x = loc[0] - width - margin;
        if (x < margin) x = Math.max(margin, dm.widthPixels - width - margin);
        // Align the top with the card, clamped so the whole popover stays on screen.
        int y = loc[1];
        if (y + height > dm.heightPixels - margin) y = Math.max(margin, dm.heightPixels - height - margin);

        popup.setOnDismissListener(() -> { if (onDismiss != null) onDismiss.run(); });
        dismiss[0] = popup::dismiss;
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y);
    }

    private static int dp(@NonNull Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
