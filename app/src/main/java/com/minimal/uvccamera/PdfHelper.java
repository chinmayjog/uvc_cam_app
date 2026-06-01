package com.minimal.uvccamera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class PdfHelper {

    private static final String TAG = "PdfHelper";
    private static final int PAGE_MARGIN = 20;
    private static final int TEXT_SIZE = 14;
    private static final int LINE_HEIGHT = 20;

    /**
     * Creates a PDF from the captured bitmap plus optional OCR text and saves it to
     * Documents/UVC_Docs/Camera_N/ (visible in Files app on all Android versions).
     *
     * @return the absolute path string to show in the success toast
     */
    public static String createAndSavePdf(Context ctx, Bitmap bitmap,
                                          String ocrText, int cameraNumber) throws IOException {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String filename = "Camera_" + cameraNumber + "_" + timestamp + ".pdf";

        Bitmap scaledBitmap = scaleBitmap(bitmap, 1200);
        PdfDocument document = buildDocument(scaledBitmap, ocrText);

        try {
            String savedPath;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                savedPath = saveViaMediaStore(ctx, document, filename, cameraNumber);
            } else {
                savedPath = saveViaFileApi(ctx, document, filename, cameraNumber);
            }
            Log.d(TAG, "PDF saved to: " + savedPath);
            return savedPath;
        } finally {
            document.close();
            if (scaledBitmap != bitmap) scaledBitmap.recycle();
        }
    }

    // -------------------------------------------------------------------------
    // Android 10+ path — MediaStore writes directly to shared Documents storage
    // No WRITE_EXTERNAL_STORAGE permission needed.
    // -------------------------------------------------------------------------
    private static String saveViaMediaStore(Context ctx, PdfDocument document,
                                            String filename, int cameraNumber) throws IOException {
        String relativePath = "Documents/UVC_Docs/Camera_" + cameraNumber;

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);

        ContentResolver resolver = ctx.getContentResolver();
        Uri collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri itemUri = resolver.insert(collection, values);

        if (itemUri == null) {
            throw new IOException("MediaStore.insert returned null — storage may be unavailable");
        }

        try (OutputStream out = resolver.openOutputStream(itemUri)) {
            if (out == null) throw new IOException("Cannot open OutputStream for " + itemUri);
            document.writeTo(out);
        } catch (IOException e) {
            resolver.delete(itemUri, null, null); // clean up the orphan entry
            throw e;
        }

        // Resolve the actual file path for the toast
        String[] proj = {MediaStore.MediaColumns.DATA};
        try (Cursor c = resolver.query(itemUri, proj, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String path = c.getString(0);
                if (path != null) return path;
            }
        }
        // Fallback display path if DATA column not available
        return "/storage/emulated/0/" + relativePath + "/" + filename;
    }

    // -------------------------------------------------------------------------
    // Android 9 and below — direct File API (needs WRITE_EXTERNAL_STORAGE)
    // -------------------------------------------------------------------------
    private static String saveViaFileApi(Context ctx, PdfDocument document,
                                         String filename, int cameraNumber) throws IOException {
        File folder = null;

        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            File docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            folder = new File(docsDir, "UVC_Docs/Camera_" + cameraNumber);
            if (!folder.exists() && !folder.mkdirs()) {
                Log.w(TAG, "Cannot create Documents sub-folder, falling back");
                folder = null;
            }
        }

        if (folder == null) {
            // Fallback: app-specific external storage
            folder = new File(ctx.getExternalFilesDir(null), "Camera_" + cameraNumber);
            if (!folder.exists() && !folder.mkdirs()) {
                throw new IOException("Cannot create any output folder");
            }
        }

        File outputFile = new File(folder, filename);
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            document.writeTo(fos);
        }
        return outputFile.getAbsolutePath();
    }

    // -------------------------------------------------------------------------
    // PDF builder — shared between both save paths
    // -------------------------------------------------------------------------
    private static PdfDocument buildDocument(Bitmap bitmap, String ocrText) {
        int imgW = bitmap.getWidth();
        int imgH = bitmap.getHeight();

        String[] lines = new String[0];
        int textBlockHeight = 0;
        if (ocrText != null && !ocrText.trim().isEmpty()) {
            lines = ocrText.split("\n");
            textBlockHeight = PAGE_MARGIN + lines.length * LINE_HEIGHT + PAGE_MARGIN;
        }

        int pageWidth = imgW + PAGE_MARGIN * 2;
        int pageHeight = PAGE_MARGIN + imgH + textBlockHeight + PAGE_MARGIN;

        PdfDocument document = new PdfDocument();
        PdfDocument.PageInfo pageInfo =
            new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create();
        PdfDocument.Page page = document.startPage(pageInfo);
        Canvas canvas = page.getCanvas();

        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(bitmap, PAGE_MARGIN, PAGE_MARGIN, null);

        if (lines.length > 0) {
            Paint paint = new Paint();
            paint.setTextSize(TEXT_SIZE);
            paint.setColor(Color.BLACK);
            paint.setAntiAlias(true);

            int y = PAGE_MARGIN + imgH + PAGE_MARGIN + TEXT_SIZE;
            for (String line : lines) {
                canvas.drawText(line, PAGE_MARGIN, y, paint);
                y += LINE_HEIGHT;
            }
        }

        document.finishPage(page);
        return document;
    }

    private static Bitmap scaleBitmap(Bitmap src, int maxWidth) {
        if (src.getWidth() <= maxWidth) return src;
        float ratio = (float) maxWidth / src.getWidth();
        int newHeight = Math.round(src.getHeight() * ratio);
        return Bitmap.createScaledBitmap(src, maxWidth, newHeight, true);
    }
}
