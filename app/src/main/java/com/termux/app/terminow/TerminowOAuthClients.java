package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.BuildConfig;

/**
 * Per-provider OAuth client configuration for cloud backup, and the exact registration values to
 * show when a provider is not configured ("Setup required"). The providers do NOT share a client
 * type or redirect URI (see docs/terminow-backup-oauth.md):
 *
 * <ul>
 *   <li><b>Dropbox</b> — a scoped app with App-folder access, PKCE public client (no secret), a
 *       custom-scheme redirect registered in the App Console.</li>
 *   <li><b>OneDrive</b> — an Entra ID public client, PKCE, redirect {@code msauth://<package>/<sig
 *       hash>} (the signing-certificate hash is part of the redirect, so it is build-specific).</li>
 *   <li><b>Google Drive</b> — custom-scheme and loopback browser redirects are deprecated for Android,
 *       so the official path is Google Identity Services (Credential Manager) with an Android OAuth
 *       client (package + SHA-1) plus a Web client for the offline server auth code. That SDK is not
 *       wired in this build, so Drive stays "Setup required"; its data layer and token refresh are
 *       ready to use once a token is obtained through GIS.</li>
 * </ul>
 *
 * All ids here are public; no confidential client secret is read or embedded.
 */
final class TerminowOAuthClients {

    static final String GDRIVE = "gdrive";
    static final String DROPBOX = "dropbox";
    static final String ONEDRIVE = "onedrive";

    static final String PACKAGE = "com.terminow";
    static final String DROPBOX_REDIRECT = "com.terminow://oauth2redirect/dropbox";

    private TerminowOAuthClients() {}

    static String dropboxAppKey() { return BuildConfig.TERMINOW_DROPBOX_APP_KEY; }
    static String microsoftClientId() { return BuildConfig.TERMINOW_MS_CLIENT_ID; }
    static String microsoftRedirect() { return BuildConfig.TERMINOW_MS_REDIRECT_URI; }
    static String googleServerClientId() { return BuildConfig.TERMINOW_GDRIVE_SERVER_CLIENT_ID; }

    /** True when the provider can actually be connected in this build. */
    static boolean configured(@NonNull String kind) {
        switch (kind) {
            case DROPBOX: return !dropboxAppKey().isEmpty();
            case ONEDRIVE: return !microsoftClientId().isEmpty() && !microsoftRedirect().isEmpty();
            case GDRIVE: return false;   // needs the Google Identity Services SDK, not wired here
            default: return false;
        }
    }

    /** Whether connecting uses the in-app browser consent flow (Drive does not — it needs GIS). */
    static boolean usesBrowserFlow(@NonNull String kind) {
        return DROPBOX.equals(kind) || ONEDRIVE.equals(kind);
    }

    @NonNull
    static String redirect(@NonNull String kind) {
        switch (kind) {
            case DROPBOX: return DROPBOX_REDIRECT;
            case ONEDRIVE: return microsoftRedirect();
            default: return "";
        }
    }

    /** The OAuth endpoints for a browser-flow provider, or {@code null} if it has none/not configured. */
    @Nullable
    static TerminowOAuth.Endpoints endpoints(@NonNull String kind) {
        switch (kind) {
            case DROPBOX:
                if (dropboxAppKey().isEmpty()) return null;
                return TerminowOAuth.dropbox(dropboxAppKey(), DROPBOX_REDIRECT);
            case ONEDRIVE:
                if (microsoftClientId().isEmpty() || microsoftRedirect().isEmpty()) return null;
                return TerminowOAuth.microsoft(microsoftClientId(), microsoftRedirect());
            default:
                return null;
        }
    }

    @NonNull
    static String displayName(@NonNull String kind) {
        switch (kind) {
            case GDRIVE: return "Google Drive";
            case DROPBOX: return "Dropbox";
            case ONEDRIVE: return "OneDrive";
            case "s3": return "S3 / S3-compatible";
            default: return kind;
        }
    }

    /** Human-readable, provider-accurate registration values for the "Setup required" screen. */
    @NonNull
    static String setupInfo(@NonNull String kind) {
        switch (kind) {
            case DROPBOX:
                return "Dropbox — not configured.\n\n"
                    + "In the Dropbox App Console, create a Scoped app with App folder access:\n"
                    + "• Client type: public app, PKCE (no client secret)\n"
                    + "• Redirect URI: " + DROPBOX_REDIRECT + "\n"
                    + "• Scopes: files.content.write, files.content.read, files.metadata.read\n"
                    + "• Offline access: enabled (token_access_type=offline) for a refresh token\n\n"
                    + "Then put the app key in app/terminow-backup-oauth.properties:\n"
                    + "  dropboxAppKey=<your app key>\n"
                    + "and rebuild. Do not add the app secret.";
            case ONEDRIVE:
                return "OneDrive — not configured.\n\n"
                    + "In the Microsoft Entra admin center, register an app:\n"
                    + "• Platform: Mobile and desktop applications (public client enabled)\n"
                    + "• Package name: " + PACKAGE + "\n"
                    + "• Redirect URI: msauth://" + PACKAGE + "/<base64url SHA-1 of your signing cert>\n"
                    + "  (register one for the debug key and one for the release key)\n"
                    + "• Scopes: Files.ReadWrite.AppFolder, offline_access\n"
                    + "• PKCE public client (no client secret)\n\n"
                    + "Then in app/terminow-backup-oauth.properties:\n"
                    + "  microsoftClientId=<application (client) id>\n"
                    + "  microsoftRedirectUri=msauth://" + PACKAGE + "/<your signature hash>\n"
                    + "and rebuild.";
            case GDRIVE:
                return "Google Drive — not configured.\n\n"
                    + "Custom-scheme and loopback browser redirects are deprecated for Android, so Drive "
                    + "uses Google Identity Services (Credential Manager), which delivers the result in-app "
                    + "with no redirect URI. In Google Cloud Console create:\n"
                    + "• An Android OAuth client: package " + PACKAGE + " + SHA-1 of your signing cert\n"
                    + "• A Web application OAuth client (for the offline server auth code)\n"
                    + "• Scope: https://www.googleapis.com/auth/drive.appdata\n"
                    + "• No client secret in the APK\n\n"
                    + "Then in app/terminow-backup-oauth.properties:\n"
                    + "  googleServerClientId=<web client id>\n"
                    + "and wire the GIS Authorization API (play-services-auth). The Drive upload/list/\n"
                    + "download/delete layer and token refresh are already implemented.";
            default:
                return displayName(kind) + " is not configured.";
        }
    }
}
