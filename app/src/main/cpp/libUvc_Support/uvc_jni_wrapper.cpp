//
// JNI wrapper for MinimalUVCCamera - adapted from reference implementation
//

#include "uvc_support.h"
#include "UVC_Camera_Saki/UVCPreview.h"

#include <jni.h>
#include "libusb.h"
#include "../libuvc/include/libuvc/libuvc.h"
#include "../libuvc/include/utilbase.h"


/**
 * set the value into the long field
 */
static jlong setField_long(JNIEnv *env, jobject java_obj, const char *field_name, jlong val) {
    jclass clazz = env->GetObjectClass(java_obj);
    jfieldID field = env->GetFieldID(clazz, field_name, "J");
    if (LIKELY(field))
        env->SetLongField(java_obj, field, val);
    else {
        LOGE("setField_long:field '%s' not found", field_name);
    }
#ifdef ANDROID_NDK
    env->DeleteLocalRef(clazz);
#endif
    return val;
}


// Create native camera structure
extern "C" JNIEXPORT long JNICALL Java_com_minimal_uvccamera_UVCCamera_nativeCreate
        (JNIEnv *env, jobject obj, ID_TYPE camera_pointer) {

    uvc_camera_t *camera = new uvc_camera_t();

    // Initialize pointers to NULL to avoid issues with freeing
    camera->frameFormat = NULL;
    camera->mUsbFs = NULL;

    setField_long(env, obj, "mNativePtr", reinterpret_cast<ID_TYPE>(camera));

    camera_pointer = reinterpret_cast<ID_TYPE>(camera);

    return reinterpret_cast<ID_TYPE>(camera);
}


// Prepare stream
extern "C" JNIEXPORT jint JNICALL Java_com_minimal_uvccamera_UVCCamera_PreviewPrepareStream
        (JNIEnv *env, jobject obj, ID_TYPE mNativePtr, jobject jSurface, jobject jIFrameCallback) {

    uvc_camera_t *camera_pointer = reinterpret_cast<uvc_camera_t *>(mNativePtr);

    LOGD("camera_pointer->imageWidth = %d", camera_pointer->imageWidth);

    ANativeWindow *preview_window = NULL;
    if (jSurface != NULL) {
        preview_window = jSurface ? ANativeWindow_fromSurface(env, jSurface) : NULL;
        LOGD("mCaptureWindow, JniSetSurfaceView");
    }

    camera_pointer->preview_pointer = create_UVCPreview(camera_pointer->camera_deviceHandle, camera_pointer->preview_pointer);
    int result = -1;
    
    // Calculate FPS from frame interval (in 100ns units)
    // fps = 10,000,000 / frameInterval
    int fps = (camera_pointer->camFrameInterval > 0) ? 
              (10000000 / camera_pointer->camFrameInterval) : 30;
    
    LOGD("Setting up preview with %dx%d @ %d fps, format=%s", 
         camera_pointer->imageWidth, camera_pointer->imageHeight, fps, camera_pointer->frameFormat);
    
    if(set_preview_size_random(camera_pointer->preview_pointer, 
                               camera_pointer->imageWidth, camera_pointer->imageHeight, 
                               fps, fps, 0,  // min_fps, max_fps, bandwidth
                               camera_pointer->activeUrbs, camera_pointer->packetsPerRequest, 
                               camera_pointer->camStreamingAltSetting, camera_pointer->maxPacketSize,
                               camera_pointer->camFormatIndex, camera_pointer->camFrameIndex, 
                               camera_pointer->camFrameInterval, camera_pointer->frameFormat,
                               camera_pointer->imageWidth, camera_pointer->imageHeight, 
                               0 /* stream bit set */) == 0) {
        result = set_preview_display(camera_pointer->preview_pointer, preview_window);
    } else {
        LOGE("set_preview_size_random failed");
        return -1;
    }
    LOGD("Setting Frame Callback");

    jobject frame_callback_obj = env->NewGlobalRef(jIFrameCallback);

    int pixel_format;
    if (strcmp(camera_pointer->frameFormat, "MJPEG") == 0) pixel_format = PIXEL_FORMAT_MJPEG;
    else if (strcmp(camera_pointer->frameFormat, "YUY2") == 0) pixel_format = PIXEL_FORMAT_YUY2;
    else if (strcmp(camera_pointer->frameFormat, "UYVY") == 0) pixel_format = PIXEL_FORMAT_UYUY;
    else if (strcmp(camera_pointer->frameFormat, "NV21") == 0) pixel_format = PIXEL_FORMAT_NV21;
    else pixel_format = 0;

    LOGD("Pixel Format = %d", pixel_format);

    result = setFrameCallback(camera_pointer->preview_pointer, env, frame_callback_obj, pixel_format);
    LOGD("setFrameCallback returned: %d", result);

    result = setJavaVM(camera_pointer->preview_pointer, env, obj);

    return result;
}


