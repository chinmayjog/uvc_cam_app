package com.minimal.uvccamera;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;

/**
 * Simple camera manager - finds next available UVC camera and initializes it.
 * Call findCamera() to detect camera, then results are available in cameraDevice.
 */
public class UvcCameraManager {
    private static final String TAG = "UvcCameraManager";

    private final Context context;
    private final Handler mainHandler;
    private final UsbManager usbManager;
    private final String permissionAction;

    public UsbDevice cameraDevice;
    public UsbDeviceConnection deviceConnection;
    public UsbInterface controlInterface;
    public UsbInterface streamingInterface;
    public UsbEndpoint streamingEndpoint;

    // Camera config
    public int imageWidth = 0;
    public int imageHeight = 0;
    public int formatIndex = 1;
    public int frameIndex = 1;
    public int frameInterval = 0;
    public int maxPacketSize = 0;
    public int streamingAltSetting = 1;
    public int streamingInterfaceNumber = 1;
    public String videoFormat = "UNKNOWN";

    private String lastSelectedDeviceName = null;
    private boolean permissionRequestInFlight = false;

    public UvcCameraManager(Context context, Handler mainHandler, String permissionAction) {
        this(context, mainHandler, permissionAction, null);
    }

    public UvcCameraManager(Context context, Handler mainHandler, String permissionAction, String lastDeviceName) {
        this.context = context;
        this.mainHandler = mainHandler;
        this.permissionAction = permissionAction;
        this.lastSelectedDeviceName = lastDeviceName;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    public void findNextCamera() {
        Log.d(TAG, "Looking for next camera (last: " + (lastSelectedDeviceName != null ? lastSelectedDeviceName : "none") + ")");
        
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        if (deviceList == null) {
            Log.e(TAG, "No devices found");
            return;
        }

        for (UsbDevice device : deviceList.values()) {
            if (!isUvcCamera(device)) continue;

            // Skip previously selected camera
            if (lastSelectedDeviceName != null && device.getDeviceName().equals(lastSelectedDeviceName)) {
                Log.d(TAG, "Skipping: " + device.getDeviceName());
                continue;
            }

            // Check permission
            if (!usbManager.hasPermission(device)) {
                Log.d(TAG, "No permission for: " + device.getDeviceName());
                requestPermission(device);
                return;
            }

            Log.d(TAG, "Found camera: " + device.getDeviceName());
            cameraDevice = device;
            lastSelectedDeviceName = device.getDeviceName();
            return;
        }

        Log.w(TAG, "No more cameras available");
    }

    public void handlePermissionResult(Intent intent) {
        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        permissionRequestInFlight = false;
        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            if (device != null) {
                Log.d(TAG, "Permission granted: " + device.getDeviceName());
                cameraDevice = device;
                lastSelectedDeviceName = device.getDeviceName();
            }
        } else {
            Log.w(TAG, "Permission denied");
        }
    }

    private void requestPermission(UsbDevice device) {
        if (permissionRequestInFlight) {
            Log.d(TAG, "Permission request already in flight");
            return;
        }
        permissionRequestInFlight = true;
        
        try {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ?
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT :
                PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getBroadcast(context, 0, new Intent(permissionAction), flags);
            Log.d(TAG, "Requesting permission for: " + device.getDeviceName());
            usbManager.requestPermission(device, pi);
        } catch (Exception e) {
            permissionRequestInFlight = false;
            Log.e(TAG, "Permission request failed", e);
        }
    }

    private boolean isUvcCamera(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            if (device.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) {
                return true;
            }
        }
        return false;
    }

    public void close() {
        if (deviceConnection != null) {
            deviceConnection.close();
        }
    }
}
