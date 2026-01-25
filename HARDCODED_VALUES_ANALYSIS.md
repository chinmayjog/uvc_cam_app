# Hardcoded Values Analysis - Negotiation Opportunities

## Summary
While implementing the buffer overflow fix with camera negotiation, I found **numerous other hardcoded values** that should be negotiated with the camera instead of assuming static values.

---

## Category 1: Resolution Hardcoding
**Issue:** Resolution priorities are hardcoded instead of querying camera's supported resolutions

### Current Hardcoding in MainActivity.java:
```java
Line 420:  tryConfigureFormat("MJPEG", 640, 480, 1, 1);   // VGA
Line 426:  tryConfigureFormat("MJPEG", 800, 600, 1, 1);   // SVGA
Line 432:  tryConfigureFormat("MJPEG", 1280, 720, 1, 1);  // HD
Line 438:  tryConfigureFormat("MJPEG", 1920, 1080, 1, 1); // Full HD
Line 444:  tryConfigureFormat("YUY2", 640, 480, 1, 1);    // Fallback
Line 450:  tryConfigureFormat("YUY2", 320, 240, 1, 1);    // Low resolution
```

### Should Query From Camera:
- **UVC Frame Descriptor** contains:
  - `wWidth` / `wHeight` - actual supported resolutions
  - `dwMinBitRate` / `dwMaxBitRate` - bandwidth constraints
  - `dwMaxVideoFrameBufferSize` - frame size requirement
  - `dwDefaultFrameInterval` - camera's preferred frame rate
  - `dwMinFrameInterval` / `dwMaxFrameInterval` - supported frame rate range

**Fix:** Instead of iterating through hardcoded resolutions, query UVC descriptors to get actual supported resolutions and use those.

---

## Category 2: Frame Interval (FPS) Hardcoding
**Issue:** Frame rates are hardcoded per resolution instead of reading from camera

### Current Hardcoding in MainActivity.java - `tryConfigureFormat()`:
```java
Line 586:  frameInterval = 66666;    // MJPEG ≤320px: 150 fps
Line 591:  frameInterval = 100000;   // MJPEG ≤640px: 100 fps
Line 595:  frameInterval = 111111;   // MJPEG ≤800px: ~90 fps
Line 599:  frameInterval = 166666;   // MJPEG ≤1280px: ~60 fps
Line 601:  frameInterval = 200000;   // MJPEG >1280px: 50 fps
Line 611:  frameInterval = calculateYuy2FrameInterval();  // At least queries something
```

### Also in FaultFormActivity.java - identical hardcoding (lines 763-793)

### Should Query From Camera:
- UVC Frame Descriptor contains **dwFrameInterval** array:
  - `dwDefaultFrameInterval` - recommended by camera
  - `dwMinFrameInterval` / `dwMaxFrameInterval` - supported range
  - `dwFrameIntervalStep` - valid step increments

**Fix:** During PROBE negotiation, read `dwFrameInterval` from camera and respect its valid range.

---

## Category 3: Packet Size Hardcoding
**Issue:** USB packet sizes assume 3072 bytes max but don't query actual endpoint capabilities

### Current Hardcoding in MainActivity.java - `tryConfigureFormat()`:
```java
Line 590:  maxPacketSize = 1024;   // MJPEG ≤320px
Line 594:  maxPacketSize = 2048;   // MJPEG ≤640px
Line 598:  maxPacketSize = 2560;   // MJPEG ≤800px
Line 600:  maxPacketSize = 3072;   // MJPEG ≤1280px
Line 603:  maxPacketSize = 3072;   // Default fallback
```

### Also in FaultFormActivity.java (lines 770-785)

### Should Query From Camera:
- UVC Streaming Interface Descriptor contains:
  - `wMaxPacketSize` - actual USB endpoint capability
  - Alternative Settings with different packet sizes
  - Streaming Header Descriptor with bandwidth hints

**Fix:** Query USB descriptor to get `wMaxPacketSize` from endpoint instead of assuming values.

---

## Category 4: Streaming Alt Setting Hardcoding
**Issue:** Alt settings (bandwidth allocation) are assumptions not based on actual camera requirements

