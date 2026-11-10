package com.recordroom.vinyl;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/** Read-only grants for this app's own generated cards, never arbitrary files. */
public final class ShareImageProvider extends ContentProvider {
    public static final String AUTHORITY = "com.recordroom.vinyl.shares";
    private static final String FILE_PATTERN = "share-[a-f0-9]{32}\\.png";

    @Override public boolean onCreate() { return true; }

    public static Uri uriForFile(Context context, File file) throws IOException {
        File directory = exportDirectory(context).getCanonicalFile();
        File canonical = file.getCanonicalFile();
        if (!canonical.getName().matches(FILE_PATTERN)
                || !directory.equals(canonical.getParentFile()) || !canonical.isFile()) {
            throw new IOException("Not an exported share image");
        }
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath(canonical.getName()).build();
    }

    static File exportDirectory(Context context) throws IOException {
        File directory = new File(context.getCacheDir(), "shares");
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Cannot create share directory");
        if (!directory.isDirectory()) throw new IOException("Share directory is unavailable");
        File cache = context.getCacheDir().getCanonicalFile();
        if (!cache.equals(directory.getCanonicalFile().getParentFile())) throw new IOException("Invalid share directory");
        return directory;
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        if (uri == null || !"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority())
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new FileNotFoundException("Unknown share image");
        }
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 1 || !segments.get(0).matches(FILE_PATTERN)) {
            throw new FileNotFoundException("Unknown share image");
        }
        try {
            File directory = exportDirectory(getContext()).getCanonicalFile();
            File image = new File(directory, segments.get(0)).getCanonicalFile();
            if (!directory.equals(image.getParentFile()) || !image.isFile()) {
                throw new FileNotFoundException("Share image is unavailable");
            }
            return image;
        } catch (IOException exception) {
            throw new FileNotFoundException("Share image is unavailable");
        }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Share images are read-only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        try { resolve(uri); return "image/png"; }
        catch (FileNotFoundException exception) { throw new IllegalArgumentException("Unknown share image"); }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        final File file;
        try { file = resolve(uri); }
        catch (FileNotFoundException exception) { throw new IllegalArgumentException("Unknown share image"); }
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = "唱片分享卡.png";
            else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
        }
        cursor.addRow(values); return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only provider"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("Read-only provider"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read-only provider"); }
}