// Start stream
extern "C" JNIEXPORT jint JNICALL Java_com_minimal_uvccamera_UVCCamera_PreviewStartStream
        (JNIEnv *env, jobject obj, ID_TYPE mNativePtr) {
    int result = -1;
    uvc_camera_t *camera_pointer = reinterpret_cast<uvc_camera_t *>(mNativePtr);
    result = startPreview(camera_pointer->preview_pointer);
    return result;
}


// Stop stream
extern "C" JNIEXPORT jint JNICALL Java_com_minimal_uvccamera_UVCCamera_PreviewStopStream
        (JNIEnv *env, jobject obj, ID_TYPE mNativePtr) {
    int result = -1;
    uvc_camera_t *camera_pointer = reinterpret_cast<uvc_camera_t *>(mNativePtr);
    result = stopPreview(camera_pointer->preview_pointer);
    return result;
}


// Capture picture
extern "C" JNIEXPORT jint JNICALL Java_com_minimal_uvccamera_UVCCamera_PreviewCapturePicture
        (JNIEnv *env, jobject obj, ID_TYPE mNativePtr) {
    int result = -1;
    uvc_camera_t *camera_pointer = reinterpret_cast<uvc_camera_t *>(mNativePtr);
    result = capturePicture(camera_pointer->preview_pointer);
    return result;
}


// Set native values - wrapper for MainActivity to configure camera
extern "C" JNIEXPORT jint JNICALL Java_com_minimal_uvccamera_MainActivity_setNativeValues
        (JNIEnv *env, jobject obj, ID_TYPE mNativePtr, jint fd,
         jint packetsPerRequest, jint maxPacketSize, jint activeUrbs,
         jint camStreamingAltSetting, jint camFormatIndex,
         jint camFrameIndex, jint camFrameInterval,
         jint imageWidth, jint imageHeight,
         jint camStreamingEndpoint, jint camStreamingInterfaceNumber,
         jstring frameFormat, jint numberOfAutoFrames,
         jint bcdUVC_int, jint lowAndroid) {

    uvc_camera_t *camera = reinterpret_cast<uvc_camera_t *>(mNativePtr);
    
    const char *format_str = env->GetStringUTFChars(frameFormat, 0);
    
    int result = set_the_native_Values(camera, fd, packetsPerRequest, maxPacketSize, activeUrbs,
                                       camStreamingAltSetting, camFormatIndex, camFrameIndex,
                                       camFrameInterval, imageWidth, imageHeight,
                                       camStreamingEndpoint, camStreamingInterfaceNumber,
                                       format_str, numberOfAutoFrames, bcdUVC_int, lowAndroid);
    
    env->ReleaseStringUTFChars(frameFormat, format_str);
    
    return result;
}


// Initialize streaming parameters - wraps FD with libusb
extern "C" JNIEXPORT jint JNICALL Java_com_minimal_uvccamera_MainActivity_initStreamingParms
        (JNIEnv *env, jobject obj, ID_TYPE mNativePtr, jint fd) {
    
    uvc_camera_t *camera = reinterpret_cast<uvc_camera_t *>(mNativePtr);
    
    int result = initStreamingParms(camera, fd);
    
    return result;
}

extern "C" JNIEXPORT jint JNICALL Java_com_minimal_uvccamera_MainActivity_listDeviceUvc
        (JNIEnv *env, jobject obj, ID_TYPE mNativePtr, jint fd) {
    
    uvc_camera_t *camera = reinterpret_cast<uvc_camera_t *>(mNativePtr);
    
    struct uvc_device_info *info = listDeviceUvc(camera, fd);
    
    return (info != NULL) ? 0 : -1;
}

// Reset camera state to allow switching cameras
extern "C" JNIEXPORT void JNICALL Java_com_minimal_uvccamera_MainActivity_resetCameraState
        (JNIEnv *env, jobject obj) {
    
    resetCameraState();
}

// Close camera device to release USB resources
extern "C" JNIEXPORT void JNICALL Java_com_minimal_uvccamera_MainActivity_closeCameraDevice
        (JNIEnv *env, jobject obj, ID_TYPE mNativePtr) {
    
    uvc_camera_t *camera = reinterpret_cast<uvc_camera_t *>(mNativePtr);
    closeCameraDevice(camera);
}

// Check if camera device is properly closed
extern "C" JNIEXPORT jboolean JNICALL Java_com_minimal_uvccamera_MainActivity_isCameraDeviceClosed
        (JNIEnv *env, jobject obj, ID_TYPE mNativePtr) {
    
    uvc_camera_t *camera = reinterpret_cast<uvc_camera_t *>(mNativePtr);
    return isCameraDeviceClosed(camera) ? JNI_TRUE : JNI_FALSE;
}

// Check if stream is stopped
extern "C" JNIEXPORT jboolean JNICALL Java_com_minimal_uvccamera_MainActivity_isStreamStopped
        (JNIEnv *env, jobject obj, ID_TYPE mNativePtr) {
    
    uvc_camera_t *camera = reinterpret_cast<uvc_camera_t *>(mNativePtr);
    return isStreamStopped(camera) ? JNI_TRUE : JNI_FALSE;
}
