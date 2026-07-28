package dev.warp.scannerstream;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
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
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

public class MainActivity extends AppCompatActivity {
  private static final float MOTION_FORCE_THRESHOLD_MS2 = 1.8f;
  private static final float MOTION_IDLE_THRESHOLD_MS2 = 0.35f;
  private static final long MOTION_FORCE_HOLD_MS = 4000L;
  private static final long MOTION_IDLE_RELEASE_MS = 12000L;
  private static final int LOCATION_PERMISSION_REQUEST_CODE = 4102;
  private static final long LOCATION_UPDATE_INTERVAL_MS = 2000L;
  private static final float LOCATION_MIN_DISTANCE_M = 3f;
  private static final long DEVICE_GPS_POST_INTERVAL_MS = 3000L;
  private static final long SERVER_ROUTE_REFRESH_MS = 5000L;
  private static final long POPUP_REPEAT_SUPPRESS_MS = 30000L;
  private static final long POPUP_AUTO_HIDE_MS = 12000L;
  private static final double DEFAULT_MAP_LAT = 37.7749;
  private static final double DEFAULT_MAP_LON = -122.4194;
  private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
  private static final Pattern COORDINATE_PATTERN =
      Pattern.compile("\\b(-?\\d{1,2}\\.\\d+)\\s*[, ]\\s*(-?\\d{1,3}\\.\\d+)\\b");

  private EditText baseUrlInput;
  private EditText destinationInput;
  private TextView statusText;
  private TextView drivingModeText;
  private TextView mapTargetText;
  private TextView outputText;
  private Button menuBtn;
  private View controlPanel;
  private MapView osmMapView;
  private Map3dView map3dView;
  private Button mapModeBtn;
  private View zoomControls;
  private boolean map3dEnabled = false;
  private volatile boolean sceneFetchInFlight = false;
  private volatile long lastSceneFetchMs = 0L;
  private volatile double lastSceneLat = Double.NaN;
  private volatile double lastSceneLon = Double.NaN;
  private volatile double lastSceneRadiusM = 700.0;
  private Polyline osmRoutePolyline;
  private Marker osmDeviceMarker;
  private Marker osmTargetMarker;
  private LinearLayout locationPopup;
  private TextView popupTitle;
  private TextView popupLocationText;
  private TextView popupTranscriptText;
  private AudioVisualizerView popupVisualizer;
  private volatile String pendingPopupQuery = null;
  private String lastPopupMentionKey = "";
  private long lastPopupShownMs = 0L;
  private boolean osmCenteredOnFix = false;
  private final Handler uiHandler = new Handler(Looper.getMainLooper());
  private final Runnable popupAutoHideRunnable = this::hideLocationPopup;
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
  private boolean serverRouteRequestInFlight = false;
  private long lastServerRouteFetchMs = 0L;
  private String lastServerRouteFingerprint = "";
  private final List<double[]> currentRoutePoints = new ArrayList<>();

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
    Configuration.getInstance().setUserAgentValue(getPackageName());
    Configuration.getInstance().setOsmdroidBasePath(new File(getCacheDir(), "osmdroid"));
    Configuration.getInstance().setOsmdroidTileCache(new File(getCacheDir(), "osmdroid/tiles"));
    setContentView(R.layout.activity_main);
    baseUrlInput = findViewById(R.id.baseUrlInput);
    destinationInput = findViewById(R.id.destinationInput);
    statusText = findViewById(R.id.statusText);
    drivingModeText = findViewById(R.id.drivingModeText);
    mapTargetText = findViewById(R.id.mapTargetText);
    outputText = findViewById(R.id.outputText);
    osmMapView = findViewById(R.id.osmMapView);
    map3dView = findViewById(R.id.map3dView);
    mapModeBtn = findViewById(R.id.mapModeBtn);
    zoomControls = findViewById(R.id.zoomControls);
    Button zoomInBtn = findViewById(R.id.zoomInBtn);
    Button zoomOutBtn = findViewById(R.id.zoomOutBtn);
    zoomInBtn.setOnClickListener(v -> map3dView.zoomBy(0.5f));
    zoomOutBtn.setOnClickListener(v -> map3dView.zoomBy(2.0f));
    menuBtn = findViewById(R.id.menuBtn);
    controlPanel = findViewById(R.id.controlPanel);
    locationPopup = findViewById(R.id.locationPopup);
    popupTitle = findViewById(R.id.popupTitle);
    popupLocationText = findViewById(R.id.popupLocationText);
    popupTranscriptText = findViewById(R.id.popupTranscriptText);
    popupVisualizer = findViewById(R.id.popupVisualizer);
    Button connectBtn = findViewById(R.id.connectBtn);
    Button disconnectBtn = findViewById(R.id.disconnectBtn);
    Button clearLogBtn = findViewById(R.id.clearLogBtn);
    Button openMapsBtn = findViewById(R.id.openMapsBtn);
    Button drawRouteBtn = findViewById(R.id.drawRouteBtn);
    Button searchBtn = findViewById(R.id.searchBtn);
    Button popupRouteBtn = findViewById(R.id.popupRouteBtn);
    Button popupDismissBtn = findViewById(R.id.popupDismissBtn);

