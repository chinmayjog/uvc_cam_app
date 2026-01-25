package com.minimal.uvccamera;

/**
 * Represents a camera's supported frame configuration for a specific format
 * Contains resolution, frame rate range, and other frame-specific settings
 */
public class CameraFrameInfo {
    public int frameIndex;
    public int width;
    public int height;
    public long dwMinBitRate;
    public long dwMaxBitRate;
    public long dwMaxVideoFrameBufferSize;
    public long dwDefaultFrameInterval;  // in 100ns units
    public long dwMinFrameInterval;      // in 100ns units
    public long dwMaxFrameInterval;      // in 100ns units
    public long dwFrameIntervalStep;     // in 100ns units
    public int bFrameIntervalType;
    
    public CameraFrameInfo(int frameIndex, int width, int height) {
        this.frameIndex = frameIndex;
        this.width = width;
        this.height = height;
    }
    
    /**
     * Get default FPS for this frame configuration
     * @return approximate FPS (frames per second)
     */
    public int getDefaultFps() {
        if (dwDefaultFrameInterval > 0) {
            return (int) (10000000 / dwDefaultFrameInterval);
        }
        return 0;
    }
    
    /**
     * Get maximum FPS for this frame configuration
     * @return approximate max FPS
     */
    public int getMaxFps() {
        if (dwMinFrameInterval > 0) {
            return (int) (10000000 / dwMinFrameInterval);
        }
        return 0;
    }
    
    /**
     * Get minimum FPS for this frame configuration
     * @return approximate min FPS
     */
    public int getMinFps() {
        if (dwMaxFrameInterval > 0) {
            return (int) (10000000 / dwMaxFrameInterval);
        }
        return 0;
    }
    
    @Override
    public String toString() {
        return width + "x" + height + " (idx=" + frameIndex + ", " + getDefaultFps() + " fps)";
    }
}