### Current Hardcoding in MainActivity.java:
```java
Line 592:  streamingAltSetting = 1;   // Most resolutions default to alt 1
Line 596:  streamingAltSetting = 2;   // Medium resolutions
Line 600:  streamingAltSetting = 3;   // HD resolution
Line 603:  streamingAltSetting = 4;   // Fallback
Line 675:  streamingAltSetting = findAppropriateAltSetting(maxPacketSize);
```

### Also in FaultFormActivity.java (lines 773-790)

### Should Query From Camera:
- UVC requires **PROBE_CONTROL SET/GET**:
  - Camera recommends optimal alt setting in response
  - `dwMaxPayloadTransferSize` field guides the selection
  - Multiple alt settings available on streaming interface

**Fix:** Use camera's PROBE response to determine ideal alt setting instead of guessing.

---

## Category 5: Format Index Hardcoding
**Issue:** Format indices (1=MJPEG, 2=YUY2, etc.) are assumed but should be detected

### Current Hardcoding in tryConfigureFormat():
```java
Line 581:  formatIndex = fmtIdx;  // Passed as 1 (MJPEG) or 2 (YUY2)
Line 420:  tryConfigureFormat("MJPEG", 640, 480, 1, 1);  // formatIdx = 1
Line 444:  tryConfigureFormat("YUY2", 640, 480, 1, 1);   // formatIdx = 1 (WRONG!)
```

### Issue:
- Format index varies by camera (MJPEG might be 2, YUY2 might be 3, etc.)
- Current code assumes MJPEG is always 1, YUY2 is always 1 (same!)
- This causes format mismatch failures

### Should Query From Camera:
- UVC Format Descriptor contains:
  - `bFormatIndex` - unique format identifier for THIS camera
  - `guidFormat` - format GUID (MJPEG, YUY2, etc.)
  - Multiple format descriptors with different indices

**Fix:** During device enumeration, read all format descriptors and build a format → index mapping.

---

## Category 6: Frame Index Hardcoding
**Issue:** Frame indices (resolution/framerate combinations) hardcoded as always 1

### Current Hardcoding:
```java
Line 420-438: All use frameIndex = 1 (hardcoded)
```

### Issue:
- Frame index should be unique for each resolution/framerate combination
- Current code uses 1 for everything - will fail if camera doesn't have frameIndex 1

### Should Query From Camera:
- UVC Frame Descriptor contains:
  - `bFrameIndex` - unique identifier for resolution/FPS combo
  - `wWidth`, `wHeight` - resolution
  - `dwDefaultFrameInterval` - default FPS
  - `dwMinFrameInterval` / `dwMaxFrameInterval` - supported range

**Fix:** Enumerate all frame descriptors under each format to find correct frameIndex for desired resolution.

---

## Category 7: Default Values Hardcoding
**Issue:** FaultFormActivity has hardcoded fallbacks not negotiated with camera

### In FaultFormActivity.java onCreate():
```java
Line 151-152: imageWidth = 640, imageHeight = 480;        // VGA default
Line 153:     formatIndex = 1;                             // Assumed MJPEG
Line 154:     frameIndex = 1;                              // Assumed first frame desc
Line 155:     frameInterval = 333333;                      // Assumed 30 fps
Line 156:     maxPacketSize = 3072;                        // Assumed USB HS max
Line 157:     streamingAltSetting = 1;                     // Assumed min alt
```

### In initCamera() Fallbacks:
```java
Line 442:  640x480 alt=2 packet=2048  // First fallback
Line 482:  640x480 alt=1 packet=1024  // Second fallback  
Line 516:  640x480 alt=0 packet=512   // Third fallback
Line 158:  8, 5                        // hardcoded packetsPerRequest, activeUrbs
```

**Fix:** Don't hardcode fallback values - negotiate with camera for optimal params.

---

## Category 8: Data Format Size Assumptions
**Issue:** Frame size calculations assume fixed aspect ratios

### In calculateOptimalPacketsPerRequest():
```java
frameSize = (long) width * height * 3 / 2;   // MJPEG estimate 30-50%
frameSize = (long) width * height * 2;       // YUY2: always 2 bytes/pixel
frameSize = (long) width * height * 3 / 2;   // NV12/NV21: always 12 bits/pixel
```