    setupOsmMap();

    sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
    if (sensorManager != null) {
      accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }
    locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

    mapModeBtn.setOnClickListener(v -> toggleMapMode());
    map3dView.setRefetchListener((lat, lon, radiusM) -> fetchMapScene(lat, lon, radiusM, false));
    connectBtn.setOnClickListener(v -> startStreaming());
    disconnectBtn.setOnClickListener(v -> stopStreaming("disconnected"));
    clearLogBtn.setOnClickListener(v -> outputText.setText(getString(R.string.stream_placeholder)));
    openMapsBtn.setOnClickListener(v -> openLatestMapTarget());
    drawRouteBtn.setOnClickListener(v -> renderRouteOnMap(true));
    menuBtn.setOnClickListener(v -> setControlPanelVisible(controlPanel.getVisibility() != View.VISIBLE));
    searchBtn.setOnClickListener(v -> searchDestination());
    popupRouteBtn.setOnClickListener(v -> routeToPopupLocation());
    popupDismissBtn.setOnClickListener(v -> hideLocationPopup());
    appendLine("MAP", "OpenStreetMap base layer active (osmdroid + OSRM routing)");

    setStatus("idle");
    updateDrivingModeUi(0f);
    updateMapTargetUi();
    renderRouteOnMap(true);
  }

  private void setupOsmMap() {
    osmMapView.setTileSource(TileSourceFactory.MAPNIK);
    osmMapView.setMultiTouchControls(true);
    osmMapView.getController().setZoom(15.5d);
    osmMapView.getController().setCenter(new GeoPoint(DEFAULT_MAP_LAT, DEFAULT_MAP_LON));

    osmRoutePolyline = new Polyline(osmMapView);
    osmRoutePolyline.getOutlinePaint().setColor(Color.parseColor("#2B6BE6"));
    osmRoutePolyline.getOutlinePaint().setStrokeWidth(10f);

    osmDeviceMarker = new Marker(osmMapView);
    osmDeviceMarker.setTitle("You");
    osmDeviceMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);

    osmTargetMarker = new Marker(osmMapView);
    osmTargetMarker.setTitle("Destination");
    osmTargetMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);

    osmMapView.getOverlays().add(osmRoutePolyline);
    osmMapView.getOverlays().add(osmDeviceMarker);
    osmMapView.getOverlays().add(osmTargetMarker);
  }

  private void searchDestination() {
    String query = destinationInput.getText().toString().trim();
    if (query.isEmpty()) {
      appendLine("SEARCH", "enter a destination address first");
      return;
    }
    geocodeAndRoute(query, "SEARCH");
  }

  private void geocodeAndRoute(String query, String label) {
    String base = normalizedBaseUrl();
    if (base == null) {
      setStatus("invalid URL");
      return;
    }
    appendLine(label, "geocoding via OSM: " + query);
    String url = base + "/api/platform/geocode?q=" + Uri.encode(query);
    Request request = new Request.Builder().url(url).build();
    client.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                appendLine(label, "geocode failed: " + e.getMessage());
              }

              @Override
              public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                  if (!response.isSuccessful() || response.body() == null) {
                    appendLine(label, "geocode unavailable (HTTP " + response.code() + ")");
                    return;
                  }
                  JSONObject json = new JSONObject(response.body().string());
                  JSONArray results = json.optJSONArray("results");
                  if (results == null || results.length() == 0) {
                    appendLine(label, "no OSM matches for: " + query);
                    return;
                  }
                  JSONObject first = results.optJSONObject(0);
                  if (first == null) {
                    appendLine(label, "malformed geocode result");
                    return;
                  }
                  double lat = Double.parseDouble(first.optString("lat", "nan"));
                  double lon = Double.parseDouble(first.optString("lon", "nan"));
                  if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
                    appendLine(label, "geocode result missing coordinates");
                    return;
                  }
                  String displayName = first.optString("display_name", query);
                  lastMapLat = lat;
                  lastMapLon = lon;
                  updateMapTargetUi();
                  appendLine(label, "destination: " + displayName);
                  uiHandler.post(
                      () -> {
                        osmMapView.getController().animateTo(new GeoPoint(lat, lon));
                        destinationInput.clearFocus();
                      });
                  renderRouteOnMap(true);
                } catch (Exception e) {
                  appendLine(label, "geocode parse error: " + e.getMessage());
                }
              }
            });
  }

  private void toggleMapMode() {
    map3dEnabled = !map3dEnabled;
    mapModeBtn.setText(map3dEnabled ? getString(R.string.map_mode_3d) : getString(R.string.map_mode_osm));
    map3dView.setVisibility(map3dEnabled ? View.VISIBLE : View.GONE);
    osmMapView.setVisibility(map3dEnabled ? View.GONE : View.VISIBLE);
    zoomControls.setVisibility(map3dEnabled ? View.VISIBLE : View.GONE);
    if (map3dEnabled) {
      double lat;
      double lon;
      if (lastDeviceLat != null && lastDeviceLon != null) {
        lat = lastDeviceLat;
        lon = lastDeviceLon;
      } else if (lastMapLat != null && lastMapLon != null) {
        lat = lastMapLat;
        lon = lastMapLon;
      } else {
        lat = DEFAULT_MAP_LAT;
        lon = DEFAULT_MAP_LON;
      }
      map3dView.recenter();
      fetchMapScene(lat, lon, 700.0, true);
      appendLine("MAP", "3D vector mode on (proprietary engine)");
    } else {
      appendLine("MAP", "OSM raster mode on");
    }
  }

  private void fetchMapScene(double lat, double lon, double radiusM, boolean force) {
    String base = normalizedBaseUrl();
    if (base == null || !map3dEnabled) {
      return;
    }
    long now = SystemClock.elapsedRealtime();
    if (sceneFetchInFlight) {
      return;
    }
    if (!force && (now - lastSceneFetchMs) < 4000L) {
      return;
    }
    sceneFetchInFlight = true;
    lastSceneFetchMs = now;
    String url =
        base
            + "/api/map/scene?lat="
            + String.format(Locale.ROOT, "%.6f", lat)
            + "&lon="
            + String.format(Locale.ROOT, "%.6f", lon)
            + "&radius_m="
            + Math.round(radiusM);
    Request request = new Request.Builder().url(url).build();
    client.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                sceneFetchInFlight = false;
                appendLine("MAP3D", "scene fetch failed: " + e.getMessage());
              }

              @Override
              public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                  if (!response.isSuccessful() || response.body() == null) {
                    appendLine("MAP3D", "scene unavailable (HTTP " + response.code() + ")");
                    return;
                  }
                  String body = response.body().string();
                  map3dView.setSceneJson(body);
                  lastSceneLat = lat;
                  lastSceneLon = lon;
                  lastSceneRadiusM = radiusM;
                } catch (Exception e) {
                  appendLine("MAP3D", "scene parse failed: " + e.getMessage());
                } finally {
                  sceneFetchInFlight = false;
                }
              }
            });
  }

  private void maybeRefreshSceneForDevice() {
    if (!map3dEnabled || lastDeviceLat == null || lastDeviceLon == null) {
      return;
    }
    if (Double.isNaN(lastSceneLat) || Double.isNaN(lastSceneLon)) {
      fetchMapScene(lastDeviceLat, lastDeviceLon, 700.0, true);
      return;
    }
    double dLat = (lastDeviceLat - lastSceneLat) * 110540.0;
    double dLon =
        (lastDeviceLon - lastSceneLon)
            * 111320.0
            * Math.max(0.2, Math.cos(Math.toRadians(lastDeviceLat)));
    // Refetch when the device leaves ~40% of the loaded scene radius.
    if (Math.hypot(dLat, dLon) > Math.max(280.0, lastSceneRadiusM * 0.4)) {
      fetchMapScene(lastDeviceLat, lastDeviceLon, lastSceneRadiusM, false);
    }
  }

  private void setControlPanelVisible(boolean visible) {
    controlPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
    menuBtn.setText(visible ? getString(R.string.menu_close) : getString(R.string.menu_open));
  }

  @Override
  public void onBackPressed() {
    if (controlPanel != null && controlPanel.getVisibility() == View.VISIBLE) {
      setControlPanelVisible(false);
      return;
    }
    super.onBackPressed();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (osmMapView != null) {
      osmMapView.onResume();
    }
    registerMotionDetection();
    registerLocationTracking();
  }

  @Override
  protected void onPause() {
    if (osmMapView != null) {
      osmMapView.onPause();
    }
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
      List<String> mentions = new ArrayList<>();
      collectMentions(json.optJSONArray("location_mentions"), mentions);
      collectMentions(json.optJSONArray("poi_mentions"), mentions);
      if (!mentions.isEmpty()) {
        maybeShowLocationPopup(eventType, mentions, text, json.optDouble("rms", 0.0));
      }
      String label =
          kind.isEmpty()
              ? eventType.toUpperCase(Locale.ROOT)
              : (eventType + "/" + kind).toUpperCase(Locale.ROOT);
      appendLine(label, text);
    } catch (JSONException e) {
      appendLine("PARSE", "error: " + e.getMessage());
    }
  }

  private void collectMentions(JSONArray array, List<String> sink) {
    if (array == null) {
      return;
    }
    for (int i = 0; i < array.length(); i++) {
      String mention = array.optString(i, "").trim();
      if (!mention.isEmpty() && !sink.contains(mention)) {
        sink.add(mention);
      }
    }
  }

  private void maybeShowLocationPopup(
      String eventType, List<String> mentions, String text, double rms) {
    String key = TextUtils.join("|", mentions).toLowerCase(Locale.ROOT);
    long now = SystemClock.elapsedRealtime();
    boolean isAlert = "alert_triggered".equals(eventType);
    if (!isAlert
        && key.equals(lastPopupMentionKey)
        && (now - lastPopupShownMs) < POPUP_REPEAT_SUPPRESS_MS) {
      return;
    }
    lastPopupMentionKey = key;
    lastPopupShownMs = now;
    pendingPopupQuery = mentions.get(0);
    float amplitude = (float) Math.min(1.0, Math.max(0.0, rms * 8.0));
    String title =
        isAlert ? getString(R.string.popup_title_alert) : getString(R.string.popup_title_location);
    String locations = TextUtils.join("  \u2022  ", mentions);
    appendLine("LOCATION", locations);
    uiHandler.post(
        () -> {
          popupTitle.setText(title);
          popupLocationText.setText(locations);
          popupTranscriptText.setText(text);
          popupVisualizer.setAmplitude(amplitude);
          popupVisualizer.start();
          locationPopup.setVisibility(View.VISIBLE);
          uiHandler.removeCallbacks(popupAutoHideRunnable);
          uiHandler.postDelayed(popupAutoHideRunnable, POPUP_AUTO_HIDE_MS);
        });
  }

  private void hideLocationPopup() {
    uiHandler.removeCallbacks(popupAutoHideRunnable);
    uiHandler.post(
        () -> {
          popupVisualizer.stop();
          locationPopup.setVisibility(View.GONE);
        });
  }

  private void routeToPopupLocation() {
    String query = pendingPopupQuery;
    hideLocationPopup();
    if (TextUtils.isEmpty(query)) {
      return;
    }
    geocodeAndRoute(query, "LOCATION");
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
      renderRouteOnMap(true);
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
    map3dView.updateDevice(
        location.getLatitude(), location.getLongitude(), lastDeviceHeadingDeg, lastDeviceSpeedMps);
    maybeRefreshSceneForDevice();
    renderRouteOnMap(false);
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

  private void renderRouteOnMap(boolean forceServerRefresh) {
    double originLat = lastDeviceLat != null ? lastDeviceLat : (lastMapLat != null ? lastMapLat : DEFAULT_MAP_LAT);
    double originLon = lastDeviceLon != null ? lastDeviceLon : (lastMapLon != null ? lastMapLon : DEFAULT_MAP_LON);
    double targetLat = (lastMapLat != null) ? lastMapLat : originLat + 0.0045d;
    double targetLon = (lastMapLon != null) ? lastMapLon : originLon + 0.0065d;

    maybeFetchServerRoute(originLat, originLon, targetLat, targetLon, forceServerRefresh);
    pushRouteSceneToView(originLat, originLon, targetLat, targetLon);
  }

  private void pushRouteSceneToView(double originLat, double originLon, double targetLat, double targetLon) {
    List<double[]> routeSnapshot;
    synchronized (currentRoutePoints) {
      routeSnapshot = new ArrayList<>(currentRoutePoints);
    }
    boolean hasFix = lastDeviceLat != null && lastDeviceLon != null;
    map3dView.setRoute(routeSnapshot);
    if (lastMapLat != null && lastMapLon != null) {
      map3dView.setDestination(lastMapLat, lastMapLon);
    } else {
      map3dView.setDestination(null, null);
    }
    uiHandler.post(
        () -> updateOsmOverlays(originLat, originLon, targetLat, targetLon, routeSnapshot, hasFix));
  }

  private void updateOsmOverlays(
      double originLat,
      double originLon,
      double targetLat,
      double targetLon,
      List<double[]> route,
      boolean hasFix) {
    if (osmMapView == null || osmRoutePolyline == null) {
      return;
    }
    List<GeoPoint> geoPoints = new ArrayList<>();
    for (double[] point : route) {
      if (point != null && point.length >= 2) {
        geoPoints.add(new GeoPoint(point[0], point[1]));
      }
    }
    osmRoutePolyline.setPoints(geoPoints);
    osmDeviceMarker.setPosition(new GeoPoint(originLat, originLon));
    osmTargetMarker.setPosition(new GeoPoint(targetLat, targetLon));
    if (hasFix && !osmCenteredOnFix) {
      osmCenteredOnFix = true;
      osmMapView.getController().animateTo(new GeoPoint(originLat, originLon));
    }
    osmMapView.invalidate();
  }

  private void maybeFetchServerRoute(
      double originLat, double originLon, double destLat, double destLon, boolean forceRefresh) {
    String base = normalizedBaseUrl();
    if (base == null) {
      return;
    }
    String fingerprint =
        String.format(
            Locale.ROOT,
            "%.4f,%.4f->%.4f,%.4f",
            originLat,
            originLon,
            destLat,
            destLon);
    long now = SystemClock.elapsedRealtime();
    if (serverRouteRequestInFlight) {
      return;
    }
    if (!forceRefresh
        && fingerprint.equals(lastServerRouteFingerprint)
        && (now - lastServerRouteFetchMs) < SERVER_ROUTE_REFRESH_MS) {
      return;
    }
    serverRouteRequestInFlight = true;
    lastServerRouteFingerprint = fingerprint;
    lastServerRouteFetchMs = now;

    String routeUrl =
        base
            + "/api/platform/route/local"
            + "?origin_lat="
            + String.format(Locale.ROOT, "%.6f", originLat)
            + "&origin_lon="
            + String.format(Locale.ROOT, "%.6f", originLon)
            + "&dest_lat="
            + String.format(Locale.ROOT, "%.6f", destLat)
            + "&dest_lon="
            + String.format(Locale.ROOT, "%.6f", destLon)
            + "&condition="
            + Uri.encode(deriveDeviceCondition());
    Request request = new Request.Builder().url(routeUrl).build();
    client.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                serverRouteRequestInFlight = false;
              }

              @Override
              public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                  if (!response.isSuccessful() || response.body() == null) {
                    return;
                  }
                  JSONObject json = new JSONObject(response.body().string());
                  JSONArray routePoints = json.optJSONArray("route_points");
                  if (routePoints == null || routePoints.length() < 2) {
                    return;
                  }
                  List<double[]> serverRoute = new ArrayList<>();
                  for (int i = 0; i < routePoints.length(); i++) {
                    JSONObject point = routePoints.optJSONObject(i);
                    if (point == null) {
                      continue;
                    }
                    double lat = point.optDouble("lat", Double.NaN);
                    double lon = point.optDouble("lon", Double.NaN);
                    if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
                      continue;
                    }
                    serverRoute.add(new double[] {lat, lon});
                  }
                  if (serverRoute.size() < 2) {
                    return;
                  }
                  synchronized (currentRoutePoints) {
                    currentRoutePoints.clear();
                    currentRoutePoints.addAll(serverRoute);
                  }
                  pushRouteSceneToView(originLat, originLon, destLat, destLon);
                } catch (Exception ignored) {
                  // previous route remains active
                } finally {
                  serverRouteRequestInFlight = false;
                }
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
