# Implementation: Using Negotiated Values Instead of Hardcoded Ones

## Overview
Instead of using hardcoded resolution/format assumptions, the app now:
1. Enumerates actual camera capabilities from UVC Class Descriptors
2. Uses camera's format indices, frame indices, and frame configurations
3. Falls back to hardcoded values only if enumeration fails

## Changes Made

### 1. New Java Classes
Created `CameraFormatInfo.java` and `CameraFrameInfo.java` to represent camera capabilities:
- `CameraFormatInfo`: Format name, index, default frame index, supported frames
- `CameraFrameInfo`: Resolution, frame index, frame rate range, buffer size requirements

### 2. New JNI Function
Added `enumerateCameraFormats()` in `libuvc_support.c`:
- Iterates through UVC streaming interface descriptors
- Extracts format and frame descriptors from libuvc's internal structures
- Maps format GUIDs to names (MJPEG, YUY2, NV12, UYVY)
- Returns all supported format/frame combinations to Java

### 3. New Java Method
Added `enumerateCameraCapabilities()` in MainActivity:
- Calls JNI enumeration function
- Logs all camera capabilities
- Selects optimal format based on actual camera support
- Sets format index, frame index, resolution, frame interval from descriptors

### 4. Modified Initialization
Updated camera initialization to:
1. **First**: Try enumeration from UVC descriptors
2. **Fallback**: Use hardcoded format attempts if enumeration fails

## Negotiated Values (Now Dynamic Instead of Hardcoded)

| Parameter | Old Method | New Method |
|-----------|-----------|-----------|
| **formatIndex** | Assumed (1, 2, 3...) | From `uvc_format_desc_t->bFormatIndex` |
| **frameIndex** | Always 1 | From `uvc_frame_desc_t->bFrameIndex` |
| **Resolution** | Hardcoded list (640×480, 800×600, etc.) | From `frame_desc->wWidth/wHeight` |
| **frameInterval** | Hardcoded per resolution | From `frame_desc->dwDefaultFrameInterval` |
| **Frame Rate Range** | Estimated | From `dwMinFrameInterval`/`dwMaxFrameInterval` |
| **Buffer Size** | Estimated from format | From `frame_desc->dwMaxVideoFrameBufferSize` |
| **Alt Setting** | Guessed per resolution | From PROBE response (previous fix) |
| **Packet Size** | Assumed 3072 max | From PROBE response (previous fix) |

## How It Works

### Step 1: Camera Connection
When device is connected, enumerate capabilities:
```java
enumerateCameraCapabilities();
```

### Step 2: Enumeration Process
```
Camera Device
  ↓
USB Device Handle
  ↓
Streaming Interface
  ↓
Format Descriptors (MJPEG, YUY2, NV12, UYVY)
  ↓
Frame Descriptors per Format
  ↓
Extract: index, resolution, frame rates, buffer size
  ↓
Return to Java as CameraFormatInfo[]
```

### Step 3: Format Selection
```java
// Find MJPEG if available, else first format
CameraFormatInfo selectedFormat = ...
CameraFrameInfo selectedFrame = ...

// Use actual indices from camera
formatIndex = selectedFormat.formatIndex;  // Not assumed 1
frameIndex = selectedFrame.frameIndex;      // Not assumed 1
imageWidth = selectedFrame.width;           // Not from hardcoded list
imageHeight = selectedFrame.height;
frameInterval = selectedFrame.dwDefaultFrameInterval;  // Camera's preference
```

## Example: Before vs After

### Before (Hardcoded)
```java
// Assume MJPEG is always formatIndex=1, frameIndex=1
if (tryConfigureFormat("MJPEG", 640, 480, 1, 1)) { ... }  // Fails on cameras where MJPEG≠1
if (tryConfigureFormat("MJPEG", 800, 600, 1, 1)) { ... }  // Wrong index for frame
if (tryConfigureFormat("YUY2", 640, 480, 1, 1)) { ... }   // YUY2 also assumes 1, conflicts with MJPEG
```

### After (Negotiated)
```java
// Actual camera values
if (enumeration found MJPEG with formatIndex=2) {
    formatIndex = 2;  // Correct!
    // Find matching resolution in frame descriptors
    if (found 640×480 with frameIndex=3) {
        frameIndex = 3;  // Correct!
        // Use camera's preferred frame rate instead of guessing
        frameInterval = dwDefaultFrameInterval;
    }
}
```

## Logs Example

When enumeration succeeds, you'll see:
```
D/MainActivity: Camera supports 2 formats:
D/MainActivity:   MJPEG (index=1, frames=5)
D/MainActivity:     640x480 (idx=1, 30 fps)
D/MainActivity:     800x600 (idx=2, 25 fps)
D/MainActivity:     1280x720 (idx=3, 15 fps)
D/MainActivity:     1920x1080 (idx=4, 10 fps)
D/MainActivity:   YUY2 (index=2, frames=3)
D/MainActivity:     640x480 (idx=1, 30 fps)
D/MainActivity:     320x240 (idx=2, 60 fps)
D/MainActivity: Selected from descriptors: MJPEG 640x480 format_idx=1 frame_idx=1 interval=333333
```

## Benefits

1. **Camera-Agnostic**: Works with any UVC camera regardless of its format index scheme
2. **No Crashes**: Correct indices prevent "format not found" errors
3. **Optimal Settings**: Uses camera's default frame rates and buffer sizes
4. **Adapter Pattern**: Enumeration → Selection → Configuration → Streaming
5. **Graceful Degradation**: Falls back to hardcoded if enumeration fails

## Fallback Behavior

If enumeration fails (returns null or empty):
1. Logs warning
2. Continues with next configuration attempt
3. Tries hardcoded resolution/format combinations
4. Eventually succeeds with generic defaults or fails informatively

## Camera Descriptor Information Now Available

From enumeration, you also have access to:
- `dwMinBitRate` / `dwMaxBitRate` - bandwidth requirements
- `dwMaxVideoFrameBufferSize` - exact buffer needed per frame
- `bFrameIntervalType` - discrete vs continuous frame rates
- `dwFrameIntervalStep` - valid frame interval increments
- `bAspectRatioX/Y` - native aspect ratio
- `bmInterlaceFlags` - interlaced video support
- `bCopyProtect` - HDCP requirements

## Next Steps for Further Improvements

1. **Use dwMaxVideoFrameBufferSize** for more accurate buffer sizing
2. **Enumerate supported frame intervals** for optimal frame rate selection
3. **Check aspect ratio** for proper display layout
4. **Validate interlacing** for field-based processing
5. **Cache enumeration results** to avoid repeated descriptor parsing
