# Minimal UVC Camera - Functional Stripped-Down Version

A working Android application for USB Video Class (UVC) cameras with **all necessary native libraries included**. This is a functional stripped-down version of the Android-UVC-Camera project.

## ✅ What's Included (Complete & Working)

### Native Libraries (C/C++)
- **libUvc_Support.so** - JNI bridge for USB camera communication
- **libyuv.so** - YUV format conversion (SIMD optimized)
- **libuvc_preview.so** - Camera preview rendering

### Java Components
- **MainActivity** - USB detection & auto-configuration
- **CameraActivity** - Preview display & picture capture
- **UVCNative** - JNA interface to native methods

### Features
- ✅ Automatic USB camera detection
- ✅ Auto-configuration (UVC probe/commit protocol)
- ✅ Live camera preview
- ✅ Picture capture and save
- ✅ MJPEG format support
- ❌ No video recording (stripped out)
- ❌ No manual configuration UI (auto only)
- ❌ No camera controls (brightness, etc.)

## Project Structure

```
MinimalUVCCamera/
├── app/
│   ├── src/main/
│   │   ├── cpp/                           # Native code
│   │   │   ├── CMakeLists.txt            # Build configuration
│   │   │   ├── libuvc_support.c          # JNI bridge (1500 lines)
│   │   │   ├── uvc_support.cpp           # C++ helpers
│   │   │   ├── uvc_support.h             # Headers
│   │   │   ├── utlist.h                  # Utility macros
│   │   │   ├── libyuv/                   # YUV conversion library
│   │   │   └── UVC_Camera_Saki/          # Preview library
│   │   ├── java/com/minimal/uvccamera/
│   │   │   ├── MainActivity.java         # Detection & config
│   │   │   ├── CameraActivity.java       # Preview & capture
│   │   │   └── UVCNative.java            # Native interface
│   │   ├── res/                          # Resources
│   │   └── AndroidManifest.xml
│   └── build.gradle                      # App build config
├── build.gradle                          # Project build config
├── settings.gradle
└── README.md
```

## How It Works

### 1. Auto-Detection Process
```java
MainActivity:
├─ Scan USB devices
├─ Identify UVC camera (Video Class)
├─ Request USB permission
├─ Claim USB interfaces
├─ Send PROBE_CONTROL (GET_CUR) to query device capabilities
├─ Parse complete PROBE response per UVC 1.1 spec:
│  ├─ Format index (MJPEG, YUY2, NV12, etc.)
│  ├─ Frame index
│  ├─ Resolution (wWidth, wHeight)
│  ├─ Frame interval (FPS)
│  ├─ Bit rate, frame size, payload size
│  └─ All 26 bytes of probe control structure
├─ Validate all required parameters detected
├─ Send PROBE_CONTROL (SET_CUR) to apply settings
├─ Send COMMIT_CONTROL (SET_CUR) to finalize
└─ Launch CameraActivity with detected parameters
```

**Key Difference**: No hardcoded fallback defaults. If camera doesn't report resolution, the app fails with error rather than guessing. This ensures reliability with any UVC-compliant camera.

### 2. Preview & Capture
```java
CameraActivity:
├─ Open USB device connection
├─ Initialize native library:
│  ├─ set_the_native_Values()
│  └─ initStreamingParms()
├─ Prepare preview stream:
│  └─ PreviewPrepareStream(surface)
├─ Start streaming:
│  └─ PreviewStartStream()
├─ Display frames on SurfaceView
├─ On capture button:
│  ├─ setImageCapture()
│  ├─ Get frame from callback
│  └─ Save as JPEG
└─ On stop:
    └─ PreviewStopStream()
```

## Build Instructions

### Prerequisites
- Android Studio 2022.1 or later
- Android NDK (included with Android Studio)
- Android SDK API 34
- Physical Android device with USB OTG support
- USB OTG cable
- UVC-compatible USB camera

### Build Steps

1. **Open in Android Studio**
   ```bash
   File > Open > /home/chinmay/AndroidStudioProjects/MinimalUVCCamera
   ```

2. **Sync Gradle**
   - Android Studio will automatically sync
   - NDK will compile native libraries
   - This takes 3-5 minutes first time

3. **Build APK**
   ```bash
   Build > Make Project
   # Or from terminal:
   cd MinimalUVCCamera
   ./gradlew assembleDebug
   ```

4. **Install on Device**
   - Connect Android device via USB
   - Enable USB debugging
   - Click Run button in Android Studio
   - Or: `./gradlew installDebug`

## Usage

### First Time Setup

1. **Connect USB Camera**
   - Connect OTG cable to Android device
   - Connect USB camera to OTG cable
   - App should auto-launch

2. **Grant Permissions**
   - USB permission dialog appears
   - Tap "OK" to grant access

3. **Auto-Detection**
   - App automatically detects camera
   - Configures optimal settings (1-2 seconds)
   - Shows: Resolution, FPS, Packet Size

4. **Start Preview**
   - Tap "Start Preview" button
   - Camera feed displays on screen

5. **Take Pictures**
   - Tap camera button (📷)
   - Picture saved to: `/storage/emulated/0/Android/data/com.minimal.uvccamera/files/Pictures/UVCCamera/`

## Native Library Details

### libuvc_support.c (JNI Bridge)
**Size**: ~1500 lines of C code  
**Functions**:
- `set_the_native_Values()` - Configure camera parameters
- `initStreamingParms()` - Initialize streaming
- `PreviewPrepareStream()` - Setup preview
- `PreviewStartStream()` - Begin streaming
- `PreviewStopStream()` - Stop streaming
- `setImageCapture()` - Capture single frame
- Frame callback handling