### Issue:
- MJPEG compression varies wildly (5% to 80%)
- Hardcoded multipliers don't account for actual compression

### Should Use:
- `dwMaxVideoFrameSize` from PROBE response (camera's actual requirement)
- `dwMaxPayloadTransferSize` already fixed in previous fix
- Actual frame data from test runs to calibrate

**Fix:** Use `dwMaxVideoFrameSize` from camera negotiation instead of calculated estimates.

---

## Category 9: Bandwidth & USB Constraints
**Issue:** Total buffer allocation (8MB) is arbitrary

### Current Hardcoding:
```java
Line 1053: long targetTotalBuffers = 8 * 1024 * 1024;  // 8 MB arbitrary
```

### Should Consider:
- Device memory constraints (some Android devices limited)
- USB 2.0 HS bandwidth (480 Mbps shared across all endpoints)
- dwMaxPayloadTransferSize * packetsPerRequest * activeUrbs = actual memory needed
- Camera's dwMaxPayloadTransferSize requirement

**Fix:** Calculate based on camera's dwMaxPayloadTransferSize and actual device memory available.

---

## Category 10: Aspect Ratio & Interlacing
**Issue:** Not querying camera's aspect ratio or interlacing support

### Missing From Negotiation:
- `bAspectRatioX` / `bAspectRatioY` - camera's native aspect ratio
- `bmInterlaceFlags` - is video interlaced (PAL/NTSC)?
- `bCopyProtect` - HDCP requirements
- Frame descriptors have discrete/continuous interval support

**Fix:** Read these from Format/Frame descriptors for proper playback.

---

## Summary Table: What Should Be Queried

| Parameter | Current | Should Query From |
|-----------|---------|------------------|
| Supported Resolutions | Hardcoded list | UVC Format → Frame Descriptors |
| Frame Indices | Always 1 | Frame Descriptor bFrameIndex |
| Format Indices | Assumed (1, 2, 3) | UVC Format Descriptor guidFormat |
| Frame Intervals | Hardcoded per res | dwDefaultFrameInterval, dwMin/MaxFrameInterval |
| Max Packet Size | 3072 assumed | wMaxPacketSize from USB Endpoint |
| Alt Setting | Guessed | PROBE_CONTROL response dwMaxPayloadTransferSize |
| dwMaxVideoFrameSize | Estimated | PROBE_CONTROL response |
| dwMaxPayloadTransferSize | ✅ FIXED (previous) | ✅ Already doing this |
| Total Buffer Size | 8 MB arbitrary | Based on actual dwMaxPayloadTransferSize |
| Aspect Ratio | Ignored | bAspectRatioX/Y from Format Descriptor |
| Interlacing | Ignored | bmInterlaceFlags from Frame Descriptor |

---

## Recommended Fix Priority

1. **HIGH:** Format & Frame Index mapping (causes immediate failures)
2. **HIGH:** Resolution enumeration from descriptors (prevents camera-specific support)
3. **HIGH:** Frame interval range validation (ensures smooth streaming)
4. **MEDIUM:** Packet size from USB endpoint (improves bandwidth efficiency)
5. **MEDIUM:** Alt setting negotiation from PROBE (reduces buffer overflows)
6. **MEDIUM:** Use dwMaxVideoFrameSize from PROBE (better buffer sizing)
7. **LOW:** Aspect ratio/interlacing (improves display but not critical)

---

## Example Fix Approach

Instead of:
```java
if (tryConfigureFormat("MJPEG", 640, 480, 1, 1)) { ... }
if (tryConfigureFormat("MJPEG", 800, 600, 1, 1)) { ... }
```

Should Do:
```java
// Query descriptors once
List<UvcFormatInfo> formats = queryUvcFormats();  // Get all format/frame combos
for (UvcFormatInfo fmt : formats) {
    if (fmt.isSupported(videoFormat, width, height)) {
        formatIndex = fmt.index;
        frameIndex = fmt.frameIndex;
        frameInterval = fmt.defaultInterval;
        // etc.
        break;
    }
}
```

This way the app adapts to ANY camera, not just those that match the hardcoded assumptions.
