# Quick Start - Multi-Camera Mode

## Setup (One Time)

1. **Build the App**
   ```bash
   ./gradlew build
   ```

2. **Install on Device**
   ```bash
   ./gradlew installDebug
   ```

3. **Connect Hardware**
   - USB hub → Android device (OTG cable)
   - 4 USB cameras → USB hub

## Usage (Every Time)

### Step 1: Launch App
- Open "MinimalUVCCamera" app
- Tap **"📸 Start Multi-Camera Form"**

### Step 2: For Each Camera (1-4)
1. Tap **"👁️ Show Preview"**
   - Grant USB permission when asked
   - Wait for live preview
2. Tap **"📷 Capture Photo"**
   - Photo is saved automatically
3. Type notes in the text box
4. Tap **"Next →"** to go to next camera

### Step 3: Generate PDF
1. After camera 4, tap **"Complete ✓"**
2. Review all captures
3. Tap **"📄 Generate PDF"**
4. PDF opens automatically or is saved to:
   ```
   /Documents/UVCCamera/MultiCamera_Report_[timestamp].pdf
   ```

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Camera not detected | Reconnect USB hub, use powered hub |
| Permission not granted | Restart app, grant permission again |
| Preview not showing | Tap "Show Preview", wait for initialization |
| Capture fails | Ensure preview is visible first |
| PDF not generated | Check storage permissions, ensure photos captured |

## Tips

- **Battery**: Use powered USB hub for stability
- **Preview**: Can hide preview after capture to save battery
- **Navigation**: Can go back to previous cameras to recapture
- **Notes**: Saved automatically when switching cameras
- **Storage**: Each PDF is ~500KB-2MB

## File Locations

**Photos:**
```
/Android/data/com.minimal.uvccamera/files/Pictures/
```

**PDFs:**
```
/Documents/UVCCamera/
```

## Features

✅ 4 camera support
✅ Live preview
✅ Photo capture (JPEG)
✅ Text notes per camera
✅ PDF report with photos
✅ Professional layout
✅ Auto file management

---

For detailed documentation, see:
- **MULTI_CAMERA_GUIDE.md** - Complete user guide
- **MULTI_CAMERA_IMPLEMENTATION.md** - Technical details
