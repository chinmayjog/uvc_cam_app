package com.minimal.uvccamera;

/**
 * Represents a camera's supported format configuration
 * Contains information about format index and supported frames/resolutions
 */
public class CameraFormatInfo {
    public int formatIndex;
    public String formatName;  // "MJPEG", "YUY2", "NV12", etc.
    public int bDefaultFrameIndex;
    public CameraFrameInfo[] supportedFrames;
    
    public CameraFormatInfo(int formatIndex, String formatName, int defaultFrameIndex, CameraFrameInfo[] frames) {
        this.formatIndex = formatIndex;
        this.formatName = formatName;
        this.bDefaultFrameIndex = defaultFrameIndex;
        this.supportedFrames = frames != null ? frames : new CameraFrameInfo[0];
    }
    
    @Override
    public String toString() {
        return formatName + " (index=" + formatIndex + ", frames=" + supportedFrames.length + ")";
    }
}
