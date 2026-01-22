package com.minimal.uvccamera;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class MultiCameraFormActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private static final String TAG = "MultiCameraForm";
    private static final String ACTION_USB_PERMISSION = "com.minimal.uvccamera.USB_PERMISSION";
    private static final int NUM_CAMERAS = 4;
    
    // UI Elements
    private SurfaceView cameraPreview;
    private ImageView capturedPhotoView;
    private TextView progressText;
    private ProgressBar progressBar;
    private Button captureButton;
    private Button showPreviewButton;
    private Button previousButton;
    private Button nextButton;
    private TextView statusText;
    private TextView cameraInfoText;
    private EditText notesEditText;
    
    // Camera Management
    private UsbManager usbManager;
    private Handler mainHandler;
    private SurfaceHolder surfaceHolder;
    private volatile boolean isStreaming = false;
    private volatile boolean capturePicture = false;
    private volatile boolean isPreviewVisible = false;
    private volatile boolean photoCaptureddForCurrentCamera = false;
    
    // Multi-Camera Data
    private List<CameraCapture> cameraCaptureList;
    private List<UsbDevice> detectedCameras;
    private int currentCameraIndex = 0;
    private UsbDevice currentCamera;
    private UsbDeviceConnection currentConnection;
    private UVCCamera uvcCamera;
    private long mNativePtr = 0;
    private HashMap<Integer, UsbDevice> permissionRequestMap = new HashMap<>();
    
    // Camera Configuration
    private int imageWidth = 640;
    private int imageHeight = 480;
    private int formatIndex = 1;
    private int frameIndex = 1;
    private int frameInterval = 0;
    private int maxPacketSize = 0;
    private int streamingAltSetting = 1;
    private int streamingInterfaceNumber = 1;
    private String videoFormat = "UNKNOWN";
    
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (device == null) {
                        Log.e(TAG, "Permission intent received but device is null");
                        return;
                    }
                    
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        Log.d(TAG, "USB permission granted for: " + device.getDeviceName());
                        if (currentCamera != null && currentCamera.equals(device)) {
                            initializeCurrentCamera();
                        }
                    } else {
                        Log.d(TAG, "USB permission denied for: " + device.getDeviceName());
                        mainHandler.post(() -> {
                            if (currentCamera != null && currentCamera.equals(device)) {
                                statusText.setText("Permission denied for camera");
                                Toast.makeText(MultiCameraFormActivity.this, "USB permission denied", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && currentCamera != null && device.equals(currentCamera)) {
                    Log.d(TAG, "Camera disconnected: " + device.getDeviceName());
                    mainHandler.post(() -> {
                        Toast.makeText(MultiCameraFormActivity.this, "Camera disconnected", Toast.LENGTH_SHORT).show();
                        cleanupCamera();
                    });
                }
            }
        }
    };
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.activity_multi_camera_form);
        
        mainHandler = new Handler(Looper.getMainLooper());
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        
        // Initialize UI Elements
        cameraPreview = findViewById(R.id.cameraPreview);
        capturedPhotoView = findViewById(R.id.capturedPhotoView);
        progressText = findViewById(R.id.progressText);
        progressBar = findViewById(R.id.progressBar);
        captureButton = findViewById(R.id.captureButton);
        showPreviewButton = findViewById(R.id.showPreviewButton);
        previousButton = findViewById(R.id.previousButton);
        nextButton = findViewById(R.id.nextButton);
        statusText = findViewById(R.id.statusText);
        cameraInfoText = findViewById(R.id.cameraInfoText);
        notesEditText = findViewById(R.id.notesEditText);
        
        // Setup surface holder
        surfaceHolder = cameraPreview.getHolder();
        surfaceHolder.addCallback(this);
        
        // Initialize camera data
        cameraCaptureList = new ArrayList<>();
        detectedCameras = new ArrayList<>();
        for (int i = 0; i < NUM_CAMERAS; i++) {
            cameraCaptureList.add(new CameraCapture(i, "Camera " + (i + 1)));
        }
        
        // Initialize UVC Camera
        uvcCamera = new UVCCamera();
        mNativePtr = uvcCamera.create();
        Log.d(TAG, "Native camera pointer: 0x" + Long.toHexString(mNativePtr));
        
        // Setup button listeners
        captureButton.setOnClickListener(v -> capturePhoto());
        showPreviewButton.setOnClickListener(v -> showPreview());
        previousButton.setOnClickListener(v -> previousCamera());
        nextButton.setOnClickListener(v -> nextCamera());
        
        // Register USB broadcast receiver
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
        
        // Find all connected cameras
        findAllCameras();
        
        // Initialize with first camera
        if (!detectedCameras.isEmpty()) {
            Log.d(TAG, "Initializing first camera form");
            loadCamera(0);
        } else {
            statusText.setText("No UVC cameras detected");
            disableControls();
        }
    }
    
    private void findAllCameras() {
        detectedCameras.clear();
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        
        Log.d(TAG, "Total USB devices: " + deviceList.size());
        
        for (UsbDevice device : deviceList.values()) {
            if (isUvcCamera(device)) {
                detectedCameras.add(device);
                Log.d(TAG, "Found UVC camera: " + device.getDeviceName());
            }
        }
        
        int cameraCount = detectedCameras.size();
        Log.d(TAG, "UVC cameras found: " + cameraCount);
        
        if (cameraCount > 1) {
            Log.d(TAG, "MULTIPLE CAMERAS DETECTED: " + cameraCount + " cameras");
            for (int i = 0; i < detectedCameras.size(); i++) {
                UsbDevice cam = detectedCameras.get(i);
                Log.d(TAG, "  Camera " + (i + 1) + ": " + cam.getDeviceName() + 
                      " (VID: " + String.format("%04x", cam.getVendorId()) + ", " +
                      "PID: " + String.format("%04x", cam.getProductId()) + ")");
            }
        }
    }
    
    private boolean isUvcCamera(UsbDevice device) {
        int interfaceCount = device.getInterfaceCount();
        for (int i = 0; i < interfaceCount; i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) {
                if (iface.getInterfaceSubclass() == 2) { // SC_VIDEOSTREAMING
                    return true;
                }
            }
        }
        return false;
    }
    
    private void loadCamera(int index) {
        if (index < 0 || index >= NUM_CAMERAS) {
            return;
        }
        
        // Save notes from current camera before switching
        if (currentCameraIndex >= 0 && currentCameraIndex < NUM_CAMERAS) {
            cameraCaptureList.get(currentCameraIndex).setNotes(notesEditText.getText().toString());
        }
        
        currentCameraIndex = index;
        currentCamera = index < detectedCameras.size() ? detectedCameras.get(index) : null;
        
        // Reset photo capture flag for new camera
        photoCaptureddForCurrentCamera = false;
        isPreviewVisible = false;
        
        // Hide preview and photo views
        cameraPreview.setVisibility(View.GONE);
        capturedPhotoView.setVisibility(View.GONE);
        showPreviewButton.setText("👁️ Show Preview");
        
        // Update UI
        updateProgressUI();
        updateNavigationButtons();
        
        CameraCapture capture = cameraCaptureList.get(index);
        notesEditText.setText(capture.getNotes());
        
        // Update camera info
        if (currentCamera != null) {
            cameraInfoText.setText("Camera: " + currentCamera.getDeviceName());
            cleanupCamera();
            statusText.setText("Tap Show Preview to view camera\");
            captureButton.setEnabled(true);
        } else {
            cameraInfoText.setText("Camera: No device available");
            statusText.setText("Camera " + (index + 1) + " not detected");
        }
    }
    
    private void initializeCameraWithPermission() {
        if (currentCamera == null) {
            Log.e(TAG, "Cannot request permission - current camera is null");
            statusText.setText("Error: No camera selected");
            return;
        }
        
        Log.d(TAG, "Checking permission for: " + currentCamera.getDeviceName());
        
        if (usbManager.hasPermission(currentCamera)) {
            Log.d(TAG, "Already have permission for: " + currentCamera.getDeviceName());
            initializeCurrentCamera();
        } else {
            Log.d(TAG, "Permission not granted yet, requesting...");
            statusText.setText("Requesting USB permission...");
            requestPermission(currentCamera);
        }
    }
    
    private void requestPermission(UsbDevice device) {
        if (device == null) {
            Log.e(TAG, "Cannot request permission for null device");
            return;
        }
        
        // Use device hash code as unique request code
        int requestCode = device.hashCode() & 0x7FFFFFFF;
        permissionRequestMap.put(requestCode, device);
        
        Log.d(TAG, "Requesting USB permission for: " + device.getDeviceName() + " (request code: " + requestCode + ")");
        
        Intent intent = new Intent(ACTION_USB_PERMISSION);
        intent.putExtra(UsbManager.EXTRA_DEVICE, device);
        
        PendingIntent permissionIntent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionIntent = PendingIntent.getBroadcast(this, requestCode, intent,
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        } else {
            permissionIntent = PendingIntent.getBroadcast(this, requestCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
        }
        
        usbManager.requestPermission(device, permissionIntent);
    }
    
    private void initializeCurrentCamera() {
        if (currentCamera == null) {
            Log.e(TAG, "Cannot initialize - current camera is null");
            statusText.setText("Error: Camera not found");
            return;
        }
        
        try {
            statusText.setText("Initializing camera...");
            Log.d(TAG, "Opening device: " + currentCamera.getDeviceName());
            
            currentConnection = usbManager.openDevice(currentCamera);
            
            if (currentConnection == null) {
                Log.e(TAG, "Failed to open device connection for: " + currentCamera.getDeviceName());
                statusText.setText("Error: Cannot open device");
                Toast.makeText(this, "Failed to open USB device", Toast.LENGTH_SHORT).show();
                return;
            }
            
            Log.d(TAG, "Device opened successfully: " + currentCamera.getDeviceName());
            
            // Configure camera (simplified for multi-camera scenario)
            configureCamera();
            
            // Show preview if user requested it
            if (isPreviewVisible) {
                displayTestPreview();
                // Start streaming in background thread
                startCameraStreaming();
            } else {
                // Camera is ready but do NOT start streaming automatically
                Log.d(TAG, "Camera initialized and ready for capture (streaming NOT started)");
                statusText.setText("Camera ready - tap Show Preview to begin");
            }
            captureButton.setEnabled(true);
        } catch (Exception e) {
            Log.e(TAG, "Error initializing camera", e);
            statusText.setText("Error: " + e.getMessage());
            Toast.makeText(this, "Camera initialization failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void configureCamera() {
        // Simplified camera configuration
        // In production, you'd extract format/resolution from device descriptors
        Log.d(TAG, "Camera configured with defaults (" + imageWidth + "x" + imageHeight + ")");
    }
    
    private void startCameraStreaming() {
        new Thread(() -> {
            try {
                if (currentConnection == null) {
                    Log.e(TAG, "Camera connection is null");
                    mainHandler.post(() -> statusText.setText("Error: Camera not connected"));
                    return;
                }
                
                int fd = currentConnection.getFileDescriptor();
                Log.d(TAG, "Camera FD: " + fd);
                
                // Create frame callback for capture
                UVCCamera.IFrameCallback frameCallback = frameData -> {
                    Log.d(TAG, "Frame received: " + frameData.length + " bytes");
                    if (capturePicture) {
                        capturePicture = false;
                        Log.d(TAG, "Capture triggered, saving frame");
                        saveFrameData(frameData);
                    }
                };
                
                Log.d(TAG, "Preparing stream with resolution: " + imageWidth + "x" + imageHeight);
                
                Surface surface = surfaceHolder.getSurface();
                if (surface == null || !surface.isValid()) {
                    Log.e(TAG, "Surface is null or invalid!");
                    mainHandler.post(() -> statusText.setText("Error: Invalid surface"));
                    return;
                }
                
                int prepareResult = uvcCamera.PreviewPrepareStream(mNativePtr, surface, frameCallback);
                Log.d(TAG, "PreviewPrepareStream result: " + prepareResult);
                
                if (prepareResult == 0) {
                    Log.d(TAG, "Calling PreviewStartStream");
                    int startResult = uvcCamera.PreviewStartStream(mNativePtr);
                    Log.d(TAG, "PreviewStartStream result: " + startResult);
                    
                    if (startResult == 0) {
                        isStreaming = true;
                        mainHandler.post(() -> {
                            statusText.setText("Live preview streaming...");
                            Log.d(TAG, "Streaming started successfully");
                        });
                    } else {
                        Log.e(TAG, "PreviewStartStream failed with result: " + startResult);
                        mainHandler.post(() -> statusText.setText("Error: Failed to start streaming (" + startResult + ")"));
                    }
                } else {
                    Log.e(TAG, "PreviewPrepareStream failed with result: " + prepareResult);
                    mainHandler.post(() -> statusText.setText("Error: Failed to prepare streaming (" + prepareResult + ")"));
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error starting camera stream", e);
                mainHandler.post(() -> statusText.setText("Error: " + e.getMessage()));
            }
        }).start();
    }
    
    private void saveFrameData(byte[] frameData) {
        try {
            File picturesDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (picturesDir != null && !picturesDir.exists()) {
                picturesDir.mkdirs();
            }
            
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            File frameFile = new File(picturesDir, "frame_camera_" + currentCameraIndex + "_" + timestamp + ".yuv");
            
            FileOutputStream fos = new FileOutputStream(frameFile);
            fos.write(frameData);
            fos.close();
            
            Log.d(TAG, "Frame data saved: " + frameFile.getAbsolutePath());
            
        } catch (Exception e) {
            Log.e(TAG, "Error saving frame data", e);
        }
    }
    
    private void stopStreaming() {
        isStreaming = false;
        captureButton.setEnabled(false);
        statusText.setText("Streaming stopped");
        Log.d(TAG, "Streaming stopped");
    }
    
    private void cleanupCamera() {
        stopStreaming();
        if (currentConnection != null) {
            currentConnection.close();
            currentConnection = null;
        }
    }
    
    private void showPreview() {
        if (isPreviewVisible) {
            // Can only hide preview after photo has been captured
            if (!photoCaptureddForCurrentCamera) {
                Toast.makeText(this, "Please capture a photo before hiding preview", Toast.LENGTH_SHORT).show();
                return;
            }
            // Hide preview and photo
            capturedPhotoView.setVisibility(View.GONE);
            showPreviewButton.setText("👁️ Show Preview");
            isPreviewVisible = false;
            statusText.setText("Preview hidden");
        } else {
            // Show preview - initialize camera if needed
            if (currentConnection == null) {
                Log.d(TAG, "Camera not initialized yet, initializing for preview");
                photoCaptureddForCurrentCamera = false;
                isPreviewVisible = true;
                // Show test preview image immediately
                displayTestPreview();
            } else {
                // Camera already initialized, show test preview
                displayTestPreview();
            }
        }
    }
    
    private void displayTestPreview() {
        // Show the SurfaceView for live camera streaming
        cameraPreview.setVisibility(View.VISIBLE);
        capturedPhotoView.setVisibility(View.GONE);
        showPreviewButton.setText("👁️ Hide Preview");
        isPreviewVisible = true;
        statusText.setText("Preview visible - initializing camera stream...");
        Log.d(TAG, "SurfaceView displayed for live streaming");
    }
    
    private void capturePhoto() {
        // If camera not initialized, initialize it first
        if (currentConnection == null) {
            Log.d(TAG, "Camera not initialized yet, initializing on first capture");
            initializeCameraWithPermission();
            return;
        }
        
        captureButton.setEnabled(false);
        Toast.makeText(this, "Photo captured!", Toast.LENGTH_SHORT).show();
        
        // In a real implementation, you would:
        // 1. Capture frame from preview
        // 2. Save to file
        // 3. Store file path in cameraCaptureList
        
        CameraCapture capture = cameraCaptureList.get(currentCameraIndex);
        String photoPath = saveTestPhoto(currentCameraIndex);
        capture.setPhotoPath(photoPath);
        capture.setCaptureTimestamp(System.currentTimeMillis());
        
        // Display captured photo
        displayCapturedPhoto(photoPath);
        
        // Mark that photo has been captured for current camera
        photoCaptureddForCurrentCamera = true;
        statusText.setText("Photo captured - tap Hide Preview to close");
        
        mainHandler.postDelayed(() -> {
            captureButton.setEnabled(true);
        }, 500);
    }
    
    private void displayCapturedPhoto(String photoPath) {
        try {
            // Load and display the captured photo
            Bitmap bitmap = BitmapFactory.decodeFile(photoPath);
            if (bitmap != null) {
                capturedPhotoView.setImageBitmap(bitmap);
                capturedPhotoView.setVisibility(View.VISIBLE);
                // Hide live preview when showing captured photo
                cameraPreview.setVisibility(View.GONE);
                Log.d(TAG, "Displayed captured photo: " + photoPath);
            } else {
                Log.e(TAG, "Failed to load bitmap from: " + photoPath);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error displaying captured photo", e);
        }
    }
    
    private String saveTestPhoto(int cameraIndex) {
        try {
            File picturesDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (picturesDir != null && !picturesDir.exists()) {
                picturesDir.mkdirs();
            }
            
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File photoFile = new File(picturesDir, "camera_" + cameraIndex + "_" + timestamp + ".jpg");
            
            // Create a test bitmap with camera info
            Bitmap testBitmap = createTestBitmap(cameraIndex);
            
            // Save bitmap to file
            FileOutputStream fos = new FileOutputStream(photoFile);
            testBitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            fos.close();
            testBitmap.recycle();
            
            Log.d(TAG, "Photo saved: " + photoFile.getAbsolutePath());
            return photoFile.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Error saving photo", e);
            return "";
        }
    }
    
    private Bitmap createTestBitmap(int cameraIndex) {
        // Create a test bitmap to display
        Bitmap bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888);
        // For now, just return a colored bitmap as placeholder
        bitmap.eraseColor(android.graphics.Color.argb(255, 100 + cameraIndex * 30, 150, 200));
        return bitmap;
    }
    
    private void previousCamera() {
        if (currentCameraIndex > 0) {
            loadCamera(currentCameraIndex - 1);
        }
    }
    
    private void nextCamera() {
        if (currentCameraIndex < NUM_CAMERAS - 1) {
            loadCamera(currentCameraIndex + 1);
        } else {
            // Completed all cameras - show summary
            showCompletionScreen();
        }
    }
    
    private void updateProgressUI() {
        int progress = (int) ((currentCameraIndex + 1) / (float) NUM_CAMERAS * 100);
        progressBar.setProgress(progress);
        progressText.setText("Camera " + (currentCameraIndex + 1) + " of " + NUM_CAMERAS);
    }
    
    private void updateNavigationButtons() {
        previousButton.setEnabled(currentCameraIndex > 0);
        nextButton.setText(currentCameraIndex < NUM_CAMERAS - 1 ? "Next →" : "Complete ✓");
    }
    
    private void disableControls() {
        captureButton.setEnabled(false);
        previousButton.setEnabled(false);
        nextButton.setEnabled(false);
    }
    
    private void showCompletionScreen() {
        // Save final notes
        cameraCaptureList.get(currentCameraIndex).setNotes(notesEditText.getText().toString());
        
        Intent intent = new Intent(this, CameraCompletionActivity.class);
        intent.putExtra("cameraCaptureList", (ArrayList<CameraCapture>) cameraCaptureList);
        startActivity(intent);
        finish();
    }
    
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "Surface created");
    }
    
    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "Surface changed: " + width + "x" + height);
    }
    
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "Surface destroyed");
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        stopStreaming();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // Only request permission if we already have a camera initialized
        if (currentConnection != null && currentCamera != null) {
            Log.d(TAG, "Resuming with active camera connection");
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        cleanupCamera();
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering receiver", e);
        }
    }
}
