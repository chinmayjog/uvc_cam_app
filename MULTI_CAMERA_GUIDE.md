# Multi-Camera UVC Setup - User Guide

## Overview

This app has been modified to support a multi-camera workflow where you can:
- Connect 4 USB cameras via a USB hub
- Navigate through a 4-page form (one page per camera)
- Capture a photo from each camera
- Add notes for each captured photo
- Generate a PDF report with all photos and notes

## Requirements

- Android device with USB OTG support
- USB hub (powered hub recommended for 4 cameras)
- 4 UVC-compatible USB cameras
- Sufficient storage space for photos and PDF

## How to Use

### 1. Connect Hardware

1. Connect a USB hub to your Android device via OTG cable
2. Connect all 4 USB cameras to the USB hub
3. Wait for the device to detect the cameras

### 2. Launch Multi-Camera Mode

1. Open the MinimalUVCCamera app
2. Tap the **"📸 Start Multi-Camera Form"** button on the main screen
3. Grant USB permissions when prompted (you may need to grant permission for each camera)

### 3. Capture Photos

For each of the 4 cameras:

1. **View Progress**: At the top, you'll see "Camera X of 4" with a progress bar
2. **Show Preview**: Tap the "👁️ Show Preview" button to:
   - Grant USB permission (first time)
   - Initialize the camera
   - Display live camera feed
3. **Capture Photo**: Tap "📷 Capture Photo" to take a picture
   - Photo is automatically saved
   - Captured photo is displayed
4. **Add Notes**: Type any notes or description in the text box
5. **Navigate**: 
   - Tap "Next →" to move to the next camera
   - Tap "← Previous" to go back to a previous camera
6. **Hide Preview** (optional): After capturing, tap "👁️ Hide Preview" to hide the camera view

### 4. Generate PDF Report

1. After capturing all 4 photos, tap **"Complete ✓"**
2. You'll see a summary screen showing all captures
3. Tap **"📄 Generate PDF"** to create the report
4. The PDF will be automatically saved to:
   ```
   /storage/emulated/0/Documents/UVCCamera/MultiCamera_Report_[timestamp].pdf
   ```
5. The PDF will open automatically if a PDF viewer is installed

### 5. PDF Report Contents

The generated PDF includes:
- Title: "Multi-Camera Capture Report"
- Timestamp of generation
- For each camera:
  - Camera number and name
  - Captured photo (full size, scaled to fit page)
  - Notes entered for that camera
- Summary: Number of cameras successfully captured

## Workflow Tips

### Camera Detection
- The app automatically detects all connected UVC cameras
- If fewer than 4 cameras are detected, you can still use the form but some positions will show "No device available"
- Camera names are shown as their USB device names (e.g., "/dev/bus/usb/001/003")

### Photo Capture
- **Preview is required**: You must show preview before capturing
- Photos are captured from the live camera stream
- Format: JPEG (converted from YUY2 camera format)
- Resolution: 640x480 by default (can be configured)
- Location: Saved to app's external files directory

### Navigation
- You can navigate back and forth between cameras
- Notes are automatically saved when you switch cameras
- Captured photos are retained when navigating
- You can recapture a photo by capturing again (overwrites previous)

### Preview Control
- Preview can be hidden to save battery/bandwidth
- Once a photo is captured, you can hide the preview
- Preview must be visible to capture a photo
- Preview automatically stops when switching cameras

## Troubleshooting

### Camera Not Detected
- Ensure USB OTG is enabled on your device
- Try disconnecting and reconnecting the USB hub
- Use a powered USB hub if cameras aren't detected
- Check that cameras are UVC-compatible

### Permission Issues
- Grant USB permission when prompted
- If permission dialog doesn't appear, try restarting the app
- Some devices require granting permission for each camera individually

### Capture Fails
- Make sure preview is visible before capturing
- Wait for camera to fully initialize (status text will confirm)
- Check that there's sufficient storage space
- Try capturing again if first attempt fails

### PDF Generation Issues
- Ensure storage permissions are granted
- Check that all photos were captured successfully
- Make sure there's sufficient storage space
- PDF requires ~5-10 MB per report depending on photo quality

### Preview Quality
- Default resolution is 640x480 for compatibility
- Some cameras may support higher resolutions
- Preview quality depends on USB bandwidth available
- Using a powered hub can improve stability

## File Locations

### Photos
```
/storage/emulated/0/Android/data/com.minimal.uvccamera/files/Pictures/
camera_0_20260122_143022.jpg
camera_1_20260122_143045.jpg
...
```

### PDF Reports
```
/storage/emulated/0/Documents/UVCCamera/
MultiCamera_Report_20260122_143525.pdf
```

## Technical Details

### Supported Cameras
- Any UVC-compliant USB camera
- Tested with standard USB webcams
- Both MJPEG and YUY2 formats supported
- Multiple cameras from same manufacturer supported

### Image Processing
- Capture format: YUY2 (uncompressed)
- Storage format: JPEG (90% quality)
- Color space conversion: YUV → RGB
- Automatic aspect ratio preservation

### PDF Generation
- Library: iText7
- Page size: A4
- Images: Scaled to fit page width
- Text: UTF-8 encoded (supports special characters)

## Permissions Required

```xml
<uses-permission android:name="android.permission.USB_PERMISSION" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
```

## Limitations

1. **Number of Cameras**: Fixed at 4 cameras
2. **Simultaneous Preview**: Only one camera at a time
3. **Resolution**: Limited by USB bandwidth (default 640x480)
4. **Format**: Currently supports YUY2 and MJPEG formats
5. **USB Hub**: Some hubs may not provide enough bandwidth for 4 cameras

## Future Enhancements

Possible improvements:
- Configurable number of cameras
- Higher resolution support
- Video recording capability
- Cloud upload of PDF reports
- Email sharing of reports
- Custom PDF templates
- Barcode/QR code scanning for notes

## Support

For issues or questions:
1. Check the app logs: `adb logcat -s MultiCameraForm`
2. Verify camera compatibility
3. Test with fewer cameras first
4. Check USB hub power supply

## Version History

- v1.0 - Initial multi-camera implementation
  - 4-camera support
  - PDF report generation
  - YUY2 to JPEG conversion
  - Notes for each camera
