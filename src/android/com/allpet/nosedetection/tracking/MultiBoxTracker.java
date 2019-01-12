package io.cordova.hellocordova.tracking;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Cap;
import android.graphics.Paint.Join;
import android.graphics.Paint.Style;
import android.graphics.RectF;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Pair;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import io.cordova.hellocordova.Classifier.Recognition;
import io.cordova.hellocordova.DetectorActivity;
import io.cordova.hellocordova.R;
import io.cordova.hellocordova.env.BorderedText;
import io.cordova.hellocordova.env.ImageUtils;
import io.cordova.hellocordova.env.Logger;

public class MultiBoxTracker {
    private final Logger logger = new Logger();

    private static final float TEXT_SIZE_DIP = 18;
    private static final float MAX_OVERLAP = 0.2f;
    private static final float MIN_SIZE = 16.0f;
    private static final float MARGINAL_CORRELATION = 0.75f;
    private static final float MIN_CORRELATION = 0.3f;
    private static final int[] COLORS = {Color.RED, Color.GREEN};
    private final Queue<Integer> availableColors = new LinkedList<Integer>();

    public ObjectTracker objectTracker;

    final List<Pair<Float, RectF>> screenRects = new LinkedList<Pair<Float, RectF>>();

    private static class TrackedRecognition {
        ObjectTracker.TrackedObject trackedObject;
        RectF location;
        float detectionConfidence;
        int color;
        String title;
    }

    private final List<TrackedRecognition> trackedObjects = new LinkedList<TrackedRecognition>();

    private final Paint boxPaint = new Paint();

    private final float textSizePx;
    private final BorderedText borderedText;

    private Matrix frameToCanvasMatrix;

    private int frameWidth;
    private int frameHeight;

    private int sensorOrientation;
    private Context context;

    private boolean detectionCheck = false;
    private int detection_Count = 0;

    public static String strPetName;
    public static String strName;
    public static String strCall;

    private static ProgressDialog mProgressDialog;

