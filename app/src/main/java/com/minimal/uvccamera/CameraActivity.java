package com.minimal.uvccamera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Environment;
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

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

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
    
    private UsbManager usbManager;
    private UsbDevice cameraDevice;
    private UsbDeviceConnection deviceConnection;
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
        // Force portrait orientation
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.activity_camera);
        
        mainHandler = new Handler(Looper.getMainLooper());
        
        surfaceView = findViewById(R.id.surfaceView);
        statusText = findViewById(R.id.statusText);
        captureButton = findViewById(R.id.captureButton);
        stopButton = findViewById(R.id.stopButton);
        
        // Get configuration from intent
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
        
        // Initialize UVC Camera with native pointer from MainActivity
        uvcCamera = new UVCCamera();
        uvcCamera.setNativePtr(mNativePtr);
        
        Log.d(TAG, "Received native pointer: 0x" + Long.toHexString(mNativePtr));
        
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
        
        stopButton.setOnClickListener(v -> {
            finish();
        });
        
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                Log.d(TAG, "Surface created");
                // Adjust surface view size to match camera resolution
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
        
        // Register USB detach receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, filter);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopStreaming();
        
        // Unregister USB receiver
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering receiver", e);
        }
        
        // Don't free - MainActivity owns the native pointer
    }
    
    private void initCamera() {
        new Thread(() -> {
            try {
                // Find camera device
                HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
                for (UsbDevice device : deviceList.values()) {
                    if (device.getDeviceName().equals(deviceName)) {
                        cameraDevice = device;
                        break;
                    }
                }
                
                if (cameraDevice == null) {
                    mainHandler.post(() -> {
                        Toast.makeText(this, "Camera not found", Toast.LENGTH_SHORT).show();
                        finish();
                    });
                    return;
                }
                
                deviceConnection = usbManager.openDevice(cameraDevice);
                if (deviceConnection == null) {
                    mainHandler.post(() -> {
                        Toast.makeText(this, "Cannot open camera", Toast.LENGTH_SHORT).show();
                        finish();
                    });
                    return;
                }
                
                int fd = deviceConnection.getFileDescriptor();
                Log.d(TAG, "Camera FD: " + fd);
                
                // Native pointer already allocated in MainActivity and passed here
                Log.d(TAG, "Using native pointer: 0x" + Long.toHexString(mNativePtr));
                
                // Start preview using JNI
                Surface surface = surfaceView.getHolder().getSurface();
                if (surface == null || !surface.isValid()) {
                    Log.e(TAG, "Surface is null or invalid!");
                    mainHandler.post(() -> {
                        Toast.makeText(this, "Surface error", Toast.LENGTH_SHORT).show();
                        finish();
                    });
                    return;
                }
                Log.d(TAG, "Surface valid: " + surface.isValid());
                
                // Create frame callback
                UVCCamera.IFrameCallback frameCallback = frameData -> {
                    Log.d(TAG, "Frame received: " + frameData.length + " bytes, expected: " + (imageWidth * imageHeight * 2) + " for YUY2");
                    if (capturePicture) {
                        capturePicture = false;
                        Log.d(TAG, "Capture triggered, saving frame");
                        saveFrame(frameData);
                    }
                };
                
                Log.d(TAG, "Calling PreviewPrepareStream with resolution: " + imageWidth + "x" + imageHeight + 
                      ", format: " + videoFormat + ", surface valid: " + surface.isValid());
                
                int prepareResult = uvcCamera.PreviewPrepareStream(mNativePtr, surface, frameCallback);
                Log.d(TAG, "PreviewPrepareStream result: " + prepareResult);
                
                if (prepareResult == 0) {
                    Log.d(TAG, "Calling PreviewStartStream");
                    int startResult = uvcCamera.PreviewStartStream(mNativePtr);
                    Log.d(TAG, "PreviewStartStream result: " + startResult);
                    
                    if (startResult == 0) {
                        isStreaming = true;
                        mainHandler.post(() -> {
                            statusText.setText("Streaming...");
                            Log.d(TAG, "UI updated to streaming");
                        });
                        Log.d(TAG, "Streaming started successfully");
                    } else {
                        final int errorCode = startResult;
                        Log.e(TAG, "PreviewStartStream failed with result: " + errorCode);
                        mainHandler.post(() -> {
                            Toast.makeText(this, "Failed to start streaming (error: " + errorCode + ")", Toast.LENGTH_SHORT).show();
                            finish();
                        });
                    }
                } else {
                    final int errorCode = prepareResult;
                    Log.e(TAG, "PreviewPrepareStream failed with result: " + errorCode);
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
        
        if (deviceConnection != null) {
            deviceConnection.close();
            deviceConnection = null;
        }
    }
    
    /**
     * Adjust the SurfaceView size to match camera resolution aspect ratio
     * while fitting within the display bounds
     */
    private void adjustSurfaceViewSize() {
        try {
            // Get display metrics
            android.view.Display display = getWindowManager().getDefaultDisplay();
            android.graphics.Point displaySize = new android.graphics.Point();
            display.getSize(displaySize);
            
            int displayWidth = displaySize.x;
            int displayHeight = displaySize.y;
            
            Log.d(TAG, "Display size: " + displayWidth + "x" + displayHeight);
            Log.d(TAG, "Camera resolution: " + imageWidth + "x" + imageHeight);
            
            // Calculate aspect ratio
            float cameraAspect = (float) imageWidth / imageHeight;
            float displayAspect = (float) displayWidth / displayHeight;
            
            int surfaceWidth, surfaceHeight;
            
            if (cameraAspect > displayAspect) {
                // Camera is wider - fit to display width
                surfaceWidth = displayWidth;
                surfaceHeight = Math.round(displayWidth / cameraAspect);
            } else {
                // Camera is taller - fit to display height
                surfaceHeight = displayHeight;
                surfaceWidth = Math.round(displayHeight * cameraAspect);
            }
            
            Log.d(TAG, "Adjusting surface view to: " + surfaceWidth + "x" + surfaceHeight);
            
            // Update SurfaceView layout parameters
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
                
                // Check expected frame sizes
                int yuy2Size = imageWidth * imageHeight * 2;
                int mjpegMinSize = 1000; // JPEG files are typically at least 1KB
                
                Log.d(TAG, "Frame analysis: size=" + frameData.length + ", YUY2 expected=" + yuy2Size + ", is JPEG likely=" + (frameData.length > mjpegMinSize && frameData[0] == (byte)0xFF && frameData[1] == (byte)0xD8));
                
                // Try to decode as JPEG first (if frame looks like JPEG)
                if (frameData.length > mjpegMinSize && frameData[0] == (byte)0xFF && frameData[1] == (byte)0xD8) {
                    Log.d(TAG, "Attempting JPEG decode");
                    bitmap = BitmapFactory.decodeByteArray(frameData, 0, frameData.length);
                }
                
                // If JPEG decoding fails or frame is YUY2, try YUY2 conversion
                if (bitmap == null && (videoFormat.equals("YUY2") || frameData.length == yuy2Size)) {
                    Log.d(TAG, "Decoding as YUY2 format, frame size: " + frameData.length);
                    bitmap = decodeYUY2(frameData, imageWidth, imageHeight);
                    if (bitmap != null) {
                        Log.d(TAG, "YUY2 decode successful: " + bitmap.getWidth() + "x" + bitmap.getHeight());
                    } else {
                        Log.e(TAG, "YUY2 decode failed");
                    }
                }
                
                if (bitmap != null) {
                    // Save bitmap
                    File picturesDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "UVCCamera");
                    if (!picturesDir.exists()) {
                        picturesDir.mkdirs();
                    }
                    
                    String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                    String fileName = "IMG_" + timeStamp + ".jpg";
                    File imageFile = new File(picturesDir, fileName);
                    
                    try (FileOutputStream fos = new FileOutputStream(imageFile)) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                    }
                    
                    String path = imageFile.getAbsolutePath();
                    mainHandler.post(() ->
                        Toast.makeText(this, getString(R.string.picture_saved, path), Toast.LENGTH_LONG).show()
                    );
                    
                    Log.d(TAG, "Picture saved: " + path);
                    bitmap.recycle();
                } else {
                    Log.e(TAG, "Failed to decode frame: format=" + videoFormat + ", size=" + frameData.length);
                    mainHandler.post(() ->
                        Toast.makeText(this, "Failed to decode frame (format: " + videoFormat + ", size: " + frameData.length + ")", Toast.LENGTH_SHORT).show()
                    );
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error saving frame", e);
                mainHandler.post(() ->
                    Toast.makeText(this, "Error saving: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                );
            }
        }).start();
    }
    
    /**
     * Convert YUY2 frame to RGB bitmap
     * YUY2 format: Y0 U Y1 V Y2 U Y3 V ... (2 bytes per pixel on average)
     */
    private Bitmap decodeYUY2(byte[] frameData, int width, int height) {
        try {
            int[] pixels = new int[width * height];
            int pixelIndex = 0;
            
            for (int i = 0; i < frameData.length && pixelIndex < pixels.length; i += 4) {
                int y0 = frameData[i] & 0xFF;
                int u = frameData[i + 1] & 0xFF;
                int y1 = frameData[i + 2] & 0xFF;
                int v = frameData[i + 3] & 0xFF;
                
                // Convert YUV to RGB
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
    
    /**
     * Convert YUV to RGB color
     */
    private int yuvToRgb(int y, int u, int v) {
        // Adjust U and V values
        u = u - 128;
        v = v - 128;
        
        // YUV to RGB conversion
        int r = (int) (y + 1.402 * v);
        int g = (int) (y - 0.344136 * u - 0.714136 * v);
        int b = (int) (y + 1.772 * u);
        
        // Clamp values to 0-255
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));
        
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
