package com.minimal.uvccamera;

import android.view.Surface;

/**
 * JNI wrapper for UVC camera operations (matching reference app architecture)
 */
public class UVCCamera {
    
    static {
        System.loadLibrary("usb1.0");
        System.loadLibrary("jpeg9");
        System.loadLibrary("yuv");
        System.loadLibrary("uvc");
        System.loadLibrary("uvc_preview");
        System.loadLibrary("Uvc_Support");
    }
    
    // Native camera pointer
    private long mNativePtr;
    
    /**
     * Create native camera structure
     * @return pointer to native camera structure
     */
    public native long nativeCreate(long cameraPointer);
    
    /**
     * Prepare stream with surface and callback
     */
    public native int PreviewPrepareStream(long cameraPtr, Surface surface, IFrameCallback callback);
    
    /**
     * Start the preview stream
     */
    public native int PreviewStartStream(long cameraPtr);
    
    /**
     * Stop the preview stream
     */
    public native int PreviewStopStream(long cameraPtr);
    
    /**
     * Capture a picture
     */
    public native int PreviewCapturePicture(long cameraPtr);
    
    /**
     * Frame callback interface
     */
    public interface IFrameCallback {
        void onFrame(byte[] frameData);
    }
    
    public long getNativePtr() {
        return mNativePtr;
    }
    
    public void setNativePtr(long ptr) {
        this.mNativePtr = ptr;
    }
    
    /**
     * Create and initialize camera
     */
    public long create() {
        mNativePtr = nativeCreate(0);
        return mNativePtr;
    }
}