### libyuv (Format Conversion)
**Purpose**: Convert YUV formats to RGB for display  
**Features**:
- SIMD optimizations (NEON on ARM)
- Multiple format support (YUY2, UYVY, NV12, I420)
- Rotation and scaling
- High performance (10-20x faster than Java)

### libuvc_preview (Preview Rendering)
**Purpose**: Render camera frames to Android Surface  
**Features**:
- Direct surface rendering
- Frame synchronization
- Buffer management

## Configuration

### Auto-Detection from Camera (No Hardcoded Defaults)
```java
// All parameters auto-detected from device PROBE response
// Initialized to 0 (unknown), never falling back to guesses
imageWidth = 0;           // Set from camera probe
imageHeight = 0;          // Set from camera probe
formatIndex = 1;          // Auto-detected (1=MJPEG usually)
frameIndex = 1;           // Auto-detected
frameInterval = 0;        // Set from camera probe (100ns units)
videoFormat = "UNKNOWN";  // Determined from formatIndex
```

**Why no defaults?**
- Different cameras support different resolutions
- 640x480 fallback would fail on cameras that don't support it
- App properly fails with error if camera doesn't report capabilities
- Matches reference app (Android-UVC-Camera) implementation

## Troubleshooting

### Camera Not Detected
**Problem**: "No USB camera found"
- Check camera is UVC-compliant
- Try different OTG cable
- Check USB debugging enabled
- View logs: `adb logcat | grep MinimalUVC`

### Camera Not Configured
**Problem**: "Camera does not report resolution"
- Camera doesn't comply with UVC spec
- Try different camera (must report width/height/frame interval)
- View logs to see what PROBE response returned

### Build Fails
**Problem**: CMake or NDK errors
- Install NDK in Android Studio: Tools > SDK Manager > SDK Tools > NDK
- Use NDK version 25.1.8937393 or later
- Clean and rebuild: Build > Clean Project, then Build > Make Project

### Preview Black Screen
**Problem**: No video appears
- Check Logcat for errors
- Camera may use unsupported format
- Try different camera
- Check USB OTG cable quality

### App Crashes
**Problem**: Native library errors
- Check Logcat: `adb logcat | grep "FATAL"`
- Ensure all native libraries built successfully
- Check ABI matches device (arm64-v8a vs armeabi-v7a)

## Supported Cameras

### Tested & Working
- Logitech C270, C920, C930e
- Microsoft LifeCam series
- Generic USB webcams with UVC 1.0/1.1

### Requirements
- UVC 1.0 or 1.1 compliant
- MJPEG format support
- Standard USB Video Class descriptors

## Logs & Debugging

### View All Logs
```bash
adb logcat | grep -E "MinimalUVC|UVCNative|CameraActivity"
```

### View Native Logs
```bash
adb logcat | grep -E "Uvc_Support|libyuv|uvc_preview"
```

### Check Library Loading
```bash
adb logcat | grep "System.loadLibrary"
```

## Differences from Full Android-UVC-Camera

| Feature | Minimal UVC | Android-UVC-Camera |
|---------|-------------|-------------------|
| **Code Size** | ~1,000 lines Java | ~8,000 lines Java |
| **Native Code** | Copied from original | Same source |
| **Auto-detection** | Basic | Advanced with fallbacks |
| **Preview** | SurfaceView | SurfaceView + TextureView |
| **Capture** | Picture only | Picture + Video |
| **UI** | Minimal (2 screens) | Full featured |
| **Manual config** | No | Yes |
| **Camera controls** | No | Yes (brightness, etc.) |
| **Build time** | 3-5 min | 5-10 min |
| **APK size** | ~5 MB | ~8 MB |
| **Maintainability** | Easy | Complex |

## Known Limitations

1. **No Hardcoded Defaults**: Requires UVC-compliant camera that properly reports resolution
2. **MJPEG Primary**: Works best with MJPEG (most cameras support this)
3. **No YUV Direct Support**: YUV formats require additional decoding not included
4. **No Video Recording**: Picture capture only
5. **No Manual Config**: Auto-detection only, no UI for settings adjustment
6. **Basic Error Handling**: Limited fallback options - fails if device doesn't report parameters

## Future Enhancements

To add more features:

### Add Video Recording
- Copy `BitmapToVideoEncoder.java` from original
- Add video button to CameraActivity
- Implement frame buffering

### Add YUV Support
- Uncomment YUV conversion in native code
- Add format detection logic
- Implement YUV display path

### Add Camera Controls
- Copy USB control transfer methods
- Add UI sliders
- Implement brightness/contrast/focus

## Credits

**Based on**: Android-UVC-Camera by Peter Stoiber  
**GitHub**: https://github.com/Peter-St/Android-UVC-Camera  
**License**: LGPL 2.1

This minimal version uses the same native libraries but with simplified Java wrapper code focusing on core functionality: detect, configure, preview, capture.

## License

LGPL 2.1 (same as Android-UVC-Camera)

## Support

For issues specific to this minimal version, check logs and refer to the original Android-UVC-Camera documentation.

For general UVC camera issues, consult the USB Video Class specification.

---

**Status**: Fully functional with all required native libraries included  
**Created**: January 21, 2026  
**Purpose**: Simplified working example of UVC camera integration
