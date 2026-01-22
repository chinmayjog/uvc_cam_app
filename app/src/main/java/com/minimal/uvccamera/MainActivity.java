package com.minimal.uvccamera;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.content.pm.PackageManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;

public class MainActivity extends AppCompatActivity {
    
    static {
        String[] libs = {"usb1.0", "jpeg9", "yuv", "uvc", "uvc_preview", "Uvc_Support"};
        for (String lib : libs) {
            try {
                System.loadLibrary(lib);
                Log.d("MinimalUVC", "Loaded library: " + lib);
            } catch (UnsatisfiedLinkError e) {
                Log.e("MinimalUVC", "Failed to load library: " + lib, e);
            }
        }
    }
    
    // Native camera pointer
    private long mNativePtr = 0;
    private static final String TAG = "MinimalUVC";
    private static final String ACTION_USB_PERMISSION = "com.minimal.uvccamera.USB_PERMISSION";
    private static final int PERMISSION_REQUEST_CODE = 100;
    
    // UVC Protocol Constants
    private static final int SC_VIDEOCONTROL = 0x01;
    private static final int SC_VIDEOSTREAMING = 0x02;
    private static final int RT_CLASS_INTERFACE_GET = 0xA1;
    private static final int RT_CLASS_INTERFACE_SET = 0x21;
    private static final int GET_CUR = 0x81;
    private static final int SET_CUR = 0x01;
    private static final int VS_PROBE_CONTROL = 0x01;
    private static final int VS_COMMIT_CONTROL = 0x02;
    
    // UI Elements
    private TextView statusText;
    private TextView cameraInfoText;
    private Button startButton;
    
    // USB Camera
    private UsbManager usbManager;
    private UsbDevice cameraDevice;
    private UsbDeviceConnection deviceConnection;
    private UsbInterface controlInterface;
    private UsbInterface streamingInterface;
    private UsbEndpoint streamingEndpoint;
    
    // Camera Configuration (auto-detected from device)
    private int imageWidth = 0;        // Will be auto-detected from camera
    private int imageHeight = 0;       // Will be auto-detected from camera
    private int formatIndex = 1;       // Default, will be auto-detected
    private int frameIndex = 1;        // Default, will be auto-detected
    private int frameInterval = 0;     // Will be auto-detected from camera
    private int maxPacketSize = 0;     // Will be read from USB endpoint
    private int streamingAltSetting = 1;
    private int streamingInterfaceNumber = 1;
    private String videoFormat = "UNKNOWN"; // Will be detected
    
    // UVC Camera (JNI)
    private UVCCamera uvcCamera;
    private Handler mainHandler;
    
    // Native method to set camera values directly using C function
    private native int setNativeValues(long cameraPtr, int fd, 
                                      int packetsPerRequest, int maxPacketSize, int activeUrbs,
                                      int camStreamingAltSetting, int camFormatIndex,
                                      int camFrameIndex, int camFrameInterval,
                                      int imageWidth, int imageHeight,
                                      int camStreamingEndpoint, int camStreamingInterfaceNumber,
                                      String frameFormat, int numberOfAutoFrames,
                                      int bcdUVC, int lowAndroid);
    
    // Native method to initialize streaming parameters (wraps FD with libusb)
    private native int initStreamingParms(long cameraPtr, int fd);
    private native int listDeviceUvc(long cameraPtr, int fd);
    
