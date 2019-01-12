package io.cordova.hellocordova;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.SystemClock;
import android.util.Size;
import android.util.TypedValue;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Vector;

import io.cordova.hellocordova.env.BorderedText;
import io.cordova.hellocordova.env.ImageUtils;
import io.cordova.hellocordova.env.Logger;
import io.cordova.hellocordova.tracking.MultiBoxTracker;


public class DetectorActivity extends CameraActivity implements OnImageAvailableListener {
    private static final Logger LOGGER = new Logger();

    private static final int TF_OD_API_INPUT_SIZE = 300;
    private static final String TF_OD_API_MODEL_FILE =
            "file:///android_asset/allpet_custom_v11_android.pb";
    private static final String TF_OD_API_LABELS_FILE =
            "file:///android_asset/allpet_labels_list.txt";

    private enum DetectorMode {TF_OD_API}

    private static final DetectorMode MODE = DetectorMode.TF_OD_API;

    private static final float MINIMUM_CONFIDENCE_TF_OD_API = 0.9f;

    private static final boolean MAINTAIN_ASPECT = MODE == DetectorMode.TF_OD_API;

    private static final Size DESIRED_PREVIEW_SIZE = new Size(640, 480);

    private static final float TEXT_SIZE_DIP = 10;

    private Integer sensorOrientation;

    private Classifier detector;

    private long lastProcessingTimeMs;
    private Bitmap rgbFrameBitmap = null;
    private Bitmap croppedBitmap = null;
    private Bitmap cropCopyBitmap = null;

    private boolean computingDetection = false;

    private long timestamp = 0;

    private Matrix frameToCropTransform;
    private Matrix cropToFrameTransform;

    private MultiBoxTracker tracker;

    private byte[] luminanceCopy;

    private BorderedText borderedText;

    public static Drawable cropPetNose;
    private boolean cropCheck = false;
    private int crop_Count = 0;

