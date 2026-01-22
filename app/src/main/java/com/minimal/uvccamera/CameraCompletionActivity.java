package com.minimal.uvccamera;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.properties.TextAlignment;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CameraCompletionActivity extends AppCompatActivity {
    private static final String TAG = "CameraCompletion";
    
    private LinearLayout captureContainer;
    private Button exportButton;
    private Button finishButton;
    private List<CameraCapture> cameraCaptureList;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.activity_camera_completion);
        
        captureContainer = findViewById(R.id.captureContainer);
        exportButton = findViewById(R.id.exportButton);
        finishButton = findViewById(R.id.finishButton);
        
        // Get camera capture list from intent
        cameraCaptureList = (ArrayList<CameraCapture>) getIntent().getSerializableExtra("cameraCaptureList");
        
        if (cameraCaptureList == null) {
            cameraCaptureList = new ArrayList<>();
        }
        
        // Display all captures
        displayCaptures();
        
        // Setup buttons
        exportButton.setOnClickListener(v -> exportData());
        finishButton.setOnClickListener(v -> {
            setResult(RESULT_OK);
            finish();
        });
    }
    
    private void displayCaptures() {
        captureContainer.removeAllViews();
        
        for (int i = 0; i < cameraCaptureList.size(); i++) {
            CameraCapture capture = cameraCaptureList.get(i);
            
            // Create card for each camera
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(16, 16, 16, 16);
            card.setBackgroundResource(android.R.drawable.dialog_holo_light_frame);
            
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            cardParams.setMargins(0, 8, 0, 8);
            card.setLayoutParams(cardParams);
            
            // Camera name
            TextView nameText = new TextView(this);
            nameText.setText("Camera " + (i + 1) + ": " + capture.getCameraName());
            nameText.setTextSize(16);
            nameText.setTypeface(null, android.graphics.Typeface.BOLD);
            card.addView(nameText);
            
            // Photo status
            TextView photoText = new TextView(this);
            boolean hasPhoto = !capture.getPhotoPath().isEmpty();
            photoText.setText("Photo: " + (hasPhoto ? "✓ Captured" : "✗ Not captured"));
            photoText.setTextSize(14);
            photoText.setTextColor(hasPhoto ? android.graphics.Color.GREEN : android.graphics.Color.RED);
            card.addView(photoText);
            
            // Notes
            TextView notesLabel = new TextView(this);
            notesLabel.setText("Notes:");
            notesLabel.setTextSize(12);
            notesLabel.setTypeface(null, android.graphics.Typeface.BOLD);
            notesLabel.setTextColor(android.graphics.Color.GRAY);
            card.addView(notesLabel);
            
            TextView notesText = new TextView(this);
            String notes = capture.getNotes().isEmpty() ? "(No notes)" : capture.getNotes();
            notesText.setText(notes);
            notesText.setTextSize(12);
            notesText.setPadding(16, 4, 0, 0);
            card.addView(notesText);
            
            // Photo path if available
            if (hasPhoto) {
                TextView pathText = new TextView(this);
                pathText.setText("File: " + new File(capture.getPhotoPath()).getName());
                pathText.setTextSize(10);
                pathText.setTextColor(android.graphics.Color.DKGRAY);
                pathText.setPadding(16, 8, 0, 0);
                card.addView(pathText);
            }
            
            captureContainer.addView(card);
        }
    }
    
    private void exportData() {
        try {
            // Generate PDF report
            String pdfPath = generatePdfReport();
            
            if (pdfPath != null && !pdfPath.isEmpty()) {
                Toast.makeText(this, "PDF generated successfully!", Toast.LENGTH_LONG).show();
                
                // Optionally open the PDF
                openPdf(pdfPath);
            } else {
                Toast.makeText(this, "Failed to generate PDF", Toast.LENGTH_SHORT).show();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error exporting data", e);
            Toast.makeText(this, "Error exporting data: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }
    
    private String generatePdfReport() {
        try {
            // Create PDF file in external storage
            File documentsDir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS), "UVCCamera");
            if (!documentsDir.exists()) {
                documentsDir.mkdirs();
            }
            
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File pdfFile = new File(documentsDir, "MultiCamera_Report_" + timestamp + ".pdf");
            
            Log.d(TAG, "Creating PDF at: " + pdfFile.getAbsolutePath());
            
            // Initialize PDF writer
            PdfWriter writer = new PdfWriter(new FileOutputStream(pdfFile));
            PdfDocument pdf = new PdfDocument(writer);
            Document document = new Document(pdf);
            
            // Add title
            Paragraph title = new Paragraph("Multi-Camera Capture Report")
                    .setFontSize(20)
                    .setBold()
                    .setTextAlignment(TextAlignment.CENTER);
            document.add(title);
            
            // Add timestamp
            Paragraph dateTime = new Paragraph("Generated: " + 
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                    .setFontSize(10)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginBottom(20);
            document.add(dateTime);
            
            // Add each camera's data
            int capturedCount = 0;
            for (int i = 0; i < cameraCaptureList.size(); i++) {
                CameraCapture capture = cameraCaptureList.get(i);
                
                // Camera header
                Paragraph cameraHeader = new Paragraph("Camera " + (i + 1) + ": " + capture.getCameraName())
                        .setFontSize(16)
                        .setBold()
                        .setMarginTop(15);
                document.add(cameraHeader);
                
                // Add photo if available
                if (!capture.getPhotoPath().isEmpty() && new File(capture.getPhotoPath()).exists()) {
                    try {
                        // Load and resize image if needed
                        Bitmap bitmap = BitmapFactory.decodeFile(capture.getPhotoPath());
                        if (bitmap != null) {
                            // Save bitmap as temporary file for iText
                            File tempImageFile = new File(getCacheDir(), "temp_" + i + ".jpg");
                            FileOutputStream fos = new FileOutputStream(tempImageFile);
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, fos);
                            fos.close();
                            
                            // Add image to PDF
                            Image image = new Image(ImageDataFactory.create(tempImageFile.getAbsolutePath()));
                            
                            // Scale image to fit page width (with margins)
                            float maxWidth = pdf.getDefaultPageSize().getWidth() - 100;
                            float maxHeight = 300;
                            
                            if (image.getImageWidth() > maxWidth) {
                                image.scaleToFit(maxWidth, maxHeight);
                            }
                            
                            document.add(image);
                            bitmap.recycle();
                            tempImageFile.delete();
                            
                            capturedCount++;
                        } else {
                            document.add(new Paragraph("Photo: Failed to load").setFontColor(ColorConstants.RED));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error adding image to PDF", e);
                        document.add(new Paragraph("Photo: Error loading image").setFontColor(ColorConstants.RED));
                    }
                } else {
                    document.add(new Paragraph("Photo: Not captured").setFontColor(ColorConstants.RED));
                }
                
                // Add notes
                Paragraph notesHeader = new Paragraph("Notes:")
                        .setFontSize(12)
                        .setBold()
                        .setMarginTop(10);
                document.add(notesHeader);
                
                String notes = capture.getNotes().isEmpty() ? "(No notes provided)" : capture.getNotes();
                Paragraph notesContent = new Paragraph(notes)
                        .setFontSize(11)
                        .setMarginLeft(20);
                document.add(notesContent);
                
                // Add separator
                if (i < cameraCaptureList.size() - 1) {
                    document.add(new Paragraph("\n" + "─".repeat(50) + "\n")
                            .setFontSize(8)
                            .setTextAlignment(TextAlignment.CENTER));
                }
            }
            
            // Add summary
            Paragraph summary = new Paragraph("\n\nSummary: " + capturedCount + "/" + 
                    cameraCaptureList.size() + " cameras captured")
                    .setFontSize(14)
                    .setBold()
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginTop(20);
            document.add(summary);
            
            // Close document
            document.close();
            
            Log.d(TAG, "PDF generated successfully: " + pdfFile.getAbsolutePath());
            return pdfFile.getAbsolutePath();
            
        } catch (Exception e) {
            Log.e(TAG, "Error generating PDF", e);
            return null;
        }
    }
    
    private void openPdf(String pdfPath) {
        try {
            File pdfFile = new File(pdfPath);
            
            if (!pdfFile.exists()) {
                Toast.makeText(this, "PDF file not found", Toast.LENGTH_SHORT).show();
                return;
            }
            
            Uri pdfUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Use FileProvider for Android 7.0+
                pdfUri = FileProvider.getUriForFile(this,
                        getApplicationContext().getPackageName() + ".provider",
                        pdfFile);
            } else {
                pdfUri = Uri.fromFile(pdfFile);
            }
            
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(pdfUri, "application/pdf");
            intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            
            // Check if there's an app that can handle PDFs
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
            } else {
                Toast.makeText(this, "No PDF viewer app found. PDF saved to: " + pdfPath,
                        Toast.LENGTH_LONG).show();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error opening PDF", e);
            Toast.makeText(this, "PDF saved to: " + pdfPath, Toast.LENGTH_LONG).show();
        }
    }
}
