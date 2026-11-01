# Cloud backup — OAuth provider setup

TermiNow backs up Linux environments to S3-compatible storage out of the box, and to Google Drive,
Dropbox and OneDrive once each provider's OAuth app is registered. All three use **public clients
with PKCE — no client secret is ever stored in the APK.** When a provider is not configured the app
shows "Setup required" with these same values.

Put the resulting ids in `app/terminow-backup-oauth.properties` (not committed) and rebuild:

```properties
dropboxAppKey=
microsoftClientId=
microsoftRedirectUri=
googleServerClientId=
```

Package name for all providers: `com.terminow`.

## Dropbox

- Dropbox App Console → create a **Scoped app**, **App folder** access.
- Client type: **public app, PKCE** (no app secret in the APK).
- Redirect URI (register exactly): `com.terminow://oauth2redirect/dropbox`
- Scopes: `files.content.write`, `files.content.read`, `files.metadata.read`
- Enable offline access (the app requests `token_access_type=offline`) so a refresh token is issued.
- `dropboxAppKey` = the app key.

The manifest already declares the matching `intent-filter` (`scheme=com.terminow`, `host=oauth2redirect`).

## OneDrive (Microsoft Entra ID)

- Entra admin center → App registrations → new registration.
- Platform: **Mobile and desktop applications**, **public client / native** enabled.
- Redirect URI: `msauth://com.terminow/<base64url SHA-1 of your signing certificate>`
  - Register one for the **debug** signing key and one for the **release** key.
  - Get the hash from the Azure portal's Android generator, or:
    `keytool -exportcert -alias <alias> -keystore <keystore> | openssl sha1 -binary | openssl base64`
    then URL-encode it.
- Scopes: `Files.ReadWrite.AppFolder`, `offline_access`
- PKCE public client (no client secret).
- `microsoftClientId` = Application (client) ID; `microsoftRedirectUri` = the full `msauth://…` URI.

The manifest declares `scheme=msauth`, `host=com.terminow`; the signature-hash path is matched by host.

## Google Drive

Google **deprecated custom-scheme and loopback browser redirects for Android**, so Drive does **not**
use the in-app browser flow. The official path is **Google Identity Services (Credential Manager)**,
which returns the result in-app with no redirect URI.

- Google Cloud Console → Credentials:
  - An **Android OAuth client**: package `com.terminow` + SHA-1 of the signing certificate.
  - A **Web application OAuth client** for the offline **server auth code** → `googleServerClientId`.
- Scope: `https://www.googleapis.com/auth/drive.appdata` (app-private folder; never the user's files).
- No client secret in the APK.
- Remaining work to connect in-app: wire `play-services-auth` (GIS Authorization API) to obtain the
  access/refresh tokens, then hand them to the store (`setOAuthTokens("gdrive", …)`). The Drive
  upload/list/download/delete layer (`TerminowDriveProvider`) and token refresh
  (`TerminowOAuth.source`) are already implemented and tested, so only token acquisition remains.
