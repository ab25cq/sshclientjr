package com.sshclientjr;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Base64;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

public final class LocalFileContentProvider extends ContentProvider {
    static final String AUTHORITY = "com.sshclientjr.localfile";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return guessMimeType(getFileForUri(uri));
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("read only");
        }
        File file = getFileForUri(uri);
        if (file == null || !file.isFile() || !isAllowedFile(file)) {
            throw new FileNotFoundException("not found");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    static Uri uriForFile(File file) {
        String encodedPath = Base64.encodeToString(
                file.getAbsolutePath().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP
        );
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(encodedPath)
                .build();
    }

    static String guessMimeType(File file) {
        if (file == null) {
            return "application/octet-stream";
        }
        String name = file.getName();
        if (name.toLowerCase(java.util.Locale.US).endsWith(".apk")) {
            return "application/vnd.android.package-archive";
        }
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex >= 0 && dotIndex < name.length() - 1) {
            String extension = name.substring(dotIndex + 1).toLowerCase(java.util.Locale.US);
            String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (!TextUtils.isEmpty(mimeType)) {
                return mimeType;
            }
        }
        return "application/octet-stream";
    }

    private File getFileForUri(Uri uri) {
        try {
            String encodedPath = uri.getLastPathSegment();
            if (TextUtils.isEmpty(encodedPath)) {
                return null;
            }
            byte[] bytes = Base64.decode(encodedPath, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            return new File(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isAllowedFile(File file) {
        return isInside(file, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
                || isInside(file, getContext().getFilesDir())
                || isInside(file, getContext().getCacheDir())
                || isInside(file, getContext().getExternalFilesDir(null));
    }

    private boolean isInside(File file, File root) {
        if (file == null || root == null) {
            return false;
        }
        try {
            String filePath = file.getCanonicalPath();
            String rootPath = root.getCanonicalPath();
            return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
        } catch (Exception e) {
            return false;
        }
    }
}
