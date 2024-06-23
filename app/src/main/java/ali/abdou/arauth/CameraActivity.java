package ali.abdou.arauth;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class CameraActivity extends AppCompatActivity implements CameraBridgeViewBase.CvCameraViewListener2 {
    private static final String TAG = "CameraActivity";
    private static final double FRAME_RATE = 0.5;
    private long lastFrameTime = 0;
    private Mat mRgba;
    private Mat mGray;
    private CameraBridgeViewBase mOpenCvCameraView;
    private Mat mIntermediateMat;
    private Mat hierarchy;
    private List<MatOfPoint> contours;
    OkHttpClient client = new OkHttpClient();




    @Override
    protected void onCreate(Bundle savedInstanceState) {

        if (OpenCVLoader.initLocal()) {
            Log.i(TAG, "OpenCV loaded successfully");
        } else {
            Log.e(TAG, "OpenCV initialization failed!");
            (Toast.makeText(this, "OpenCV initialization failed!", Toast.LENGTH_LONG)).show();
            return;
        }
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        int MY_PERMISSIONS_REQUEST_CAMERA = 123;
        if (ContextCompat.checkSelfPermission(CameraActivity.this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(CameraActivity.this, new String[]{Manifest.permission.CAMERA}, MY_PERMISSIONS_REQUEST_CAMERA);
        }

        setContentView(R.layout.activity_camera);
        mOpenCvCameraView = findViewById(R.id.frame_Surface);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);
    }

    @Override
    public void onPause()
    {
        super.onPause();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    @Override
    public void onResume()
    {
        super.onResume();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.enableView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mOpenCvCameraView != null) {
            mOpenCvCameraView.disableView();
        }
    }

    @Override
    public void onCameraViewStarted(int width, int height) {
        mRgba = new Mat(height, width, CvType.CV_8UC4);
        mIntermediateMat=new Mat(height, width, CvType.CV_8UC4);
        mGray = new Mat(height, width, CvType.CV_8UC1);
        hierarchy=new Mat();
    }

    @Override
    public void onCameraViewStopped() {
        mRgba.release();
        mGray.release();
        mIntermediateMat.release();
        hierarchy.release();
    }

    @Override
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastFrameTime < 2000 / FRAME_RATE) {
            return inputFrame.rgba();
        }

        Mat mRgba = inputFrame.rgba();
        byte[] frameBytes = convertMatToBytes(inputFrame.rgba());
        String jsonResponse = sendFrameToServer(frameBytes);
        lastFrameTime = currentTime;

        if (jsonResponse != null) {
            Log.d("JSON Response", jsonResponse);

            try {
                JSONObject jsonObject = new JSONObject(jsonResponse);
                if (jsonObject.has("detected_objects")) {
                    JSONArray detectedObjects = jsonObject.getJSONArray("detected_objects");
                    for (int i = 0; i < detectedObjects.length(); i++) {
                        JSONObject object = detectedObjects.getJSONObject(i);

                        // Extract the bounding box coordinates
                        double ymin = object.getDouble("ymin");
                        double xmin = object.getDouble("xmin");
                        double ymax = object.getDouble("ymax");
                        double xmax = object.getDouble("xmax");

                        int imgWidth = mRgba.cols();
                        int imgHeight = mRgba.rows();

                        int rectLeft = (int) (Math.max(1, xmin * imgWidth));
                        int rectTop = (int) (Math.max(1, ymin * imgHeight));
                        int rectRight = (int) (Math.min(imgWidth, xmax * imgWidth));
                        int rectBottom = (int) (Math.min(imgHeight, ymax * imgHeight));

                        mRgba = inputFrame.rgba();
                        contours = new ArrayList<MatOfPoint>();
                        hierarchy = new Mat();
                        Imgproc.Canny(mRgba, mIntermediateMat, 80, 100);
                        Imgproc.findContours(mIntermediateMat, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE, new Point(0,0));
                        hierarchy.release();

                        for (int contourIndex = 0; contourIndex < contours.size(); contourIndex++) {
                            MatOfPoint2f approxCurve = new MatOfPoint2f();
                            MatOfPoint2f contour2f = new MatOfPoint2f(contours.get(contourIndex).toArray());
                            double approxDistance = Imgproc.arcLength(contour2f, true) * 0.01;
                            Imgproc.approxPolyDP(contour2f, approxCurve, approxDistance, true);

                            MatOfPoint points = new MatOfPoint(approxCurve.toArray());
                            Rect rect = Imgproc.boundingRect(points);
                            double height = rect.height;
                            double width = rect.width;
                            Log.d("rect", imgHeight + "," + rect.y + "," + rect.height + "," + rect.width);

                            if (height > 300 && width > 300) {
                                Imgproc.rectangle(mRgba, new Point(rect.x, rect.y), new Point(rect.x + rect.width, rect.y + rect.height), new Scalar(0, 255, 0, 0), 3);
                                Imgproc.putText(mRgba, object.getString("object_name"), rect.tl(), Imgproc.FONT_HERSHEY_SIMPLEX, 2, new Scalar(0, 0, 0), 4);
                            }
                        }
                    }
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        return mRgba;
    }



    private byte[] convertMatToBytes(Mat frame) {
        MatOfByte matOfByte = new MatOfByte();
        Imgcodecs.imencode(".jpg", frame, matOfByte);
        return matOfByte.toArray();
    }
    private String sendFrameToServer(byte[] frameBytes) {
        AtomicReference<String> responseData = new AtomicReference<>("");

        MultipartBody.Builder multipartBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("frame", "frame.jpg", RequestBody.create(MediaType.parse("image/jpeg"), frameBytes));

        RequestBody requestBody = multipartBuilder.build();
        Request request = new Request.Builder()
                .url("http://192.168.43.96:5001/detect_objects")
                .post(requestBody)
                .build();
        CountDownLatch latch = new CountDownLatch(1);
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                runOnUiThread(() -> Toast.makeText(CameraActivity.this, "Server down: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                latch.countDown();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    throw new IOException("Unexpected code " + response);
                }
                String responseBody = response.body().string();
                responseData.set(responseBody);
                Log.d("Response", responseBody);
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return responseData.get();
    }
}
