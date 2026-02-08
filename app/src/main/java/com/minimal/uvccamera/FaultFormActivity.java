package com.minimal.uvccamera;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class FaultFormActivity extends AppCompatActivity {
    private static final String TAG = "FaultFormActivity";
    private static final int MAX_FORM_STEPS = 4;  // Maximum number of steps allowed
    private int FORM_STEPS = MAX_FORM_STEPS;  // Dynamic based on connected cameras
    
    private SurfaceView surfaceView;
    private TextView statusText;
    private TextView stepIndicator;
    private ImageView capturedPreview;
    private EditText noteInput;
    private Button captureButton;
    private Button saveButton;
    private Button retryButton;
    private Button backButton;

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
    private volatile boolean streamStarted = false; // true only after PreviewStartStream succeeds
    private volatile boolean capturePending = false;
    private volatile boolean isStopping = false;  // Prevent concurrent stop calls
    private volatile boolean isInitializing = false;  // Prevent concurrent camera initialization
    private final Object cameraLock = new Object();  // Lock for camera operations
    private volatile boolean stepTransitionInProgress = false;  // Prevent surfaceCreated from interfering during step changes

    private String[] imagePaths;  // Will be resized after camera count is known
    private String[] notes;  // Will be resized after camera count is known
    private String[] allCameraNames = new String[0];  // Cached list from MainActivity
    private int currentStep = 0;
    private volatile boolean isActivityDestroyed = false;
    private String currentCameraDeviceName = null;  // Track current camera to detect switches

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {
                    Log.d(TAG, "Camera detached: " + device.getDeviceName());
                    stopStreaming();

                    // Refresh cached list of cameras
                    String[] refreshed = scanUvcCameras();
                    allCameraNames = refreshed;

                    mainHandler.post(() -> {
                        AlertDialog.Builder builder = new AlertDialog.Builder(FaultFormActivity.this);
                        builder.setTitle("Camera disconnected");
                        builder.setMessage("A camera was disconnected. Restart the form to avoid missing photos?");
                        builder.setCancelable(false);
                        builder.setPositiveButton("Restart", (d, which) -> finish());
                        builder.setNegativeButton("Continue", (d, which) -> {
                            if (allCameraNames.length == 0) {
                                Toast.makeText(FaultFormActivity.this, "No cameras available. Closing form.", Toast.LENGTH_SHORT).show();
                                finish();
                            } else {
                                statusText.setText("Camera reconnected. Continuing...");
                                loadCameraForStep();
                            }
                        });
                        builder.show();
                    });
                }
            }
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.activity_fault_form);

        mainHandler = new Handler(Looper.getMainLooper());

        surfaceView = findViewById(R.id.surfaceView);
        statusText = findViewById(R.id.statusText);
        stepIndicator = findViewById(R.id.stepIndicator);
        capturedPreview = findViewById(R.id.capturedPreview);
        noteInput = findViewById(R.id.noteInput);
        captureButton = findViewById(R.id.captureButton);
        saveButton = findViewById(R.id.saveButton);
        retryButton = findViewById(R.id.retryButton);
        backButton = findViewById(R.id.backButton);

        // Camera configuration from MainActivity
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
        Log.d(TAG, "Received mNativePtr from MainActivity: 0x" + Long.toHexString(mNativePtr));
        
            // Get the cached list of all cameras from MainActivity
            allCameraNames = getIntent().getStringArrayExtra("allCameraNames");
            if (allCameraNames == null || allCameraNames.length == 0) {
                allCameraNames = new String[] {deviceName};  // Fallback to single camera
            }
            Log.d(TAG, "Received " + allCameraNames.length + " cameras: " + java.util.Arrays.toString(allCameraNames));
        
        // Calculate actual form steps based on number of cameras, capped at MAX_FORM_STEPS
        FORM_STEPS = Math.min(allCameraNames.length, MAX_FORM_STEPS);
        Log.d(TAG, "Setting FORM_STEPS to " + FORM_STEPS + " (cameras: " + allCameraNames.length + ")");
        
        // Initialize arrays with correct size
        imagePaths = new String[FORM_STEPS];
        notes = new String[FORM_STEPS];

        uvcCamera = new UVCCamera();
        uvcCamera.setNativePtr(mNativePtr);

        captureButton.setOnClickListener(v -> {
            triggerCapture();
        });
        saveButton.setOnClickListener(v -> saveCurrentStep());
        retryButton.setOnClickListener(v -> retryCapture());
        backButton.setOnClickListener(v -> moveToPreviousStep());


        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                adjustSurfaceViewSize();
                // Only initialize if:
                // - No preview image exists for this step
                // - Not already streaming or initializing
                // - Not in the middle of a step transition (which will handle camera loading)
                if (imagePaths[currentStep] == null && !isStreaming && !isInitializing && !stepTransitionInProgress) {
                    Log.d(TAG, "surfaceCreated: Initializing camera for step " + currentStep);
                    loadCameraForStep();
                } else {
                    Log.d(TAG, "surfaceCreated: Skipping init (imagePath=" + imagePaths[currentStep] + 
                          ", streaming=" + isStreaming + ", initializing=" + isInitializing + 
                          ", transition=" + stepTransitionInProgress + ")");
                }
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                // Surface changes are handled by the native preview
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                // Don't stop streaming here - it's called during normal view visibility changes
                // Streaming will be explicitly stopped in switchCamera() when needed
                Log.d(TAG, "Surface destroyed (not stopping stream - visibility change only)");
            }
        });

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, filter);

        updateStepUi();

        // Find and set the camera device for the first step (don't start streaming yet)
        // The surfaceCreated callback will handle actual initialization once surface is ready
        new Thread(() -> {
            String[] freshCameraList = scanUvcCameras();
            if (freshCameraList.length > 0) {
                allCameraNames = freshCameraList;
                int cameraIndex = currentStep % allCameraNames.length;
                deviceName = allCameraNames[cameraIndex];

                HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
                for (UsbDevice device : deviceList.values()) {
                    if (device.getDeviceName().equals(deviceName)) {
                        cameraDevice = device;
                        Log.d(TAG, "Camera device set for step 0: " + deviceName);
                        break;
                    }
                }
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isActivityDestroyed = true;
        stopStreaming();
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Receiver cleanup failed", e);
        }
    }

    private void initCamera() {
        initCamera(cameraDevice);  // Use the already-selected device
    }

    private void initCamera(UsbDevice device) {
        new Thread(() -> {
            Log.d(TAG, "initCamera called with device: " + (device != null ? device.getDeviceName() : "null"));
            synchronized (cameraLock) {
                // Prevent concurrent initialization
                if (isInitializing) {
                    Log.w(TAG, "Camera initialization already in progress, skipping duplicate request");
                    return;
                }
                isInitializing = true;
            }

            try {
                UsbDevice targetDevice = device;
                Log.d(TAG, "targetDevice initial: " + (targetDevice != null ? targetDevice.getDeviceName() : "null"));
                if (targetDevice == null) {
                    // Try to find it by name if device not provided
                    Log.d(TAG, "targetDevice is null, looking up by deviceName: " + deviceName);
                    HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
                    for (UsbDevice d : deviceList.values()) {
                        if (d.getDeviceName().equals(deviceName)) {
                            targetDevice = d;
                            Log.d(TAG, "Found device by name: " + targetDevice.getDeviceName());
                            break;
                        }
                    }
                }

                Log.d(TAG, "targetDevice final: " + (targetDevice != null ? targetDevice.getDeviceName() : "null"));
                if (targetDevice == null) {
                    mainHandler.post(() -> {
                        Toast.makeText(FaultFormActivity.this, "Camera not found: " + deviceName, Toast.LENGTH_SHORT).show();
                        statusText.setText("Camera error - tap Capture to retry");
                    });
                    return;
                }

                cameraDevice = targetDevice;  // Update the field
                
                // UNIVERSAL CAMERA INITIALIZATION FLOW:
                // Same steps for ALL cameras (first, switching, or re-init)
                
                // Step 1: Close previous camera BEFORE opening new one to prevent state corruption
                if (currentCameraDeviceName != null && !currentCameraDeviceName.equals(targetDevice.getDeviceName())) {
                    Log.d(TAG, "Closing previous camera: " + currentCameraDeviceName);
                    try {
                        // Stop stream if running
                        if (isStreaming) {
                            Log.d(TAG, "Stopping stream");
                            uvcCamera.PreviewStopStream(mNativePtr);
                            if (!waitForStreamStopped(mNativePtr, 2000)) {
                                Log.w(TAG, "Stream stop timed out");
                            }
                        }
                        
                        // Close device handle
                        Log.d(TAG, "Closing device handle");
                        MainActivity.closeCameraDevice(mNativePtr);
                        if (!waitForDeviceClosed(mNativePtr, 2000)) {
                            Log.w(TAG, "Device close timed out");
                        }
                        
                        // Close USB connection
                        if (deviceConnection != null) {
                            Log.d(TAG, "Closing USB connection");
                            deviceConnection.close();
                            deviceConnection = null;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error closing previous camera: " + e.getMessage(), e);
                    }
                    
                    // Step 2: Reset camera state after closing previous camera
                    Log.d(TAG, "Resetting camera state after closing previous camera");
                    MainActivity.resetCameraState();
                    
                    // Add a small delay to ensure decoder/preview state is fully cleared
                    // This is especially important when switching between cameras with same format (e.g., MJPEG→MJPEG)
                    try {
                        Thread.sleep(200);
                        Log.d(TAG, "Waited for decoder/preview cleanup");
                    } catch (InterruptedException e) {
                        Log.w(TAG, "Sleep interrupted: " + e.getMessage());
                    }
                }
                
                // Step 3: Open new camera device
                // Request USB permission if not already granted
                if (!usbManager.hasPermission(targetDevice)) {
                    Log.d(TAG, "Requesting USB permission for: " + targetDevice.getDeviceName());
                    android.app.PendingIntent permissionIntent = android.app.PendingIntent.getBroadcast(
                        FaultFormActivity.this, 0,
                        new Intent("com.minimal.uvccamera.USB_PERMISSION"),
                        android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
                    usbManager.requestPermission(targetDevice, permissionIntent);
                    
                    // Wait for permission grant (with timeout)
                    long waitStart = System.currentTimeMillis();
                    while (!usbManager.hasPermission(targetDevice) && System.currentTimeMillis() - waitStart < 5000) {
                        Thread.sleep(100);
                    }
                }
                
                deviceConnection = usbManager.openDevice(targetDevice);
                if (deviceConnection == null) {
                    mainHandler.post(() -> {
                        Toast.makeText(FaultFormActivity.this, "Cannot open camera", Toast.LENGTH_SHORT).show();
                        statusText.setText("Camera error - tap Capture to retry");
                    });
                    return;
                }

                int fd = deviceConnection.getFileDescriptor();
                Log.d(TAG, "Camera FD: " + fd + " for device: " + targetDevice.getDeviceName() + 
                      " (VID: " + String.format("0x%04x", targetDevice.getVendorId()) + 
                      ", PID: " + String.format("0x%04x", targetDevice.getProductId()) + ")");
                
                // Step 4: Initialize new camera (format enumeration, configuration, streaming)
                Log.d(TAG, "Initializing camera: " + targetDevice.getDeviceName());
                Log.d(TAG, "Using mNativePtr: 0x" + Long.toHexString(mNativePtr) + " with FD: " + fd);

                int uvcResult = MainActivity.listDeviceUvc(mNativePtr, fd);
                if (uvcResult != 0) {
                    Log.e(TAG, "Failed to create device handle with listDeviceUvc: " + uvcResult);
                    mainHandler.post(() -> {
                        Toast.makeText(FaultFormActivity.this, "Camera initialization failed", Toast.LENGTH_SHORT).show();
                        statusText.setText("Camera error - tap Capture to retry");
                    });
                    return;
                }
                Log.d(TAG, "Device handle created, ready for format enumeration");

                // NOW enumerate formats with valid device handle
                // This applies to both first camera AND camera switching
                // Never rely on default/stale values - each camera has unique capabilities
                Log.d(TAG, "Enumerating and negotiating format for camera");
                enumerateAndConfigureFormat();

                // CRITICAL: Sync native camera state BEFORE control transfer
                // This ensures initStreamingParms uses the correct format/frame indices for THIS camera
                Log.d(TAG, "Setting native values to sync with camera's enumerated capabilities");
                Log.d(TAG, "Format: " + videoFormat + " " + imageWidth + "x" + imageHeight +
                           " alt=" + streamingAltSetting + " packet=" + maxPacketSize);

                try {
                    int syncResult = MainActivity.setNativeValues(mNativePtr, fd,
                            8,                          // packetsPerRequest
                            maxPacketSize,
                            5,                          // activeUrbs
                            streamingAltSetting,
                            formatIndex,
                            frameIndex,
                            frameInterval,
                            imageWidth,
                            imageHeight,
                            streamingInterfaceNumber,   // endpoint
                            streamingInterfaceNumber,
                            videoFormat,
                            1,                          // numberOfAutoFrames
                            0x110,                      // bcdUVC (UVC 1.10)
                            1                           // lowAndroid
                    );
                    if (syncResult != 0) {
                        Log.w(TAG, "setNativeValues returned non-zero: " + syncResult +
                                   " (may indicate format mismatch, will try fallback)");
                    } else {
                        Log.d(TAG, "setNativeValues succeeded");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error in setNativeValues: " + e.getMessage(), e);
                    // Continue anyway - native code might still work with defaults
                }

                // CRITICAL: Initialize streaming parameters with correct FD and format values
                // This performs control transfer WITH UPDATED VALUES (device handle already exists from listDeviceUvc)
                Log.d(TAG, "Calling initStreamingParms to perform control transfer");
                Log.d(TAG, "Using format: " + videoFormat + " " + imageWidth + "x" + imageHeight);
                int initResult = MainActivity.initStreamingParms(mNativePtr, fd);
                if (initResult != 0) {
                    Log.e(TAG, "initStreamingParms failed: " + initResult);
                    mainHandler.post(() -> {
                        Toast.makeText(FaultFormActivity.this, "Failed to initialize camera", Toast.LENGTH_SHORT).show();
                        statusText.setText("Camera error - tap Capture to retry");
                    });
                    return;
                }
                Log.d(TAG, "initStreamingParms succeeded");

                currentCameraDeviceName = targetDevice.getDeviceName();
                Log.d(TAG, "Active camera is now: " + currentCameraDeviceName + " for step " + currentStep);

                // Native values already set above before initStreamingParms
                // No need to set them again here

                // Adjust preview surface size based on negotiated resolution

                // This ensures the preview displays correct aspect ratio for the current camera
                mainHandler.post(() -> {
                    Log.d(TAG, "Adjusting surface view for resolution: " + imageWidth + "x" + imageHeight);
                    adjustSurfaceViewSize();
                });

                // Clear surface to remove any residual frames from previous camera
                Surface surface = surfaceView.getHolder().getSurface();
                Log.d(TAG, "Surface check: surface=" + surface + ", isValid=" + (surface != null ? surface.isValid() : "N/A"));
                if (surface == null || !surface.isValid()) {
                    Log.e(TAG, "Surface is invalid! Cannot start preview.");
                    mainHandler.post(() -> {
                        Toast.makeText(this, "Surface invalid", Toast.LENGTH_SHORT).show();
                        statusText.setText("Surface error - restart app");
                    });
                    return;
                }
                
                // Clear the surface canvas to black to prevent old frames from being visible
                try {
                    Canvas canvas = surfaceView.getHolder().lockCanvas();
                    if (canvas != null) {
                        canvas.drawColor(android.graphics.Color.BLACK);
                        surfaceView.getHolder().unlockCanvasAndPost(canvas);
                        Log.d(TAG, "Surface cleared to black");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not clear surface: " + e.getMessage());
                }
                
                Log.d(TAG, "Surface is valid, proceeding with PreviewPrepareStream");

                UVCCamera.IFrameCallback frameCallback = frameData -> {
                    if (capturePending) {
                        capturePending = false;
                        saveFrameAsync(frameData, currentStep);
                    }
                };

                Log.d(TAG, "Calling PreviewPrepareStream...");
                int prepareResult = uvcCamera.PreviewPrepareStream(mNativePtr, surface, frameCallback);
                Log.d(TAG, "PreviewPrepareStream returned: " + prepareResult);
                if (prepareResult == 0) {
                    Log.d(TAG, "Calling PreviewStartStream...");

                    int startResult = uvcCamera.PreviewStartStream(mNativePtr);
                    if (startResult == 0) {
                        isStreaming = true;
                        streamStarted = true;
                        Log.d(TAG, "Stream started successfully from camera: " + currentCameraDeviceName);
                        mainHandler.post(() -> {
                            statusText.setText("Streaming from: " + currentCameraDeviceName.substring(currentCameraDeviceName.lastIndexOf("/") + 1));
                            captureButton.setEnabled(true);  // Enable capture when stream is ready
                        });
                    } else {
                        // Stream start failed - try fallbacks
                        Log.w(TAG, "PreviewStartStream failed with code " + startResult + 
                              ", trying fallback formats");
                        try {
                            uvcCamera.PreviewStopStream(mNativePtr);
                        } catch (Exception e) {
                            Log.e(TAG, "Error cleaning up failed stream", e);
                        }

                        // First fallback: 640x480 MJPEG with larger packet size, alt=2
                        if (imageWidth > 640 || imageHeight > 480) {
                            Log.d(TAG, "Trying fallback #1: 640x480 alt=2 packet=2048 instead of " + imageWidth + "x" + imageHeight);
                            imageWidth = 640;
                            imageHeight = 480;
                            frameInterval = 100000;
                            maxPacketSize = 2048;
                            streamingAltSetting = 2;
                            videoFormat = "MJPEG";
                            try {
                                Log.d(TAG, "Re-calling initStreamingParms with fallback #1 (alt=2)");
                                int retryResult = MainActivity.initStreamingParms(mNativePtr, fd);
                                if (retryResult == 0) {
                                    Log.d(TAG, "Retrying PreviewPrepareStream with fallback #1");
                                    int retryPrepare = uvcCamera.PreviewPrepareStream(mNativePtr, surface, frameCallback);
                                    if (retryPrepare == 0) {
                                        int retryStart = uvcCamera.PreviewStartStream(mNativePtr);
                                        if (retryStart == 0) {
                                            isStreaming = true;
                                            streamStarted = true;
                                            mainHandler.post(() -> statusText.setText("Streaming (fallback 640x480 alt=2)"));
                                            Log.d(TAG, "Fallback #1 succeeded");
                                            return;
                                        } else {
                                            Log.w(TAG, "Fallback #1 start failed with code " + retryStart + ", trying fallback #2");
                                            try {
                                                uvcCamera.PreviewStopStream(mNativePtr);
                                            } catch (Exception stopEx) {
                                                Log.e(TAG, "Error cleaning up after fallback #1 start failure", stopEx);
                                            }
                                        }
                                    } else {
                                        Log.w(TAG, "Fallback #1 prepare failed with code " + retryPrepare + ", trying fallback #2");
                                    }
                                } else {
                                    Log.w(TAG, "Fallback #1 initStreamingParms failed with code " + retryResult + ", trying fallback #2");
                                }
                            } catch (Exception ex) {
                                Log.e(TAG, "Error retrying with fallback #1", ex);
                            }
                        }

                        // Second fallback: lower bandwidth MJPEG 640x480 alt=1, packet=1024
                        try {
                            Log.d(TAG, "Trying fallback #2: 640x480 alt=1 packet=1024");
                            imageWidth = 640;
                            imageHeight = 480;
                            frameInterval = 100000;
                            maxPacketSize = 1024;
                            streamingAltSetting = 1;
                            videoFormat = "MJPEG";

                            Log.d(TAG, "Calling initStreamingParms with fallback #2 (alt=1)");
                            int retryResult2 = MainActivity.initStreamingParms(mNativePtr, fd);
                            if (retryResult2 == 0) {
                                int retryPrepare2 = uvcCamera.PreviewPrepareStream(mNativePtr, surface, frameCallback);
                                if (retryPrepare2 == 0) {
                                    int retryStart2 = uvcCamera.PreviewStartStream(mNativePtr);
                                    if (retryStart2 == 0) {
                                        isStreaming = true;
                                        streamStarted = true;
                                        mainHandler.post(() -> statusText.setText("Streaming (fallback 640x480 alt=1)"));
                                        Log.d(TAG, "Fallback #2 succeeded");
                                        return;
                                    } else {
                                        Log.w(TAG, "Fallback #2 start failed with code " + retryStart2 + ", trying fallback #3");
                                        try {
                                            uvcCamera.PreviewStopStream(mNativePtr);
                                        } catch (Exception stopEx) {
                                            Log.e(TAG, "Error cleaning up after fallback #2 start failure", stopEx);
                                        }
                                    }
                                } else {
                                    Log.w(TAG, "Fallback #2 prepare failed with code " + retryPrepare2 + ", trying fallback #3");
                                }
                            } else {
                                Log.w(TAG, "Fallback #2 initStreamingParms failed with code " + retryResult2 + ", trying fallback #3");
                            }
                        } catch (Exception ex2) {
                            Log.e(TAG, "Error retrying with fallback #2", ex2);
                        }

                        // Third fallback: Try YUY2 if MJPEG failed (some cameras only support uncompressed)
                        try {
                            Log.d(TAG, "Trying fallback #3: 640x480 YUY2 alt=3 packet=2048");
                            imageWidth = 640;
                            imageHeight = 480;
                            frameInterval = 333333;  // 30 fps
                            maxPacketSize = 2048;
                            streamingAltSetting = 3;
                            videoFormat = "YUY2";

                            Log.d(TAG, "Calling initStreamingParms with fallback #3 (YUY2)");
                            int retryResult3 = MainActivity.initStreamingParms(mNativePtr, fd);
                            if (retryResult3 == 0) {
                                int retryPrepare3 = uvcCamera.PreviewPrepareStream(mNativePtr, surface, frameCallback);
                                if (retryPrepare3 == 0) {
                                    int retryStart3 = uvcCamera.PreviewStartStream(mNativePtr);
                                    if (retryStart3 == 0) {
                                        isStreaming = true;
                                        streamStarted = true;
                                        mainHandler.post(() -> statusText.setText("Streaming (fallback YUY2)"));
                                        Log.d(TAG, "Fallback #3 (YUY2) succeeded");
                                        return;
                                    } else {
                                        Log.w(TAG, "Fallback #3 start failed with code " + retryStart3);
                                    }
                                } else {
                                    Log.w(TAG, "Fallback #3 prepare failed with code " + retryPrepare3);
                                }
                            } else {
                                Log.w(TAG, "Fallback #3 initStreamingParms failed with code " + retryResult3);
                            }
                        } catch (Exception ex3) {
                            Log.e(TAG, "Error retrying with fallback #3", ex3);
                        }

                        // Fourth fallback: YUY2 with minimal settings (320x240 or lower framerate)
                        try {
                            Log.d(TAG, "Trying fallback #4: 320x240 YUY2 alt=2 packet=1024");
                            imageWidth = 320;
                            imageHeight = 240;
                            frameInterval = 333333;  // 30 fps
                            maxPacketSize = 1024;
                            streamingAltSetting = 2;
                            videoFormat = "YUY2";

                            Log.d(TAG, "Calling initStreamingParms with fallback #4 (YUY2 low-res)");
                            int retryResult4 = MainActivity.initStreamingParms(mNativePtr, fd);
                            if (retryResult4 == 0) {
                                int retryPrepare4 = uvcCamera.PreviewPrepareStream(mNativePtr, surface, frameCallback);
                                if (retryPrepare4 == 0) {
                                    int retryStart4 = uvcCamera.PreviewStartStream(mNativePtr);
                                    if (retryStart4 == 0) {
                                        isStreaming = true;
                                        streamStarted = true;
                                        mainHandler.post(() -> statusText.setText("Streaming (fallback YUY2 320x240)"));
                                        Log.d(TAG, "Fallback #4 (YUY2 low-res) succeeded");
                                        return;
                                    } else {
                                        Log.w(TAG, "Fallback #4 start failed with code " + retryStart4);
                                    }
                                } else {
                                    Log.w(TAG, "Fallback #4 prepare failed with code " + retryPrepare4);
                                }
                            } else {
                                Log.w(TAG, "Fallback #4 initStreamingParms failed with code " + retryResult4);
                            }
                        } catch (Exception ex4) {
                            Log.e(TAG, "Error retrying with fallback #4", ex4);
                        }

                        // All attempts failed
                        final int errorCode = startResult;
                        streamStarted = false;
                        isStreaming = false;
                        mainHandler.post(() -> {
                            Toast.makeText(FaultFormActivity.this, "Start stream failed: " + errorCode, Toast.LENGTH_SHORT).show();
                            statusText.setText("Camera error - tap Capture to retry");
                            captureButton.setEnabled(false);  // Disable capture since stream failed
                        });
                    }
                } else {
                    final int errorCode = prepareResult;
                    streamStarted = false;
                    isStreaming = false;
                    mainHandler.post(() -> {
                        Toast.makeText(this, "Prepare stream failed: " + errorCode, Toast.LENGTH_SHORT).show();
                        statusText.setText("Camera error - tap Capture to retry");
                        captureButton.setEnabled(false);  // Disable capture since stream failed
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error initializing camera", e);
                mainHandler.post(() -> {
                    Toast.makeText(this, "Camera error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    statusText.setText("Camera error - tap Capture to retry");
                    captureButton.setEnabled(false);  // Disable capture on exception
                });
            } finally {
                synchronized (cameraLock) {
                    isInitializing = false;
                    stepTransitionInProgress = false;  // Clear transition flag after camera initialization
                    Log.d(TAG, "Camera initialization completed, lock released");
                }
            }
        }).start();
    }

    private void triggerCapture() {
        if (!isStreaming) {
            Toast.makeText(this, "Preview not ready", Toast.LENGTH_SHORT).show();
            return;
        }
        capturePending = true;
        try {
            uvcCamera.PreviewCapturePicture(mNativePtr);
            statusText.setText("Capturing frame...");
        } catch (Exception e) {
            capturePending = false;
            Log.e(TAG, "Capture failed", e);
            Toast.makeText(this, "Capture failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void retryCapture() {
        // Clear the captured image and show live preview again
        imagePaths[currentStep] = null;
        notes[currentStep] = noteInput.getText().toString().trim();
        // Explicitly toggle visibility to live preview
        surfaceView.setVisibility(android.view.View.VISIBLE);
        capturedPreview.setVisibility(android.view.View.GONE);
        capturedPreview.setImageDrawable(null);

        // Ensure streaming is running for the retry
        if (!isStreaming && cameraDevice != null) {
            initCamera(cameraDevice);
        }

        statusText.setText("Retrying...");
        Toast.makeText(this, "Ready to capture again", Toast.LENGTH_SHORT).show();
    }

    private void saveCurrentStep() {
        Log.d(TAG, "saveCurrentStep called for step " + currentStep);
        notes[currentStep] = noteInput.getText().toString().trim();
        if (imagePaths[currentStep] == null) {
            Toast.makeText(this, "Capture a photo before saving", Toast.LENGTH_SHORT).show();
            return;
        }

        if (currentStep < FORM_STEPS - 1) {
            Log.d(TAG, "Moving to next step: " + (currentStep + 1));
            stepTransitionInProgress = true;  // Prevent surfaceCreated() from interfering
            currentStep++;
            Log.d(TAG, "Updated currentStep to: " + currentStep);
            updateStepUi();
            Log.d(TAG, "Updated UI, now loading camera for step " + currentStep);
            loadCameraForStep();
            // stepTransitionInProgress will be cleared after camera initialization completes
        } else {
            Log.d(TAG, "All steps complete, exporting PDF");
            exportPdf();
        }
    }

    private void moveToPreviousStep() {
        notes[currentStep] = noteInput.getText().toString().trim();
        if (currentStep > 0) {
            stepTransitionInProgress = true;  // Prevent surfaceCreated() from interfering
            currentStep--;
            updateStepUi();
            loadCameraForStep();
            // stepTransitionInProgress will be cleared after camera initialization completes
        }
    }
    
    private void loadCameraForStep() {
            Log.d(TAG, "loadCameraForStep called for step " + currentStep);

            // Query fresh device list instead of using cache to ensure devices are current
            String[] freshCameraList = scanUvcCameras();
            if (freshCameraList.length == 0) {
                mainHandler.post(() -> {
                    Toast.makeText(FaultFormActivity.this, "No cameras available", Toast.LENGTH_SHORT).show();
                    statusText.setText("No cameras found");
                });
                return;
            }

            // Update cache with fresh list
            allCameraNames = freshCameraList;

            // Each step should use its corresponding camera directly (step 0→camera 0, step 1→camera 1, etc.)
            // No cycling - if we don't have enough cameras, show error
            if (currentStep >= allCameraNames.length) {
                mainHandler.post(() -> {
                    Toast.makeText(FaultFormActivity.this, 
                        "Not enough cameras: need camera " + (currentStep + 1) + " but only " + allCameraNames.length + " available", 
                        Toast.LENGTH_LONG).show();
                    statusText.setText("Missing camera #" + (currentStep + 1));
                });
                Log.e(TAG, "Step " + currentStep + " needs camera at index " + currentStep + 
                      " but only " + allCameraNames.length + " cameras available");
                return;
            }
            
            String cameraName = allCameraNames[currentStep];
            Log.d(TAG, "Step " + currentStep + ": Using camera at index " + currentStep + ": " + cameraName);

            deviceName = cameraName;
            Log.d(TAG, "Set deviceName to: " + deviceName + ", calling loadCameraByName()");
            loadCameraByName();
    }
    
    private void loadCameraByName() {
        Log.d(TAG, "loadCameraByName called for device: " + deviceName);
        new Thread(() -> {
            Log.d(TAG, "loadCameraByName thread started, looking for: " + deviceName);
            HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
            Log.d(TAG, "USB device list size: " + deviceList.size());
            UsbDevice targetDevice = null;
            for (UsbDevice device : deviceList.values()) {
                Log.d(TAG, "Checking device: " + device.getDeviceName());
                if (device.getDeviceName().equals(deviceName)) {
                    targetDevice = device;
                    Log.d(TAG, "Found matching device!");
                    break;
                }
            }
            if (targetDevice == null) {
                mainHandler.post(() -> {
                    Toast.makeText(FaultFormActivity.this, "Camera " + deviceName + " not found", Toast.LENGTH_SHORT).show();
                        statusText.setText("Camera not found. Ensure all cameras are connected.");
                });
                return;
            }
            switchCamera(targetDevice);
        }).start();
    }

    /**
     * Scan and return all connected UVC camera device names
     */
    private String[] scanUvcCameras() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        ArrayList<String> names = new ArrayList<>();
        for (UsbDevice device : deviceList.values()) {
            if (isUvcCamera(device)) {
                String deviceName = device.getDeviceName();
                if (names.contains(deviceName)) {
                    Log.w(TAG, "WARNING: Duplicate camera detected, skipping: " + deviceName +
                          " (VID: " + device.getVendorId() + ", PID: " + device.getProductId() + ")");
                    continue;  // Skip duplicates
                }
                names.add(deviceName);
                Log.d(TAG, "Found UVC camera: " + deviceName +
                      " (VID: " + device.getVendorId() + ", PID: " + device.getProductId() +
                      ", interfaces: " + device.getInterfaceCount() + ")");
            }
        }
        
        // CRITICAL: Sort camera names to ensure consistent ordering across scans
        // HashMap.values() returns elements in unpredictable order, causing same camera
        // to appear at different indices each time scanUvcCameras() is called
        java.util.Collections.sort(names);
        Log.d(TAG, "Total UVC cameras found: " + names.size() + ", sorted: " + names);
        
        return names.toArray(new String[0]);
    }

    private boolean isUvcCamera(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            android.hardware.usb.UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Enumerate camera's actual supported formats and select the best one
     * This replaces guessing with actual camera capabilities
     */
    private void enumerateAndConfigureFormat() {
        Log.d(TAG, "Enumerating formats for camera: " + deviceName);

        try {
            CameraFormatInfo[] formats = uvcCamera.enumerateCameraFormats(mNativePtr);

            if (formats == null || formats.length == 0) {
                Log.w(TAG, "Could not enumerate formats, falling back to negotiation");
                negotiateCameraFormat();
                return;
            }

            Log.d(TAG, "Camera supports " + formats.length + " formats");

            // Prefer MJPEG (compressed), fall back to YUY2 (uncompressed)
            CameraFormatInfo selectedFormat = null;
            for (CameraFormatInfo fmt : formats) {
                Log.d(TAG, "Available format: " + fmt.formatName + " with " + fmt.supportedFrames.length + " frames");
                if ("MJPEG".equals(fmt.formatName)) {
                    selectedFormat = fmt;
                    break;
                }
            }

            // Fall back to first format if MJPEG not available
            if (selectedFormat == null) {
                selectedFormat = formats[0];
                Log.d(TAG, "MJPEG not available, using: " + selectedFormat.formatName);
            }

            if (selectedFormat.supportedFrames.length == 0) {
                Log.w(TAG, "No frames available for format, falling back");
                negotiateCameraFormat();
                return;
            }

            // Select best frame (prefer common resolutions)
            CameraFrameInfo selectedFrame = selectedFormat.supportedFrames[0];
            for (CameraFrameInfo frame : selectedFormat.supportedFrames) {
                Log.d(TAG, "  Frame: " + frame.width + "x" + frame.height + " index=" + frame.frameIndex);
                // Prefer VGA (640x480) for compatibility
                if (frame.width == 640 && frame.height == 480) {
                    selectedFrame = frame;
                    break;
                }
            }

            // Apply discovered configuration
            formatIndex = selectedFormat.formatIndex;
            frameIndex = selectedFrame.frameIndex;
            imageWidth = selectedFrame.width;
            imageHeight = selectedFrame.height;
            frameInterval = (int) selectedFrame.dwDefaultFrameInterval;
            videoFormat = selectedFormat.formatName;

            // Calculate bandwidth requirements from camera's own specifications
            // dwMaxBitRate is in bits per second
            long requiredBandwidthBps = selectedFrame.dwMaxBitRate > 0 ?
                                        selectedFrame.dwMaxBitRate :
                                        calculateBandwidth(imageWidth, imageHeight, videoFormat, frameInterval);

            // Convert to bytes per microframe (USB HS has 8000 microframes/second)
            int requiredBytesPerMicroframe = (int) ((requiredBandwidthBps / 8) / 8000);

            // USB 2.0 HS isochronous can do up to 3x1024 bytes = 3072 bytes per microframe
            // Round up to nearest power-of-2-ish packet size: 512, 1024, 2048, 3072
            // Estimate alt setting based on typical camera patterns (but let native code verify)
            if (requiredBytesPerMicroframe <= 512) {
                maxPacketSize = 1024;  // Use 1024 for safety margin
                streamingAltSetting = 1;  // Typically alt 1-2 for low bandwidth
            } else if (requiredBytesPerMicroframe <= 1024) {
                maxPacketSize = 2048;
                streamingAltSetting = 2;  // Typically alt 2-3 for medium bandwidth
            } else if (requiredBytesPerMicroframe <= 2048) {
                maxPacketSize = 3072;
                streamingAltSetting = 3;  // Typically alt 3-4 for high bandwidth
            } else {
                maxPacketSize = 3072;  // Maximum
                streamingAltSetting = 4;  // Typically alt 4+ for maximum bandwidth
            }

            Log.d(TAG, "Bandwidth calculation: " + (requiredBandwidthBps / 1000000) + " Mbps, " +
                       requiredBytesPerMicroframe + " bytes/μframe, using maxPacket=" + maxPacketSize +
                       ", estimated alt=" + streamingAltSetting);

            Log.d(TAG, "Enumerated: " + videoFormat + " " + imageWidth + "x" + imageHeight +
                       " @" + (10000000 / frameInterval) + "fps, formatIdx=" + formatIndex +
                       ", frameIdx=" + frameIndex + ", packet=" + maxPacketSize + ", alt=" + streamingAltSetting);

        } catch (Exception e) {
            Log.e(TAG, "Error enumerating formats: " + e.getMessage(), e);
            Log.w(TAG, "Falling back to format negotiation");
            negotiateCameraFormat();
        }
    }

    /**
     * Calculate required bandwidth for a given format/resolution/framerate
     * Used as fallback when camera doesn't report dwMaxBitRate
     * @return bandwidth in bits per second
     */
    private long calculateBandwidth(int width, int height, String format, int frameIntervalIn100ns) {
        // Frame interval is in 100ns units, convert to fps
        double fps = 10000000.0 / frameIntervalIn100ns;

        long bytesPerFrame;
        if ("MJPEG".equals(format)) {
            // MJPEG is compressed - estimate ~1.5 bits per pixel (very rough estimate)
            // Actual compression varies widely, but this gives a reasonable upper bound
            bytesPerFrame = (long) (width * height * 1.5 / 8);
        } else if ("YUY2".equals(format) || "UYVY".equals(format)) {
            // Uncompressed YUV 4:2:2 format - exactly 2 bytes per pixel
            bytesPerFrame = width * height * 2;
        } else if ("NV12".equals(format)) {
            // YUV 4:2:0 format - 1.5 bytes per pixel
            bytesPerFrame = (width * height * 3) / 2;
        } else {
            // Unknown format - assume uncompressed RGB (3 bytes/pixel) for safety
            bytesPerFrame = width * height * 3;
        }

        long bitsPerSecond = (long) (bytesPerFrame * fps * 8);
        Log.d(TAG, "Calculated bandwidth for " + format + " " + width + "x" + height +
                   " @" + (int)fps + "fps: " + (bitsPerSecond / 1000000) + " Mbps");
        return bitsPerSecond;
    }

    /**
     * Negotiate optimal format/resolution for the current camera (fallback method)
     * Each camera may have different capabilities, so we need per-camera config
     */
    private void negotiateCameraFormat() {
        Log.d(TAG, "Negotiating format for camera: " + deviceName);

        // Try multiple format options with fallbacks
        // Priority: MJPEG (compressed) with common resolutions

        // Try 1: MJPEG 640x480 (VGA - most commonly supported)
        if (tryConfigureFormat("MJPEG", 640, 480, 1, 1)) {
            Log.d(TAG, "Negotiated: 640x480 MJPEG for " + deviceName);
            return;
        }

        // Try 2: MJPEG 800x600 (SVGA)
        if (tryConfigureFormat("MJPEG", 800, 600, 1, 1)) {
            Log.d(TAG, "Negotiated: 800x600 MJPEG for " + deviceName);
            return;
        }

        // Try 3: MJPEG 1280x720 (HD)
        if (tryConfigureFormat("MJPEG", 1280, 720, 1, 1)) {
            Log.d(TAG, "Negotiated: 1280x720 MJPEG for " + deviceName);
            return;
        }

        // Try 4: YUY2 640x480 (uncompressed fallback)
        if (tryConfigureFormat("YUY2", 640, 480, 1, 1)) {
            Log.d(TAG, "Negotiated: 640x480 YUY2 for " + deviceName);
            return;
        }

        // Default: Use existing config and hope for the best
        Log.w(TAG, "Could not negotiate format for " + deviceName + ", using defaults");
        // Keep current values
    }
    
    /**
     * Try to configure format - mirrors logic from MainActivity but adapted for FaultFormActivity
     * This is a placeholder that assumes the format will work; real validation happens in native code
     */
    private boolean tryConfigureFormat(String format, int width, int height, int fmtIdx, int frmIdx) {
        imageWidth = width;
        imageHeight = height;
        formatIndex = fmtIdx;
        frameIndex = frmIdx;
        videoFormat = format;
        
        // Calculate optimal frame interval and packet size based on format and resolution
        if ("MJPEG".equals(format)) {
            // MJPEG quality optimization
            if (width <= 320) {
                frameInterval = 66666;   // 150 fps - smooth for low res
                maxPacketSize = 1024;
                streamingAltSetting = 1;
            } else if (width <= 640) {
                frameInterval = 100000;  // 100 fps - high quality
                maxPacketSize = 2048;
                streamingAltSetting = 2;
            } else if (width <= 800) {
                frameInterval = 111111;  // ~90 fps
                maxPacketSize = 2560;
                streamingAltSetting = 2;
            } else if (width <= 1280) {
                frameInterval = 166666;  // ~60 fps for HD
                maxPacketSize = 3072;
                streamingAltSetting = 3;
            } else {
                frameInterval = 200000;  // 50 fps for Full HD
                maxPacketSize = 3072;
                streamingAltSetting = 4;
            }
        } else if ("YUY2".equals(format)) {
            // YUY2 is uncompressed, needs high bandwidth
            frameInterval = 333333;  // 30 fps
            // YUY2 is 2 bytes per pixel, requires significant bandwidth
            // Use high packet size and alt setting for uncompressed formats
            if (width <= 640) {
                maxPacketSize = 3072;  // Maximum isochronous packet size
                streamingAltSetting = 4;  // Higher alt setting for uncompressed
            } else {
                maxPacketSize = 3072;
                streamingAltSetting = 5;
            }
        } else {
            // Other formats - use quality-focused settings
            frameInterval = 166666; // ~60 fps
            maxPacketSize = 3072;
            streamingAltSetting = 3;
        }
        
        Log.d(TAG, "Config attempt: " + format + " " + width + "x" + height + 
              " @" + (10000000 / frameInterval) + "fps, packet=" + maxPacketSize + 
              ", alt=" + streamingAltSetting);
        return true; // Optimistically assume it's supported - native code will validate
    }
    
    private void switchCamera(UsbDevice newDevice) {
        new Thread(() -> {
            try {
                if (isActivityDestroyed) {
                    Log.d(TAG, "Activity destroyed, skipping camera switch");
                    return;
                }

                // Check if camera is already being initialized
                synchronized (cameraLock) {
                    if (isInitializing) {
                        Log.w(TAG, "Camera initialization in progress, waiting before switch");
                        // Wait for current initialization to complete
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            Log.e(TAG, "Wait interrupted", e);
                        }
                        // Check again
                        if (isInitializing) {
                            Log.w(TAG, "Camera still initializing, skipping duplicate switch request");
                            return;
                        }
                    }
                }

                // CRITICAL: Stop streaming from previous camera before switching
                // This ensures the stream buffer from the old camera is destroyed
                // and doesn't cause buffer overflow when new camera starts
                Log.d(TAG, "Stopping streaming before camera switch");
                stopStreaming();
                
                // Wait for stream to fully stop
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Log.e(TAG, "Sleep interrupted", e);
                }
                
                // Close existing connection
                if (deviceConnection != null) {
                    try {
                        deviceConnection.close();
                    } catch (Exception e) {
                        Log.e(TAG, "Error closing device connection", e);
                    }
                    deviceConnection = null;
                }
                
                // IMPORTANT: Don't call closeCameraDevice here, it's called in initCamera
                // when switching to a different camera
                
                try {
                    Thread.sleep(300);  // Brief delay for cleanup
                } catch (InterruptedException e) {
                    Log.e(TAG, "Sleep interrupted", e);
                }
                
                if (isActivityDestroyed) {
                    Log.d(TAG, "Activity destroyed before camera init, skipping");
                    return;
                }
                
                initCamera(newDevice);
            } catch (Exception e) {
                Log.e(TAG, "Error in switchCamera", e);
            }
        }).start();
    }

    private void updateStepUi() {
        String label = "Step " + (currentStep + 1) + " of " + FORM_STEPS;
        stepIndicator.setText(label);
        noteInput.setText(notes[currentStep] != null ? notes[currentStep] : "");
        loadPreview(imagePaths[currentStep]);
        String overlayText = "Camera preview (step " + (currentStep + 1) + ")";
        TextView overlay = findViewById(R.id.overlayHint);
        overlay.setText(overlayText);
    }

    private void loadPreview(String path) {
        if (path == null) {
            // No captured image - show live preview
            surfaceView.setVisibility(android.view.View.VISIBLE);
            capturedPreview.setVisibility(android.view.View.GONE);
            capturedPreview.setImageDrawable(null);
            // Show overlay text when showing camera preview
            TextView overlay = findViewById(R.id.overlayHint);
            overlay.setVisibility(android.view.View.VISIBLE);
            // NOTE: Don't call initCamera() here! 
            // Camera initialization is handled by loadCameraForStep() when changing steps
            // or by surfaceCreated callback on initial load
            // Calling initCamera(cameraDevice) here would restart the WRONG camera
            // since cameraDevice field is not updated when switching cameras
            return;
        }
        
        // Show captured image, hide live preview
        surfaceView.setVisibility(android.view.View.GONE);
        capturedPreview.setVisibility(android.view.View.VISIBLE);
        // Hide overlay text when showing captured image
        TextView overlay = findViewById(R.id.overlayHint);
        overlay.setVisibility(android.view.View.GONE);
        // Stop streaming to fully hide camera feed
        stopStreaming();
        
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        int targetWidth = capturedPreview.getWidth() == 0 ? 800 : capturedPreview.getWidth();
        int targetHeight = capturedPreview.getHeight() == 0 ? 800 : capturedPreview.getHeight();
        options.inSampleSize = Math.max(1, calculateSampleSize(options.outWidth, options.outHeight, targetWidth, targetHeight));
        options.inJustDecodeBounds = false;
        Bitmap bitmap = BitmapFactory.decodeFile(path, options);
        capturedPreview.setImageBitmap(bitmap);
    }

    private int calculateSampleSize(int width, int height, int reqWidth, int reqHeight) {
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return Math.max(inSampleSize, 1);
    }

    private void saveFrameAsync(byte[] frameData, int stepIndex) {
        new Thread(() -> {
            String path = saveFrameToFile(frameData, stepIndex);
            if (path != null) {
                imagePaths[stepIndex] = path;
                mainHandler.post(() -> {
                    statusText.setText("Captured step " + (stepIndex + 1));
                    loadPreview(path);
                });
            } else {
                mainHandler.post(() -> Toast.makeText(this, "Failed to save frame", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private String saveFrameToFile(byte[] frameData, int stepIndex) {
        try {
            Bitmap bitmap = null;
            int yuy2Size = imageWidth * imageHeight * 2;
            boolean likelyJpeg = frameData.length > 1000 && frameData[0] == (byte) 0xFF && frameData[1] == (byte) 0xD8;

            if (likelyJpeg) {
                bitmap = BitmapFactory.decodeByteArray(frameData, 0, frameData.length);
            }
            if (bitmap == null && ("YUY2".equals(videoFormat) || frameData.length == yuy2Size)) {
                bitmap = decodeYUY2(frameData, imageWidth, imageHeight);
            }
            if (bitmap == null) {
                Log.e(TAG, "Unable to decode frame for step " + stepIndex);
                return null;
            }

            File picturesDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "FaultDocs");
            if (!picturesDir.exists()) {
                picturesDir.mkdirs();
            }
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String fileName = "fault_step_" + (stepIndex + 1) + "_" + timeStamp + ".jpg";
            File imageFile = new File(picturesDir, fileName);
            try (FileOutputStream fos = new FileOutputStream(imageFile)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos);
            }
            String path = imageFile.getAbsolutePath();
            Log.d(TAG, "Saved frame to " + path);
            bitmap.recycle();
            return path;
        } catch (Exception e) {
            Log.e(TAG, "Error saving frame", e);
            return null;
        }
    }

    private void exportPdf() {
        // CRITICAL: Clean up ONLY if there's an active stream
        // Don't cleanup non-existent streams - it crashes
        if (streamStarted || isStreaming) {
            Log.d(TAG, "exportPdf: cleaning up active stream");
            
            try {
                // Stop any active streaming
                stopStreaming();
                Thread.sleep(500);  // Wait for stream to fully stop
                
                // Close USB device connection
                if (deviceConnection != null) {
                    try {
                        deviceConnection.close();
                    } catch (Exception e) {
                        Log.e(TAG, "Error closing device connection during PDF export", e);
                    }
                    deviceConnection = null;
                }
                
                // Clean up native camera state
                if (mNativePtr != 0) {
                    try {
                        MainActivity.closeCameraDevice(mNativePtr);
                    } catch (Exception e) {
                        Log.e(TAG, "Error closing camera device during PDF export", e);
                    }
                }
                
                Thread.sleep(300);  // Wait for USB cleanup
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted during cleanup before PDF export", e);
            }
            
            // Explicitly reset flags after cleanup (stopStreaming should do this, but be explicit)
            streamStarted = false;
            isStreaming = false;
            Log.d(TAG, "Cleanup complete, flags reset, starting PDF export");
        } else {
            Log.d(TAG, "No active stream to cleanup, proceeding directly to PDF export");
        }
        
        for (int i = 0; i < FORM_STEPS; i++) {
            if (imagePaths[i] == null) {
                Toast.makeText(this, "Missing photo for step " + (i + 1), Toast.LENGTH_SHORT).show();
                currentStep = i;
                updateStepUi();
                return;
            }
        }

        PdfDocument document = new PdfDocument();
        Paint textPaint = new Paint();
        textPaint.setColor(android.graphics.Color.BLACK);
        textPaint.setTextSize(14f);

        int pageWidth = 595; // A4 in points (72 dpi)
        int pageHeight = 842;

        for (int i = 0; i < FORM_STEPS; i++) {
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, i + 1).create();
            PdfDocument.Page page = document.startPage(pageInfo);
            Canvas canvas = page.getCanvas();

            String header = "Step " + (i + 1);
            canvas.drawText(header, 40, 40, textPaint);

            Bitmap bitmap = BitmapFactory.decodeFile(imagePaths[i]);
            if (bitmap != null) {
                RectF dest = new RectF(40, 60, pageWidth - 40, pageHeight / 2f);
                Rect src = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
                canvas.drawBitmap(bitmap, src, dest, null);
                bitmap.recycle();
            }

            float textStartY = (pageHeight / 2f) + 40;
            canvas.drawText("Notes:", 40, textStartY, textPaint);
            drawMultilineText(canvas, notes[i] == null ? "" : notes[i], 40, textStartY + 20, pageWidth - 80, textPaint);

            document.finishPage(page);
        }

        File reportsDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "FaultReports");
        if (!reportsDir.exists()) {
            reportsDir.mkdirs();
        }
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File pdfFile = new File(reportsDir, "fault_report_" + timeStamp + ".pdf");
        try (FileOutputStream fos = new FileOutputStream(pdfFile)) {
            document.writeTo(fos);
            String savedPath = pdfFile.getAbsolutePath();
            Toast.makeText(this, "Report saved: " + savedPath, Toast.LENGTH_LONG).show();
            Log.d(TAG, "PDF generated at " + savedPath);

            // Open the generated PDF for the user
            try {
                Uri pdfUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pdfFile);
                Intent viewIntent = new Intent(Intent.ACTION_VIEW);
                viewIntent.setDataAndType(pdfUri, "application/pdf");
                viewIntent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NO_HISTORY);

                if (viewIntent.resolveActivity(getPackageManager()) != null) {
                    startActivity(viewIntent);
                } else {
                    Log.w(TAG, "No PDF viewer available to open report");
                }
            } catch (Exception e) {
                Log.e(TAG, "Unable to open generated PDF", e);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to write PDF", e);
            Toast.makeText(this, "PDF error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
            document.close();
        }
        finish();
    }

    private void drawMultilineText(Canvas canvas, String text, float x, float y, float maxWidth, Paint paint) {
        if (text == null) {
            return;
        }
        String[] lines = text.split("\\n");
        float lineHeight = Math.abs(paint.ascent()) + paint.descent();
        for (String line : lines) {
            int start = 0;
            while (start < line.length()) {
                int end = paint.breakText(line, start, line.length(), true, maxWidth, null);
                canvas.drawText(line, start, start + end, x, y, paint);
                y += lineHeight + 4;
                start += end;
            }
        }
    }

    private void stopStreaming() {
        // Guard against double-stopping (can be called from multiple sources: SurfaceDestroyed, switchCamera, onDestroy)
        if (isStopping) {
            Log.d(TAG, "stopStreaming already in progress, skipping");
            return;
        }
        // If nothing is streaming or native pointer is invalid, skip
        if ((!isStreaming && deviceConnection == null) || mNativePtr == 0) {
            Log.d(TAG, "stopStreaming called but already stopped or native ptr invalid, skipping");
            return;
        }

        isStopping = true;
        
        // Stop the preview FIRST (and wait for threads to join), THEN close the device
        // Closing device first would invalidate native structures that stopPreview needs
        if (streamStarted && isStreaming && mNativePtr != 0) {
            try {
                Log.d(TAG, "Stopping preview stream");

                Runnable stopTask = () -> {
                    try {
                        if (streamStarted && isStreaming) {
                            Log.d(TAG, "Calling native PreviewStopStream");
                            try {
                                uvcCamera.PreviewStopStream(mNativePtr);
                            } catch (Throwable t) {
                                Log.e(TAG, "Native PreviewStopStream crashed", t);
                            }
                            Log.d(TAG, "Native PreviewStopStream completed");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error stopping stream", e);
                    }
                    isStreaming = false;
                    streamStarted = false;
                    captureButton.setEnabled(false);  // Disable capture when stream stops
                };

                if (Looper.myLooper() == Looper.getMainLooper()) {
                    stopTask.run();
                } else {
                    CountDownLatch latch = new CountDownLatch(1);
                    mainHandler.post(() -> {
                        stopTask.run();
                        latch.countDown();
                    });
                    // Wait for stop to complete to avoid closing FD while buffers are active
                    latch.await(1500, TimeUnit.MILLISECONDS);
                }

                Log.d(TAG, "Preview stream stopped, isStreaming=" + isStreaming);
            } catch (Exception e) {
                Log.e(TAG, "Error posting stop stream to main thread", e);
                isStreaming = false;
                streamStarted = false;
            } finally {
                isStopping = false;
            }
        }
        
        // NOW close the device connection after preview has cleanly stopped
        if (deviceConnection != null) {
            try {
                Log.d(TAG, "Closing device connection");
                deviceConnection.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing device connection", e);
            }
            deviceConnection = null;
        }
        // Reset stop guard so future stop attempts can proceed
        isStopping = false;
        // Ensure flags are reset even if we skipped PreviewStopStream
        streamStarted = false;
        isStreaming = false;
    }

    private void adjustSurfaceViewSize() {
        try {
            android.view.Display display = getWindowManager().getDefaultDisplay();
            android.graphics.Point displaySize = new android.graphics.Point();
            display.getSize(displaySize);

            int displayWidth = displaySize.x;
            int displayHeight = displaySize.y;

            float cameraAspect = (float) imageWidth / imageHeight;
            float displayAspect = (float) displayWidth / displayHeight;

            int surfaceWidth;
            int surfaceHeight;

            if (cameraAspect > displayAspect) {
                surfaceWidth = displayWidth;
                surfaceHeight = Math.round(displayWidth / cameraAspect);
            } else {
                surfaceHeight = displayHeight;
                surfaceWidth = Math.round(displayHeight * cameraAspect);
            }

            android.view.ViewGroup.LayoutParams params = surfaceView.getLayoutParams();
            params.width = surfaceWidth;
            params.height = surfaceHeight;
            surfaceView.setLayoutParams(params);
        } catch (Exception e) {
            Log.e(TAG, "Surface sizing error", e);
        }
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
            Log.e(TAG, "YUY2 decode error", e);
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

    /**
     * Poll until stream is stopped or timeout occurs
     * @param cameraPtr Native camera pointer
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return true if stream stopped, false if timeout
     */
    private boolean waitForStreamStopped(long cameraPtr, long timeoutMs) {
        long startTime = System.currentTimeMillis();
        int pollCount = 0;
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (MainActivity.isStreamStopped(cameraPtr)) {
                Log.d(TAG, "Stream stopped after " + pollCount + " polls (" + 
                      (System.currentTimeMillis() - startTime) + "ms)");
                return true;
            }
            
            pollCount++;
            try {
                Thread.sleep(10); // Poll every 10ms
            } catch (InterruptedException e) {
                Log.e(TAG, "Poll interrupted", e);
                return false;
            }
        }
        
        Log.w(TAG, "Stream stop timeout after " + pollCount + " polls");
        return false;
    }

    /**
     * Poll until camera device is closed or timeout occurs
     * @param cameraPtr Native camera pointer
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return true if device closed, false if timeout
     */
    private boolean waitForDeviceClosed(long cameraPtr, long timeoutMs) {
        long startTime = System.currentTimeMillis();
        int pollCount = 0;
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (MainActivity.isCameraDeviceClosed(cameraPtr)) {
                Log.d(TAG, "Device closed after " + pollCount + " polls (" + 
                      (System.currentTimeMillis() - startTime) + "ms)");
                return true;
            }
            
            pollCount++;
            try {
                Thread.sleep(10); // Poll every 10ms
            } catch (InterruptedException e) {
                Log.e(TAG, "Poll interrupted", e);
                return false;
            }
        }
        
        Log.w(TAG, "Device close timeout after " + pollCount + " polls");
        return false;
    }
}
