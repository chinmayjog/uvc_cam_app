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
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
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
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
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

    private FrameLayout previewContainer;

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
        previewContainer = findViewById(R.id.previewContainer);
        capturedPreview = findViewById(R.id.capturedPreview);
        noteInput = findViewById(R.id.noteInput);
        captureButton = findViewById(R.id.captureButton);
        saveButton = findViewById(R.id.saveButton);
        retryButton = findViewById(R.id.retryButton);
        backButton = findViewById(R.id.backButton);

        // Camera configuration from MainActivity
        imageWidth = getIntent().getIntExtra("imageWidth", 640);
        imageHeight = getIntent().getIntExtra("imageHeight", 480);
        setupPreviewSize();
        formatIndex = getIntent().getIntExtra("formatIndex", 1);
        frameIndex = getIntent().getIntExtra("frameIndex", 1);
        frameInterval = getIntent().getIntExtra("frameInterval", 333333);
        maxPacketSize = getIntent().getIntExtra("maxPacketSize", 3072);
        streamingAltSetting = getIntent().getIntExtra("streamingAltSetting", 1);
        streamingInterfaceNumber = getIntent().getIntExtra("streamingInterfaceNumber", 1);
        videoFormat = getIntent().getStringExtra("videoFormat");
        deviceName = getIntent().getStringExtra("deviceName");
        mNativePtr = getIntent().getLongExtra("mNativePtr", 0);
        
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
//                adjustSurfaceViewSize();
//                setupPreviewSize();
                if (imagePaths[currentStep] == null && isStreaming == false) {
                    initCamera(cameraDevice);
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
            try {
                UsbDevice targetDevice = device;
                if (targetDevice == null) {
                    // Try to find it by name if device not provided
                    HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
                    for (UsbDevice d : deviceList.values()) {
                        if (d.getDeviceName().equals(deviceName)) {
                            targetDevice = d;
                            break;
                        }
                    }
                }

                if (targetDevice == null) {
                    mainHandler.post(() -> {
                        Toast.makeText(FaultFormActivity.this, "Camera not found: " + deviceName, Toast.LENGTH_SHORT).show();
                        statusText.setText("Camera error - tap Capture to retry");
                    });
                    return;
                }

                cameraDevice = targetDevice;  // Update the field
                
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
                Log.d(TAG, "Camera FD: " + fd + " for device: " + targetDevice.getDeviceName());

                // Only reset and reinitialize if switching to a DIFFERENT camera
                // First camera is already initialized by MainActivity
                boolean isSwitchingCamera = currentCameraDeviceName != null && 
                                           !currentCameraDeviceName.equals(targetDevice.getDeviceName());
                
                if (isSwitchingCamera) {
                    Log.d(TAG, "Switching from " + currentCameraDeviceName + " to " + targetDevice.getDeviceName());
                    
                    // Stream should already be stopped by loadPreview() when showing captured image
                    // or by explicit user action - don't call stopStreaming here to avoid double-stop
                    
                    // CRITICAL: Only close if we have a valid camera initialized
                    // (first camera might not be fully initialized yet)
                    if (mNativePtr != 0) {
                        Log.d(TAG, "Closing previous camera device");
                        try {
                            MainActivity.closeCameraDevice(mNativePtr);
                            // Give USB time to release interfaces before opening new camera
                            Thread.sleep(200);
                        } catch (Exception e) {
                            Log.e(TAG, "Error closing camera device: " + e.getMessage(), e);
                        }
                    }
                    
                    // Brief wait for USB cleanup
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Log.e(TAG, "Sleep interrupted", e);
                    }
                    
                    // CRITICAL: Negotiate optimal format for THIS camera before initializing stream
                    // Each camera may have different capabilities and buffer requirements
                    Log.d(TAG, "Negotiating format for new camera");
                    negotiateCameraFormat();
                    
                    // CRITICAL: Must reinitialize streaming parameters with new FD to update device handle
                    // This wraps the new FD with libusb and updates camera_deviceHandle
                    Log.d(TAG, "Calling initStreamingParms to update device handle for new camera");
                    Log.d(TAG, "Using format: " + videoFormat + " " + imageWidth + "x" + imageHeight);
                    int initResult = MainActivity.initStreamingParms(mNativePtr, fd);
                    if (initResult != 0) {
                        Log.e(TAG, "initStreamingParms failed: " + initResult);
                        mainHandler.post(() -> {
                            Toast.makeText(FaultFormActivity.this, "Failed to initialize new camera", Toast.LENGTH_SHORT).show();
                            statusText.setText("Camera error - tap Capture to retry");
                        });
                        return;
                    }
                    Log.d(TAG, "initStreamingParms succeeded");
                } else {
                    Log.d(TAG, "Using already-initialized camera: " + targetDevice.getDeviceName());
                }
                
                currentCameraDeviceName = targetDevice.getDeviceName();

                // CRITICAL: Must call listDeviceUvc to initialize native device handle for this camera
                // Each camera needs to be individually registered with the native code
                Log.d(TAG, "Calling listDeviceUvc (static) to initialize device");
                int uvcResult = MainActivity.listDeviceUvc(mNativePtr, fd);
                if (uvcResult != 0) {
                    Log.e(TAG, "Failed to initialize camera with listDeviceUvc: " + uvcResult);
                    mainHandler.post(() -> {
                        Toast.makeText(FaultFormActivity.this, "Camera initialization failed", Toast.LENGTH_SHORT).show();
                        statusText.setText("Camera error - tap Capture to retry");
                    });
                    return;
                }
                Log.d(TAG, "listDeviceUvc succeeded");

                // CRITICAL: Sync native camera state after opening new device
                // This ensures the native side has the correct format/frame indices for THIS camera
                Log.d(TAG, "Setting native values to sync with camera's actual capabilities");
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

                // Adjust preview surface size based on negotiated resolution

                // This ensures the preview displays correct aspect ratio for the current camera
                mainHandler.post(() -> {
                    Log.d(TAG, "Adjusting surface view for resolution: " + imageWidth + "x" + imageHeight);
//                    adjustSurfaceViewSize();
//                    setupPreviewSize();
                });

                Surface surface = surfaceView.getHolder().getSurface();
                if (surface == null || !surface.isValid()) {
                    mainHandler.post(() -> Toast.makeText(this, "Surface invalid", Toast.LENGTH_SHORT).show());
                    return;
                }

                UVCCamera.IFrameCallback frameCallback = frameData -> {
                    if (capturePending) {
                        capturePending = false;
                        saveFrameAsync(frameData, currentStep);
                    }
                };

                int prepareResult = uvcCamera.PreviewPrepareStream(mNativePtr, surface, frameCallback);
                if (prepareResult == 0) {
                    int startResult = uvcCamera.PreviewStartStream(mNativePtr);
                    if (startResult == 0) {
                        isStreaming = true;
                        streamStarted = true;
                        mainHandler.post(() -> {
                            statusText.setText("Streaming");
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

                        // Third fallback: no alternate setting (alt=0), minimal packet size
                        try {
                            Log.d(TAG, "Trying fallback #3: 640x480 alt=0 packet=512");
                            imageWidth = 640;
                            imageHeight = 480;
                            frameInterval = 100000;
                            maxPacketSize = 512;
                            streamingAltSetting = 0;
                            videoFormat = "MJPEG";

                            Log.d(TAG, "Calling initStreamingParms with fallback #3 (alt=0)");
                            int retryResult3 = MainActivity.initStreamingParms(mNativePtr, fd);
                            if (retryResult3 == 0) {
                                int retryPrepare3 = uvcCamera.PreviewPrepareStream(mNativePtr, surface, frameCallback);
                                if (retryPrepare3 == 0) {
                                    int retryStart3 = uvcCamera.PreviewStartStream(mNativePtr);
                                    if (retryStart3 == 0) {
                                        isStreaming = true;
                                        streamStarted = true;
                                        mainHandler.post(() -> statusText.setText("Streaming (fallback 640x480 alt=0)"));
                                        Log.d(TAG, "Fallback #3 succeeded");
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
        Log.d(TAG,"inside retry capture");
        notes[currentStep] = noteInput.getText().toString().trim();
        Log.d(TAG,"inside retry capture --- 1 "+notes.length);
        // Explicitly toggle visibility to live preview
        surfaceView.setVisibility(android.view.View.VISIBLE);
        Log.d(TAG,"inside retry capture maake surface view visible");
        capturedPreview.setVisibility(android.view.View.GONE);
        Log.d(TAG,"inside retry capture make captured view gone");
        capturedPreview.setImageDrawable(null);
        Log.d(TAG,"inside retry capture set drawable to null");

        // Ensure streaming is running for the retry
        Log.d(TAG,"inside retry capture ----- 2  "+!isStreaming +" and cameraDevice "+cameraDevice);
        if (!isStreaming && cameraDevice != null) {
            Log.d(TAG,"inside straming condition");
            initCamera(cameraDevice);
        }
        Log.d(TAG,"inside retry capture --- retrying");
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
            currentStep++;
            Log.d(TAG, "Updated currentStep to: " + currentStep);
            updateStepUi();
            Log.d(TAG, "Updated UI, now loading camera for step " + currentStep);
            loadCameraForStep();
        } else {
            Log.d(TAG, "All steps complete, exporting PDF");
            exportPdf();
        }
    }

    private void moveToPreviousStep() {
        Log.d(TAG, "All steps-- " + notes.length);
        notes[currentStep] = noteInput.getText().toString().trim();
        if (currentStep > 0) {
            currentStep--;
            updateStepUi();
            loadCameraForStep();
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
            
            // Use different camera for each step (cycle if fewer cameras than steps)
            int cameraIndex = currentStep % allCameraNames.length;
            String cameraName = allCameraNames[cameraIndex];
            Log.d(TAG, "Using camera at index " + cameraIndex + ": " + cameraName);
        
            deviceName = cameraName;
            loadCameraByName();
    }
    
    private void loadCameraByName() {
        new Thread(() -> {
            HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
            UsbDevice targetDevice = null;
            for (UsbDevice device : deviceList.values()) {
                if (device.getDeviceName().equals(deviceName)) {
                    targetDevice = device;
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
                names.add(device.getDeviceName());
            }
        }
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
     * Negotiate optimal format/resolution for the current camera
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
            // YUY2 is uncompressed, needs adaptive bandwidth
            frameInterval = 166666;  // Moderate frame rate
            // YUY2 is 2 bytes per pixel
            int bytesPerSecond = width * height * 2 * 30;  // assuming 30 fps
            // Typical USB HS isochronous max: 3 * 1024 bytes per 125us microframe
            maxPacketSize = Math.min(3072, (bytesPerSecond / 8000) + 512);
            streamingAltSetting = maxPacketSize > 2048 ? 3 : 2;
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
            // Ensure preview is running when needed
            if (!isStreaming && cameraDevice != null) {
                initCamera(cameraDevice);
            }
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

//            android.view.ViewGroup.LayoutParams params = surfaceView.getLayoutParams();
//            params.width = surfaceWidth;
//            params.height = surfaceHeight;
//            surfaceView.setLayoutParams(params);

        } catch (Exception e) {
            Log.e(TAG, "Surface sizing error", e);
        }
    }

    private void setupPreviewSize() {

        previewContainer.post(new Runnable() {
            @Override
            public void run() {

                if (imageWidth == 0 || imageHeight == 0) return;

                int containerWidth = previewContainer.getWidth();

                float cameraRatio = (float) imageWidth / imageHeight;
                int calculatedHeight = (int) (containerWidth / cameraRatio);

                FrameLayout.LayoutParams params =
                        new FrameLayout.LayoutParams(containerWidth, calculatedHeight);

                params.gravity = android.view.Gravity.CENTER;

                surfaceView.setLayoutParams(params);
                capturedPreview.setLayoutParams(params);
            }
        });
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
}
