package com.ollee.pulse;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Photos for Ask AI: where the camera saves its shot, and shrinking a picture to a size
 * Gemini reads well (text stays sharp) without a big upload on mobile data.
 *
 * It is also the tiny file provider the camera app writes the shot into
 * (plain Android has no ready-made one without extra libraries).
 */
public class Photo extends ContentProvider {

    static final String AUTHORITY = "com.ollee.pulse.photo";
    private static final int MAX_SIDE = 2048;            // small print on a full page stays readable

    static File shotFile(Context c) { return new File(c.getCacheDir(), "shot.jpg"); }

    static Uri shotUri() { return Uri.parse("content://" + AUTHORITY + "/shot.jpg"); }

    /** The prepared picture handed to the Gemini app. */
    static File shareFile(Context c) { return new File(c.getCacheDir(), "share.jpg"); }

    static Uri shareUri() { return Uri.parse("content://" + AUTHORITY + "/share.jpg"); }

    private File fileFor(Uri uri) throws FileNotFoundException {
        String n = uri.getLastPathSegment();
        if ("shot.jpg".equals(n)) return shotFile(getContext());
        if ("share.jpg".equals(n)) return shareFile(getContext());
        throw new FileNotFoundException(String.valueOf(uri));
    }

    /** The picture as a JPEG at most 2048 px on its long side, turned upright. */
    static byte[] prepare(Context c, Uri uri) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new IOException("not a picture");
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap b;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            b = BitmapFactory.decodeStream(in, null, d);
        }
        if (b == null) throw new IOException("not a picture");

        int turn = 0;                                   // phones save photos sideways and note the turn
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            int or = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            turn = or == ExifInterface.ORIENTATION_ROTATE_90 ? 90
                    : or == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                    : or == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        } catch (Exception ignored) { }

        float scale = Math.min(1f, (float) MAX_SIDE / Math.max(b.getWidth(), b.getHeight()));
        if (scale < 1f || turn != 0) {
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            m.postRotate(turn);
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (r != b) b.recycle();
            b = r;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 80, out);
        b.recycle();
        return out.toByteArray();
    }

    /** A small preview for the app. */
    static Bitmap preview(byte[] jpeg) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 4;
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
    }

    // ---------- the file provider the camera writes into ----------

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) { return "image/jpeg"; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        try {
            File f = fileFor(uri);
            c.addRow(new Object[]{f.getName(), f.length()});
        } catch (FileNotFoundException ignored) { }
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues v) { return null; }

    @Override public int delete(Uri uri, String sel, String[] args) { return 0; }

    @Override public int update(Uri uri, ContentValues v, String sel, String[] args) { return 0; }
}