    @Override
    public void onPreviewSizeChosen(final Size size, final int rotation) {
        final float textSizePx =
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
        borderedText = new BorderedText(textSizePx);
        borderedText.setTypeface(Typeface.MONOSPACE);

        tracker = new MultiBoxTracker(this);

        int cropSize = TF_OD_API_INPUT_SIZE;
        try {
            detector = TensorFlowObjectDetectionAPIModel.create(
                    getAssets(), TF_OD_API_MODEL_FILE, TF_OD_API_LABELS_FILE, TF_OD_API_INPUT_SIZE);
            cropSize = TF_OD_API_INPUT_SIZE;
        } catch (final IOException e) {
            LOGGER.e("Exception initializing classifier!", e);
            finish();
        }

        previewWidth = size.getWidth();
        previewHeight = size.getHeight();

        sensorOrientation = rotation - getScreenOrientation();
        LOGGER.i("Camera orientation relative to screen canvas: %d", sensorOrientation);

        LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight);
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
        croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Config.ARGB_8888);

        frameToCropTransform =
                ImageUtils.getTransformationMatrix(
                        previewWidth, previewHeight,
                        cropSize, cropSize,
                        sensorOrientation, MAINTAIN_ASPECT);

        cropToFrameTransform = new Matrix();
        frameToCropTransform.invert(cropToFrameTransform);

        trackingOverlay = (OverlayView) findViewById(R.id.tracking_overlay);
        trackingOverlay.addCallback(
                new OverlayView.DrawCallback() {
                    @Override
                    public void drawCallback(final Canvas canvas) {
                        tracker.draw(canvas);
                        if (isDebug()) {
                            tracker.drawDebug(canvas);
                        }
                    }
                });

        addCallback(
                new OverlayView.DrawCallback() {
                    @Override
                    public void drawCallback(final Canvas canvas) {
                        if (!isDebug()) {
                            return;
                        }
                        final Bitmap copy = cropCopyBitmap;
                        if (copy == null) {
                            return;
                        }

                        final int backgroundColor = Color.argb(100, 0, 0, 0);
                        canvas.drawColor(backgroundColor);

                        final Matrix matrix = new Matrix();
                        final float scaleFactor = 2;
                        matrix.postScale(scaleFactor, scaleFactor);
                        matrix.postTranslate(
                                canvas.getWidth() - copy.getWidth() * scaleFactor,
                                canvas.getHeight() - copy.getHeight() * scaleFactor);
                        canvas.drawBitmap(copy, matrix, new Paint());

                        final Vector<String> lines = new Vector<String>();
                        if (detector != null) {
                            final String statString = detector.getStatString();
                            final String[] statLines = statString.split("\n");
                            for (final String line : statLines) {
                                lines.add(line);
                            }
                        }
                        lines.add("");

                        lines.add("Frame: " + previewWidth + "x" + previewHeight);
                        lines.add("Crop: " + copy.getWidth() + "x" + copy.getHeight());
                        lines.add("View: " + canvas.getWidth() + "x" + canvas.getHeight());
                        lines.add("Rotation: " + sensorOrientation);
                        lines.add("Inference time: " + lastProcessingTimeMs + "ms");

                        borderedText.drawLines(canvas, 10, canvas.getHeight() - 10, lines);
                    }
                });
    }

    OverlayView trackingOverlay;

    @Override
    protected void processImage() {
        ++timestamp;
        final long currTimestamp = timestamp;
        byte[] originalLuminance = getLuminance();
        tracker.onFrame(
                previewWidth,
                previewHeight,
                getLuminanceStride(),
                sensorOrientation,
                originalLuminance,
                timestamp);
        trackingOverlay.postInvalidate();

        if (computingDetection) {
            readyForNextImage();
            return;
        }
        computingDetection = true;

        rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);

        if (luminanceCopy == null) {
            luminanceCopy = new byte[originalLuminance.length];
        }
        System.arraycopy(originalLuminance, 0, luminanceCopy, 0, originalLuminance.length);
        readyForNextImage();

        final Canvas canvas = new Canvas(croppedBitmap);
        canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);

        runInBackground(
                new Runnable() {
                    @Override
                    public void run() {
                        final long startTime = SystemClock.uptimeMillis();
                        final List<Classifier.Recognition> results = detector.recognizeImage(croppedBitmap);
                        lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;

                        cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
                        final Canvas canvas = new Canvas(cropCopyBitmap);
                        final Paint paint = new Paint();
                        paint.setColor(Color.GREEN);
                        paint.setStyle(Style.STROKE);
                        paint.setStrokeWidth(2.0f);

                        float minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                        switch (MODE) {
                            case TF_OD_API:
                                minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                                break;
                        }

                        final List<Classifier.Recognition> mappedRecognitions =
                                new LinkedList<Classifier.Recognition>();

                        for (final Classifier.Recognition result : results) {
                            final RectF location = result.getLocation();
                            if (result != null && location != null
                                    && (results.get(0).getTitle().contains("Noseprint")
                                    && results.get(1).getTitle().contains("Nose"))
                                    && result.getConfidence() >= minimumConfidence
                                    && result.getConfidence() < 1.0f) {
                                if (cropCheck == false && result.getConfidence() >= 0.99f) {
                                    crop_Count++;
                                    LOGGER.i("crop_Count = " + String.valueOf(crop_Count));
                                    if (crop_Count == 10) {
                                        Matrix new_matrix = new Matrix();
                                        new_matrix.postScale(1f, 1f);

                                        canvas.drawBitmap(croppedBitmap, new Matrix(), null);

                                        Bitmap croppedFinalBitmap = cropBitmap(croppedBitmap,
                                                (int) results.get(0).getLocation().left,
                                                (int) results.get(0).getLocation().top,
                                                Math.abs((int) results.get(0).getLocation().right - (int) results.get(0).getLocation().left),
                                                Math.abs((int) results.get(0).getLocation().top - (int) results.get(0).getLocation().bottom));

                                        cropPetNose = new BitmapDrawable(getResources(), croppedFinalBitmap);
                                        setCropPetNose(cropPetNose);
                                        cropCheck = true;
                                        crop_Count = 0;
                                    }
                                } else if (cropCheck == true && result.getConfidence() >= 0.99f) {
                                    crop_Count++;
                                    LOGGER.i("crop_Count = " + String.valueOf(crop_Count));
                                    if (crop_Count == 10) {
                                        Matrix new_matrix = new Matrix();
                                        new_matrix.postScale(1f, 1f);

                                        canvas.drawBitmap(croppedBitmap, new Matrix(), null);

                                        Bitmap croppedFinalBitmap = cropBitmap(croppedBitmap,
                                                (int) results.get(0).getLocation().left,
                                                (int) results.get(0).getLocation().top,
                                                Math.abs((int) results.get(0).getLocation().right - (int) results.get(0).getLocation().left),
                                                Math.abs((int) results.get(0).getLocation().top - (int) results.get(0).getLocation().bottom));

                                        cropPetNose = new BitmapDrawable(getResources(), croppedFinalBitmap);
                                        setCropPetNose(cropPetNose);
                                        cropCheck = true;
                                        crop_Count = 0;
                                    }
                                } else if (cropCheck == true) {

                                }
                                canvas.drawRect(results.get(0).getLocation(), paint);

                                cropToFrameTransform.mapRect(location);
                                result.setLocation(location);
                                mappedRecognitions.add(result);

                            } else if (result != null && location != null
                                    && (results.get(1).getTitle().contains("Noseprint")
                                    && results.get(0).getTitle().contains("Nose"))
                                    && result.getConfidence() >= minimumConfidence
                                    && result.getConfidence() < 1.0f) {
                                if (cropCheck == false && result.getConfidence() >= 0.99f) {
                                    crop_Count++;
                                    LOGGER.i("crop_Count = " + String.valueOf(crop_Count));
                                    if (crop_Count == 10) {
                                        Matrix new_matrix = new Matrix();
                                        new_matrix.postScale(1f, 1f);

                                        canvas.drawBitmap(croppedBitmap, new Matrix(), null);

                                        Bitmap croppedFinalBitmap = cropBitmap(croppedBitmap,
                                                (int) results.get(1).getLocation().left,
                                                (int) results.get(1).getLocation().top,
                                                Math.abs((int) results.get(1).getLocation().right - (int) results.get(1).getLocation().left),
                                                Math.abs((int) results.get(1).getLocation().top - (int) results.get(1).getLocation().bottom));
                                        cropPetNose = new BitmapDrawable(getResources(), croppedFinalBitmap);
                                        setCropPetNose(cropPetNose);
                                        cropCheck = true;
                                        crop_Count = 0;
                                    }
                                } else if (cropCheck == true && result.getConfidence() >= 0.99f) {
                                    crop_Count++;
                                    LOGGER.i("crop_Count = " + String.valueOf(crop_Count));
                                    if (crop_Count == 10) {
                                        Matrix new_matrix = new Matrix();
                                        new_matrix.postScale(1f, 1f);

                                        canvas.drawBitmap(croppedBitmap, new Matrix(), null);

                                        Bitmap croppedFinalBitmap = cropBitmap(croppedBitmap,
                                                (int) results.get(1).getLocation().left,
                                                (int) results.get(1).getLocation().top,
                                                Math.abs((int) results.get(1).getLocation().right - (int) results.get(1).getLocation().left),
                                                Math.abs((int) results.get(1).getLocation().top - (int) results.get(1).getLocation().bottom));
                                        cropPetNose = new BitmapDrawable(getResources(), croppedFinalBitmap);
                                        setCropPetNose(cropPetNose);
                                        cropCheck = true;
                                        crop_Count = 0;
                                    }
                                } else if (cropCheck == true) {

                                }
                                canvas.drawRect(results.get(1).getLocation(), paint);

                                cropToFrameTransform.mapRect(location);
                                result.setLocation(location);
                                mappedRecognitions.add(result);
                            } else if (location != null && result.getConfidence() >= minimumConfidence) {
                                canvas.drawRect(location, paint);

                                cropToFrameTransform.mapRect(location);
                                result.setLocation(location);
                                mappedRecognitions.add(result);
                            }
                        }
                        tracker.trackResults(mappedRecognitions, luminanceCopy, currTimestamp);
                        trackingOverlay.postInvalidate();

                        requestRender();
                        computingDetection = false;
                    }
                });
    }

    private Bitmap cropBitmap(Bitmap original, int left, int top, int width, int height) {
        Bitmap result = null;
        if (left + width <= original.getWidth()) {
            result = Bitmap.createBitmap(original, left, top, width, height);
        }
        return result;
    }

    @Override
    protected int getLayoutId() {
        return R.layout.camera_connection_fragment_tracking;
    }

    @Override
    protected Size getDesiredPreviewFrameSize() {
        return DESIRED_PREVIEW_SIZE;
    }

    @Override
    public void onSetDebug(final boolean debug) {
        detector.enableStatLogging(debug);
    }

    public void setCropPetNose(Drawable cropPetNose) {
        this.cropPetNose = cropPetNose;
    }

    public static Drawable getCropPetNose() {
        return cropPetNose;
    }
}
