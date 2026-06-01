package com.minimal.uvccamera;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;

public class CameraActivity extends AppCompatActivity {
    private static final String TAG = "CameraActivity";

    private SurfaceView surfaceView;
    private TextView statusText;
    private Button captureButton;
    private Button stopButton;

    private int imageWidth;
    private int imageHeight;
    private int formatIndex;
    private int frameIndex;
    private int frameInterval;
    private int maxPacketSize;
    private int streamingAltSetting;
    private int streamingInterfaceNumber;
    private String videoFormat;
    private String deviceName;
    private int cameraNumber;

    private UsbManager usbManager;
    private UsbDevice cameraDevice;
    private UVCCamera uvcCamera;
    private long mNativePtr = 0;
    private Handler mainHandler;
    private volatile boolean isStreaming = false;
    private volatile boolean capturePicture = false;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && cameraDevice != null &&
                    device.getDeviceName().equals(cameraDevice.getDeviceName())) {
                    Log.d(TAG, "Camera unplugged: " + device.getDeviceName());
                    mainHandler.post(() -> {
                        Toast.makeText(CameraActivity.this, "Camera disconnected", Toast.LENGTH_SHORT).show();
                        stopStreaming();
                        finish();
                    });
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.activity_camera);

        mainHandler = new Handler(Looper.getMainLooper());

        surfaceView = findViewById(R.id.surfaceView);
        statusText = findViewById(R.id.statusText);
        captureButton = findViewById(R.id.captureButton);
        stopButton = findViewById(R.id.stopButton);

        // Read configuration from intent
        imageWidth = getIntent().getIntExtra("imageWidth", 640);
        imageHeight = getIntent().getIntExtra("imageHeight", 480);
        formatIndex = getIntent().getIntExtra("formatIndex", 1);
        frameIndex = getIntent().getIntExtra("frameIndex", 1);
        frameInterval = getIntent().getIntExtra("frameInterval", 333333);
        maxPacketSize = getIntent().getIntExtra("maxPacketSize", 3072);
        streamingAltSetting = getIntent().getIntExtra("streamingAltSetting", 1);
        streamingInterfaceNumber = getIntent().getIntExtra("streamingInterfaceNumber", 1);
        videoFormat = getIntent().getStringExtra("videoFormat");
        deviceName = getIntent().getStringExtra("deviceName");
        mNativePtr = getIntent().getLongExtra("mNativePtr", 0);
        cameraNumber = getIntent().getIntExtra("cameraNumber", 1);

        uvcCamera = new UVCCamera();
        uvcCamera.setNativePtr(mNativePtr);

        Log.d(TAG, "Received native pointer: 0x" + Long.toHexString(mNativePtr) +
              ", cameraNumber=" + cameraNumber);

        captureButton.setOnClickListener(v -> {
            if (isStreaming) {
                capturePicture = true;
                try {
                    uvcCamera.PreviewCapturePicture(mNativePtr);
                    Toast.makeText(this, "Capturing...", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Log.e(TAG, "Capture error", e);
                }
            }
        });

        stopButton.setOnClickListener(v -> finish());

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                Log.d(TAG, "Surface created");
                adjustSurfaceViewSize();
                initCamera();
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                Log.d(TAG, "Surface changed: " + width + "x" + height);
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                Log.d(TAG, "Surface destroyed");
                stopStreaming();
            }
        });

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, filter);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopStreaming();
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering receiver", e);
        }
        // MainActivity owns the native pointer — do not free it here
    }

    private void initCamera() {
        new Thread(() -> {
            try {
                // Resolve the UsbDevice reference so the detach BroadcastReceiver can
                // compare device names. Do NOT call openDevice() — MainActivity already
                // holds the only connection we need; a second open would destabilise the device.
                for (UsbDevice device : usbManager.getDeviceList().values()) {
                    if (device.getDeviceName().equals(deviceName)) {
                        cameraDevice = device;
                        break;
                    }
                }
                Log.d(TAG, "initCamera: using existing native pointer 0x" + Long.toHexString(mNativePtr));

                Surface surface = surfaceView.getHolder().getSurface();
                if (surface == null || !surface.isValid()) {
                    Log.e(TAG, "Surface is null or invalid!");
                    mainHandler.post(() -> {
                        Toast.makeText(this, "Surface error", Toast.LENGTH_SHORT).show();
                        finish();
                    });
                    return;
                }

                UVCCamera.IFrameCallback frameCallback = frameData -> {
                    Log.d(TAG, "Frame received: " + frameData.length + " bytes");
                    if (capturePicture) {
                        capturePicture = false;
                        Log.d(TAG, "Capture triggered");
                        saveFrame(frameData);
                    }
                };

                int prepareResult = uvcCamera.PreviewPrepareStream(mNativePtr, surface, frameCallback);
                Log.d(TAG, "PreviewPrepareStream result: " + prepareResult);

                if (prepareResult == 0) {
                    int startResult = uvcCamera.PreviewStartStream(mNativePtr);
                    Log.d(TAG, "PreviewStartStream result: " + startResult);

                    if (startResult == 0) {
                        isStreaming = true;
                        mainHandler.post(() -> statusText.setText("Streaming..."));
                    } else {
                        final int errorCode = startResult;
                        mainHandler.post(() -> {
                            Toast.makeText(this, "Failed to start streaming (error: " + errorCode + ")", Toast.LENGTH_SHORT).show();
                            finish();
                        });
                    }
                } else {
                    final int errorCode = prepareResult;
                    mainHandler.post(() -> {
                        Toast.makeText(this, "Failed to prepare streaming (error: " + errorCode + ")", Toast.LENGTH_SHORT).show();
                        finish();
                    });
                }

            } catch (Exception e) {
                Log.e(TAG, "Error initializing camera", e);
                mainHandler.post(() -> {
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        }).start();
    }

    private void stopStreaming() {
        if (isStreaming && mNativePtr != 0) {
            try {
                uvcCamera.PreviewStopStream(mNativePtr);
                isStreaming = false;
            } catch (Exception e) {
                Log.e(TAG, "Error stopping stream", e);
            }
        }
        // Do NOT close the UsbDeviceConnection here — MainActivity owns it.
    }

    private void adjustSurfaceViewSize() {
        try {
            android.view.Display display = getWindowManager().getDefaultDisplay();
            android.graphics.Point displaySize = new android.graphics.Point();
            display.getSize(displaySize);

            float cameraAspect = (float) imageWidth / imageHeight;
            float displayAspect = (float) displaySize.x / displaySize.y;

            int surfaceWidth, surfaceHeight;
            if (cameraAspect > displayAspect) {
                surfaceWidth = displaySize.x;
                surfaceHeight = Math.round(displaySize.x / cameraAspect);
            } else {
                surfaceHeight = displaySize.y;
                surfaceWidth = Math.round(displaySize.y * cameraAspect);
            }

            android.view.ViewGroup.LayoutParams params = surfaceView.getLayoutParams();
            params.width = surfaceWidth;
            params.height = surfaceHeight;
            surfaceView.setLayoutParams(params);
        } catch (Exception e) {
            Log.e(TAG, "Error adjusting surface view size", e);
        }
    }

    private void saveFrame(byte[] frameData) {
        new Thread(() -> {
            try {
                Log.d(TAG, "saveFrame() called with " + frameData.length + " bytes, format=" + videoFormat);
                Bitmap bitmap = null;

                int yuy2Size = imageWidth * imageHeight * 2;
                int mjpegMinSize = 1000;

                if (frameData.length > mjpegMinSize &&
                    frameData[0] == (byte) 0xFF && frameData[1] == (byte) 0xD8) {
                    Log.d(TAG, "Decoding as JPEG");
                    bitmap = BitmapFactory.decodeByteArray(frameData, 0, frameData.length);
                }

                if (bitmap == null && ("YUY2".equals(videoFormat) || frameData.length == yuy2Size)) {
                    Log.d(TAG, "Decoding as YUY2");
                    bitmap = decodeYUY2(frameData, imageWidth, imageHeight);
                }

                if (bitmap == null) {
                    Log.e(TAG, "Failed to decode frame: format=" + videoFormat + ", size=" + frameData.length);
                    mainHandler.post(() ->
                        Toast.makeText(this, "Failed to decode frame", Toast.LENGTH_SHORT).show()
                    );
                    return;
                }

                final Bitmap captured = bitmap;
                mainHandler.post(() -> showSaveDialog(captured));

            } catch (Exception e) {
                Log.e(TAG, "Error decoding frame", e);
                mainHandler.post(() ->
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                );
            }
        }).start();
    }

    private void showSaveDialog(Bitmap bitmap) {
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.save_photo_title))
            .setMessage(getString(R.string.save_photo_msg))
            .setPositiveButton(getString(R.string.ocr_save), (dialog, which) -> performOcr(bitmap))
            .setNegativeButton(getString(R.string.save_direct), (dialog, which) -> savePdf(bitmap, ""))
            .setCancelable(false)
            .show();
    }

    @SuppressWarnings("deprecation")
    private void performOcr(Bitmap bitmap) {
        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage(getString(R.string.ocr_processing));
        progress.setCancelable(false);
        progress.show();

        Bitmap processedBitmap = preprocessForOcr(bitmap);
        InputImage image = InputImage.fromBitmap(processedBitmap, 0);
        TextRecognizer recognizer = TextRecognition.getClient(new TextRecognizerOptions.Builder().build());

        recognizer.process(image)
            .addOnSuccessListener(visionText -> {
                progress.dismiss();
                Log.d(TAG, "OCR success, chars=" + visionText.getText().length());
                savePdf(bitmap, visionText.getText());
            })
            .addOnFailureListener(e -> {
                progress.dismiss();
                Log.e(TAG, "OCR failed", e);
                Toast.makeText(this, getString(R.string.ocr_failed), Toast.LENGTH_SHORT).show();
                savePdf(bitmap, "");
            });
    }

    private Bitmap preprocessForOcr(Bitmap src) {
        Bitmap processed = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(processed);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0); // grayscale
        cm.postConcat(new ColorMatrix(new float[]{
            1.5f, 0, 0, 0, -50,
            0, 1.5f, 0, 0, -50,
            0, 0, 1.5f, 0, -50,
            0, 0, 0, 1, 0
        }));
        Paint paint = new Paint();
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        canvas.drawBitmap(src, 0, 0, paint);
        return processed;
    }

    private void savePdf(Bitmap bitmap, String ocrText) {
        new Thread(() -> {
            try {
                String savedPath = PdfHelper.createAndSavePdf(this, bitmap, ocrText, cameraNumber);
                clearAppCache();
                Log.d(TAG, "PDF saved: " + savedPath);
                final String finalPath = savedPath;
                mainHandler.post(() -> {
                    Toast.makeText(this, "Saved: " + finalPath, Toast.LENGTH_LONG).show();
                    finish();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error saving PDF", e);
                mainHandler.post(() ->
                    Toast.makeText(this, "Error saving PDF: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            }
        }).start();
    }

    private void clearAppCache() {
        try {
            deleteDirectory(getCacheDir());
            File extCache = getExternalCacheDir();
            if (extCache != null) deleteDirectory(extCache);
        } catch (Exception e) {
            Log.e(TAG, "Error clearing cache", e);
        }
    }

    private void deleteDirectory(File dir) {
        if (dir == null) return;
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File child : files) deleteDirectory(child);
            }
        }
        dir.delete();
    }

    private Bitmap decodeYUY2(byte[] frameData, int width, int height) {
        try {
            int[] pixels = new int[width * height];
            int pixelIndex = 0;

            for (int i = 0; i < frameData.length && pixelIndex < pixels.length; i += 4) {
                int y0 = frameData[i] & 0xFF;
                int u = frameData[i + 1] & 0xFF;
                int y1 = frameData[i + 2] & 0xFF;
                int v = frameData[i + 3] & 0xFF;

                pixels[pixelIndex++] = yuvToRgb(y0, u, v);
                if (pixelIndex < pixels.length) {
                    pixels[pixelIndex++] = yuvToRgb(y1, u, v);
                }
            }

            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            Log.e(TAG, "Error decoding YUY2", e);
            return null;
        }
    }

    private int yuvToRgb(int y, int u, int v) {
        u = u - 128;
        v = v - 128;

        int r = (int) (y + 1.402 * v);
        int g = (int) (y - 0.344136 * u - 0.714136 * v);
        int b = (int) (y + 1.772 * u);

        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));

        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