    public MultiBoxTracker(final Context context) {
        this.context = context;
        for (final int color : COLORS) {
            availableColors.add(color);
        }

        boxPaint.setColor(Color.GREEN);
        boxPaint.setStyle(Style.STROKE);
        boxPaint.setStrokeWidth(12.0f);
        boxPaint.setStrokeCap(Cap.ROUND);
        boxPaint.setStrokeJoin(Join.ROUND);
        boxPaint.setStrokeMiter(100);

        textSizePx =
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, context.getResources().getDisplayMetrics());
        borderedText = new BorderedText(textSizePx);
    }

    private Matrix getFrameToCanvasMatrix() {
        return frameToCanvasMatrix;
    }

    public synchronized void drawDebug(final Canvas canvas) {
        final Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(60.0f);

        final Paint boxPaint = new Paint();
        boxPaint.setColor(Color.RED);
        boxPaint.setAlpha(200);
        boxPaint.setStyle(Style.STROKE);

        for (final Pair<Float, RectF> detection : screenRects) {
            final RectF rect = detection.second;
            canvas.drawRect(rect, boxPaint);
            canvas.drawText("" + detection.first, rect.left, rect.top, textPaint);
            borderedText.drawText(canvas, rect.centerX(), rect.centerY(), "" + detection.first);
        }

        if (objectTracker == null) {
            return;
        }

        for (final TrackedRecognition recognition : trackedObjects) {
            final ObjectTracker.TrackedObject trackedObject = recognition.trackedObject;

            final RectF trackedPos = trackedObject.getTrackedPositionInPreviewFrame();

            if (getFrameToCanvasMatrix().mapRect(trackedPos)) {
                final String labelString = String.format("%.1f%%", 100 * trackedObject.getCurrentCorrelation());
                borderedText.drawText(canvas, trackedPos.right, trackedPos.bottom, labelString);
            }
        }

        final Matrix matrix = getFrameToCanvasMatrix();
        objectTracker.drawDebug(canvas, matrix);
    }

    public synchronized void trackResults(
            final List<Recognition> results, final byte[] frame, final long timestamp) {
        processResults(timestamp, results, frame);
    }

    public synchronized void draw(final Canvas canvas) {
        final boolean rotated = sensorOrientation % 180 == 90;
        final float multiplier =
                Math.min(canvas.getHeight() / (float) (rotated ? frameWidth : frameHeight),
                        canvas.getWidth() / (float) (rotated ? frameHeight : frameWidth));
        frameToCanvasMatrix =
                ImageUtils.getTransformationMatrix(
                        frameWidth,
                        frameHeight,
                        (int) (multiplier * (rotated ? frameHeight : frameWidth)),
                        (int) (multiplier * (rotated ? frameWidth : frameHeight)),
                        sensorOrientation,
                        false);
        for (final TrackedRecognition recognition : trackedObjects) {
            final RectF trackedPos =
                    (objectTracker != null)
                            ? recognition.trackedObject.getTrackedPositionInPreviewFrame()
                            : new RectF(recognition.location);

            getFrameToCanvasMatrix().mapRect(trackedPos);
            boxPaint.setColor(recognition.color);

            final float cornerSize = Math.min(trackedPos.width(), trackedPos.height()) / 8.0f;
            canvas.drawRoundRect(trackedPos, cornerSize, cornerSize, boxPaint);

            final String labelString =
                    !TextUtils.isEmpty(recognition.title)
                            ? String.format("%s %.1f%%", recognition.title, 100 * recognition.detectionConfidence)
                            : String.format("%.1f%%", 100 * recognition.detectionConfidence);
            borderedText.drawText(canvas, trackedPos.left + cornerSize, trackedPos.bottom, labelString);
        }
    }

    private boolean initialized = false;

    public synchronized void onFrame(
            final int w,
            final int h,
            final int rowStride,
            final int sensorOrientation,
            final byte[] frame,
            final long timestamp) {
        if (objectTracker == null && !initialized) {
            ObjectTracker.clearInstance();

            logger.i("Initializing ObjectTracker: %dx%d", w, h);
            objectTracker = ObjectTracker.getInstance(w, h, rowStride, true);
            frameWidth = w;
            frameHeight = h;
            this.sensorOrientation = sensorOrientation;
            initialized = true;

            if (objectTracker == null) {
                String message = "비문인식기능을 작동시킬 수 없습니다.";
                shortAlertDialog(message);
                logger.e(message);
            }
        }

        if (objectTracker == null) {
            return;
        }

        objectTracker.nextFrame(frame, null, timestamp, null, true);

        final LinkedList<TrackedRecognition> copyList =
                new LinkedList<TrackedRecognition>(trackedObjects);
        for (final TrackedRecognition recognition : copyList) {
            final ObjectTracker.TrackedObject trackedObject = recognition.trackedObject;
            final float correlation = trackedObject.getCurrentCorrelation();
            if (correlation < MIN_CORRELATION) {
                logger.v("Removing tracked object %s because NCC is %.2f", trackedObject, correlation);
                trackedObject.stopTracking();
                trackedObjects.remove(recognition);

                availableColors.add(recognition.color);
                detectionCheck = false;
                detection_Count = 0;
            }
        }
    }

    private void processResults(
            final long timestamp, final List<Recognition> results, final byte[] originalFrame) {
        final List<Pair<Float, Recognition>> rectsToTrack = new LinkedList<Pair<Float, Recognition>>();

        screenRects.clear();
        final Matrix rgbFrameToScreen = new Matrix(getFrameToCanvasMatrix());

        for (final Recognition result : results) {
            if (result.getLocation() == null) {
                continue;
            }
            final RectF detectionFrameRect = new RectF(result.getLocation());

            final RectF detectionScreenRect = new RectF();
            rgbFrameToScreen.mapRect(detectionScreenRect, detectionFrameRect);

            logger.v(
                    "Result! Frame: " + result.getLocation() + " mapped to screen:" + detectionScreenRect);

            screenRects.add(new Pair<Float, RectF>(result.getConfidence(), detectionScreenRect));

            if (detectionFrameRect.width() < MIN_SIZE || detectionFrameRect.height() < MIN_SIZE) {
                logger.w("Degenerate rectangle! " + detectionFrameRect);
                continue;
            }

            rectsToTrack.add(new Pair<Float, Recognition>(result.getConfidence(), result));
        }

        if (rectsToTrack.isEmpty()) {
            logger.v("Nothing to track, aborting.");
            return;
        }

        if (objectTracker == null) {
            trackedObjects.clear();
            for (final Pair<Float, Recognition> potential : rectsToTrack) {
                final TrackedRecognition trackedRecognition = new TrackedRecognition();
                trackedRecognition.detectionConfidence = potential.first;
                trackedRecognition.location = new RectF(potential.second.getLocation());
                trackedRecognition.trackedObject = null;
                trackedRecognition.title = potential.second.getTitle();
                trackedRecognition.color = COLORS[trackedObjects.size()];
                trackedObjects.add(trackedRecognition);
                if (trackedObjects.size() >= COLORS.length) {
                    break;
                }
            }
            return;
        }

        if (rectsToTrack.size() == 2) {
            logger.i("비문 %d개 인식중", rectsToTrack.size() / 2);
            for (final Pair<Float, Recognition> potential : rectsToTrack) {
                handleDetection(originalFrame, timestamp, potential);
            }
            if (detectionCheck == false) {
                detection_Count++;
                logger.i("인식중....%d", detection_Count);
                if (detection_Count == 6) {
                    puppy_Sound_1();
                    custom_alertDialog();
                    detectionCheck = true;
                    detection_Count = 0;
                }
            } else if (detectionCheck == true) {

            }
        }
    }

    private void handleDetection(
            final byte[] frameCopy, final long timestamp, final Pair<Float, Recognition> potential) {
        final ObjectTracker.TrackedObject potentialObject =
                objectTracker.trackObject(potential.second.getLocation(), timestamp, frameCopy);

        final float potentialCorrelation = potentialObject.getCurrentCorrelation();
        logger.v(
                "Tracked object went from %s to %s with correlation %.2f",
                potential.second, potentialObject.getTrackedPositionInPreviewFrame(), potentialCorrelation);

        if (potentialCorrelation < MARGINAL_CORRELATION) {
            logger.v("Correlation too low to begin tracking %s.", potentialObject);
            potentialObject.stopTracking();
            return;
        }

        final List<TrackedRecognition> removeList = new LinkedList<TrackedRecognition>();

        float maxIntersect = 0.0f;

        TrackedRecognition recogToReplace = null;

        for (final TrackedRecognition trackedRecognition : trackedObjects) {
            final RectF a = trackedRecognition.trackedObject.getTrackedPositionInPreviewFrame();
            final RectF b = potentialObject.getTrackedPositionInPreviewFrame();
            final RectF intersection = new RectF();
            final boolean intersects = intersection.setIntersect(a, b);

            final float intersectArea = intersection.width() * intersection.height();
            final float totalArea = a.width() * a.height() + b.width() * b.height() - intersectArea;
            final float intersectOverUnion = intersectArea / totalArea;

            if (intersects && intersectOverUnion > MAX_OVERLAP) {
                if (potential.first < trackedRecognition.detectionConfidence
                        && trackedRecognition.trackedObject.getCurrentCorrelation() > MARGINAL_CORRELATION) {
                    potentialObject.stopTracking();
                    return;
                } else {
                    removeList.add(trackedRecognition);

                    if (intersectOverUnion > maxIntersect) {
                        maxIntersect = intersectOverUnion;
                        recogToReplace = trackedRecognition;
                    }
                }
            }
        }

        if (availableColors.isEmpty() && removeList.isEmpty()) {
            for (final TrackedRecognition candidate : trackedObjects) {
                if (candidate.detectionConfidence < potential.first) {
                    if (recogToReplace == null
                            || candidate.detectionConfidence < recogToReplace.detectionConfidence) {
                        recogToReplace = candidate;
                    }
                }
            }
            if (recogToReplace != null) {
                logger.v("Found non-intersecting object to remove.");
                removeList.add(recogToReplace);
            } else {
                logger.v("No non-intersecting object found to remove");
            }
        }

        for (final TrackedRecognition trackedRecognition : removeList) {
            logger.v(
                    "Removing tracked object %s with detection confidence %.2f, correlation %.2f",
                    trackedRecognition.trackedObject,
                    trackedRecognition.detectionConfidence,
                    trackedRecognition.trackedObject.getCurrentCorrelation());
            trackedRecognition.trackedObject.stopTracking();
            trackedObjects.remove(trackedRecognition);
            if (trackedRecognition != recogToReplace) {
                availableColors.add(trackedRecognition.color);
            }
        }

        if (recogToReplace == null && availableColors.isEmpty()) {
            logger.e("No room to track this object, aborting.");
            potentialObject.stopTracking();
            return;
        }

        logger.v(
                "Tracking object %s (%s) with detection confidence %.2f at position %s",
                potentialObject,
                potential.second.getTitle(),
                potential.first,
                potential.second.getLocation());
        final TrackedRecognition trackedRecognition = new TrackedRecognition();
        trackedRecognition.detectionConfidence = potential.first;
        trackedRecognition.trackedObject = potentialObject;
        trackedRecognition.title = potential.second.getTitle();

        trackedRecognition.color =
                recogToReplace != null ? recogToReplace.color : availableColors.poll();
        trackedObjects.add(trackedRecognition);
    }

    private void custom_alertDialog() {
        final AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(context);
        alertDialogBuilder.setIcon(R.drawable.ic_allpet_launcher).setCancelable(false);
        alertDialogBuilder.setTitle("My Pet 등록").setMessage("아래 해당하는 정보를 등록해주세요.");
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        final EditText petName = new EditText(context);
        petName.setHint("반려동물 이름");
        petName.setInputType(InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        petName.setCursorVisible(false);
        layout.addView(petName);
        final EditText name = new EditText(context);
        name.setHint("반려인 이름");
        name.setInputType(InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        name.setCursorVisible(false); // name.setMaxWidth(name.getWidth());
        layout.addView(name);
        final EditText call = new EditText(context);
        call.setHint("반려인 연락처");
        call.setInputType(InputType.TYPE_CLASS_PHONE);
        call.setCursorVisible(false); // call.setMaxWidth(call.getWidth());
        layout.setPadding(70, 0, 70, 0);
        layout.addView(call);
        alertDialogBuilder.setView(layout);
        alertDialogBuilder.setPositiveButton("등록", null);
        alertDialogBuilder.setNeutralButton("사진확인", null);
        alertDialogBuilder.setNegativeButton("취소", null);

        final AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(final DialogInterface dialog) {
                Button posBtn = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                posBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        strPetName = petName.getText().toString();
                        strName = name.getText().toString();
                        strCall = call.getText().toString();

                        if (strPetName.isEmpty() || strName.isEmpty() || strCall.isEmpty()) {
                            dialog.dismiss();
                            String message = "해당 정보를 모두 입력해주세요.";
                            AlertDialog.Builder alertDialogBuilder_new = new AlertDialog.Builder(context);
                            alertDialogBuilder_new.setIcon(R.drawable.ic_allpet_launcher).setCancelable(true);
                            alertDialogBuilder_new.setMessage(message);

                            final AlertDialog alertDialog_new = alertDialogBuilder_new.create();
                            alertDialog_new.show();

                            Handler mHandler = new Handler();
                            Runnable r = new Runnable() {
                                @Override
                                public void run() {
                                    alertDialog_new.dismiss();
                                    custom_alertDialog();
                                }
                            };
                            mHandler.postDelayed(r, 1000);
                        } else {
                            setstrPetName(strPetName);
                            setStrName(strName);
                            setStrCall(strCall);
                            dialog.dismiss();
                            dialog.cancel();
                            progress_Spinner_On(context, "My Pet 정보\n서버 전송 중.....");

                            Handler mHandler = new Handler();
                            Runnable r = new Runnable() {
                                @Override
                                public void run() {
                                    progress_Spinner_Off();
                                    puppy_Sound_2();
                                    custom_alertDialog_resultCheck(strPetName, strName, strCall);
                                }
                            };
                            mHandler.postDelayed(r, 5000);
                        }
                    }
                });
                Button neuBtn = alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL);
                neuBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        imageDialog(context, alertDialog, "사진확인");
                    }
                });
                Button negBtn = alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                negBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String message = "취소하셨습니다. \n등록이 필요합니다.";
                        shortAlertDialog(message);
                        DetectorActivity.cropPetNose = null;
                        dialog.dismiss();
                        dialog.cancel();
                    }
                });
            }
        });
        alertDialog.show();
    }

    private void custom_alertDialog_resultCheck(String strPetName, String strName, String strCall) {
        final AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(context);
        alertDialogBuilder.setIcon(R.drawable.ic_allpet_launcher).setCancelable(false);
        alertDialogBuilder.setTitle("등록결과")
                .setMessage(strName + "님의" + "\n반려동물 " + strPetName + " 등록완료." +
                        "\n연락처 " + strCall);

        alertDialogBuilder.setPositiveButton("확인", null);
        alertDialogBuilder.setNeutralButton("사진확인", null);

        final AlertDialog alertDialog = alertDialogBuilder.create();

        alertDialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(final DialogInterface dialog) {
                Button posBtn = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                posBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog.dismiss();
                    }
                });
                Button neuBtn = alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL);
                neuBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        imageDialog(context, alertDialog, "등록된 사진");
                    }
                });
            }
        });
        alertDialog.show();
    }

    public void imageDialog(Context context, AlertDialog alertDialog, String title) {
        AlertDialog.Builder imgDialogBuilder = new AlertDialog.Builder(context);
        imgDialogBuilder.setIcon(R.drawable.ic_allpet_launcher).setCancelable(false);
        imgDialogBuilder.setTitle(title);

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.HORIZONTAL);
        linearLayout.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView imageView = new ImageView(context);
        linearLayout.setPadding(10, 70, 10, 70);
        linearLayout.addView(imageView);
        imgDialogBuilder.setView(linearLayout);

        imgDialogBuilder.setPositiveButton("확인", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                dialog.dismiss();
            }
        });

        final AlertDialog imgDialog = imgDialogBuilder.create();

        if (DetectorActivity.getCropPetNose() != null
                && DetectorActivity.getCropPetNose().getIntrinsicWidth() >= 30
                && DetectorActivity.getCropPetNose().getIntrinsicHeight() >= 55) {
            imageView.setImageDrawable(DetectorActivity.getCropPetNose());
            imageView.setLayoutParams(new LinearLayout.LayoutParams(
                    DetectorActivity.getCropPetNose().getIntrinsicWidth() * 6,
                    DetectorActivity.getCropPetNose().getIntrinsicHeight() * 6
            ));
            logger.i("이미지 w x h = %d x %d",
                    DetectorActivity.getCropPetNose().getIntrinsicWidth() * 6,
                    DetectorActivity.getCropPetNose().getIntrinsicHeight() * 6);
            imgDialog.show();
        } else {
            alertDialog.dismiss();
            String message = "비문인식오류!\n비문을 가까이 인식해주세요!";
            shortAlertDialog(message);
            DetectorActivity.cropPetNose = null;
        }
    }

    public void progress_Spinner_On(Context context, String message) {
        mProgressDialog = new ProgressDialog(context);
        mProgressDialog.setCancelable(false);
        mProgressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        mProgressDialog.setMessage(message);
        mProgressDialog.show();
    }

    public void progress_Spinner_Off() {
        if (mProgressDialog != null && mProgressDialog.isShowing()) {
            mProgressDialog.dismiss();
        }
    }

    private void puppy_Sound_1() {
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                .build();
        final SoundPool soundPool =
                new SoundPool.Builder().setAudioAttributes(audioAttributes).setMaxStreams(3).build();
        soundPool.load(context, R.raw.puppy_sound_1, 0);
        soundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
            @Override
            public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                soundPool.play(sampleId, .1f, .1f, 0, 0, 1.5f);
            }
        });
    }

    private void puppy_Sound_2() {
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                .build();
        final SoundPool soundPool =
                new SoundPool.Builder().setAudioAttributes(audioAttributes).setMaxStreams(3).build();
        soundPool.load(context, R.raw.puppy_sound_2, 0);
        soundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
            @Override
            public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                soundPool.play(sampleId, .1f, .1f, 0, 0, 1.5f);
            }
        });
    }

    private void shortAlertDialog(String message) {
        AlertDialog.Builder alertDialogBuilder_new = new AlertDialog.Builder(context);
        alertDialogBuilder_new.setIcon(R.drawable.ic_allpet_launcher).setCancelable(true);
        alertDialogBuilder_new.setMessage(message);

        final AlertDialog alertDialog_new = alertDialogBuilder_new.create();
        alertDialog_new.show();

        Handler mHandler = new Handler();
        Runnable r = new Runnable() {
            @Override
            public void run() {
                alertDialog_new.dismiss();
            }
        };
        mHandler.postDelayed(r, 1000);
    }

    public void setstrPetName(String strPetName) {
        this.strPetName = strPetName;
    }

    public static String getstrPetName() {
        return strPetName;
    }

    public void setStrName(String strName) {
        this.strName = strName;
    }

    public static String getStrName() {
        return strName;
    }

    public void setStrCall(String strCall) {
        this.strCall = strCall;
    }

    public static String getStrCall() {
        return strCall;
    }
}
