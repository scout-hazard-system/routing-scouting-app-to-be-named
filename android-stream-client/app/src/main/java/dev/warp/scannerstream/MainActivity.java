package dev.warp.scannerstream;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {
  private static final float MOTION_FORCE_THRESHOLD_MS2 = 1.8f;
  private static final float MOTION_IDLE_THRESHOLD_MS2 = 0.35f;
  private static final long MOTION_FORCE_HOLD_MS = 4000L;
  private static final long MOTION_IDLE_RELEASE_MS = 12000L;
  private static final int LOCATION_PERMISSION_REQUEST_CODE = 4102;
  private static final long LOCATION_UPDATE_INTERVAL_MS = 2000L;
  private static final float LOCATION_MIN_DISTANCE_M = 3f;
  private static final long DEVICE_GPS_POST_INTERVAL_MS = 3000L;
  private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
  private static final Pattern COORDINATE_PATTERN =
      Pattern.compile("\\b(-?\\d{1,2}\\.\\d+)\\s*[, ]\\s*(-?\\d{1,3}\\.\\d+)\\b");

  private EditText baseUrlInput;
  private TextView statusText;
  private TextView drivingModeText;
  private TextView mapTargetText;
  private TextView outputText;
  private final Handler uiHandler = new Handler(Looper.getMainLooper());
  private final OkHttpClient client = new OkHttpClient.Builder().build();
  private volatile boolean running = false;
  private Call streamCall;
  private SensorManager sensorManager;
  private Sensor accelerometer;
  private LocationManager locationManager;
  private final float[] gravity = new float[] {0f, 0f, 0f};
  private long motionAboveSinceMs = 0L;
  private long motionBelowSinceMs = 0L;
  private boolean forceDrivingMode = false;
  private long lastMotionUiUpdateMs = 0L;
  private float lastMotionMagnitude = 0f;
  private long lastDeviceGpsPostMs = 0L;
  private Double lastMapLat = null;
  private Double lastMapLon = null;
  private Double lastDeviceLat = null;
  private Double lastDeviceLon = null;
  private Float lastDeviceAccuracyM = null;
  private Float lastDeviceSpeedMps = null;
  private Float lastDeviceHeadingDeg = null;

  private final SensorEventListener accelListener =
      new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
          processAccelerometerSample(event);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
          // no-op
        }
      };

  private final LocationListener locationListener =
      location -> {
        if (location != null) {
          handleDeviceLocationUpdate(location);
        }
      };

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    baseUrlInput = findViewById(R.id.baseUrlInput);
    statusText = findViewById(R.id.statusText);
    drivingModeText = findViewById(R.id.drivingModeText);
    mapTargetText = findViewById(R.id.mapTargetText);
    outputText = findViewById(R.id.outputText);
    Button connectBtn = findViewById(R.id.connectBtn);
    Button disconnectBtn = findViewById(R.id.disconnectBtn);
    Button clearLogBtn = findViewById(R.id.clearLogBtn);
    Button openMapsBtn = findViewById(R.id.openMapsBtn);

    sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
    if (sensorManager != null) {
      accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }
    locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

    connectBtn.setOnClickListener(v -> startStreaming());
    disconnectBtn.setOnClickListener(v -> stopStreaming("disconnected"));
    clearLogBtn.setOnClickListener(v -> outputText.setText(getString(R.string.stream_placeholder)));
    openMapsBtn.setOnClickListener(v -> openLatestMapTarget());

    setStatus("idle");
    updateDrivingModeUi(0f);
    updateMapTargetUi();
  }

  @Override
  protected void onResume() {
    super.onResume();
    registerMotionDetection();
    registerLocationTracking();
  }

  @Override
  protected void onPause() {
    unregisterLocationTracking();
    unregisterMotionDetection();
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    unregisterLocationTracking();
    unregisterMotionDetection();
    stopStreaming("stopped");
    super.onDestroy();
  }

  private void registerMotionDetection() {
    if (sensorManager == null || accelerometer == null) {
      updateDrivingModeUi(0f);
      appendLine("MOTION", "accelerometer unavailable; driving mode remains manual");
      return;
    }
    sensorManager.registerListener(accelListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
  }

  private void unregisterMotionDetection() {
    if (sensorManager != null) {
      sensorManager.unregisterListener(accelListener);
    }
  }

  private void registerLocationTracking() {
    if (locationManager == null) {
      appendLine("GPS", "location manager unavailable");
      return;
    }
    boolean fineGranted =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
    boolean coarseGranted =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
    if (!fineGranted && !coarseGranted) {
      ActivityCompat.requestPermissions(
          this,
          new String[] {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
          LOCATION_PERMISSION_REQUEST_CODE);
      return;
    }
    try {
      locationManager.requestLocationUpdates(
          LocationManager.GPS_PROVIDER,
          LOCATION_UPDATE_INTERVAL_MS,
          LOCATION_MIN_DISTANCE_M,
          locationListener);
    } catch (Exception ignored) {
      // GPS provider might be unavailable
    }
    try {
      locationManager.requestLocationUpdates(
          LocationManager.NETWORK_PROVIDER,
          LOCATION_UPDATE_INTERVAL_MS,
          LOCATION_MIN_DISTANCE_M,
          locationListener);
    } catch (Exception ignored) {
      // Network provider might be unavailable
    }
  }

  private void unregisterLocationTracking() {
    if (locationManager == null) {
      return;
    }
    try {
      locationManager.removeUpdates(locationListener);
    } catch (Exception ignored) {
      // no-op
    }
  }

  private boolean hasLocationPermission() {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode != LOCATION_PERMISSION_REQUEST_CODE) {
      return;
    }
    boolean granted = false;
    for (int result : grantResults) {
      if (result == PackageManager.PERMISSION_GRANTED) {
        granted = true;
        break;
      }
    }
    if (granted) {
      appendLine("GPS", "location permission granted");
      registerLocationTracking();
    } else {
      appendLine("GPS", "location permission denied");
    }
  }

  private void processAccelerometerSample(SensorEvent event) {
    if (event == null || event.values == null || event.values.length < 3) {
      return;
    }
    final float alpha = 0.85f;
    gravity[0] = alpha * gravity[0] + (1f - alpha) * event.values[0];
    gravity[1] = alpha * gravity[1] + (1f - alpha) * event.values[1];
    gravity[2] = alpha * gravity[2] + (1f - alpha) * event.values[2];
    float linearX = event.values[0] - gravity[0];
    float linearY = event.values[1] - gravity[1];
    float linearZ = event.values[2] - gravity[2];
    float motion = (float) Math.sqrt((linearX * linearX) + (linearY * linearY) + (linearZ * linearZ));
    lastMotionMagnitude = motion;

    long now = SystemClock.elapsedRealtime();
    if (motion >= MOTION_FORCE_THRESHOLD_MS2) {
      if (motionAboveSinceMs == 0L) {
        motionAboveSinceMs = now;
      }
      motionBelowSinceMs = 0L;
      if (!forceDrivingMode && (now - motionAboveSinceMs >= MOTION_FORCE_HOLD_MS)) {
        forceDrivingMode = true;
        onForcedDrivingModeEnabled();
      }
    } else if (motion <= MOTION_IDLE_THRESHOLD_MS2) {
      motionAboveSinceMs = 0L;
      if (motionBelowSinceMs == 0L) {
        motionBelowSinceMs = now;
      }
      if (forceDrivingMode && (now - motionBelowSinceMs >= MOTION_IDLE_RELEASE_MS)) {
        forceDrivingMode = false;
        onForcedDrivingModeReleased();
      }
    } else {
      motionAboveSinceMs = 0L;
      motionBelowSinceMs = 0L;
    }

    if ((now - lastMotionUiUpdateMs) >= 1000L) {
      lastMotionUiUpdateMs = now;
      updateDrivingModeUi(motion);
    }
  }

  private void onForcedDrivingModeEnabled() {
    updateDrivingModeUi(MOTION_FORCE_THRESHOLD_MS2);
    appendLine("DRIVE_MODE", "forced driving mode enabled from accelerometer motion");
    uiHandler.post(() -> baseUrlInput.setEnabled(false));
    if (lastMapLat == null && lastDeviceLat != null && lastDeviceLon != null) {
      lastMapLat = lastDeviceLat;
      lastMapLon = lastDeviceLon;
      updateMapTargetUi();
    }
    if (!running) {
      startStreaming();
    }
  }

  private void onForcedDrivingModeReleased() {
    updateDrivingModeUi(0f);
    appendLine("DRIVE_MODE", "forced driving mode released after sustained idle motion");
    uiHandler.post(() -> baseUrlInput.setEnabled(true));
  }

  private void updateDrivingModeUi(float motion) {
    uiHandler.post(
        () -> {
          if (drivingModeText == null) {
            return;
          }
          String mode = forceDrivingMode ? "FORCED ON" : "manual";
          drivingModeText.setText(
              String.format(
                  Locale.ROOT,
                  "Driving Mode: %s  |  accel=%.2f m/s²",
                  mode,
                  motion));
        });
  }

  private void startStreaming() {
    if (running) {
      return;
    }
    String base = normalizedBaseUrl();
    if (base == null) {
      setStatus("invalid URL");
      return;
    }
    running = true;
    setStatus("connecting...");
    appendLine("STREAM TARGET", base);
    final String target = base;
    new Thread(
            () -> {
              fetchSnapshot(target);
              streamSse(target);
            })
        .start();
    syncDeviceGpsToBackend();
  }

  private String normalizedBaseUrl() {
    String base = baseUrlInput.getText().toString().trim();
    if (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    if (!base.startsWith("http://") && !base.startsWith("https://")) {
      return null;
    }
    return base;
  }

  private void stopStreaming(String reason) {
    running = false;
    if (streamCall != null) {
      streamCall.cancel();
      streamCall = null;
    }
    setStatus(reason);
  }

  private void fetchSnapshot(String base) {
    Request request = new Request.Builder().url(base + "/api/pipeline/snapshot").build();
    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful() || response.body() == null) {
        appendLine("SNAPSHOT", "unavailable");
        return;
      }
      String body = response.body().string();
      JSONObject json = new JSONObject(body);
      JSONObject metrics = json.optJSONObject("metrics");
      if (metrics != null) {
        appendLine(
            "SNAPSHOT",
            String.format(
                Locale.ROOT,
                "captured=%d silence=%d clipped=%d alerts=%d fallback=%d",
                metrics.optInt("captured", 0),
                metrics.optInt("skipped_silence", 0),
                metrics.optInt("skipped_clipped", 0),
                metrics.optInt("llm_alert", 0),
                metrics.optInt("soft_alert_fallback", 0)));
      } else {
        appendLine("SNAPSHOT", "loaded");
      }
    } catch (Exception e) {
      appendLine("SNAPSHOT", "error: " + e.getMessage());
    }
  }

  private void streamSse(String base) {
    Request request = new Request.Builder().url(base + "/api/pipeline/stream").build();
    streamCall = client.newCall(request);
    try (Response response = streamCall.execute()) {
      if (!response.isSuccessful() || response.body() == null) {
        setStatus("stream unavailable");
        return;
      }
      setStatus("streaming");
      InputStream stream = response.body().byteStream();
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
        String line;
        while (running && (line = reader.readLine()) != null) {
          if (!line.startsWith("data:")) {
            continue;
          }
          String payload = line.substring(5).trim();
          if (payload.isEmpty()) {
            continue;
          }
          appendEvent(payload);
        }
      }
    } catch (IOException e) {
      if (running) {
        setStatus("stream error");
        appendLine("STREAM", "error: " + e.getMessage());
      }
    } finally {
      if (running) {
        setStatus("idle");
      }
      running = false;
    }
  }

  private void appendEvent(String payload) {
    try {
      JSONObject json = new JSONObject(payload);
      String eventType = json.optString("event_type", "unknown");
      String kind = json.optString("kind", "");
      String alert = json.optString("alert", "");
      String transcript = json.optString("transcript", "");
      String message = json.optString("message", "");
      String text;
      if (!alert.isEmpty()) {
        text = alert;
      } else if (!message.isEmpty()) {
        text = message;
      } else {
        text = transcript;
      }
      if (TextUtils.isEmpty(text)) {
        text = "(no text payload)";
      }
      captureMapTargetFromEventPayload(alert);
      captureMapTargetFromEventPayload(transcript);
      captureMapTargetFromEventPayload(message);
      String label =
          kind.isEmpty()
              ? eventType.toUpperCase(Locale.ROOT)
              : (eventType + "/" + kind).toUpperCase(Locale.ROOT);
      appendLine(label, text);
    } catch (JSONException e) {
      appendLine("PARSE", "error: " + e.getMessage());
    }
  }

  private void captureMapTargetFromEventPayload(String payloadText) {
    if (TextUtils.isEmpty(payloadText)) {
      return;
    }
    Matcher matcher = COORDINATE_PATTERN.matcher(payloadText);
    if (!matcher.find()) {
      return;
    }
    try {
      double lat = Double.parseDouble(matcher.group(1));
      double lon = Double.parseDouble(matcher.group(2));
      if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
        return;
      }
      lastMapLat = lat;
      lastMapLon = lon;
      updateMapTargetUi();
    } catch (NumberFormatException ignored) {
      // ignore malformed coordinate values
    }
  }

  private void updateMapTargetUi() {
    uiHandler.post(
        () -> {
          if (mapTargetText == null) {
            return;
          }
          if (lastMapLat != null && lastMapLon != null) {
            mapTargetText.setText(
                String.format(
                    Locale.ROOT,
                    "Map Target: %.5f, %.5f",
                    lastMapLat,
                    lastMapLon));
            return;
          }
          if (lastDeviceLat != null && lastDeviceLon != null) {
            mapTargetText.setText(
                String.format(
                    Locale.ROOT,
                    "Map Target: device %.5f, %.5f",
                    lastDeviceLat,
                    lastDeviceLon));
            return;
          }
          mapTargetText.setText(getString(R.string.map_target_none));
        });
  }

  private void openLatestMapTarget() {
    double targetLat;
    double targetLon;
    if (lastMapLat != null && lastMapLon != null) {
      targetLat = lastMapLat;
      targetLon = lastMapLon;
    } else if (lastDeviceLat != null && lastDeviceLon != null) {
      targetLat = lastDeviceLat;
      targetLon = lastDeviceLon;
      appendLine("MAPS", "using live device GPS as route target");
    } else {
      appendLine("MAPS", "no coordinate target available yet");
      return;
    }

    String base = normalizedBaseUrl();
    if (base != null) {
      double finalTargetLat = targetLat;
      double finalTargetLon = targetLon;
      new Thread(
              () -> {
                String serverRouteUrl = fetchServerRouteUrl(base, finalTargetLat, finalTargetLon);
                if (!TextUtils.isEmpty(serverRouteUrl)) {
                  openMapIntentFromUrl(serverRouteUrl, "server route");
                } else {
                  openGoogleNavigation(finalTargetLat, finalTargetLon, "fallback local route");
                }
              })
          .start();
      return;
    }
    openGoogleNavigation(targetLat, targetLon, "local route");
  }

  private void openGoogleNavigation(double lat, double lon, String sourceLabel) {
    String coord = String.format(Locale.ROOT, "%.6f,%.6f", lat, lon);
    Intent navIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + coord));
    navIntent.setPackage("com.google.android.apps.maps");
    navIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    try {
      startActivity(navIntent);
      appendLine("MAPS", "opened Google Maps " + sourceLabel + " to " + coord);
      return;
    } catch (ActivityNotFoundException ignored) {
      // fallback below
    }
    Intent geoIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + coord));
    geoIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    try {
      startActivity(geoIntent);
      appendLine("MAPS", "opened fallback maps app to " + coord);
    } catch (ActivityNotFoundException e) {
      appendLine("MAPS", "no maps app available: " + e.getMessage());
    }
  }

  private void openMapIntentFromUrl(String url, String sourceLabel) {
    uiHandler.post(
        () -> {
          try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            appendLine("MAPS", "opened " + sourceLabel + " URL");
          } catch (ActivityNotFoundException e) {
            appendLine("MAPS", "unable to open " + sourceLabel + ": " + e.getMessage());
          }
        });
  }

  private String fetchServerRouteUrl(String base, double lat, double lon) {
    String routeUrl =
        base
            + "/api/platform/waze/route?lat="
            + String.format(Locale.ROOT, "%.6f", lat)
            + "&lon="
            + String.format(Locale.ROOT, "%.6f", lon);
    Request request = new Request.Builder().url(routeUrl).build();
    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful() || response.body() == null) {
        return null;
      }
      JSONObject json = new JSONObject(response.body().string());
      String appUrl = json.optString("app_url", "");
      if (TextUtils.isEmpty(appUrl)) {
        return null;
      }
      return appUrl;
    } catch (Exception ignored) {
      return null;
    }
  }

  private void handleDeviceLocationUpdate(Location location) {
    lastDeviceLat = location.getLatitude();
    lastDeviceLon = location.getLongitude();
    lastDeviceAccuracyM = location.hasAccuracy() ? location.getAccuracy() : null;
    lastDeviceSpeedMps = location.hasSpeed() ? location.getSpeed() : null;
    lastDeviceHeadingDeg = location.hasBearing() ? location.getBearing() : null;
    if (lastMapLat == null || lastMapLon == null) {
      updateMapTargetUi();
    }
    syncDeviceGpsToBackend();
  }

  private String deriveDeviceCondition() {
    if (forceDrivingMode && running) {
      return "driving_streaming";
    }
    if (forceDrivingMode) {
      return "driving_idle";
    }
    if (running) {
      return "streaming_stationary";
    }
    if (lastMotionMagnitude >= MOTION_FORCE_THRESHOLD_MS2 * 0.6f) {
      return "motion_detected";
    }
    return "idle";
  }

  private void syncDeviceGpsToBackend() {
    if (lastDeviceLat == null || lastDeviceLon == null) {
      return;
    }
    if (!running && !forceDrivingMode) {
      return;
    }
    String base = normalizedBaseUrl();
    if (base == null) {
      return;
    }
    long now = SystemClock.elapsedRealtime();
    if ((now - lastDeviceGpsPostMs) < DEVICE_GPS_POST_INTERVAL_MS) {
      return;
    }
    lastDeviceGpsPostMs = now;
    String userId = "android-" + Build.MODEL.replaceAll("\\s+", "_").toLowerCase(Locale.ROOT);
    String payload =
        "{"
            + "\"user_id\":\""
            + userId
            + "\","
            + "\"source\":\"android_stream_client\","
            + "\"lat\":"
            + String.format(Locale.ROOT, "%.7f", lastDeviceLat)
            + ","
            + "\"lon\":"
            + String.format(Locale.ROOT, "%.7f", lastDeviceLon)
            + ","
            + "\"accuracy\":"
            + String.format(
                Locale.ROOT, "%.2f", lastDeviceAccuracyM != null ? lastDeviceAccuracyM : 0f)
            + ","
            + "\"speed\":"
            + String.format(Locale.ROOT, "%.2f", lastDeviceSpeedMps != null ? lastDeviceSpeedMps : 0f)
            + ","
            + "\"heading\":"
            + String.format(
                Locale.ROOT, "%.2f", lastDeviceHeadingDeg != null ? lastDeviceHeadingDeg : 0f)
            + ","
            + "\"device_condition\":\""
            + deriveDeviceCondition()
            + "\","
            + "\"forced_driving\":"
            + (forceDrivingMode ? "true" : "false")
            + "}";

    Request request =
        new Request.Builder()
            .url(base + "/api/gps/update")
            .post(RequestBody.create(payload, JSON_MEDIA_TYPE))
            .build();
    client.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                // non-blocking fire-and-forget sync
              }

              @Override
              public void onResponse(Call call, Response response) {
                response.close();
              }
            });
  }

  private void setStatus(String status) {
    uiHandler.post(() -> statusText.setText("Status: " + status));
  }

  private void appendLine(String label, String text) {
    uiHandler.post(
        () -> {
          String existing = outputText.getText().toString();
          if (getString(R.string.stream_placeholder).equals(existing)) {
            existing = "";
          }
          if (existing.length() > 20000) {
            existing = existing.substring(existing.length() - 12000);
          }
          String stamp = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
          outputText.setText(existing + "[" + stamp + "] " + label + "  " + text + "\n");
        });
  }
}
