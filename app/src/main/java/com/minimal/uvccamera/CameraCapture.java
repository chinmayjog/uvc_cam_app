package com.minimal.uvccamera;

import java.io.Serializable;
import java.util.Date;

/**
 * Model class to store camera capture data for multi-camera workflow
 */
public class CameraCapture implements Serializable {
    private String cameraName;              // Device name or camera identifier
    private String photoPath;               // File path to captured photo
    private String notes;                   // User notes for this camera
    private long captureTimestamp;          // Timestamp of capture
    private int cameraIndex;                // 0-3 for the 4 cameras
    
    public CameraCapture(int cameraIndex, String cameraName) {
        this.cameraIndex = cameraIndex;
        this.cameraName = cameraName;
        this.notes = "";
        this.photoPath = "";
        this.captureTimestamp = 0;
    }
    
    // Getters and Setters
    public String getCameraName() {
        return cameraName;
    }
    
    public void setCameraName(String cameraName) {
        this.cameraName = cameraName;
    }
    
    public String getPhotoPath() {
        return photoPath;
    }
    
    public void setPhotoPath(String photoPath) {
        this.photoPath = photoPath;
    }
    
    public String getNotes() {
        return notes;
    }
    
    public void setNotes(String notes) {
        this.notes = notes != null ? notes : "";
    }
    
    public long getCaptureTimestamp() {
        return captureTimestamp;
    }
    
    public void setCaptureTimestamp(long timestamp) {
        this.captureTimestamp = timestamp;
    }
    
    public int getCameraIndex() {
        return cameraIndex;
    }
    
    public void setCameraIndex(int cameraIndex) {
        this.cameraIndex = cameraIndex;
    }
    
    public boolean isCaptured() {
        return !photoPath.isEmpty();
    }
    
    @Override
    public String toString() {
        return "CameraCapture{" +
                "cameraName='" + cameraName + '\'' +
                ", cameraIndex=" + cameraIndex +
                ", photoPath='" + photoPath + '\'' +
                ", notes='" + notes + '\'' +
                ", captureTimestamp=" + captureTimestamp +
                '}';
    }
}
