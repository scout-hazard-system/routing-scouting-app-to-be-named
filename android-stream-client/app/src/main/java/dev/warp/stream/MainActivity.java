package dev.warp.stream;

import android.Manifest;
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
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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

public class MainActivity extends AppCompatActivity {
  private static final String TAG = "MainActivity";
  private static final float MOTION_FORCE_THRESHOLD_MS2 = 1.8f;
  private static final float MOTION_IDLE_THRESHOLD_MS2 = 0.35f;
  private static final long MOTION_FORCE_HOLD_MS = 4000L;
  private static final long MOTION_IDLE_RELEASE_MS = 12000L;
  private static final int LOCATION_PERMISSION_REQUEST_CODE = 4102;
  private static final long LOCATION_UPDATE_INTERVAL_MS = 2000L;
  private static final float LOCATION_MIN_DISTANCE_M = 3f;
  private static final long DEVICE_GPS_POST_INTERVAL_MS = 3000L;
  private static final long SERVER_ROUTE_REFRESH_MS = 5000L;
  private static final long ERROR_REPORT_POLL_INTERVAL_MS = 12000L;
  private static final int ERROR_REPORT_SEEN_MAX_IDS = 180;
  private static final long POPUP_REPEAT_SUPPRESS_MS = 30000L;
  private static final long POPUP_AUTO_HIDE_MS = 12000L;
  private static final double DEFAULT_MAP_LAT = 37.7749;
  private static final double DEFAULT_MAP_LON = -122.4194;
  private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
  private static final String STATE_MAP3D_ENABLED = "state_map3d_enabled";
  private static final String STATE_MAP_LAT = "state_map_lat";
  private static final String STATE_MAP_LON = "state_map_lon";
  private static final String STATE_DEVICE_LAT = "state_device_lat";
  private static final String STATE_DEVICE_LON = "state_device_lon";
  private static final Pattern COORDINATE_PATTERN =
      Pattern.compile("\\b(-?\\d{1,2}\\.\\d+)\\s*[, ]\\s*(-?\\d{1,3}\\.\\d+)\\b");
  /** Mention-extractor tokens that are useless as geocode queries (directions, road furniture). */
  private static final Set<String> NON_ROUTABLE_MENTIONS =
      new HashSet<>(
          Arrays.asList(
              "northbound",
              "southbound",
              "eastbound",
              "westbound",
              "shoulder",
              "on-ramp",
              "off-ramp",
              "interchange"));
  private static final Pattern DIRECTIONAL_TOKEN_PATTERN =
      Pattern.compile("(?i)\\b(northbound|southbound|eastbound|westbound)\\b");
  private static final boolean ENABLE_DEV_CONTROLS = BuildConfig.ENABLE_DEV_CONTROLS;

