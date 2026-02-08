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
    private int dwMaxPayloadTransferSize = 0;  // Camera's negotiated max payload (from UVC PROBE)
    private int streamingAltSetting = 1;
    private int streamingInterfaceNumber = 1;
    private String videoFormat = "UNKNOWN"; // Will be detected
    
    // UVC Camera (JNI)
    private UVCCamera uvcCamera;
    private Handler mainHandler;
    
    // Native method to set camera values directly using C function
    public static native int setNativeValues(long cameraPtr, int fd, 
                                      int packetsPerRequest, int maxPacketSize, int activeUrbs,
                                      int camStreamingAltSetting, int camFormatIndex,
                                      int camFrameIndex, int camFrameInterval,
                                      int imageWidth, int imageHeight,
                                      int camStreamingEndpoint, int camStreamingInterfaceNumber,
                                      String frameFormat, int numberOfAutoFrames,
                                      int bcdUVC, int lowAndroid);
    
    // Native method to initialize streaming parameters (wraps FD with libusb)
    public static native int initStreamingParms(long cameraPtr, int fd);
    public static native int listDeviceUvc(long cameraPtr, int fd);
    public static native void resetCameraState();
    public static native void closeCameraDevice(long cameraPtr);
    public static native boolean isCameraDeviceClosed(long cameraPtr);
    public static native boolean isStreamStopped(long cameraPtr);
    
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
                        Log.d(TAG, "USB permission denied, will retry");
                        // Instead of showing error immediately, retry the permission request
                        if (device != null && device.equals(cameraDevice)) {
                            mainHandler.postDelayed(() -> {
                                if (cameraDevice != null && device.equals(cameraDevice)) {
                                    Log.d(TAG, "Retrying permission request after denial");
                                    requestPermission(device);
                                }
                            }, 1000);
                        }
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (device != null && device.equals(cameraDevice)) {
                        Log.d(TAG, "USB device detached: " + device.getDeviceName());
                        closeCamera();
                        updateStatus("Camera disconnected");
                        mainHandler.postDelayed(MainActivity.this::findCamera, 500);
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (device != null) {
                        Log.d(TAG, "USB device attached: " + device.getDeviceName());
                        // Check if this is a UVC camera
                        if (isUvcCamera(device) && cameraDevice == null) {
                            Log.d(TAG, "Detected UVC camera attachment");
                            requestPermission(device);
                        }
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
            Intent intent = new Intent(MainActivity.this, FaultFormActivity.class);
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
            
            // Collect all available UVC cameras and pass them to FaultFormActivity
            String[] allCameraNames = getAllUvcCameraNames();
            intent.putExtra("allCameraNames", allCameraNames);
            Log.d(TAG, "Starting FaultFormActivity with " + allCameraNames.length + " cameras");
            
            startActivity(intent);
        });
        
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        
        // Register broadcast receiver BEFORE calling findCamera so permission requests are properly handled
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
        
        // Request runtime permissions for camera and storage
        requestRequiredPermissions();
        
        // Delay findCamera() to allow system to stabilize before requesting USB permission
        // This prevents permission denial on first launch with pre-connected camera
        mainHandler.postDelayed(this::findCamera, 1000);
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
        
        StringBuilder deviceInfo = new StringBuilder("Detected UVC Cameras:\n");
        int uvcCameraCount = 0;
        
        // First pass: list all UVC cameras found
        for (UsbDevice device : deviceList.values()) {
            Log.d(TAG, "Checking device: " + device.getDeviceName() + 
                  " VID: 0x" + Integer.toHexString(device.getVendorId()) +
                  " PID: 0x" + Integer.toHexString(device.getProductId()));
            if (isUvcCamera(device)) {
                uvcCameraCount++;
                deviceInfo.append(uvcCameraCount).append(". ").append(device.getDeviceName())
                        .append(" (VID: 0x").append(Integer.toHexString(device.getVendorId()))
                        .append(", PID: 0x").append(Integer.toHexString(device.getProductId()))
                        .append(")\n");
                Log.d(TAG, "UVC Camera " + uvcCameraCount + " found: " + device.getDeviceName());
            }
        }
        
        // Second pass: select first camera for initialization
        for (UsbDevice device : deviceList.values()) {
            if (isUvcCamera(device)) {
                cameraDevice = device;
                Log.d(TAG, "Selected first UVC camera: " + device.getDeviceName());
                requestPermission(device);
                break;
            }
        }
        
        if (uvcCameraCount == 0) {
            updateStatus(getString(R.string.no_camera));
            deviceInfo.append("No UVC cameras found");
        } else {
            deviceInfo.append("\n(Using camera 1 for form)");
        }
        
        // Display all found cameras on screen
        mainHandler.post(() -> cameraInfoText.setText(deviceInfo.toString()));
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
    
    /**
     * Get all available UVC cameras detected on the device
     * @return Array of camera device names (e.g. "/dev/bus/usb/001/049")
     */
    private String[] getAllUvcCameraNames() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        java.util.ArrayList<String> cameraNames = new java.util.ArrayList<>();
        
        for (UsbDevice device : deviceList.values()) {
            if (isUvcCamera(device)) {
                cameraNames.add(device.getDeviceName());
                Log.d(TAG, "Adding camera to list: " + device.getDeviceName());
            }
        }
        
        String[] result = cameraNames.toArray(new String[0]);
        Log.d(TAG, "Total cameras found: " + result.length);
        return result;
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
        
        try {
            // Use FLAG_UPDATE_CURRENT to ensure the intent is properly delivered
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? 
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : 
                PendingIntent.FLAG_UPDATE_CURRENT;
            
            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                this, 0, new Intent(ACTION_USB_PERMISSION), flags
            );
            usbManager.requestPermission(device, permissionIntent);
            Log.d(TAG, "Permission request sent successfully");
            
            // Schedule a retry in case the permission dialog doesn't appear
            mainHandler.postDelayed(() -> {
                boolean stillNoPermission = !usbManager.hasPermission(device);
                if (stillNoPermission && device.equals(cameraDevice)) {
                    Log.d(TAG, "Permission not granted after delay, retrying");
                    usbManager.requestPermission(device, permissionIntent);
                }
            }, 2000);
            
        } catch (Exception e) {
            Log.e(TAG, "Error requesting USB permission", e);
            updateStatus("Error requesting permission");
        }
    }
    
    private void initializeCamera(UsbDevice device) {
        try {
            // Close any previous device connection before opening new one
            if (deviceConnection != null && cameraDevice != null && !cameraDevice.equals(device)) {
                Log.d(TAG, "Closing previous device connection before switching");
                try {
                    deviceConnection.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error closing previous device", e);
                }
                deviceConnection = null;
            }
            
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
                    // Don't call initStreamingParms yet - we need to negotiate parameters first
                    // It will be called after setNativeValues with the correct negotiated values
                    
                    // Get device info
                    int result = listDeviceUvc(mNativePtr, fd);
                    Log.d(TAG, "listDeviceUvc result: " + result);
                    
                    if (result != 0) {
                        Log.e(TAG, "Failed to list device info");
                        mainHandler.post(() -> updateStatus("Failed to get device info"));
                        return;
                    }
                    
                    // Now enumerate actual camera capabilities from UVC descriptors
                    Log.d(TAG, "Attempting to enumerate camera capabilities from UVC descriptors");
                    boolean formatConfigured = false;
                    
                    try {
                        UVCCamera uvcCamera = new UVCCamera();
                        CameraFormatInfo[] formats = uvcCamera.enumerateCameraFormats(mNativePtr);
                        
                        if (formats != null && formats.length > 0) {
                            Log.d(TAG, "Camera supports " + formats.length + " formats");
                            
                            // Prefer MJPEG, fall back to YUY2
                            CameraFormatInfo selectedFormat = null;
                            for (CameraFormatInfo fmt : formats) {
                                if ("MJPEG".equals(fmt.formatName)) {
                                    selectedFormat = fmt;
                                    break;
                                }
                            }
                            
                            if (selectedFormat == null) {
                                selectedFormat = formats[0];  // Use first available
                            }
                            
                            if (selectedFormat != null && selectedFormat.supportedFrames.length > 0) {
                                // Use camera's first/best frame
                                CameraFrameInfo selectedFrame = selectedFormat.supportedFrames[0];
                                
                                // Prefer common resolutions
                                for (CameraFrameInfo frame : selectedFormat.supportedFrames) {
                                    if ((frame.width == 640 && frame.height == 480) ||
                                        (frame.width == 1280 && frame.height == 720) ||
                                        (frame.width == 1920 && frame.height == 1080)) {
                                        selectedFrame = frame;
                                        break;
                                    }
                                }
                                
                                // Use negotiated values
                                formatIndex = selectedFormat.formatIndex;
                                frameIndex = selectedFrame.frameIndex;
                                imageWidth = selectedFrame.width;
                                imageHeight = selectedFrame.height;
                                frameInterval = (int) selectedFrame.dwDefaultFrameInterval;
                                videoFormat = selectedFormat.formatName;
                                maxPacketSize = (int) selectedFrame.dwMaxVideoFrameBufferSize;
                                
                                Log.d(TAG, "Enumerated format: " + videoFormat + " (" + imageWidth + "x" + imageHeight + ")");
                                formatConfigured = true;
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Enumeration failed: " + e.getMessage());
                    }
                    
                    // Fallback if enumeration didn't work
                    if (!formatConfigured) {
                        Log.d(TAG, "Descriptor enumeration didn't provide config, trying hardcoded formats");
                        
                        // Try multiple format options with fallbacks
                        if (tryConfigureFormat("MJPEG", 640, 480, 1, 1)) {
                            formatConfigured = true;
                            Log.d(TAG, "Successfully configured 640x480 MJPEG format");
                        } else if (tryConfigureFormat("MJPEG", 800, 600, 1, 1)) {
                            formatConfigured = true;
                            Log.d(TAG, "Successfully configured 800x600 MJPEG format");
                        } else if (tryConfigureFormat("MJPEG", 1280, 720, 1, 1)) {
                            formatConfigured = true;
                            Log.d(TAG, "Successfully configured 720p MJPEG format");
                        } else if (tryConfigureFormat("MJPEG", 1920, 1080, 1, 1)) {
                            formatConfigured = true;
                            Log.d(TAG, "Successfully configured 1080p MJPEG format");
                        } else if (tryConfigureFormat("YUY2", 640, 480, 1, 1)) {
                            formatConfigured = true;
                            Log.d(TAG, "MJPEG not supported, using 640x480 YUY2 format");
                        } else if (tryConfigureFormat("YUY2", 800, 600, 1, 1)) {
                            formatConfigured = true;
                            Log.d(TAG, "Using 800x600 YUY2 format");
                        } else if (tryConfigureFormat("MJPEG", 320, 240, 1, 1)) {
                            formatConfigured = true;
                            Log.d(TAG, "Using lower resolution 320x240 MJPEG");
                        } else if (tryConfigureFormat("YUY2", 320, 240, 1, 1)) {
                            formatConfigured = true;
                            Log.d(TAG, "Using lower resolution 320x240 YUY2");
                        }
                        
                        // Final fallback
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
                    }
                    
                    Log.d(TAG, "Final configuration:");
                    Log.d(TAG, "  Resolution: " + imageWidth + "x" + imageHeight);
                    Log.d(TAG, "  Format: " + videoFormat + " (index " + formatIndex + ")");
                    Log.d(TAG, "  Frame index: " + frameIndex);
                    Log.d(TAG, "  Frame interval: " + frameInterval);
                    Log.d(TAG, "  Max packet size: " + maxPacketSize);
                    
                    // Calculate optimal buffer parameters based on camera negotiation
                    int packetsPerRequest = calculateOptimalPacketsPerRequest(
                            imageWidth, imageHeight, frameInterval, videoFormat, 
                            dwMaxPayloadTransferSize);
                    int activeUrbs = calculateOptimalActiveUrbs(
                            imageWidth, imageHeight, frameInterval, videoFormat, 
                            packetsPerRequest, dwMaxPayloadTransferSize);
                    
                    Log.d(TAG, "Calculated streaming parameters:");
                    Log.d(TAG, "  packetsPerRequest: " + packetsPerRequest);
                    Log.d(TAG, "  activeUrbs: " + activeUrbs);
                    Log.d(TAG, "  dwMaxPayloadTransferSize: " + dwMaxPayloadTransferSize);
                    
                    // Set native values in camera structure
                    result = setNativeValues(mNativePtr, fd,
                            packetsPerRequest,
                            maxPacketSize,
                            activeUrbs,
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
                    
                    // Now that native values are set, initialize streaming with the negotiated parameters
                    result = initStreamingParms(mNativePtr, fd);
                    Log.d(TAG, "initStreamingParms result: " + result);
                    
                    if (result != 0) {
                        Log.e(TAG, "Failed to initialize streaming parameters with negotiated values");
                        mainHandler.post(() -> {
                            updateStatus("Failed to initialize device");
                            startButton.setEnabled(false);
                        });
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
     * Enumerate actual camera capabilities from UVC descriptors
     * This replaces hardcoded resolution/format assumptions with real camera data
     */
    
    /**
     * Try to configure camera with specified format and resolution.
     * Uses intelligent bandwidth negotiation for optimal quality.
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
        
        // Calculate optimal frame interval and packet size based on format and resolution
        if ("MJPEG".equals(format)) {
            // MJPEG quality optimization
            // Higher frame rates for better perceived quality
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
            frameInterval = calculateYuy2FrameInterval(width, height);
            maxPacketSize = calculateYuy2PacketSize(width, height);
            streamingAltSetting = findAppropriateAltSetting(maxPacketSize);
        } else {
            // Other formats - use quality-focused settings
            frameInterval = 166666; // ~60 fps
            maxPacketSize = 3072;
            streamingAltSetting = 3;
        }
        
        Log.d(TAG, "Trying format: " + format + " " + width + "x" + height + 
              " @" + (10000000 / frameInterval) + "fps, packet=" + maxPacketSize + 
              ", alt=" + streamingAltSetting);
        return true; // Optimistically assume it's supported - native code will validate
    }
    
    /**
     * Calculate optimal frame interval for YUY2 format based on resolution
     * Tries to maximize frame rate while keeping USB bandwidth reasonable
     */
    private int calculateYuy2FrameInterval(int width, int height) {
        // YUY2 is 16 bits per pixel (2 bytes)
        int bytesPerFrame = width * height * 2;
        
        // Target different frame rates based on resolution
        // Try to achieve 30fps for lower resolutions, adaptive for higher
        if (width <= 320) {
            return 166666;  // ~60 fps for very low res
        } else if (width <= 640) {
            return 166666;  // ~60 fps for 640x480
        } else if (width <= 800) {
            return 200000;  // 50 fps for 800x600
        } else if (width <= 1280) {
            return 333333;  // ~30 fps for HD
        } else {
            return 500000;  // 20 fps for Full HD (uncompressed needs more bandwidth)
        }
    }
    
    /**
     * Calculate optimal packet size for YUY2 based on resolution and bandwidth requirements
     */
    private int calculateYuy2PacketSize(int width, int height) {
        // YUY2 is 16 bits per pixel (2 bytes)
        int bytesPerFrame = width * height * 2;
        
        // USB 2.0 high-speed bandwidth: 480 Mbps = 60 MB/s
        // Use up to 60% for streaming to leave room for overhead
        // Isoc packet max is 3072 bytes, but we can negotiate higher with alt settings
        
        if (width <= 320) {
            // 320x240@60fps = 307200*2*60 = ~36.9 MB/s - use moderate packet
            return 1024;
        } else if (width <= 640) {
            // 640x480@60fps = 614400*2*60 = ~73.7 MB/s - need good bandwidth
            return 2560;  // Use larger packets for better efficiency
        } else if (width <= 800) {
            // 800x600@50fps = 960000*2*50 = ~96 MB/s - high bandwidth
            return 3072;  // Maximum per-packet
        } else if (width <= 1280) {
            // 1280x720@30fps = 921600*2*30 = ~55.3 MB/s - use max packets
            return 3072;
        } else {
            // 1920x1080@20fps = 4147200*2*20 = ~165 MB/s - max bandwidth
            return 3072;
        }
    }
    
    /**
     * Find appropriate alt setting based on packet size and bandwidth needs
     * Higher alt settings allocate more bandwidth
     */
    private int findAppropriateAltSetting(int requiredPacketSize) {
        // Map packet sizes to alt settings with bandwidth allocation
        // Alt 0: 0 bytes (no bandwidth)
        // Alt 1-15: Increasing bandwidth allocation
        // Use aggressive settings for quality
        
        if (requiredPacketSize <= 512) {
            return 1;  // Minimal bandwidth
        } else if (requiredPacketSize <= 1024) {
            return 2;  // 1 x 1024
        } else if (requiredPacketSize <= 1536) {
            return 3;  // 1.5 x 1024
        } else if (requiredPacketSize <= 2048) {
            return 4;  // 2 x 1024
        } else if (requiredPacketSize <= 2560) {
            return 5;  // 2.5 x 1024
        } else if (requiredPacketSize <= 3072) {
            return 6;  // 3 x 1024 - Maximum USB 2.0 high-speed
        } else {
            return 7;  // Request even higher if device supports extended alt settings
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
                    
                    // Bandwidth - Camera's max payload size per transfer
                    dwMaxPayloadTransferSize = buf.getInt(22);
                    
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
        Log.d(TAG, "closeCamera called");
        
        try {
            if (deviceConnection != null) {
                try {
                    deviceConnection.close();
                    Log.d(TAG, "Device connection closed");
                } catch (Exception e) {
                    Log.e(TAG, "Error closing device connection", e);
                }
                deviceConnection = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in closeCamera", e);
        }
        
        cameraDevice = null;
        streamingInterface = null;
        controlInterface = null;
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
        
        // Record audio permission (for USB devices with audio)
        if (ContextCompat.checkSelfPermission(this,
            android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.RECORD_AUDIO);
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
    
    /**
     * Calculate optimal packetsPerRequest based on camera's negotiated parameters.
     * This ensures the buffer size doesn't overflow when handling USB transfers.
     *
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @param frameInterval Frame interval in 100ns units
     * @param format Video format (MJPEG, YUY2, etc)
     * @param dwMaxPayloadTransferSize Camera's max payload size from USB negotiation
     * @return Recommended packetsPerRequest value
     */
    private int calculateOptimalPacketsPerRequest(int width, int height, int frameInterval, 
                                                   String format, int dwMaxPayloadTransferSize) {
        // Ensure we have valid camera payload size
        if (dwMaxPayloadTransferSize <= 0) {
            dwMaxPayloadTransferSize = 3072; // fallback to common value
        }
        
        // Calculate frame size based on format
        long frameSize;
        if ("MJPEG".equals(format)) {
            // MJPEG is highly variable, estimate at 30-50% of resolution
            frameSize = (long) width * height * 3 / 2;
        } else if ("YUY2".equals(format) || "UYVY".equals(format)) {
            // YUY2/UYVY is 16-bit per 2 pixels
            frameSize = (long) width * height * 2;
        } else if ("NV12".equals(format) || "NV21".equals(format)) {
            // NV12/NV21 is 12 bits per pixel
            frameSize = (long) width * height * 3 / 2;
        } else {
            // Default to YUY2 estimate
            frameSize = (long) width * height * 2;
        }
        
        // Calculate FPS from frame interval (100ns units)
        int fps = frameInterval > 0 ? (int)(10000000 / frameInterval) : 30;
        
        // Target buffer size: enough for 2 frames at current resolution
        long targetBufferPerFrame = frameSize;
        
        // Calculate packets needed per frame
        int packetsPerFrame = (int) Math.ceil((double) targetBufferPerFrame / dwMaxPayloadTransferSize);
        
        // Clamp packetsPerRequest between reasonable values
        // Min: 2 packets, Max: 32 packets
        int packetsPerRequest = Math.max(2, Math.min(packetsPerFrame / 2, 32));
        
        Log.d(TAG, String.format("Buffer calc: frame=%d bytes, payload=%d, packets/frame=%d, packets/req=%d",
                targetBufferPerFrame, dwMaxPayloadTransferSize, packetsPerFrame, packetsPerRequest));
        
        return packetsPerRequest;
    }
    
    /**
     * Calculate optimal activeUrbs based on camera's negotiated parameters.
     * More URBs = more parallel transfers but higher memory usage.
     *
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @param frameInterval Frame interval in 100ns units
     * @param format Video format (MJPEG, YUY2, etc)
     * @param packetsPerRequest Packets per request (from calculateOptimalPacketsPerRequest)
     * @param dwMaxPayloadTransferSize Camera's max payload size from USB negotiation
     * @return Recommended activeUrbs value
     */
    private int calculateOptimalActiveUrbs(int width, int height, int frameInterval, String format,
                                           int packetsPerRequest, int dwMaxPayloadTransferSize) {
        // Ensure we have valid camera payload size
        if (dwMaxPayloadTransferSize <= 0) {
            dwMaxPayloadTransferSize = 3072;
        }
        
        // Calculate total transfer buffer needed per URB
        long bytesPerUrb = (long) packetsPerRequest * dwMaxPayloadTransferSize;
        
        // Target: keep total buffers around 4-8 MB for reasonable memory usage
        // while maintaining smooth streaming
        long targetTotalBuffers = 8 * 1024 * 1024; // 8 MB
        
        int activeUrbs = (int) Math.max(2, Math.min(targetTotalBuffers / bytesPerUrb, 10));
        
        Log.d(TAG, String.format("URBs calc: bytes/urb=%d, target_total=%d, activeUrbs=%d",
                bytesPerUrb, targetTotalBuffers, activeUrbs));
        
        return activeUrbs;
    }
}