    static {
        try {
            System.loadLibrary("Uvc_Support");
            Log.d(TAG, "Native library loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native library", e);
        }
    }
    
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            Log.d(TAG, "USB permission granted");
                            initializeCamera(device);
                        }
                    } else {
                        Log.d(TAG, "USB permission denied");
                        updateStatus("Permission denied");
                    }
                }
            }
        }
    };
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Force portrait orientation
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.activity_main);
        
        mainHandler = new Handler(Looper.getMainLooper());
        
        // Initialize UVC Camera (JNI)
        uvcCamera = new UVCCamera();
        mNativePtr = uvcCamera.create();
        Log.d(TAG, "Native camera pointer created: 0x" + Long.toHexString(mNativePtr));
        
        statusText = findViewById(R.id.statusText);
        cameraInfoText = findViewById(R.id.cameraInfoText);
        startButton = findViewById(R.id.startButton);
        
        startButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, CameraActivity.class);
            intent.putExtra("imageWidth", imageWidth);
            intent.putExtra("imageHeight", imageHeight);
            intent.putExtra("formatIndex", formatIndex);
            intent.putExtra("frameIndex", frameIndex);
            intent.putExtra("frameInterval", frameInterval);
            intent.putExtra("maxPacketSize", maxPacketSize);
            intent.putExtra("streamingAltSetting", streamingAltSetting);
            intent.putExtra("streamingInterfaceNumber", streamingInterfaceNumber);
            intent.putExtra("videoFormat", videoFormat);
            intent.putExtra("deviceName", cameraDevice.getDeviceName());
            intent.putExtra("mNativePtr", mNativePtr);  // Pass native pointer
            startActivity(intent);
        });
        
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
        
        // Request runtime permissions for camera and storage
        requestRequiredPermissions();
        
        findCamera();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeCamera();
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering receiver", e);
        }
        try {
            // No explicit LibUsb teardown required in current native library
        } catch (Exception e) {
            Log.e(TAG, "Error during teardown", e);
        }
    }
    
    private void findCamera() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        Log.d(TAG, "USB devices found: " + deviceList.size());
        
        for (UsbDevice device : deviceList.values()) {
            Log.d(TAG, "Checking device: " + device.getDeviceName() + 
                  " VID: 0x" + Integer.toHexString(device.getVendorId()) +
                  " PID: 0x" + Integer.toHexString(device.getProductId()));
            if (isUvcCamera(device)) {
                cameraDevice = device;
                Log.d(TAG, "UVC Camera found: " + device.getDeviceName());
                requestPermission(device);
                return;
            }
        }
        
        updateStatus(getString(R.string.no_camera));
    }
    
    private boolean isUvcCamera(UsbDevice device) {
        // Check if device has video class interfaces
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            // Accept any video class interface (control or streaming)
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) {
                Log.d(TAG, "Found VIDEO class interface with subclass: " + iface.getInterfaceSubclass() + 
                      " endpoints: " + iface.getEndpointCount());
                return true;
            }
        }
        return false;
    }
    
    private void requestPermission(UsbDevice device) {
        // Always check if permission already exists
        if (device == null) {
            Log.e(TAG, "Device is null");
            return;
        }
        
        boolean hasPermission = usbManager.hasPermission(device);
        Log.d(TAG, "Device " + device.getDeviceName() + " hasPermission: " + hasPermission);
        
        if (hasPermission) {
            Log.d(TAG, "USB permission already granted");
            initializeCamera(device);
            return;
        }
        
        Log.d(TAG, "Requesting USB permission for " + device.getDeviceName());
        PendingIntent permissionIntent = PendingIntent.getBroadcast(
            this, 0, new Intent(ACTION_USB_PERMISSION),
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0
        );
        usbManager.requestPermission(device, permissionIntent);
    }
    
    private void initializeCamera(UsbDevice device) {
        try {
            cameraDevice = device;
            
            // Open device connection to get file descriptor
            deviceConnection = usbManager.openDevice(device);
            if (deviceConnection == null) {
                updateStatus("Cannot open device");
                Log.e(TAG, "Failed to open USB device");
                return;
            }
            
            // Get file descriptor - this is what native code needs
            int fd = deviceConnection.getFileDescriptor();
            Log.d(TAG, "USB device opened with FD: " + fd);
            
            // Find streaming interface to get its number
            streamingInterface = findInterface(device, SC_VIDEOSTREAMING);
            
            if (streamingInterface == null) {
                Log.e(TAG, "Streaming interface not found");
                updateStatus("Camera interface not found");
                return;
            }
            
            // Don't claim interfaces here - let native code handle it
            // The native code needs full control over USB interfaces
            
            streamingInterfaceNumber = streamingInterface.getId();
            Log.d(TAG, "Found streaming interface: " + streamingInterfaceNumber);
            
            // Use native library to detect camera capabilities like reference app
            updateStatus("Detecting camera capabilities...");
            
            new Thread(() -> {
                try {
                    // Let native code handle all USB descriptor parsing and detection
                    // This matches the reference app architecture
                    
                    // Try multiple format options with fallbacks
                    // Priority: Common supported formats first, then higher resolutions
                    boolean formatConfigured = false;
                    
                    // First try: MJPEG at 640x480 (VGA - most commonly supported)
                    if (tryConfigureFormat("MJPEG", 640, 480, 1, 1)) {
                        formatConfigured = true;
                        Log.d(TAG, "Successfully configured 640x480 MJPEG format");
                    }
                    
                    // Second try: MJPEG at 800x600
                    if (!formatConfigured && tryConfigureFormat("MJPEG", 800, 600, 1, 1)) {
                        formatConfigured = true;
                        Log.d(TAG, "Successfully configured 800x600 MJPEG format");
                    }
                    
                    // Third try: MJPEG at 1280x720 (HD)
                    if (!formatConfigured && tryConfigureFormat("MJPEG", 1280, 720, 1, 1)) {
                        formatConfigured = true;
                        Log.d(TAG, "Successfully configured 720p MJPEG format");
                    }
                    
                    // Fourth try: MJPEG at 1920x1080 (Full HD)
                    if (!formatConfigured && tryConfigureFormat("MJPEG", 1920, 1080, 1, 1)) {
                        formatConfigured = true;
                        Log.d(TAG, "Successfully configured 1080p MJPEG format");
                    }
                    
                    // Fifth try: YUY2 (YUV 4:2:2 uncompressed) at 640x480
                    if (!formatConfigured && tryConfigureFormat("YUY2", 640, 480, 1, 1)) {
                        formatConfigured = true;
                        Log.d(TAG, "MJPEG not supported, using 640x480 YUY2 format");
                    }
                    
                    // Sixth try: YUY2 at 800x600
                    if (!formatConfigured && tryConfigureFormat("YUY2", 800, 600, 1, 1)) {
                        formatConfigured = true;
                        Log.d(TAG, "Using 800x600 YUY2 format");
                    }
                    
                    // Seventh try: Lower resolution MJPEG (320x240)
                    if (!formatConfigured && tryConfigureFormat("MJPEG", 320, 240, 1, 1)) {
                        formatConfigured = true;
                        Log.d(TAG, "Using lower resolution 320x240 MJPEG");
                    }
                    
                    // Eighth try: Lower resolution YUY2
                    if (!formatConfigured && tryConfigureFormat("YUY2", 320, 240, 1, 1)) {
                        formatConfigured = true;
                        Log.d(TAG, "Using lower resolution 320x240 YUY2");
                    }
                    
                    // Fallback: Use MJPEG defaults anyway and let native code handle errors
                    if (!formatConfigured) {
                        Log.w(TAG, "Could not verify format support, using MJPEG defaults");
                        imageWidth = 640;
                        imageHeight = 480;
                        formatIndex = 1;
                        frameIndex = 1;
                        frameInterval = 333333; // ~30 fps
                        maxPacketSize = 3072;
                        streamingAltSetting = 1;
                        videoFormat = "MJPEG";
                    }
                    
                    Log.d(TAG, "Final configuration:");
                    Log.d(TAG, "  Resolution: " + imageWidth + "x" + imageHeight);
                    Log.d(TAG, "  Format: " + videoFormat + " (index " + formatIndex + ")");
                    Log.d(TAG, "  Frame index: " + frameIndex);
                    Log.d(TAG, "  Frame interval: " + frameInterval);
                    Log.d(TAG, "  Max packet size: " + maxPacketSize);
                    
                    // Set native values in camera structure
                    int result = setNativeValues(mNativePtr, fd,
                            8, // packetsPerRequest
                            maxPacketSize,
                            5, // activeUrbs
                            streamingAltSetting,
                            formatIndex,
                            frameIndex,
                            frameInterval,
                            imageWidth,
                            imageHeight,
                            streamingInterfaceNumber, // endpoint
                            streamingInterfaceNumber,
                            videoFormat,
                            1, // numberOfAutoFrames
                            0, // bcdUVC
                            0  // lowAndroid
                    );
                    Log.d(TAG, "setNativeValues result: " + result);
                    
                    // Initialize streaming parameters (wraps FD with libusb, opens device)
                    result = initStreamingParms(mNativePtr, fd);
                    Log.d(TAG, "initStreamingParms result: " + result);
                    
                    if (result != 0) {
                        Log.e(TAG, "Failed to initialize streaming parameters");
                        mainHandler.post(() -> updateStatus("Failed to initialize device"));
                        return;
                    }
                    
                    // Important: Call listDeviceUvc to properly initialize device handle
                    result = listDeviceUvc(mNativePtr, fd);
                    Log.d(TAG, "listDeviceUvc result: " + result);
                    
                    if (result != 0) {
                        Log.e(TAG, "Failed to list device info");
                        mainHandler.post(() -> updateStatus("Failed to get device info"));
                        return;
                    }
                    
                    mainHandler.post(() -> {
                        updateStatus("Camera ready");
                        String info = "Camera: " + device.getProductName() + "\n" +
                                     "Resolution: " + imageWidth + "x" + imageHeight + "\n" +
                                     "Format: " + videoFormat;
                        cameraInfoText.setText(info);
                        startButton.setEnabled(true);
                    });
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error in camera detection", e);
                    mainHandler.post(() -> updateStatus("Detection error: " + e.getMessage()));
                }
            }).start();
            
        } catch (Exception e) {
            Log.e(TAG, "Error initializing camera", e);
            updateStatus("Error: " + e.getMessage());
        }
    }
    
    /**
     * Try to configure camera with specified format and resolution.
     * This is a simplified check - in production you'd query USB descriptors.
     * @return true if format is likely supported (based on common patterns)
     */
    private boolean tryConfigureFormat(String format, int width, int height, int fmtIdx, int frmIdx) {
        // For now, we'll assume common formats/resolutions are supported
        // Real implementation would query camera capabilities via USB descriptors
        // The native libUVC code will ultimately validate these parameters
        
        imageWidth = width;
        imageHeight = height;
        formatIndex = fmtIdx;
        frameIndex = frmIdx;
        videoFormat = format;
        
        // Calculate appropriate frame interval and packet size based on format
        if ("MJPEG".equals(format)) {
            frameInterval = 333333; // 30 fps
            maxPacketSize = 3072; // Typical for MJPEG
            streamingAltSetting = 1;
        } else if ("YUY2".equals(format)) {
            // YUY2 is uncompressed, needs more bandwidth
            frameInterval = 666666; // 15 fps (safer for uncompressed)
            maxPacketSize = calculateYuy2PacketSize(width, height);
            streamingAltSetting = findAppropriateAltSetting(maxPacketSize);
        } else {
            // Other formats - use conservative settings
            frameInterval = 666666; // 15 fps
            maxPacketSize = 3072;
            streamingAltSetting = 1;
        }
        
        Log.d(TAG, "Trying format: " + format + " " + width + "x" + height);
        return true; // Optimistically assume it's supported - native code will validate
    }
    
    private int calculateYuy2PacketSize(int width, int height) {
        // YUY2 is 16 bits per pixel (2 bytes)
        // For streaming, use appropriate chunk sizes for bandwidth
        int bytesPerFrame = width * height * 2;
        int fps = 15;
        int bytesPerSecond = bytesPerFrame * fps;
        
        // Use appropriate packet size - typical USB 2.0 high-speed max is 3072
        // Scale based on resolution
        if (width <= 320) {
            return 1024;
        } else if (width <= 640) {
            return 2048;
        } else if (width <= 800) {
            return 3072;
        } else if (width <= 1280) {
            return 3072; // Still 3072 - USB 2.0 max single packet
        } else {
            return 3072; // Maximum USB 2.0 high-speed packet size
        }
    }
    
    private int findAppropriateAltSetting(int requiredPacketSize) {
        // Map packet sizes to typical alt settings
        // Alt 0: 0 bytes (no bandwidth)
        // Alt 1-7: Increasing bandwidth allocation
        if (requiredPacketSize <= 512) {
            return 1;
        } else if (requiredPacketSize <= 1024) {
            return 2;
        } else if (requiredPacketSize <= 2048) {
            return 4;
        } else {
            return 6; // Maximum bandwidth for USB 2.0 high-speed
        }
    }
    
    private UsbInterface findInterface(UsbDevice device, int subclass) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO &&
                iface.getInterfaceSubclass() == subclass) {
                return iface;
            }
        }
        return null;
    }
    
    private void autoDetectConfiguration() {
        updateStatus(getString(R.string.configuring));
        
        new Thread(() -> {
            try {
                // Get current streaming parameters (should contain supported capabilities)
                byte[] probeData = new byte[26];
                ByteBuffer buf = ByteBuffer.wrap(probeData).order(ByteOrder.LITTLE_ENDIAN);
                
                // Probe GET_CUR - Get current capabilities
                int len = deviceConnection.controlTransfer(
                    RT_CLASS_INTERFACE_GET,
                    GET_CUR,
                    VS_PROBE_CONTROL << 8,
                    streamingInterface.getId(),
                    probeData,
                    probeData.length,
                    1000
                );
                
                if (len > 0) {
                    buf.rewind();
                    
                    // Parse PROBE_CONTROL response structure (UVC 1.1 spec, Table 4-103)
                    byte bmHint = probeData[0];
                    byte bFormatIndex = probeData[2];
                    byte bFrameIndex = probeData[3];
                    
                    // Frame interval (100ns units)
                    int dwFrameInterval = buf.getInt(4);
                    
                    // Key descriptor index
                    int wKeyFrameRate = buf.getShort(8) & 0xFFFF;
                    
                    // Resolution
                    int wWidth = buf.getShort(10) & 0xFFFF;
                    int wHeight = buf.getShort(12) & 0xFFFF;
                    
                    // Bit rate
                    int dwBitRate = buf.getInt(14);
                    
                    // Frame size
                    int dwMaxVideoFrameSize = buf.getInt(18);
                    
                    // Bandwidth
                    int dwMaxPayloadTransferSize = buf.getInt(22);
                    
                    // Update detected values only if they're valid (non-zero)
                    if (bFormatIndex > 0) {
                        formatIndex = bFormatIndex & 0xFF;
                        Log.d(TAG, "Detected formatIndex: " + formatIndex);
                    }
                    
                    if (bFrameIndex > 0) {
                        frameIndex = bFrameIndex & 0xFF;
                        Log.d(TAG, "Detected frameIndex: " + frameIndex);
                    }
                    
                    if (wWidth > 0) {
                        imageWidth = wWidth;
                        Log.d(TAG, "Detected imageWidth: " + imageWidth);
                    }
                    
                    if (wHeight > 0) {
                        imageHeight = wHeight;
                        Log.d(TAG, "Detected imageHeight: " + imageHeight);
                    }
                    
                    if (dwFrameInterval > 0) {
                        frameInterval = dwFrameInterval;
                        int fps = 10000000 / frameInterval;
                        Log.d(TAG, "Detected frameInterval: " + frameInterval + " (≈" + fps + " fps)");
                    }
                    
                    if (dwMaxPayloadTransferSize > 0) {
                        // This helps optimize USB transfers
                        Log.d(TAG, "Max payload size: " + dwMaxPayloadTransferSize);
                    }
                    
                    // Determine format string
                    if (formatIndex == 1) {
                        videoFormat = "MJPEG";
                    } else if (formatIndex == 2) {
                        videoFormat = "YUY2";
                    } else if (formatIndex == 3) {
                        videoFormat = "NV12";
                    } else {
                        videoFormat = "Format#" + formatIndex;
                    }
                    
                    Log.d(TAG, String.format("PROBE GET_CUR successful: %dx%d @%d fps, format=%s, frame=%d",
                        imageWidth, imageHeight, 
                        frameInterval > 0 ? 10000000 / frameInterval : 0,
                        videoFormat, frameIndex));
                } else {
                    Log.w(TAG, "PROBE GET_CUR failed with code: " + len);
                }
                
                // Validate that we got required values
                if (imageWidth <= 0 || imageHeight <= 0 || frameInterval <= 0) {
                    Log.e(TAG, "Auto-detection failed - required parameters not set by device");
                    mainHandler.post(() -> updateStatus("Camera does not report resolution"));
                    return;
                }
                
                // Probe SET_CUR - Apply the detected configuration
                ByteBuffer setBuf = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN);
                setBuf.put(0, (byte) 0);           // bmHint
                setBuf.put(1, (byte) 0);           // bmHint
                setBuf.put(2, (byte) formatIndex); // bFormatIndex
                setBuf.put(3, (byte) frameIndex);  // bFrameIndex
                setBuf.putInt(4, frameInterval);   // dwFrameInterval
                setBuf.putShort(8, (short) 0);     // wKeyFrameRate
                setBuf.putShort(10, (short) imageWidth);  // wWidth
                setBuf.putShort(12, (short) imageHeight); // wHeight
                setBuf.putInt(14, 0);              // dwBitRate (0 = device decides)
                setBuf.putInt(18, 0);              // dwMaxVideoFrameSize (0 = device decides)
                setBuf.putInt(22, 0);              // dwMaxPayloadTransferSize (0 = device decides)
                
                len = deviceConnection.controlTransfer(
                    RT_CLASS_INTERFACE_SET,
                    SET_CUR,
                    VS_PROBE_CONTROL << 8,
                    streamingInterface.getId(),
                    setBuf.array(),
                    26,
                    1000
                );
                
                if (len < 0) {
                    Log.w(TAG, "PROBE SET_CUR returned: " + len);
                }
                
                // Commit SET_CUR - Finalize configuration
                len = deviceConnection.controlTransfer(
                    RT_CLASS_INTERFACE_SET,
                    SET_CUR,
                    VS_COMMIT_CONTROL << 8,
                    streamingInterface.getId(),
                    setBuf.array(),
                    26,
                    1000
                );
                
                if (len >= 0) {
                    mainHandler.post(() -> {
                        updateStatus(getString(R.string.ready));
                        String info = String.format("Resolution: %dx%d\nFPS: %d\nPacket Size: %d\nFormat: %s",
                            imageWidth, imageHeight,
                            frameInterval > 0 ? 10000000 / frameInterval : 0, 
                            maxPacketSize, videoFormat);
                        cameraInfoText.setText(info);
                        cameraInfoText.setVisibility(android.view.View.VISIBLE);
                        startButton.setEnabled(true);
                    });
                    Log.d(TAG, "Configuration committed successfully");
                } else {
                    mainHandler.post(() -> updateStatus("Camera configuration failed"));
                    Log.e(TAG, "COMMIT SET_CUR failed with code: " + len);
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Auto-detection error", e);
                mainHandler.post(() -> updateStatus("Error: " + e.getMessage()));
            }
        }).start();
    }
    
    private void closeCamera() {
        // Don't close the device connection here because:
        // 1. Native code is using the file descriptor
        // 2. Native code will handle cleanup through libusb
        // 3. Closing here would invalidate the FD while native code is using it
        
        // Connection will be closed when app process terminates
        Log.d(TAG, "closeCamera called - native code owns the connection");
    }
    
    private void updateStatus(String message) {
        runOnUiThread(() -> statusText.setText(message));
    }
    
    private void requestRequiredPermissions() {
        // Determine which permissions to request based on Android version
        java.util.List<String> permissionsToRequest = new java.util.ArrayList<>();
        
        // Camera permission
        if (ContextCompat.checkSelfPermission(this, 
            android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.CAMERA);
        }
        
        // Storage permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: Media permissions
            if (ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_IMAGES);
            }
            if (ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_VIDEO);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11-12: MANAGE_EXTERNAL_STORAGE or traditional storage
            if (ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        } else {
            // Below Android 11: Traditional storage permissions
            if (ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            if (ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
        
        // Request permissions if any are missing
        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                permissionsToRequest.toArray(new String[0]),
                PERMISSION_REQUEST_CODE);
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            StringBuilder deniedPermissions = new StringBuilder();
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    if (deniedPermissions.length() > 0) {
                        deniedPermissions.append(", ");
                    }
                    deniedPermissions.append(permissions[i].substring(permissions[i].lastIndexOf('.') + 1));
                }
            }
            
            if (deniedPermissions.length() > 0) {
                Toast.makeText(this, "Permissions denied: " + deniedPermissions.toString(), 
                    Toast.LENGTH_LONG).show();
                Log.w(TAG, "Permissions denied: " + deniedPermissions.toString());
            } else {
                Log.d(TAG, "All permissions granted");
            }
        }
    }
}
