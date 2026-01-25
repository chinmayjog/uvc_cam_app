# Quick Reference: Hardcoded Values Now Using Negotiation

## Summary of Changes

### ✅ NOW NEGOTIATED (No Longer Hardcoded)

#### 1. Format Index
- **Before**: Assumed MJPEG=1, YUY2=2, NV12=3 (wrong for many cameras)
- **After**: Read actual `bFormatIndex` from UVC Format Descriptor
- **Benefit**: Works with cameras where MJPEG is index 2 or 3

#### 2. Frame Index  
- **Before**: Always 1 (worked only if camera had frame 1)
- **After**: Read actual `bFrameIndex` from UVC Frame Descriptor per resolution
- **Benefit**: Finds correct frame index for each resolution

#### 3. Supported Resolutions
- **Before**: Hardcoded list [640×480, 800×600, 1280×720, 1920×1080, 320×240]
- **After**: Enumerate all from UVC Frame Descriptors
- **Benefit**: Supports any resolution the camera offers

#### 4. Frame Intervals (FPS)
- **Before**: Hardcoded values per resolution (66666-200000 ns)
- **After**: Read `dwDefaultFrameInterval` from Frame Descriptor
- **Benefit**: Uses camera's preferred frame rate, not guessed

#### 5. Frame Rate Range
- **Before**: Not used (just one hardcoded value per resolution)
- **After**: Read `dwMinFrameInterval` and `dwMaxFrameInterval`
- **Benefit**: Can adjust frame rate dynamically, validate user selections

#### 6. Max Video Frame Buffer Size
- **Before**: Estimated as width × height × multiplier
- **After**: Read `dwMaxVideoFrameBufferSize` from Frame Descriptor
- **Benefit**: Exact buffer sizing, prevents overflow

### ⚠️ PARTIALLY NEGOTIATED (Mixed Hardcoded + Negotiated)

#### 7. Packet Size (maxPacketSize)
- **Before**: Hardcoded 1024-3072 per resolution
- **After**: Read `dwMaxPayloadTransferSize` from PROBE_CONTROL response ✅
- **Status**: Already fixed in previous buffer overflow fix
- **Remaining**: Could also read from USB Endpoint descriptor

#### 8. Alt Setting (streamingAltSetting)
- **Before**: Guessed 1-4 based on resolution
- **After**: Use PROBE_CONTROL response to validate ✅ (partially)
- **Remaining**: Could optimize based on actual endpoint capabilities

#### 9. PacketsPerRequest & ActiveUrbs
- **Before**: Hardcoded 8 and 5
- **After**: Calculated from `dwMaxPayloadTransferSize` ✅ (already fixed)
- **Remaining**: Could further optimize with frame size

### ❌ STILL HARDCODED (Not Yet Negotiated)

#### 10. Aspect Ratio
- **Currently**: Ignored
- **Available**: `bAspectRatioX` / `bAspectRatioY` from Format Descriptor
- **Impact**: Low (doesn't affect streaming, only display)

#### 11. Interlacing Support
- **Currently**: Ignored
- **Available**: `bmInterlaceFlags` from Format Descriptor
- **Impact**: Medium (affects frame processing)

#### 12. Copy Protection
- **Currently**: Ignored
- **Available**: `bCopyProtect` from Format Descriptor
- **Impact**: Low (may not apply to USB cameras)

#### 13. Frame Interval Step
- **Currently**: Not used
- **Available**: `dwFrameIntervalStep` from Frame Descriptor
- **Impact**: Medium (for fine-tuning frame rates)

---

## Code Locations

### Format/Frame Enumeration (NEW)
- **C**: `/app/src/main/cpp/libUvc_Support/libuvc_support.c` - `enumerateCameraFormats()` (line ~1107)
- **Java**: `/app/src/main/java/com/minimal/uvccamera/MainActivity.java` - `enumerateCameraCapabilities()` (line ~560)

### Old Hardcoded Values (NOW WITH FALLBACK)
- `/app/src/main/java/com/minimal/uvccamera/MainActivity.java` - `tryConfigureFormat()` (line ~630)
  - Still used as fallback if enumeration fails
  - Now only tried if explicit format negotiation fails

### Negotiated Values Used
- **formatIndex**: From `CameraFormatInfo.formatIndex`
- **frameIndex**: From `CameraFrameInfo.frameIndex`  
- **imageWidth/Height**: From `CameraFrameInfo.width/height`
- **frameInterval**: From `CameraFrameInfo.dwDefaultFrameInterval`
- **dwMaxPayloadTransferSize**: From PROBE response (already fixed)
- **dwMaxVideoFrameSize**: Available but not yet used (enhancement opportunity)

---

## Testing the Changes

### What to Look For in Logs:

**Success (Enumeration worked):**
```
D/MainActivity: Attempting to enumerate camera capabilities from UVC descriptors
D/MainActivity: Camera supports 2 formats:
D/MainActivity:   MJPEG (index=1, frames=5)
D/MainActivity:   YUY2 (index=2, frames=3)
D/MainActivity: Selected from descriptors: MJPEG 640x480 format_idx=1 frame_idx=1
D/MainActivity: Using negotiated values from descriptors
```

**Fallback (Enumeration failed):**
```
D/MainActivity: Attempting to enumerate camera capabilities from UVC descriptors
W/MainActivity: No formats enumerated from device
D/MainActivity: Descriptor enumeration didn't provide config, trying hardcoded formats
D/MainActivity: Successfully configured 640x480 MJPEG format
```

---

## Remaining Hardcoded Values (Minor)

These still use assumptions but have minimal impact:

| Parameter | Hardcoded Value | Impact | Notes |
|-----------|-----------------|--------|-------|
| bAspectRatioX/Y | Ignored | Display layout | Optional for USB cameras |
| bmInterlaceFlags | Ignored | Frame processing | Need to implement if PAL/NTSC |
| bCopyProtect | Ignored | DRM | Typically not used for USB |
| dwFrameIntervalStep | Ignored | FPS granularity | Good for future enhancement |
| bDefaultFrameIndex | Read but not auto-selected | Format default | Could auto-select if enumeration fails |

---

## Architecture Improvement Flow

### Before
```
Hardcoded List → Try Format → PROBE → Set Config
     ↓
  Maybe fails if camera uses different indices
```

### After
```
USB Descriptors → Enumerate Formats → Select Best → PROBE → Set Config
     ↓
Works with ANY UVC camera, regardless of index scheme
```

This is a significant architecture improvement that eliminates the most fragile part of the previous implementation.
