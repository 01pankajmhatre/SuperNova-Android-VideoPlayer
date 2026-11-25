package com.archos.mediacenter.video.browser.dlna;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The content of the DLNA server: the shared folders (Storage Access Framework trees), seen as a
 * UPnP object tree. Id "0" is the root, "f&lt;n&gt;" the n-th shared folder, "d&lt;n&gt;/&lt;documentId&gt;" a document
 * (sub-folder or file) of that folder.
 */
final class DlnaMediaTree {
    static final String ROOT_ID = "0";

    static final class Node {
        String id;
        String parentId;
        String title;
        String fileName;       // with extension: players guess the container from the end of the URL
        boolean container;
        String mime;
        long size = -1;
        Uri documentUri;       // for files
        int mediaKind;         // 1 video, 2 audio, 3 image
    }

    private final Context mContext;
    private final ContentResolver mResolver;
    private final List<DlnaServerPrefs.Folder> mFolders;

    DlnaMediaTree(Context context, List<DlnaServerPrefs.Folder> folders) {
        mContext = context.getApplicationContext();
        mResolver = mContext.getContentResolver();
        mFolders = folders;
    }

    Node rootNode() {
        Node n = new Node();
        n.id = ROOT_ID;
        n.parentId = "-1";
        n.title = "Shared folders";
        n.container = true;
        return n;
    }

    /** The object with this id, or null. */
    Node get(String id) {
        if (ROOT_ID.equals(id)) return rootNode();
        try {
            if (id.startsWith("f")) {
                int index = Integer.parseInt(id.substring(1));
                DlnaServerPrefs.Folder folder = mFolders.get(index);
                Node n = new Node();
                n.id = id;
                n.parentId = ROOT_ID;
                n.title = folder.name;
                n.container = true;
                return n;
            }
            if (id.startsWith("d")) {
                int slash = id.indexOf('/');
                int index = Integer.parseInt(id.substring(1, slash));
                String documentId = Uri.decode(id.substring(slash + 1));
                return queryDocument(index, documentId, null);
            }
        } catch (RuntimeException e) {
            // invalid id
        }
        return null;
    }

    /** Children of a container, folders first, then by name. */
    List<Node> children(String id) {
        List<Node> result = new ArrayList<>();
        if (ROOT_ID.equals(id)) {
            for (int i = 0; i < mFolders.size(); i++) result.add(get("f" + i));
            return result;
        }
        try {
            int index;
            String documentId;
            String parentId = id;
            if (id.startsWith("f")) {
                index = Integer.parseInt(id.substring(1));
                documentId = DocumentsContract.getTreeDocumentId(mFolders.get(index).uri);
            } else {
                int slash = id.indexOf('/');
                index = Integer.parseInt(id.substring(1, slash));
                documentId = Uri.decode(id.substring(slash + 1));
            }
            Uri tree = mFolders.get(index).uri;
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId);
            try (Cursor c = mResolver.query(childrenUri, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    Node n = fromRow(index, parentId, c.getString(0), c.getString(1), c.getString(2),
                            c.isNull(3) ? -1 : c.getLong(3));
                    if (n != null) result.add(n);
                }
            }
        } catch (Exception e) {
            // folder no longer readable: empty
        }
        Collections.sort(result, (a, b) -> {
            if (a.container != b.container) return a.container ? -1 : 1;
            return a.title.compareToIgnoreCase(b.title);
        });
        return result;
    }

    private Node queryDocument(int index, String documentId, String parentId) {
        Uri tree = mFolders.get(index).uri;
        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId);
        try (Cursor c = mResolver.query(docUri, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                Node n = fromRow(index, parentId, c.getString(0), c.getString(1), c.getString(2),
                        c.isNull(3) ? -1 : c.getLong(3));
                if (n != null && n.parentId == null) n.parentId = ROOT_ID;
                return n;
            }
        } catch (Exception e) {
            // gone
        }
        return null;
    }

    private Node fromRow(int index, String parentId, String documentId, String name, String mime, long size) {
        if (name == null) return null;
        Node n = new Node();
        n.id = "d" + index + "/" + Uri.encode(documentId);
        n.parentId = parentId;
        n.documentUri = DocumentsContract.buildDocumentUriUsingTree(mFolders.get(index).uri, documentId);
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
            n.container = true;
            n.title = name;
            return n;
        }
        String type = mime;
        String extension = MimeTypeMap.getFileExtensionFromUrl(name.replace(" ", "_")).toLowerCase(Locale.ROOT);
        if (type == null || type.equals("application/octet-stream") || type.isEmpty()) {
            String guess = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (guess == null) guess = guessVideoMime(extension);
            type = guess;
        }
        if (type == null) return null;
        if (type.startsWith("video/")) n.mediaKind = 1;
        else if (type.startsWith("audio/")) n.mediaKind = 2;
        else if (type.startsWith("image/")) n.mediaKind = 3;
        else return null;                 // only media is shared
        n.mime = type;
        n.size = size;
        n.fileName = name;
        int dot = name.lastIndexOf('.');
        n.title = dot > 0 ? name.substring(0, dot) : name;
        return n;
    }

    private static String guessVideoMime(String extension) {
        switch (extension) {
            case "mkv": return "video/x-matroska";
            case "avi": return "video/x-msvideo";
            case "ts": case "m2ts": case "mts": return "video/mp2t";
            case "flv": return "video/x-flv";
            case "wmv": return "video/x-ms-wmv";
            case "mov": return "video/quicktime";
            case "flac": return "audio/flac";
            default: return null;
        }
    }
}