  private EditText baseUrlInput;
  private AutoCompleteTextView destinationInput;
  private TextView statusText;
  private TextView drivingModeText;
  private TextView mapTargetText;
  private TextView outputText;
  private Button menuBtn;
  private Button errorReportBtn;
  private TextView stackManageStatusText;
  private View controlPanel;
  private Map3dView map3dView;
  private Button mapModeBtn;
  private Button tailscaleModeBtn;
  private View zoomControls;
  private boolean map3dEnabled = true;
  private volatile boolean sceneFetchInFlight = false;
  private volatile boolean sceneRetryScheduled = false;
  private volatile long lastSceneFetchMs = 0L;
  private volatile double lastSceneLat = Double.NaN;
  private volatile double lastSceneLon = Double.NaN;
  private volatile double lastSceneRadiusM = 700.0;
  private LinearLayout locationPopup;
  private TextView popupTitle;
  private TextView popupLocationText;
  private TextView popupIntelText;
  private TextView popupTranscriptText;
  private TextView errorReportStatusText;
  private AudioVisualizerView popupVisualizer;
  private Button popupRouteBtn;
  private volatile String pendingPopupQuery = null;
  private String lastPopupMentionKey = "";
  private long lastPopupShownMs = 0L;
  private final Handler uiHandler = new Handler(Looper.getMainLooper());
  private final Runnable popupAutoHideRunnable = this::hideLocationPopup;
  private final OkHttpClient client = new OkHttpClient.Builder().build();
  // SSE stream can be quiet for long stretches (pipeline emits ~every 12s or
  // slower); the default 10s read timeout was killing the connection, so the
  // stream client reads forever and streamSse() reconnects on failure.
  private final OkHttpClient sseClient =
      new OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build();
  private final AddressCatalogRouter addressCatalogRouter = new AddressCatalogRouter(client);
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
  private String cachedClientId = null;
  private volatile String clientPullToken = "";
  private final String streamSessionId = Long.toHexString(System.currentTimeMillis());
  private final List<double[]> currentRoutePoints = new ArrayList<>();
  private final List<AddressCatalogRouter.AddressCandidate> destinationSuggestionCandidates =
      new ArrayList<>();
  private final List<String> destinationSuggestionLabels = new ArrayList<>();
  private ArrayAdapter<String> destinationSuggestionAdapter;
  private final Runnable destinationSuggestRunnable = this::requestDestinationSuggestions;
  private final Runnable errorReportPollRunnable = this::pollErrorReportsLoop;
  private long destinationSuggestGeneration = 0L;
  private String pendingSuggestQuery = "";
  private AddressCatalogRouter.AddressCandidate selectedDestinationSuggestion = null;
  private long lastErrorReportPollMs = 0L;
  private long lastSeenErrorReportCreatedAtMs = 0L;
  private final Set<String> seenErrorReportIdSet = new HashSet<>();
  private final Deque<String> seenErrorReportIds = new ArrayDeque<>();
  private boolean trackingConsentDialogShowing = false;
  private boolean trackingDisabledNoticeLogged = false;

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
    destinationInput = findViewById(R.id.destinationInput);
    destinationSuggestionAdapter =
        new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, destinationSuggestionLabels);
    destinationInput.setAdapter(destinationSuggestionAdapter);
    destinationInput.setThreshold(1);
    destinationInput.setOnItemClickListener(
        (parent, view, position, id) -> {
          if (position >= 0 && position < destinationSuggestionCandidates.size()) {
            selectedDestinationSuggestion = destinationSuggestionCandidates.get(position);
          }
        });
    destinationInput.setOnEditorActionListener(
        (v, actionId, event) -> {
          if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
            searchDestination();
            return true;
          }
          return false;
        });
    destinationInput.addTextChangedListener(
        new TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          @Override
          public void onTextChanged(CharSequence s, int start, int before, int count) {
            String text = s == null ? "" : s.toString().trim();
            if (selectedDestinationSuggestion != null
                && !text.equalsIgnoreCase(selectedDestinationSuggestion.displayName)) {
              selectedDestinationSuggestion = null;
            }
            scheduleDestinationSuggestions(text);
          }

          @Override
          public void afterTextChanged(Editable s) {}
        });
    statusText = findViewById(R.id.statusText);
    drivingModeText = findViewById(R.id.drivingModeText);
    mapTargetText = findViewById(R.id.mapTargetText);
    outputText = findViewById(R.id.outputText);
    TextView osmOdbNoticeText = findViewById(R.id.osmOdbNoticeText);
    map3dView = findViewById(R.id.map3dView);
    mapModeBtn = findViewById(R.id.mapModeBtn);
    tailscaleModeBtn = findViewById(R.id.tailscaleModeBtn);
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
    popupIntelText = findViewById(R.id.popupIntelText);
    popupTranscriptText = findViewById(R.id.popupTranscriptText);
    popupVisualizer = findViewById(R.id.popupVisualizer);
    Button connectBtn = findViewById(R.id.connectBtn);
    Button disconnectBtn = findViewById(R.id.disconnectBtn);
    Button clearLogBtn = findViewById(R.id.clearLogBtn);
    Button openMapsBtn = findViewById(R.id.openMapsBtn);
    errorReportBtn = findViewById(R.id.errorReportBtn);
    errorReportStatusText = findViewById(R.id.errorReportStatusText);
    stackManageStatusText = findViewById(R.id.stackManageStatusText);
    Button stackStatusBtn = findViewById(R.id.stackStatusBtn);
    Button stackHealthBtn = findViewById(R.id.stackHealthBtn);
    Button stackStartBtn = findViewById(R.id.stackStartBtn);
    Button stackRestartBtn = findViewById(R.id.stackRestartBtn);
    Button stackStopBtn = findViewById(R.id.stackStopBtn);
    Button drawRouteBtn = findViewById(R.id.drawRouteBtn);
    Button searchBtn = findViewById(R.id.searchBtn);
    popupRouteBtn = findViewById(R.id.popupRouteBtn);
    View popupDismissBtn = findViewById(R.id.popupDismissBtn);
    if (osmOdbNoticeText != null) {
      osmOdbNoticeText.setMovementMethod(LinkMovementMethod.getInstance());
    }


    sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
    if (sensorManager != null) {
      accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }
    locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    baseUrlInput.setText(AppPrefs.baseUrl(this));
    new Thread(
            () -> {
              String resolved = AppPrefs.resolveReachableBaseUrl(this);
              AppPrefs.saveBaseUrl(this, resolved);
              uiHandler.post(() -> baseUrlInput.setText(resolved));
            })
        .start();

    mapModeBtn.setOnClickListener(v -> toggleMapMode());
    if (tailscaleModeBtn != null) {
      updateTailscaleModeButton();
      tailscaleModeBtn.setOnClickListener(v -> setTailscaleModeEnabled(!AppPrefs.preferTailscale(this)));
    }
    map3dView.setRefetchListener((lat, lon, radiusM) -> fetchMapScene(lat, lon, radiusM, false));
    connectBtn.setOnClickListener(v -> startStreaming());
    disconnectBtn.setOnClickListener(v -> stopStreaming("disconnected"));
    clearLogBtn.setOnClickListener(v -> outputText.setText(getString(R.string.stream_placeholder)));
    openMapsBtn.setOnClickListener(v -> openLatestMapTarget());
    drawRouteBtn.setOnClickListener(v -> renderRouteOnMap(true));
    menuBtn.setOnClickListener(v -> setControlPanelVisible(controlPanel.getVisibility() != View.VISIBLE));
    searchBtn.setOnClickListener(v -> searchDestination());
    if (errorReportBtn != null) {
      errorReportBtn.setText(getString(R.string.error_report_button));
      errorReportBtn.setOnClickListener(v -> showErrorReportDialog());
    }
    updateErrorReportStatus(getString(R.string.error_report_status_idle));
    updateStackManageStatus(getString(R.string.stack_status_idle));
    if (stackStatusBtn != null) {
      stackStatusBtn.setOnClickListener(v -> runStackManageAction("status"));
    }
    if (stackHealthBtn != null) {
      stackHealthBtn.setOnClickListener(v -> runStackManageAction("health"));
    }
    if (stackStartBtn != null) {
      stackStartBtn.setOnClickListener(v -> runStackManageAction("start"));
    }
    if (stackRestartBtn != null) {
      stackRestartBtn.setOnClickListener(v -> runStackManageAction("restart"));
    }
    if (stackStopBtn != null) {
      stackStopBtn.setOnClickListener(v -> runStackManageAction("stop"));
    }
    popupRouteBtn.setOnClickListener(v -> routeToPopupLocation());
    popupDismissBtn.setOnClickListener(v -> hideLocationPopup());
    if (!ENABLE_DEV_CONTROLS) {
      statusText.setOnLongClickListener(
          v -> {
            showEndpointOverrideDialog();
            return true;
          });
    }
    appendLine("MAP", "vector map engine active (OSM road geometry as line data)");
    applyAppModeUi();

    restoreUiState(savedInstanceState);
    applyMapMode(true);

    setStatus("idle");
    updateDrivingModeUi(0f);
    updateMapTargetUi();
    renderRouteOnMap(true);
  }

  private String relayUserId() {
    return "android-" + Build.MODEL.replaceAll("\\s+", "_").toLowerCase(Locale.ROOT);
  }

  private String clientId() {
    if (!TextUtils.isEmpty(cachedClientId)) {
      return cachedClientId;
    }
    String androidId = "";
    try {
      androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
    } catch (Exception ignored) {
      androidId = "";
    }
    if (TextUtils.isEmpty(androidId)) {
      androidId = "unknown";
    }
    cachedClientId =
        ("android-stream-" + Build.MODEL + "-" + androidId)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._-]", "_");
    return cachedClientId;
  }

  private void registerClientRoute(String base) {
    if (TextUtils.isEmpty(base)) {
      return;
    }
    boolean analyticsOptOut = !AppPrefs.isAnalyticsEnabled(this);
    String payload =
        "{"
            + "\"client_id\":\""
            + clientId()
            + "\","
            + "\"user_id\":\""
            + relayUserId()
            + "\","
            + "\"source\":\"android_stream_client\","
            + "\"analytics_opt_out\":"
            + (analyticsOptOut ? "true" : "false")
            + ","
            + "\"session_id\":\""
            + streamSessionId
            + "\"}";
    Request request =
        new Request.Builder()
            .url(base + "/api/mobile/client/register")
            .post(RequestBody.create(payload, JSON_MEDIA_TYPE))
            .build();
    client.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                // best effort registration
              }

              @Override
              public void onResponse(Call call, Response response) {
                try (response) {
                  if (!response.isSuccessful() || response.body() == null) {
                    return;
                  }
                  JSONObject json = new JSONObject(response.body().string());
                  String token = json.optString("pull_token", "").trim();
                  if (!token.isEmpty()) {
                    clientPullToken = token;
                  }
                } catch (Exception ignored) {
                  // best effort registration
                }
              }
            });
  }

  private void showEndpointOverrideDialog() {
    LinearLayout container = new LinearLayout(this);
    container.setOrientation(LinearLayout.VERTICAL);
    EditText input = new EditText(this);
    input.setSingleLine(true);
    input.setText(AppPrefs.baseUrl(this));
    input.setSelection(input.getText().length());
    CheckBox tailscaleBox = new CheckBox(this);
    tailscaleBox.setText(getString(R.string.tailscale_mode_checkbox));
    tailscaleBox.setChecked(AppPrefs.preferTailscale(this));
    container.addView(input);
    container.addView(tailscaleBox);
    new AlertDialog.Builder(this)
        .setTitle("Server URL")
        .setMessage("Set backend URL for Internet/off-network use.")
        .setView(container)
        .setPositiveButton(
            "Save",
            (dialog, which) -> {
              String normalized = normalizeBaseUrlCandidate(input.getText().toString());
              if (normalized == null) {
                appendLine("NET", "invalid server URL (must start with http:// or https://)");
                return;
              }
              AppPrefs.saveBaseUrl(this, normalized);
              setTailscaleModeEnabled(tailscaleBox.isChecked());
              String resolved = AppPrefs.resolveReachableBaseUrl(this);
              if (baseUrlInput != null) {
                baseUrlInput.setText(resolved);
              }
              appendLine("NET", "server URL updated to " + resolved);
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void setTailscaleModeEnabled(boolean enabled) {
    AppPrefs.setPreferTailscale(this, enabled);
    updateTailscaleModeButton();
    appendLine("NET", enabled ? "tailscale mode enabled (100.x preferred)" : "tailscale mode disabled");
    new Thread(
            () -> {
              String resolved = AppPrefs.resolveReachableBaseUrl(this);
              AppPrefs.saveBaseUrl(this, resolved);
              uiHandler.post(
                  () -> {
                    if (baseUrlInput != null) {
                      baseUrlInput.setText(resolved);
                    }
                  });
            })
        .start();
  }

  private void showErrorReportDialog() {
    EditText input = new EditText(this);
    input.setHint(getString(R.string.error_report_dialog_hint));
    input.setMinLines(3);
    input.setMaxLines(6);
    new AlertDialog.Builder(this)
        .setTitle(getString(R.string.error_report_dialog_title))
        .setView(input)
        .setPositiveButton(
            getString(R.string.error_report_dialog_submit),
            (dialog, which) -> {
              String message = input.getText() == null ? "" : input.getText().toString().trim();
              if (message.isEmpty()) {
                appendLine("REPORT", "report not sent: message is empty");
                return;
              }
              submitErrorReport(message);
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void submitErrorReport(String message) {
    String base = normalizedBaseUrl();
    if (base == null) {
      setStatus("invalid URL");
      return;
    }
    boolean analyticsOptOut = !AppPrefs.isAnalyticsEnabled(this);
    updateErrorReportStatus(getString(R.string.error_report_status_polling));
    String details =
        "running="
            + running
            + ", forceDrivingMode="
            + forceDrivingMode
            + ", mapTarget="
            + (lastMapLat != null && lastMapLon != null
                ? String.format(Locale.ROOT, "%.6f,%.6f", lastMapLat, lastMapLon)
                : "none");
    String payload =
        "{"
            + "\"message\":\""
            + jsonEscapeLocal(message)
            + "\","
            + "\"details\":\""
            + jsonEscapeLocal(details)
            + "\","
            + "\"severity\":\"error\","
            + "\"source\":\"android_stream_client\","
            + "\"user_id\":\""
            + jsonEscapeLocal(relayUserId())
            + "\","
            + "\"client_id\":\""
            + jsonEscapeLocal(clientId())
            + "\","
            + "\"analytics_opt_out\":"
            + (analyticsOptOut ? "true" : "false")
            + "}";
    Request request =
        new Request.Builder()
            .url(base + "/api/platform/error-reports/submit")
            .post(RequestBody.create(payload, JSON_MEDIA_TYPE))
            .build();
    client.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                appendLine("REPORT", "submit failed: " + e.getMessage());
                updateErrorReportStatus(getString(R.string.error_report_status_idle));
              }

              @Override
              public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                  if (!response.isSuccessful() || response.body() == null) {
                    appendLine("REPORT", "submit failed (HTTP " + response.code() + ")");
                    return;
                  }
                  JSONObject json = new JSONObject(response.body().string());
                  JSONObject entry = json.optJSONObject("entry");
                  String id = entry != null ? entry.optString("id", "") : "";
                  String ack = id.isEmpty() ? "submitted" : ("submitted: " + id);
                  appendLine("REPORT", ack);
                  pollRecentErrorReports(true);
                } catch (Exception e) {
                  appendLine("REPORT", "submit parse error: " + e.getMessage());
                } finally {
                  updateErrorReportStatus(getString(R.string.error_report_status_idle));
                }
              }
            });
  }

  private void pollErrorReportsLoop() {
    pollRecentErrorReports(false);
    uiHandler.removeCallbacks(errorReportPollRunnable);
    uiHandler.postDelayed(errorReportPollRunnable, ERROR_REPORT_POLL_INTERVAL_MS);
  }

  private void pollRecentErrorReports(boolean forceNow) {
    String base = normalizedBaseUrl();
    if (base == null) {
      return;
    }
    long now = SystemClock.elapsedRealtime();
    if (!forceNow && (now - lastErrorReportPollMs) < ERROR_REPORT_POLL_INTERVAL_MS) {
      return;
    }
    lastErrorReportPollMs = now;
    String pollUrl =
        base
            + "/api/platform/error-reports/recent?limit=20&since_ms="
            + Math.max(0L, lastSeenErrorReportCreatedAtMs);
    Request request = new Request.Builder().url(pollUrl).build();
    client.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                updateErrorReportStatus(getString(R.string.error_report_status_idle));
              }

              @Override
              public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                  if (!response.isSuccessful() || response.body() == null) {
                    return;
                  }
                  JSONObject json = new JSONObject(response.body().string());
                  JSONArray results = json.optJSONArray("results");
                  int recentCount = json.optInt("count", 0);
                  long newestMs = lastSeenErrorReportCreatedAtMs;
                  int newCount = 0;
                  String latestMessage = "";
                  if (results != null) {
                    for (int i = 0; i < results.length(); i++) {
                      JSONObject entry = results.optJSONObject(i);
                      if (entry == null) {
                        continue;
                      }
                      String id = entry.optString("id", "").trim();
                      long createdAt = entry.optLong("created_at_ms", 0L);
                      if (createdAt > newestMs) {
                        newestMs = createdAt;
                      }
                      if (id.isEmpty() || seenErrorReportIdSet.contains(id)) {
                        continue;
                      }
                      rememberSeenErrorReportId(id);
                      newCount++;
                      if (latestMessage.isEmpty()) {
                        String severity = entry.optString("severity", "error");
                        String message = entry.optString("message", "");
                        latestMessage = "[" + severity + "] " + message;
                      }
                    }
                  }
                  lastSeenErrorReportCreatedAtMs = Math.max(lastSeenErrorReportCreatedAtMs, newestMs);
                  String stamp = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
                  updateErrorReportStatus(
                      getString(R.string.error_report_status_recent, recentCount, stamp));
                  if (newCount > 0 && !latestMessage.isEmpty()) {
                    appendLine("REPORTS", "new=" + newCount + " • " + latestMessage);
                  }
                } catch (Exception ignored) {
                  // keep polling quietly
                }
              }
            });
  }

  private void rememberSeenErrorReportId(String id) {
    if (id == null || id.isEmpty() || seenErrorReportIdSet.contains(id)) {
      return;
    }
    seenErrorReportIdSet.add(id);
    seenErrorReportIds.addLast(id);
    while (seenErrorReportIds.size() > ERROR_REPORT_SEEN_MAX_IDS) {
      String dropped = seenErrorReportIds.removeFirst();
      seenErrorReportIdSet.remove(dropped);
    }
  }

  private void maybeShowTrackingConsentDialog() {
    if (trackingConsentDialogShowing || AppPrefs.isTrackingConsentResolved(this) || isFinishing()) {
      return;
    }
    trackingConsentDialogShowing = true;
    LinearLayout container = new LinearLayout(this);
    container.setOrientation(LinearLayout.VERTICAL);
    int pad = (int) (16 * getResources().getDisplayMetrics().density);
    container.setPadding(pad, pad, pad, 0);

    TextView message = new TextView(this);
    message.setText(getString(R.string.tracking_consent_message));
    message.setLineSpacing(0f, 1.1f);
    container.addView(message);

    CheckBox doNotPromptAgain = new CheckBox(this);
    doNotPromptAgain.setText(getString(R.string.tracking_consent_dont_prompt_again));
    doNotPromptAgain.setChecked(true);
    container.addView(doNotPromptAgain);
    CheckBox analyticsOptOut = new CheckBox(this);
    analyticsOptOut.setText(getString(R.string.tracking_consent_analytics_opt_out));
    analyticsOptOut.setChecked(false);
    container.addView(analyticsOptOut);

    new AlertDialog.Builder(this)
        .setTitle(getString(R.string.tracking_consent_title))
        .setView(container)
        .setCancelable(false)
        .setPositiveButton(
            getString(R.string.tracking_consent_enable),
            (dialog, which) -> {
              AppPrefs.setTrackingConsent(this, true, true);
              AppPrefs.setAnalyticsEnabled(this, !analyticsOptOut.isChecked());
              trackingConsentDialogShowing = false;
              appendLine("GPS", "active tracking enabled");
              if (analyticsOptOut.isChecked()) {
                appendLine("PRIVACY", "performance/data analytics opted out");
              }
              registerLocationTracking();
            })
        .setNegativeButton(
            getString(R.string.tracking_consent_skip),
            (dialog, which) -> {
              boolean suppressFuturePrompts = doNotPromptAgain.isChecked();
              AppPrefs.setTrackingConsent(this, false, suppressFuturePrompts);
              AppPrefs.setAnalyticsEnabled(this, !analyticsOptOut.isChecked());
              trackingConsentDialogShowing = false;
              unregisterLocationTracking();
              if (analyticsOptOut.isChecked()) {
                appendLine("PRIVACY", "performance/data analytics opted out");
              }
              if (suppressFuturePrompts) {
                appendLine("GPS", "active tracking disabled and future tracking prompts turned off");
              } else {
                appendLine("GPS", "active tracking skipped for now; prompt will appear again");
              }
            })
        .setOnDismissListener(dialog -> trackingConsentDialogShowing = false)
        .show();
  }

  private void updateErrorReportStatus(String status) {
    uiHandler.post(
        () -> {
          if (errorReportStatusText != null) {
            errorReportStatusText.setText(status);
          }
        });
  }

  private void updateStackManageStatus(String status) {
    uiHandler.post(
        () -> {
          if (stackManageStatusText != null) {
            stackManageStatusText.setText(status);
          }
        });
  }

  private void runStackManageAction(String action) {
    String base = normalizedBaseUrl();
    if (base == null) {
      setStatus("invalid URL");
      return;
    }
    updateStackManageStatus("Stack: " + action + "…");
    String payload = "{\"action\":\"" + jsonEscapeLocal(action) + "\"}";
    Request request =
        new Request.Builder()
            .url(base + "/api/platform/dev/stack/manage")
            .post(RequestBody.create(payload, JSON_MEDIA_TYPE))
            .build();
    client.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                appendLine("STACK", action + " failed: " + e.getMessage());
                updateStackManageStatus("Stack: " + action + " failed");
              }

              @Override
              public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                  if (response.body() == null) {
                    appendLine("STACK", action + " failed (empty response)");
                    updateStackManageStatus("Stack: " + action + " failed");
                    return;
                  }
                  String raw = response.body().string();
                  JSONObject json = new JSONObject(raw);
                  int exitCode = json.optInt("exit_code", -1);
                  String status = json.optString("status", "");
                  String output = json.optString("output", "").trim();
                  if (!response.isSuccessful() || "error".equalsIgnoreCase(status)) {
                    appendLine("STACK", action + " failed (HTTP " + response.code() + ")");
                    if (!output.isEmpty()) {
                      appendLine("STACK", output.split("\n")[0]);
                    }
                    updateStackManageStatus("Stack: " + action + " failed");
                    return;
                  }
                  appendLine("STACK", action + " exit=" + exitCode);
                  if (!output.isEmpty()) {
                    appendLine("STACK", output.split("\n")[0]);
                  }
                  updateStackManageStatus("Stack: " + action + " done");
                } catch (Exception e) {
                  appendLine("STACK", action + " parse error: " + e.getMessage());
                  updateStackManageStatus("Stack: " + action + " failed");
                }
              }
            });
  }

  private String jsonEscapeLocal(String value) {
    if (value == null) {
      return "";
    }
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  private void updateTailscaleModeButton() {
    if (tailscaleModeBtn == null) {
      return;
    }
    boolean enabled = AppPrefs.preferTailscale(this);
    tailscaleModeBtn.setText(
        enabled ? getString(R.string.tailscale_mode_on) : getString(R.string.tailscale_mode_off));
  }

  private String normalizeBaseUrlCandidate(String rawValue) {
    if (rawValue == null) {
      return null;
    }
    String base = rawValue.trim();
    if (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    if (!base.startsWith("http://") && !base.startsWith("https://")) {
      return null;
    }
    return base;
  }

  /** Restores map mode and coordinates after a configuration change (e.g. rotation). */
  private void restoreUiState(Bundle saved) {
    if (saved == null) {
      return;
    }
    if (saved.containsKey(STATE_MAP_LAT) && saved.containsKey(STATE_MAP_LON)) {
      lastMapLat = saved.getDouble(STATE_MAP_LAT);
      lastMapLon = saved.getDouble(STATE_MAP_LON);
    }
    if (saved.containsKey(STATE_DEVICE_LAT) && saved.containsKey(STATE_DEVICE_LON)) {
      lastDeviceLat = saved.getDouble(STATE_DEVICE_LAT);
      lastDeviceLon = saved.getDouble(STATE_DEVICE_LON);
    }
    if (saved.getBoolean(STATE_MAP3D_ENABLED, false)) {
      applyMapMode(true);
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putBoolean(STATE_MAP3D_ENABLED, map3dEnabled);
    if (lastMapLat != null && lastMapLon != null) {
      outState.putDouble(STATE_MAP_LAT, lastMapLat);
      outState.putDouble(STATE_MAP_LON, lastMapLon);
    }
    if (lastDeviceLat != null && lastDeviceLon != null) {
      outState.putDouble(STATE_DEVICE_LAT, lastDeviceLat);
      outState.putDouble(STATE_DEVICE_LON, lastDeviceLon);
    }
  }


  private void searchDestination() {
    String query = destinationInput.getText().toString().trim();
    if (query.isEmpty()) {
      appendLine("SEARCH", "enter a destination address first");
      return;
    }
    AddressCatalogRouter.AddressCandidate selected = selectedDestinationSuggestion;
    if (selected != null && query.equalsIgnoreCase(selected.displayName)) {
      routeToResolvedCandidate(selected, "SEARCH", "suggest");
      return;
    }
    geocodeAndRoute(query, "SEARCH");
  }

  private void scheduleDestinationSuggestions(String query) {
    uiHandler.removeCallbacks(destinationSuggestRunnable);
    pendingSuggestQuery = query;
    if (query.isEmpty()) {
      updateDestinationSuggestions(new ArrayList<>(), 0L);
      return;
    }
    uiHandler.postDelayed(destinationSuggestRunnable, 280L);
  }

  private void requestDestinationSuggestions() {
    String query = pendingSuggestQuery == null ? "" : pendingSuggestQuery.trim();
    if (query.isEmpty()) {
      updateDestinationSuggestions(new ArrayList<>(), 0L);
      return;
    }
    String base = normalizedBaseUrl();
    if (base == null) {
      return;
    }
    long generation = ++destinationSuggestGeneration;
    Double biasLat = lastDeviceLat != null ? lastDeviceLat : lastMapLat;
    Double biasLon = lastDeviceLon != null ? lastDeviceLon : lastMapLon;
    addressCatalogRouter.suggest(
        base,
        query,
        biasLat,
        biasLon,
        8,
        new AddressCatalogRouter.SuggestCallback() {
          @Override
          public void onSuggestions(List<AddressCatalogRouter.AddressCandidate> suggestions) {
            updateDestinationSuggestions(suggestions, generation);
          }

          @Override
          public void onFailure(String message) {
            // keep silent for per-keystroke background fetches
          }
        });
  }

  private void updateDestinationSuggestions(
      List<AddressCatalogRouter.AddressCandidate> suggestions, long generation) {
    if (generation > 0 && generation != destinationSuggestGeneration) {
      return;
    }
    uiHandler.post(
        () -> {
          destinationSuggestionCandidates.clear();
          destinationSuggestionLabels.clear();
          if (suggestions != null) {
            for (AddressCatalogRouter.AddressCandidate suggestion : suggestions) {
              if (suggestion == null || TextUtils.isEmpty(suggestion.displayName)) {
                continue;
              }
              destinationSuggestionCandidates.add(suggestion);
              destinationSuggestionLabels.add(suggestion.displayName);
            }
          }
          destinationSuggestionAdapter.notifyDataSetChanged();
          if (!destinationSuggestionLabels.isEmpty() && destinationInput.hasFocus()) {
            destinationInput.showDropDown();
          } else {
            destinationInput.dismissDropDown();
          }
        });
  }

  private void routeToResolvedCandidate(
      AddressCatalogRouter.AddressCandidate candidate, String label, String sourceTag) {
    if (candidate == null) {
      return;
    }
    lastMapLat = candidate.lat;
    lastMapLon = candidate.lon;
    selectedDestinationSuggestion = candidate;
    updateMapTargetUi();
    appendLine(label, "destination (" + sourceTag + "): " + candidate.displayName);
    uiHandler.post(
        () -> {
          destinationInput.setText(candidate.displayName);
          destinationInput.setSelection(destinationInput.getText().length());
          destinationInput.clearFocus();
          destinationInput.dismissDropDown();
        });
    renderRouteOnMap(true);
    openRouteOptionsScreen(candidate.lat, candidate.lon, candidate.displayName);
  }

  private void geocodeAndRoute(String query, String label) {
    String base = normalizedBaseUrl();
    if (base == null) {
      setStatus("invalid URL");
      return;
    }
    appendLine(label, "address catalog lookup: " + query);
    Double biasLat = lastDeviceLat != null ? lastDeviceLat : lastMapLat;
    Double biasLon = lastDeviceLon != null ? lastDeviceLon : lastMapLon;
    addressCatalogRouter.resolve(
        base,
        query,
        biasLat,
        biasLon,
        new AddressCatalogRouter.ResolveCallback() {
          @Override
          public void onResolved(AddressCatalogRouter.AddressCandidate candidate, boolean fromCatalog) {
            routeToResolvedCandidate(candidate, label, fromCatalog ? "catalog" : "osm-fallback");
          }

          @Override
          public void onFailure(String message) {
            appendLine(label, message);
          }
        });
  }

  private void openRouteOptionsScreen(double destLat, double destLon, String destLabel) {
    String base = normalizedBaseUrl();
    if (base == null) {
      return;
    }
    double originLat = lastDeviceLat != null ? lastDeviceLat : destLat;
    double originLon = lastDeviceLon != null ? lastDeviceLon : destLon;
    Intent intent = new Intent(this, RouteOptionsActivity.class);
    intent.putExtra(RouteOptionsActivity.EXTRA_BASE_URL, base);
    intent.putExtra(RouteOptionsActivity.EXTRA_ORIGIN_LAT, originLat);
    intent.putExtra(RouteOptionsActivity.EXTRA_ORIGIN_LON, originLon);
    intent.putExtra(RouteOptionsActivity.EXTRA_DEST_LAT, destLat);
    intent.putExtra(RouteOptionsActivity.EXTRA_DEST_LON, destLon);
    intent.putExtra(RouteOptionsActivity.EXTRA_DEST_LABEL, destLabel);
    startActivity(intent);
  }

  private void toggleMapMode() {
    map3dView.recenter();
    if (lastMapLat != null && lastMapLon != null) {
      fetchMapScene(lastMapLat, lastMapLon, 700.0, true);
    } else if (lastDeviceLat != null && lastDeviceLon != null) {
      fetchMapScene(lastDeviceLat, lastDeviceLon, 700.0, true);
    } else {
      fetchMapScene(DEFAULT_MAP_LAT, DEFAULT_MAP_LON, 700.0, true);
    }
  }

  private void applyMapMode(boolean enable3d) {
    map3dEnabled = true;
    mapModeBtn.setText(getString(R.string.map_mode_vector));
    map3dView.setVisibility(View.VISIBLE);
    zoomControls.setVisibility(View.VISIBLE);
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
  }

  private void fetchMapScene(double lat, double lon, double radiusM, boolean force) {
    String base = normalizedBaseUrl();
    if (base == null) {
      Log.d(TAG, "fetchMapScene skip: base url unavailable");
      return;
    }
    if (!map3dEnabled) {
      Log.d(TAG, "fetchMapScene skip: map3d disabled");
      return;
    }
    long now = SystemClock.elapsedRealtime();
    if (sceneFetchInFlight) {
      Log.d(TAG, "fetchMapScene skip: fetch already in flight");
      return;
    }
    if (!force && (now - lastSceneFetchMs) < 4000L) {
      Log.d(TAG, "fetchMapScene skip: throttled");
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
    Log.i(
        TAG,
        String.format(
            Locale.ROOT,
            "fetchMapScene request lat=%.6f lon=%.6f radius=%.1f force=%b url=%s",
            lat,
            lon,
            radiusM,
            force,
            url));
    Request request = new Request.Builder().url(url).build();
    client.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                sceneFetchInFlight = false;
                Log.w(TAG, "fetchMapScene failure: " + e.getMessage(), e);
                appendLine("MAP3D", "scene fetch failed: " + e.getMessage());
                map3dView.setLoadingHint("backend unreachable \u2014 retrying\u2026");
                scheduleSceneRetry();
              }

              @Override
              public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                  if (!response.isSuccessful() || response.body() == null) {
                    Log.w(TAG, "fetchMapScene http failure code=" + response.code());
                    appendLine("MAP3D", "scene unavailable (HTTP " + response.code() + ")");
                    map3dView.setLoadingHint(
                        "scene unavailable (HTTP " + response.code() + ") \u2014 retrying\u2026");
                    scheduleSceneRetry();
                    return;
                  }
                  String body = response.body().string();
                  Log.i(
                      TAG,
                      "fetchMapScene success code="
                          + response.code()
                          + " body_bytes="
                          + body.length());
                  map3dView.setSceneJson(body);
                  Log.i(TAG, "fetchMapScene applied hasScene=" + map3dView.hasScene());
                  lastSceneLat = lat;
                  lastSceneLon = lon;
                  lastSceneRadiusM = radiusM;
                } catch (Exception e) {
                  Log.w(TAG, "fetchMapScene parse/apply failure: " + e.getMessage(), e);
                  appendLine("MAP3D", "scene parse failed: " + e.getMessage());
                  map3dView.setLoadingHint("scene parse failed \u2014 retrying\u2026");
                  scheduleSceneRetry();
                } finally {
                  sceneFetchInFlight = false;
                }
              }
            });
  }

  /**
   * The 3D view has no scene to render until a fetch succeeds; keep retrying
   * (one pending retry at a time) so a transient backend failure does not
   * leave the map stuck on the loading screen.
   */
  private void scheduleSceneRetry() {
    if (sceneRetryScheduled) {
      Log.d(TAG, "scheduleSceneRetry skip: already scheduled");
      return;
    }
    sceneRetryScheduled = true;
    Log.i(TAG, "scheduleSceneRetry +3000ms");
    uiHandler.postDelayed(
        () -> {
          sceneRetryScheduled = false;
          if (!map3dEnabled || map3dView.hasScene()) {
            Log.d(
                TAG,
                "scheduleSceneRetry cancel: map3d="
                    + map3dEnabled
                    + " hasScene="
                    + map3dView.hasScene());
            return;
          }
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
          Log.i(
              TAG,
              String.format(
                  Locale.ROOT,
                  "scheduleSceneRetry firing lat=%.6f lon=%.6f radius=700.0",
                  lat,
                  lon));
          fetchMapScene(lat, lon, 700.0, true);
        },
        3000L);
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
    if (!ENABLE_DEV_CONTROLS) {
      return;
    }
    controlPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
    menuBtn.setText(visible ? getString(R.string.menu_close) : getString(R.string.menu_open));
  }

  private void applyAppModeUi() {
    if (ENABLE_DEV_CONTROLS) {
      return;
    }
    if (menuBtn != null) {
      menuBtn.setVisibility(View.GONE);
    }
    if (controlPanel != null) {
      controlPanel.setVisibility(View.GONE);
    }
    if (baseUrlInput != null) {
      baseUrlInput.setEnabled(false);
    }
    appendLine(
        "NET",
        "long-press status to set remote server URL when off local network");
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
    registerMotionDetection();
    maybeShowTrackingConsentDialog();
    registerLocationTracking();
    uiHandler.removeCallbacks(errorReportPollRunnable);
    uiHandler.postDelayed(errorReportPollRunnable, 1200L);
  }

  @Override
  protected void onPause() {
    uiHandler.removeCallbacks(errorReportPollRunnable);
    unregisterLocationTracking();
    unregisterMotionDetection();
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    unregisterLocationTracking();
    unregisterMotionDetection();
    uiHandler.removeCallbacks(destinationSuggestRunnable);
    uiHandler.removeCallbacks(errorReportPollRunnable);
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
    if (!AppPrefs.isActiveTrackingEnabled(this)) {
      unregisterLocationTracking();
      if (!trackingDisabledNoticeLogged) {
        trackingDisabledNoticeLogged = true;
        appendLine("GPS", "active tracking disabled by user; location permission prompts are suppressed");
      }
      return;
    }
    trackingDisabledNoticeLogged = false;
    if (!AppPrefs.isTrackingConsentResolved(this)) {
      return;
    }
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
    if (ENABLE_DEV_CONTROLS) {
      uiHandler.post(() -> baseUrlInput.setEnabled(false));
    }
    if (lastMapLat == null && lastDeviceLat != null && lastDeviceLon != null) {
      lastMapLat = lastDeviceLat;
      lastMapLon = lastDeviceLon;
      updateMapTargetUi();
    }
    if (ENABLE_DEV_CONTROLS && !running) {
      startStreaming();
    }
  }

  private void onForcedDrivingModeReleased() {
    updateDrivingModeUi(0f);
    appendLine("DRIVE_MODE", "forced driving mode released after sustained idle motion");
    if (ENABLE_DEV_CONTROLS) {
      uiHandler.post(() -> baseUrlInput.setEnabled(true));
    }
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
    if (!ENABLE_DEV_CONTROLS) {
      setStatus("dev stream controls disabled");
      return;
    }
    if (running) {
      return;
    }
    String base = normalizedBaseUrl();
    if (base == null) {
      setStatus("invalid URL");
      return;
    }
    AppPrefs.saveBaseUrl(this, base);
    String resolved = AppPrefs.resolveReachableBaseUrl(this);
    if (!resolved.equals(base)) {
      appendLine("NET", "auto-switched backend to " + resolved);
      if (ENABLE_DEV_CONTROLS) {
        baseUrlInput.setText(resolved);
      }
    }
    running = true;
    setStatus("connecting...");
    appendLine("STREAM TARGET", resolved);
    registerClientRoute(resolved);
    final String target = resolved;
    new Thread(
            () -> {
              fetchSnapshot(target);
              streamSse(target);
            })
        .start();
    syncDeviceGpsToBackend();
  }

  private String normalizedBaseUrl() {
    String base;
    if (ENABLE_DEV_CONTROLS) {
      base = baseUrlInput.getText().toString();
    } else {
      base = AppPrefs.resolveReachableBaseUrl(this);
    }
    String normalized = normalizeBaseUrlCandidate(base);
    if (normalized == null) {
      return null;
    }
    AppPrefs.saveBaseUrl(this, normalized);
    return AppPrefs.resolveReachableBaseUrl(this);
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
    int attempt = 0;
    String activeBase = base;
    while (running) {
      String streamUrl = activeBase + "/api/pipeline/stream";
      String token = clientPullToken == null ? "" : clientPullToken.trim();
      if (!token.isEmpty()) {
        streamUrl =
            streamUrl
                + "?client_id="
                + Uri.encode(clientId())
                + "&user_id="
                + Uri.encode(relayUserId())
                + "&source="
                + Uri.encode("android_stream_client")
                + "&session_id="
                + Uri.encode(streamSessionId)
                + "&pull_token="
                + Uri.encode(token);
      }
      Request request = new Request.Builder().url(streamUrl).build();
      streamCall = sseClient.newCall(request);
      try (Response response = streamCall.execute()) {
        if (!response.isSuccessful() || response.body() == null) {
          setStatus("stream unavailable");
        } else {
          setStatus("streaming");
          attempt = 0;
          InputStream stream = response.body().byteStream();
          try (BufferedReader reader =
              new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
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
        }
      } catch (IOException e) {
        if (running) {
          appendLine("STREAM", "error: " + e.getMessage());
        }
      }
      if (!running) {
        break;
      }
      String nextBase = AppPrefs.resolveReachableBaseUrl(this);
      if (!nextBase.equals(activeBase)) {
        activeBase = nextBase;
        registerClientRoute(activeBase);
        appendLine("NET", "auto-switched backend to " + activeBase);
        String switchedBase = activeBase;
        uiHandler.post(() -> baseUrlInput.setText(switchedBase));
      }
      // Server closed the stream or the connection dropped: back off and retry.
      attempt++;
      long delayMs = Math.min(15000L, 1000L << Math.min(attempt, 4));
      setStatus("stream reconnecting...");
      try {
        Thread.sleep(delayMs);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    if (running) {
      setStatus("idle");
    }
    running = false;
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
      String intelLine = buildIntelLine(json.optJSONObject("llm_intel"));
      boolean isAlert = "alert_triggered".equals(eventType);
      if (isAlert) {
        String vetLine = buildVetLine(json);
        if (!TextUtils.isEmpty(vetLine)) {
          intelLine = TextUtils.isEmpty(intelLine) ? vetLine : (vetLine + "\n" + intelLine);
        }
      }
      if (!mentions.isEmpty() || (isAlert && !TextUtils.isEmpty(text))) {
        float[] levelSeries = parseAudioLevels(json.optJSONArray("audio_levels"));
        long levelWindowMs = json.optLong("audio_level_window_ms", 250L);
        maybeShowLocationPopup(
            eventType, mentions, intelLine, text, json.optDouble("rms", 0.0),
            levelSeries, levelWindowMs);
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

  /**
   * Converts the event's per-window RMS envelope into normalized visualizer amplitudes
   * (same rms*8 scaling as the static amplitude path); null when absent.
   */
  private float[] parseAudioLevels(JSONArray array) {
    if (array == null || array.length() == 0) {
      return null;
    }
    float[] levels = new float[array.length()];
    for (int i = 0; i < array.length(); i++) {
      double windowRms = array.optDouble(i, 0.0);
      levels[i] = (float) Math.min(1.0, Math.max(0.0, windowRms * 8.0));
    }
    return levels;
  }

  /**
   * Picks the first mention that is actually routable: strips bare directional words and skips
   * junk tokens (e.g. "northbound") that would geocode to arbitrary far-away places.
   */
  private String pickRoutableQuery(List<String> mentions) {
    for (String mention : mentions) {
      String cleaned = stripDirectional(mention);
      if (cleaned.isEmpty()
          || NON_ROUTABLE_MENTIONS.contains(cleaned.toLowerCase(Locale.ROOT))) {
        continue;
      }
      return cleaned;
    }
    return null;
  }

  private String stripDirectional(String mention) {
    return DIRECTIONAL_TOKEN_PATTERN
        .matcher(mention)
        .replaceAll(" ")
        .replaceAll("\\s+", " ")
        .trim();
  }

  /** One-line summary of the scout-intel extraction; empty when intel is unavailable. */
  private String buildIntelLine(JSONObject intel) {
    if (intel == null) {
      return "";
    }
    List<String> parts = new ArrayList<>();
    List<String> callTypes = new ArrayList<>();
    collectMentions(intel.optJSONArray("call_types"), callTypes);
    if (!callTypes.isEmpty()) {
      parts.add(TextUtils.join(", ", callTypes).replace('_', ' '));
    }
    String priority = intel.optString("priority", "").trim();
    if (!priority.isEmpty() && !"unknown".equalsIgnoreCase(priority)) {
      parts.add("priority " + priority);
    }
    List<String> units = new ArrayList<>();
    collectMentions(intel.optJSONArray("units"), units);
    if (!units.isEmpty()) {
      parts.add("units " + TextUtils.join(", ", units));
    }
    String line = TextUtils.join("  \u2022  ", parts);
    String summary = intel.optString("summary", "").trim();
    if (!summary.isEmpty()) {
      line = line.isEmpty() ? summary : line + "\n" + summary;
    }
    return line;
  }

  /** Short vet-status line shown at the top of popup intel details for alert events. */
  private String buildVetLine(JSONObject eventJson) {
    if (eventJson == null) {
      return "";
    }
    boolean vetted = eventJson.optBoolean("alert_vetted", true);
    String decision = eventJson.optString("alert_vet_decision", "").trim();
    String reason = eventJson.optString("alert_vet_reason", "").trim();
    if (TextUtils.isEmpty(decision) && TextUtils.isEmpty(reason) && vetted) {
      return getString(R.string.popup_vet_pass);
    }
    StringBuilder sb = new StringBuilder(vetted ? getString(R.string.popup_vet_pass) : getString(R.string.popup_vet_fail));
    if (!TextUtils.isEmpty(decision)) {
      sb.append(" • ").append(decision);
    }
    if (!TextUtils.isEmpty(reason)) {
      sb.append(" • ").append(reason.replace('_', ' '));
    }
    return sb.toString();
  }

  private void maybeShowLocationPopup(
      String eventType,
      List<String> mentions,
      String intelLine,
      String text,
      double rms,
      float[] levelSeries,
      long levelWindowMs) {
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
    boolean hasMention = !mentions.isEmpty();
    pendingPopupQuery = pickRoutableQuery(mentions);
    boolean canRoute = pendingPopupQuery != null;
    float amplitude = (float) Math.min(1.0, Math.max(0.0, rms * 8.0));
    String title =
        isAlert ? getString(R.string.popup_title_alert) : getString(R.string.popup_title_location);
    String locations =
        hasMention ? TextUtils.join("  \u2022  ", mentions) : getString(R.string.popup_no_location);
    if (hasMention) {
      appendLine("LOCATION", locations);
    }
    if (!TextUtils.isEmpty(intelLine)) {
      appendLine("INTEL", intelLine.replace('\n', ' '));
    }
    uiHandler.post(
        () -> {
          popupTitle.setText(title);
          popupLocationText.setText(locations);
          popupIntelText.setText(intelLine);
          popupIntelText.setVisibility(TextUtils.isEmpty(intelLine) ? View.GONE : View.VISIBLE);
          popupRouteBtn.setEnabled(canRoute);
          popupRouteBtn.setAlpha(canRoute ? 1f : 0.5f);
          popupTranscriptText.setText(text);
          if (levelSeries != null) {
            popupVisualizer.setLevels(levelSeries, levelWindowMs);
          } else {
            popupVisualizer.setAmplitude(amplitude);
          }
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
    AppPrefs.saveDestination(this, lastMapLat, lastMapLon);
    uiHandler.post(
        () -> {
          if (mapTargetText == null) {
            return;
          }
          if (lastMapLat != null && lastMapLon != null) {
            mapTargetText.setText("Map Target: navigation target active");
            return;
          }
          if (lastDeviceLat != null && lastDeviceLon != null) {
            mapTargetText.setText("Map Target: device fallback active");
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

    lastMapLat = targetLat;
    lastMapLon = targetLon;
    AppPrefs.saveDestination(this, targetLat, targetLon);
    AppPrefs.saveDestinationLabel(
        this,
        String.format(Locale.ROOT, "Target %.5f, %.5f", targetLat, targetLon));
    updateMapTargetUi();
    if (!map3dEnabled) {
      applyMapMode(true);
    }
    renderRouteOnMap(true);
    openRouteOptionsScreen(targetLat, targetLon, "Map target");
    appendLine(
        "MAPS",
        "opened in-app route view to current target");
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
    boolean analyticsOptOut = !AppPrefs.isAnalyticsEnabled(this);
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
            + ","
            + "\"analytics_opt_out\":"
            + (analyticsOptOut ? "true" : "false")
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
    map3dView.setRoute(routeSnapshot);
    if (lastMapLat != null && lastMapLon != null) {
      map3dView.setDestination(lastMapLat, lastMapLon);
    } else {
      map3dView.setDestination(null, null);
    }
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
