package com.termux.app.terminow.files;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A folder of shared storage that the user picked with the Storage Access Framework. Node ids
 * are document URIs, which are not file paths: the folder can not be a working directory of a
 * terminal, and its location is shown by name, not as a path.
 */
public class TerminowSafFs implements TerminowFs {

    private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS};

    private final Context mContext;
    private final ContentResolver mResolver;
    private final Uri mTreeUri;
    private final String mTitle;

    public TerminowSafFs(@NonNull Context context, @NonNull Uri treeUri, @NonNull String title) {
        mContext = context;
        mResolver = context.getContentResolver();
        mTreeUri = treeUri;
        mTitle = title;
    }

    @NonNull
    public static String idFor(@NonNull Uri treeUri) {
        return "saf:" + treeUri;
    }

    @NonNull
    @Override
    public String getId() {
        return idFor(mTreeUri);
    }

    @NonNull
    @Override
    public String getTitle() {
        return mTitle;
    }

    @NonNull
    public Uri getTreeUri() {
        return mTreeUri;
    }

    /** Whether the app still has the permission that the user gave for the folder. */
    public boolean hasAccess() {
        for (android.content.UriPermission permission : mResolver.getPersistedUriPermissions())
            if (permission.getUri().equals(mTreeUri) && permission.isReadPermission()) return true;
        return false;
    }

    @NonNull
    @Override
    public Capabilities getCapabilities() {
        Capabilities capabilities = new Capabilities();
        capabilities.directShare = true;
        capabilities.terminal = false;
        boolean writable = false;
        for (android.content.UriPermission permission : mResolver.getPersistedUriPermissions())
            if (permission.getUri().equals(mTreeUri)) writable = permission.isWritePermission();
        if (!writable) {
            capabilities.write = capabilities.rename = capabilities.delete = false;
            capabilities.readOnlyReason = "TermiNow only has permission to read this folder";
        }
        return capabilities;
    }

    @NonNull
    @Override
    public Node getRoot() throws IOException {
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(mTreeUri, DocumentsContract.getTreeDocumentId(mTreeUri));
        Node node = query(uri);
        if (node == null) throw new FileNotFoundException("The folder is not available anymore. Choose it again");
        return new Node(node.id, mTitle, true, false, -1, node.modified, node.mimeType);
    }

    @Nullable
    private Node query(@NonNull Uri uri) throws IOException {
        try (Cursor cursor = mResolver.query(uri, COLUMNS, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            return toNode(uri, cursor);
        } catch (SecurityException e) {
            throw new IOException("TermiNow lost access to this folder. Choose it again", e);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @NonNull
    private static Node toNode(@NonNull Uri uri, @NonNull Cursor cursor) {
        String mime = cursor.getString(2);
        boolean directory = Document.MIME_TYPE_DIR.equals(mime);
        long size = cursor.isNull(3) ? -1 : cursor.getLong(3);
        long modified = cursor.isNull(4) ? 0 : cursor.getLong(4);
        String name = cursor.getString(1);
        return new Node(uri.toString(), name != null ? name : "?", directory, false, directory ? -1 : size, modified, mime);
    }

    @NonNull
    @Override
    public List<Node> list(@NonNull Node directory, @NonNull Cancel cancel) throws IOException {
        Uri dirUri = Uri.parse(directory.id);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(mTreeUri, DocumentsContract.getDocumentId(dirUri));
        List<Node> nodes = new ArrayList<>();
        try (Cursor cursor = mResolver.query(children, COLUMNS, null, null, null)) {
            if (cursor == null) throw new IOException("Can not read this folder");
            while (cursor.moveToNext()) {
                cancel.check();
                Uri uri = DocumentsContract.buildDocumentUriUsingTree(mTreeUri, cursor.getString(0));
                nodes.add(toNode(uri, cursor));
            }
        } catch (SecurityException e) {
            throw new IOException("TermiNow lost access to this folder. Choose it again", e);
        }
        return nodes;
    }

    @Nullable
    @Override
    public Node find(@NonNull Node directory, @NonNull String name) throws IOException {
        for (Node node : list(directory, new Cancel()))
            if (node.name.equals(name)) return node;
        return null;
    }

    @NonNull
    @Override
    public Node createDirectory(@NonNull Node parent, @NonNull String name) throws IOException {
        TerminowLocalFs.checkName(name);
        if (find(parent, name) != null) throw new IOException(name + " exists already");
        Uri uri = DocumentsContract.createDocument(mResolver, Uri.parse(parent.id), Document.MIME_TYPE_DIR, name);
        if (uri == null) throw new IOException("Failed to create " + name);
        Node node = query(uri);
        if (node == null) throw new IOException("Failed to create " + name);
        return node;
    }

    @NonNull
    @Override
    public Node rename(@NonNull Node node, @NonNull Node parent, @NonNull String newName) throws IOException {
        TerminowLocalFs.checkName(newName);
        if (find(parent, newName) != null) throw new IOException(newName + " exists already");
        Uri uri = DocumentsContract.renameDocument(mResolver, Uri.parse(node.id), newName);
        Node renamed = uri != null ? query(uri) : null;
        if (renamed == null) throw new IOException("Failed to rename " + node.name);
        return renamed;
    }

    @Nullable
    @Override
    public Node move(@NonNull Node node, @NonNull Node fromParent, @NonNull Node toParent) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null;
        try {
            Uri uri = DocumentsContract.moveDocument(mResolver, Uri.parse(node.id), Uri.parse(fromParent.id), Uri.parse(toParent.id));
            return uri != null ? query(uri) : null;
        } catch (UnsupportedOperationException | IllegalStateException | FileNotFoundException e) {
            // Not every provider can move, then the caller copies
            return null;
        }
    }

    @Override
    public void delete(@NonNull Node node, @NonNull Node parent, @NonNull Cancel cancel) throws IOException {
        cancel.check();
        // A provider deletes a directory with what is in it
        if (!DocumentsContract.deleteDocument(mResolver, Uri.parse(node.id)))
            throw new IOException("Failed to delete " + node.name);
    }

    @NonNull
    @Override
    public InputStream openInput(@NonNull Node file) throws IOException {
        InputStream in = mResolver.openInputStream(Uri.parse(file.id));
        if (in == null) throw new IOException("Can not read " + file.name);
        return in;
    }

    @NonNull
    @Override
    public OutputStream openOutput(@NonNull Node directory, @NonNull String name, boolean replace) throws IOException {
        TerminowLocalFs.checkName(name);
        Node existing = find(directory, name);
        Uri uri;
        if (existing != null) {
            if (!replace) throw new IOException(name + " exists already");
            if (existing.directory) throw new IOException(name + " is a folder");
            uri = Uri.parse(existing.id);
        } else {
            String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension(name));
            uri = DocumentsContract.createDocument(mResolver, Uri.parse(directory.id), mime != null ? mime : "application/octet-stream", name);
            if (uri == null) throw new IOException("Failed to create " + name);
        }
        // "wt" truncates a file that is replaced
        OutputStream out = mResolver.openOutputStream(uri, "wt");
        if (out == null) throw new IOException("Can not write " + name);
        return out;
    }

    @NonNull
    static String extension(@NonNull String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
    }

    @NonNull
    @Override
    public String getDisplayPath(@NonNull List<Node> breadcrumbs) {
        StringBuilder path = new StringBuilder();
        for (Node node : breadcrumbs) {
            if (path.length() > 0) path.append(" › ");
            path.append(node.name);
        }
        return path.toString();
    }

    @Override
    public void close() {
    }

}
