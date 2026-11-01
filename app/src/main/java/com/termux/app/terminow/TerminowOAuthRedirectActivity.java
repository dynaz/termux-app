package com.termux.app.terminow;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;

/**
 * Receives the OAuth redirect from the browser after the user grants (or denies) consent for a cloud
 * provider. It hands the redirect URI to {@link TerminowOAuthFlow#complete}, which validates it
 * against the pending authorization stored on the device — so it still works after the app's process
 * was recreated while the browser was in front — then exchanges the code and stores the tokens. It
 * shows a short result and brings the app back to the front. Declared for the Dropbox custom scheme
 * and the Microsoft {@code msauth} scheme in the manifest.
 */
public final class TerminowOAuthRedirectActivity extends Activity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        Uri data = intent == null ? null : intent.getData();
        if (data == null) { finish(); return; }

        final String redirect = data.toString();
        new Thread(() -> {
            TerminowStore store = new TerminowStore(getApplicationContext());
            final TerminowOAuthFlow.Result result = TerminowOAuthFlow.complete(store, redirect);
            runOnUiThread(() -> {
                String message;
                switch (result.status) {
                    case CONNECTED:
                        message = TerminowOAuthClients.displayName(result.kind) + " connected";
                        break;
                    case CANCELLED:
                        message = "Sign-in cancelled";
                        break;
                    default:
                        message = "Could not connect: " + (result.message == null ? "unknown error" : result.message);
                        break;
                }
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
                Intent back = getPackageManager().getLaunchIntentForPackage(getPackageName());
                if (back != null) {
                    back.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(back);
                }
                finish();
            });
        }).start();
    }
}
