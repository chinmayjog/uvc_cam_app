package com.minimal.uvccamera;

import android.app.AlertDialog;
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
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.content.pm.PackageManager;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

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
    private View cameraButtonGrid;
    private AlertDialog multiCameraDialog;

    // USB Camera
    private UsbManager usbManager;
    private UsbDevice cameraDevice;
    private UsbDeviceConnection deviceConnection;
    private UsbInterface controlInterface;
    private UsbInterface streamingInterface;
    private UsbEndpoint streamingEndpoint;

    // Camera Configuration (auto-detected from device)
    private int imageWidth = 0;
    private int imageHeight = 0;
    private int formatIndex = 1;
    private int frameIndex = 1;
    private int frameInterval = 0;
    private int maxPacketSize = 0;
    private int streamingAltSetting = 1;
    private int streamingInterfaceNumber = 1;
    private String videoFormat = "UNKNOWN";

    // UVC Camera (JNI)
    private UVCCamera uvcCamera;
    private Handler mainHandler;

    private native int setNativeValues(long cameraPtr, int fd,
                                      int packetsPerRequest, int maxPacketSize, int activeUrbs,
                                      int camStreamingAltSetting, int camFormatIndex,
                                      int camFrameIndex, int camFrameInterval,
                                      int imageWidth, int imageHeight,
                                      int camStreamingEndpoint, int camStreamingInterfaceNumber,
                                      String frameFormat, int numberOfAutoFrames,
                                      int bcdUVC, int lowAndroid);

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
                        Log.d(TAG, "USB permission denied, will retry");
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
                    if (device != null) {
                        Log.d(TAG, "USB device detached: " + device.getDeviceName());
                        if (device.equals(cameraDevice)) {
                            closeCamera();
                        }
                        dismissMultiCameraDialog();
                        hideCameraGrid();
                        updateStatus("Camera disconnected");
                        mainHandler.postDelayed(MainActivity.this::findCamera, 500);
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (device != null && isUvcCamera(device)) {
                        Log.d(TAG, "Detected UVC camera attachment");
                        mainHandler.postDelayed(MainActivity.this::findCamera, 500);
                    }
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.activity_main);

        mainHandler = new Handler(Looper.getMainLooper());

        uvcCamera = new UVCCamera();
        mNativePtr = uvcCamera.create();
        Log.d(TAG, "Native camera pointer created: 0x" + Long.toHexString(mNativePtr));

        statusText = findViewById(R.id.statusText);
        cameraInfoText = findViewById(R.id.cameraInfoText);
        cameraButtonGrid = findViewById(R.id.cameraButtonGrid);

        Button btn1 = findViewById(R.id.btn_camera1);
        Button btn2 = findViewById(R.id.btn_camera2);
        Button btn3 = findViewById(R.id.btn_camera3);
        Button btn4 = findViewById(R.id.btn_camera4);

        btn1.setOnClickListener(v -> launchCameraActivity(1));
        btn2.setOnClickListener(v -> launchCameraActivity(2));
        btn3.setOnClickListener(v -> launchCameraActivity(3));
        btn4.setOnClickListener(v -> launchCameraActivity(4));

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

        requestRequiredPermissions();
        mainHandler.postDelayed(this::findCamera, 1000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // When returning from CameraActivity, re-show the grid if camera is still configured
        if (mNativePtr != 0 && cameraDevice != null) {
            showCameraGrid();
        }
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
    }

    private void findCamera() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        Log.d(TAG, "USB devices found: " + deviceList.size());

        List<UsbDevice> uvcCameras = new ArrayList<>();
        for (UsbDevice device : deviceList.values()) {
            Log.d(TAG, "Checking device: " + device.getDeviceName() +
                  " VID: 0x" + Integer.toHexString(device.getVendorId()) +
                  " PID: 0x" + Integer.toHexString(device.getProductId()));
            if (isUvcCamera(device)) {
                uvcCameras.add(device);
            }
        }

        Log.d(TAG, "UVC cameras found: " + uvcCameras.size());

        if (uvcCameras.isEmpty()) {
            updateStatus(getString(R.string.no_camera));
        } else if (uvcCameras.size() > 1) {
            updateStatus(getString(R.string.multiple_cameras_detected));
            showMultiCameraDialog();
        } else {
            UsbDevice device = uvcCameras.get(0);
            cameraDevice = device;
            Log.d(TAG, "Single UVC camera found: " + device.getDeviceName());
            requestPermission(device);
        }
    }

    private void showMultiCameraDialog() {
        if (multiCameraDialog != null && multiCameraDialog.isShowing()) return;
        mainHandler.post(() -> {
            multiCameraDialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.multiple_cameras_title))
                .setMessage(getString(R.string.multiple_cameras_msg))
                .setCancelable(false)
                .create();
            multiCameraDialog.show();
        });
    }

    private void dismissMultiCameraDialog() {
        mainHandler.post(() -> {
            if (multiCameraDialog != null && multiCameraDialog.isShowing()) {
                multiCameraDialog.dismiss();
                multiCameraDialog = null;
            }
        });
    }

    private void showCameraGrid() {
        mainHandler.post(() -> {
            if (cameraButtonGrid != null) cameraButtonGrid.setVisibility(View.VISIBLE);
            if (cameraInfoText != null) cameraInfoText.setVisibility(View.VISIBLE);
        });
    }

    private void hideCameraGrid() {
        mainHandler.post(() -> {
            if (cameraButtonGrid != null) cameraButtonGrid.setVisibility(View.GONE);
            if (cameraInfoText != null) cameraInfoText.setVisibility(View.GONE);
        });
    }

    private void launchCameraActivity(int cameraNumber) {
        clearAppCache();
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
        intent.putExtra("mNativePtr", mNativePtr);
        intent.putExtra("cameraNumber", cameraNumber);
        startActivity(intent);
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

    private boolean isUvcCamera(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) {
                Log.d(TAG, "Found VIDEO class interface with subclass: " + iface.getInterfaceSubclass() +
                      " endpoints: " + iface.getEndpointCount());
                return true;
            }
        }
        return false;
    }

    private void requestPermission(UsbDevice device) {
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
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ?
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT :
                PendingIntent.FLAG_UPDATE_CURRENT;

            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                this, 0, new Intent(ACTION_USB_PERMISSION), flags
            );
            usbManager.requestPermission(device, permissionIntent);
            Log.d(TAG, "Permission request sent successfully");

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

            deviceConnection = usbManager.openDevice(device);
            if (deviceConnection == null) {
                updateStatus("Cannot open device");
                Log.e(TAG, "Failed to open USB device");
                return;
            }

            int fd = deviceConnection.getFileDescriptor();
            Log.d(TAG, "USB device opened with FD: " + fd);

            streamingInterface = findInterface(device, SC_VIDEOSTREAMING);

            if (streamingInterface == null) {
                Log.e(TAG, "Streaming interface not found");
                updateStatus("Camera interface not found");
                return;
            }

            streamingInterfaceNumber = streamingInterface.getId();
            Log.d(TAG, "Found streaming interface: " + streamingInterfaceNumber);

            updateStatus("Detecting camera capabilities...");

            new Thread(() -> {
                try {
                    boolean formatConfigured = false;

                    if (tryConfigureFormat("MJPEG", 640, 480, 1, 1)) {
                        formatConfigured = true;
                        Log.d(TAG, "Successfully configured 640x480 MJPEG format");
                    }
                    if (!formatConfigured && tryConfigureFormat("MJPEG", 800, 600, 1, 1)) {
                        formatConfigured = true;
                        Log.d(TAG, "Successfully configured 800x600 MJPEG format");
                    }
                    if (!formatConfigured && tryConfigureFormat("MJPEG", 1280, 720, 1, 1)) {
                        formatConfigured = true;
                        Log.d(TAG, "Successfully configured 720p MJPEG format");
                    }
                    if (!formatConfigured && tryConfigureFormat("MJPEG", 1920, 1080, 1, 1)) {
                        formatConfigured = true;
                        Log.d(TAG, "Successfully configured 1080p MJPEG format");
                    }
                    if (!formatConfigured && tryConfigureFormat("YUY2", 640, 480, 1, 1)) {
                        formatConfigured = true;
                        Log.d(TAG, "MJPEG not supported, using 640x480 YUY2 format");
                    }
                    if (!formatConfigured && tryConfigureFormat("YUY2", 800, 600, 1, 1)) {
                        formatConfigured = true;
                        Log.d(TAG, "Using 800x600 YUY2 format");
                    }
                    if (!formatConfigured && tryConfigureFormat("MJPEG", 320, 240, 1, 1)) {
                        formatConfigured = true;
                        Log.d(TAG, "Using lower resolution 320x240 MJPEG");
                    }
                    if (!formatConfigured && tryConfigureFormat("YUY2", 320, 240, 1, 1)) {
                        formatConfigured = true;
                        Log.d(TAG, "Using lower resolution 320x240 YUY2");
                    }

                    if (!formatConfigured) {
                        Log.w(TAG, "Could not verify format support, using MJPEG defaults");
                        imageWidth = 640;
                        imageHeight = 480;
                        formatIndex = 1;
                        frameIndex = 1;
                        frameInterval = 333333;
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

                    int result = setNativeValues(mNativePtr, fd,
                            8,
                            maxPacketSize,
                            5,
                            streamingAltSetting,
                            formatIndex,
                            frameIndex,
                            frameInterval,
                            imageWidth,
                            imageHeight,
                            streamingInterfaceNumber,
                            streamingInterfaceNumber,
                            videoFormat,
                            1,
                            0,
                            0
                    );
                    Log.d(TAG, "setNativeValues result: " + result);

                    result = initStreamingParms(mNativePtr, fd);
                    Log.d(TAG, "initStreamingParms result: " + result);

                    if (result != 0) {
                        Log.e(TAG, "Failed to initialize streaming parameters");
                        mainHandler.post(() -> updateStatus("Failed to initialize device"));
                        return;
                    }

                    result = listDeviceUvc(mNativePtr, fd);
                    Log.d(TAG, "listDeviceUvc result: " + result);

                    if (result != 0) {
                        Log.e(TAG, "Failed to list device info");
                        mainHandler.post(() -> updateStatus("Failed to get device info"));
                        return;
                    }

                    mainHandler.post(() -> {
                        updateStatus(getString(R.string.ready));
                        String info = "Camera: " + device.getProductName() + "\n" +
                                     "Resolution: " + imageWidth + "x" + imageHeight + "\n" +
                                     "Format: " + videoFormat;
                        cameraInfoText.setText(info);
                        cameraInfoText.setVisibility(View.VISIBLE);
                        showCameraGrid();
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

    private boolean tryConfigureFormat(String format, int width, int height, int fmtIdx, int frmIdx) {
        imageWidth = width;
        imageHeight = height;
        formatIndex = fmtIdx;
        frameIndex = frmIdx;
        videoFormat = format;

        if ("MJPEG".equals(format)) {
            if (width <= 320) {
                frameInterval = 66666;
                maxPacketSize = 1024;
                streamingAltSetting = 1;
            } else if (width <= 640) {
                frameInterval = 100000;
                maxPacketSize = 2048;
                streamingAltSetting = 2;
            } else if (width <= 800) {
                frameInterval = 111111;
                maxPacketSize = 2560;
                streamingAltSetting = 2;
            } else if (width <= 1280) {
                frameInterval = 166666;
                maxPacketSize = 3072;
                streamingAltSetting = 3;
            } else {
                frameInterval = 200000;
                maxPacketSize = 3072;
                streamingAltSetting = 4;
            }
        } else if ("YUY2".equals(format)) {
            frameInterval = calculateYuy2FrameInterval(width, height);
            maxPacketSize = calculateYuy2PacketSize(width, height);
            streamingAltSetting = findAppropriateAltSetting(maxPacketSize);
        } else {
            frameInterval = 166666;
            maxPacketSize = 3072;
            streamingAltSetting = 3;
        }

        Log.d(TAG, "Trying format: " + format + " " + width + "x" + height +
              " @" + (10000000 / frameInterval) + "fps, packet=" + maxPacketSize +
              ", alt=" + streamingAltSetting);
        return true;
    }

    private int calculateYuy2FrameInterval(int width, int height) {
        if (width <= 320) {
            return 166666;
        } else if (width <= 640) {
            return 166666;
        } else if (width <= 800) {
            return 200000;
        } else if (width <= 1280) {
            return 333333;
        } else {
            return 500000;
        }
    }

    private int calculateYuy2PacketSize(int width, int height) {
        if (width <= 320) {
            return 1024;
        } else if (width <= 640) {
            return 2560;
        } else if (width <= 800) {
            return 3072;
        } else if (width <= 1280) {
            return 3072;
        } else {
            return 3072;
        }
    }

    private int findAppropriateAltSetting(int requiredPacketSize) {
        if (requiredPacketSize <= 512) {
            return 1;
        } else if (requiredPacketSize <= 1024) {
            return 2;
        } else if (requiredPacketSize <= 1536) {
            return 3;
        } else if (requiredPacketSize <= 2048) {
            return 4;
        } else if (requiredPacketSize <= 2560) {
            return 5;
        } else if (requiredPacketSize <= 3072) {
            return 6;
        } else {
            return 7;
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
                byte[] probeData = new byte[26];
                ByteBuffer buf = ByteBuffer.wrap(probeData).order(ByteOrder.LITTLE_ENDIAN);

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

                    byte bmHint = probeData[0];
                    byte bFormatIndex = probeData[2];
                    byte bFrameIndex = probeData[3];

                    int dwFrameInterval = buf.getInt(4);
                    int wKeyFrameRate = buf.getShort(8) & 0xFFFF;
                    int wWidth = buf.getShort(10) & 0xFFFF;
                    int wHeight = buf.getShort(12) & 0xFFFF;
                    int dwBitRate = buf.getInt(14);
                    int dwMaxVideoFrameSize = buf.getInt(18);
                    int dwMaxPayloadTransferSize = buf.getInt(22);

                    if (bFormatIndex > 0) formatIndex = bFormatIndex & 0xFF;
                    if (bFrameIndex > 0) frameIndex = bFrameIndex & 0xFF;
                    if (wWidth > 0) imageWidth = wWidth;
                    if (wHeight > 0) imageHeight = wHeight;
                    if (dwFrameInterval > 0) frameInterval = dwFrameInterval;

                    if (formatIndex == 1) videoFormat = "MJPEG";
                    else if (formatIndex == 2) videoFormat = "YUY2";
                    else if (formatIndex == 3) videoFormat = "NV12";
                    else videoFormat = "Format#" + formatIndex;

                    Log.d(TAG, String.format("PROBE GET_CUR: %dx%d @%d fps, format=%s",
                        imageWidth, imageHeight,
                        frameInterval > 0 ? 10000000 / frameInterval : 0,
                        videoFormat));
                } else {
                    Log.w(TAG, "PROBE GET_CUR failed with code: " + len);
                }

                if (imageWidth <= 0 || imageHeight <= 0 || frameInterval <= 0) {
                    Log.e(TAG, "Auto-detection failed - required parameters not set by device");
                    mainHandler.post(() -> updateStatus("Camera does not report resolution"));
                    return;
                }

                ByteBuffer setBuf = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN);
                setBuf.put(2, (byte) formatIndex);
                setBuf.put(3, (byte) frameIndex);
                setBuf.putInt(4, frameInterval);
                setBuf.putShort(10, (short) imageWidth);
                setBuf.putShort(12, (short) imageHeight);

                len = deviceConnection.controlTransfer(
                    RT_CLASS_INTERFACE_SET, SET_CUR,
                    VS_PROBE_CONTROL << 8, streamingInterface.getId(),
                    setBuf.array(), 26, 1000
                );
                if (len < 0) Log.w(TAG, "PROBE SET_CUR returned: " + len);

                len = deviceConnection.controlTransfer(
                    RT_CLASS_INTERFACE_SET, SET_CUR,
                    VS_COMMIT_CONTROL << 8, streamingInterface.getId(),
                    setBuf.array(), 26, 1000
                );

                if (len >= 0) {
                    mainHandler.post(() -> {
                        updateStatus(getString(R.string.ready));
                        String info = String.format("Resolution: %dx%d\nFPS: %d\nFormat: %s",
                            imageWidth, imageHeight,
                            frameInterval > 0 ? 10000000 / frameInterval : 0,
                            videoFormat);
                        cameraInfoText.setText(info);
                        cameraInfoText.setVisibility(View.VISIBLE);
                        showCameraGrid();
                    });
                    Log.d(TAG, "Configuration committed successfully");
                } else {
                    mainHandler.post(() -> updateStatus("Camera configuration failed"));
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
        List<String> permissionsToRequest = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this,
            android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.CAMERA);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_IMAGES);
            }
            if (ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_VIDEO);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                if (ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(android.Manifest.permission.READ_EXTERNAL_STORAGE);
                }
            }
        }

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
                    if (deniedPermissions.length() > 0) deniedPermissions.append(", ");
                    deniedPermissions.append(permissions[i].substring(permissions[i].lastIndexOf('.') + 1));
                }
            }
            if (deniedPermissions.length() > 0) {
                Toast.makeText(this, "Permissions denied: " + deniedPermissions, Toast.LENGTH_LONG).show();
                Log.w(TAG, "Permissions denied: " + deniedPermissions);
            } else {
                Log.d(TAG, "All permissions granted");
            }
        }
    }
}
