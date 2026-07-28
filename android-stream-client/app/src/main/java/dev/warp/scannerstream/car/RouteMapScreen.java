package dev.warp.scannerstream.car;

import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.car.app.AppManager;
import androidx.car.app.CarContext;
import androidx.car.app.CarToast;
import androidx.car.app.Screen;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import dev.warp.scannerstream.AppPrefs;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Android Auto navigation screen. Draws the backend map engine
 * (/api/map/render, which overlays the OSRM route when a destination is set)
 * onto the car's surface, forwards the vehicle GPS fix to the backend so
 * scanner channel selection follows the car, and polls /api/mobile/snapshot
 * for scanner alerts which are shown as car toasts.
 */
public final class RouteMapScreen extends Screen implements DefaultLifecycleObserver {
  private static final String TAG = "ScannerCar";
  private static final long RENDER_INTERVAL_MS = 1500L;
  private static final long GPS_POST_INTERVAL_MS = 3000L;
  private static final long ALERT_POLL_INTERVAL_MS = 5000L;
  private static final double DEFAULT_LAT = 48.4941;
  private static final double DEFAULT_LON = -122.6120;
  private static final double MIN_MPP = 0.4;
  private static final double MAX_MPP = 5000.0;
  private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

  private final CarContext carContext;
  private final OkHttpClient httpClient =
      new OkHttpClient.Builder()
          .connectTimeout(4, TimeUnit.SECONDS)
          .readTimeout(8, TimeUnit.SECONDS)
          .build();

  private HandlerThread renderThread;
  private Handler renderHandler;
  private LocationManager locationManager;
  private LocationListener locationListener;

  private final Object surfaceLock = new Object();
  private Surface surface;
  private int surfaceWidth;
  private int surfaceHeight;

  private volatile double metersPerPixel = 2.0;
  private volatile boolean followVehicle = true;
  private volatile Location lastFix;
  private long lastGpsPostMs;
  private long lastAlertPollMs;
  private String lastAlertKey = "";

  private final Runnable renderTick =
      new Runnable() {
        @Override
        public void run() {
          try {
            renderOnce();
          } catch (Exception ex) {
            Log.w(TAG, "render tick failed: " + ex);
          }
          Handler handler = renderHandler;
          if (handler != null) {
            handler.postDelayed(this, RENDER_INTERVAL_MS);
          }
        }
      };

  private final SurfaceCallback surfaceCallback =
      new SurfaceCallback() {
        @Override
        public void onSurfaceAvailable(@NonNull SurfaceContainer container) {
          synchronized (surfaceLock) {
            surface = container.getSurface();
            surfaceWidth = container.getWidth();
            surfaceHeight = container.getHeight();
          }
          requestRenderSoon();
        }

        @Override
        public void onSurfaceDestroyed(@NonNull SurfaceContainer container) {
          synchronized (surfaceLock) {
            surface = null;
          }
        }

        @Override
        public void onScroll(float distanceX, float distanceY) {
          // Pan gestures disable follow mode until "Center" is pressed.
          followVehicle = false;
        }

        @Override
        public void onScale(float focusX, float focusY, float scaleFactor) {
          if (scaleFactor > 0f) {
            adjustZoom(1f / scaleFactor);
          }
        }
      };

  public RouteMapScreen(@NonNull CarContext context) {
    super(context);
    this.carContext = context;
    getLifecycle().addObserver(this);
  }

  @Override
  public void onCreate(@NonNull LifecycleOwner owner) {
    carContext.getCarService(AppManager.class).setSurfaceCallback(surfaceCallback);
    renderThread = new HandlerThread("car-map-render");
    renderThread.start();
    renderHandler = new Handler(renderThread.getLooper());
    renderHandler.postDelayed(renderTick, 300L);
    startLocationUpdates();
  }

  @Override
  public void onDestroy(@NonNull LifecycleOwner owner) {
    stopLocationUpdates();
    Handler handler = renderHandler;
    if (handler != null) {
      handler.removeCallbacksAndMessages(null);
    }
    renderHandler = null;
    if (renderThread != null) {
      renderThread.quitSafely();
      renderThread = null;
    }
    try {
      carContext.getCarService(AppManager.class).setSurfaceCallback(null);
    } catch (Exception ignored) {
    }
  }

  @NonNull
  @Override
  public Template onGetTemplate() {
    ActionStrip actionStrip =
        new ActionStrip.Builder()
            .addAction(
                new Action.Builder()
                    .setTitle("Zoom +")
                    .setOnClickListener(() -> adjustZoom(1.0 / 1.5))
                    .build())
            .addAction(
                new Action.Builder()
                    .setTitle("Zoom -")
                    .setOnClickListener(() -> adjustZoom(1.5))
                    .build())
            .addAction(
                new Action.Builder()
                    .setTitle("Center")
                    .setOnClickListener(
                        () -> {
                          followVehicle = true;
                          requestRenderSoon();
                        })
                    .build())
            .build();
    return new NavigationTemplate.Builder().setActionStrip(actionStrip).build();
  }

  private void adjustZoom(double factor) {
    double next = metersPerPixel * factor;
    if (next < MIN_MPP) {
      next = MIN_MPP;
    }
    if (next > MAX_MPP) {
      next = MAX_MPP;
    }
    metersPerPixel = next;
    requestRenderSoon();
  }

  private void requestRenderSoon() {
    Handler handler = renderHandler;
    if (handler != null) {
      handler.removeCallbacks(renderTick);
      handler.post(renderTick);
    }
  }

  @SuppressLint("MissingPermission")
  private void startLocationUpdates() {
    if (carContext.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED) {
      Log.w(TAG, "location permission missing; falling back to backend GPS");
      return;
    }
    try {
      locationManager = (LocationManager) carContext.getSystemService(CarContext.LOCATION_SERVICE);
      locationListener =
          location -> {
            lastFix = location;
          };
      if (locationManager != null) {
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER, 2000L, 3f, locationListener);
      }
    } catch (Exception ex) {
      Log.w(TAG, "location updates unavailable: " + ex);
    }
  }

  private void stopLocationUpdates() {
    if (locationManager != null && locationListener != null) {
      try {
        locationManager.removeUpdates(locationListener);
      } catch (Exception ignored) {
      }
    }
    locationManager = null;
    locationListener = null;
  }

  /** One render pass: resolve center, fetch map PNG from backend, blit to surface. */
  private void renderOnce() {
    Surface target;
    int width;
    int height;
    synchronized (surfaceLock) {
      target = surface;
      width = surfaceWidth;
      height = surfaceHeight;
    }
    if (target == null || !target.isValid() || width <= 0 || height <= 0) {
      return;
    }

    String baseUrl = AppPrefs.baseUrl(carContext);
    Location fix = lastFix;
    double lat;
    double lon;
    double heading = 0.0;
    if (fix != null) {
      lat = fix.getLatitude();
      lon = fix.getLongitude();
      if (fix.hasBearing()) {
        heading = fix.getBearing();
      }
      maybePostGps(baseUrl, fix);
    } else {
      double[] backendFix = fetchBackendGps(baseUrl);
      if (backendFix != null) {
        lat = backendFix[0];
        lon = backendFix[1];
      } else {
        double[] dest = AppPrefs.destination(carContext);
        lat = dest != null ? dest[0] : DEFAULT_LAT;
        lon = dest != null ? dest[1] : DEFAULT_LON;
      }
    }

    StringBuilder url =
        new StringBuilder(baseUrl)
            .append("/api/map/render?lat=")
            .append(String.format(Locale.US, "%.6f", lat))
            .append("&lon=")
            .append(String.format(Locale.US, "%.6f", lon))
            .append("&mpp=")
            .append(String.format(Locale.US, "%.2f", metersPerPixel))
            .append("&heading=")
            .append(String.format(Locale.US, "%.1f", followVehicle ? heading : 0.0))
            .append("&w=")
            .append(width)
            .append("&h=")
            .append(height);
    double[] dest = AppPrefs.destination(carContext);
    if (dest != null) {
      url.append("&dest_lat=")
          .append(String.format(Locale.US, "%.6f", dest[0]))
          .append("&dest_lon=")
          .append(String.format(Locale.US, "%.6f", dest[1]));
    }

    Bitmap bitmap = null;
    try (Response response =
        httpClient.newCall(new Request.Builder().url(url.toString()).build()).execute()) {
      if (response.isSuccessful() && response.body() != null) {
        byte[] bytes = response.body().bytes();
        bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
      }
    } catch (Exception ex) {
      Log.w(TAG, "map fetch failed: " + ex);
    }

    drawFrame(target, bitmap);
    maybePollAlerts(baseUrl);
  }

  private void drawFrame(Surface target, Bitmap bitmap) {
    Canvas canvas = null;
    try {
      canvas = target.lockCanvas(null);
      canvas.drawColor(Color.BLACK);
      if (bitmap != null) {
        Rect dst = new Rect(0, 0, canvas.getWidth(), canvas.getHeight());
        canvas.drawBitmap(bitmap, null, dst, null);
      }
    } catch (Exception ex) {
      Log.w(TAG, "surface draw failed: " + ex);
    } finally {
      if (canvas != null) {
        try {
          target.unlockCanvasAndPost(canvas);
        } catch (Exception ignored) {
        }
      }
    }
  }

  /** Forward the car's GPS fix to the backend so scanning follows the vehicle. */
  private void maybePostGps(String baseUrl, Location fix) {
    long now = System.currentTimeMillis();
    if (now - lastGpsPostMs < GPS_POST_INTERVAL_MS) {
      return;
    }
    lastGpsPostMs = now;
    try {
      JSONObject body = new JSONObject();
      body.put("user_id", "android-auto-" + Build.MODEL.replace(' ', '_'));
      body.put("source", "android_auto");
      body.put("lat", fix.getLatitude());
      body.put("lon", fix.getLongitude());
      if (fix.hasAccuracy()) {
        body.put("accuracy", (double) fix.getAccuracy());
      }
      if (fix.hasSpeed()) {
        body.put("speed", (double) fix.getSpeed());
      }
      if (fix.hasBearing()) {
        body.put("heading", (double) fix.getBearing());
      }
      Request request =
          new Request.Builder()
              .url(baseUrl + "/api/gps/update")
              .post(RequestBody.create(body.toString(), JSON_MEDIA))
              .build();
      try (Response response = httpClient.newCall(request).execute()) {
        // best effort
      }
    } catch (Exception ex) {
      Log.w(TAG, "gps post failed: " + ex);
    }
  }

  /** Fallback center: latest fix known to the backend (e.g. from the phone app). */
  private double[] fetchBackendGps(String baseUrl) {
    try (Response response =
        httpClient
            .newCall(new Request.Builder().url(baseUrl + "/api/gps/latest").build())
            .execute()) {
      if (!response.isSuccessful() || response.body() == null) {
        return null;
      }
      JSONObject json = new JSONObject(response.body().string());
      JSONObject point = json.optJSONObject("point");
      if (point == null) {
        return null;
      }
      return new double[] {point.getDouble("lat"), point.getDouble("lon")};
    } catch (Exception ex) {
      return null;
    }
  }

  /** Poll scanner alerts and surface new ones as car toasts. */
  private void maybePollAlerts(String baseUrl) {
    long now = System.currentTimeMillis();
    if (now - lastAlertPollMs < ALERT_POLL_INTERVAL_MS) {
      return;
    }
    lastAlertPollMs = now;
    try (Response response =
        httpClient
            .newCall(new Request.Builder().url(baseUrl + "/api/mobile/snapshot").build())
            .execute()) {
      if (!response.isSuccessful() || response.body() == null) {
        return;
      }
      JSONObject json = new JSONObject(response.body().string());
      JSONArray events = json.optJSONArray("events");
      if (events == null || events.length() == 0) {
        return;
      }
      for (int i = events.length() - 1; i >= 0; i--) {
        JSONObject event = events.optJSONObject(i);
        if (event == null) {
          continue;
        }
        String type = event.optString("event_type", "");
        if (!"alert_triggered".equals(type)) {
          continue;
        }
        String key = event.optString("ts", "") + "|" + event.optString("alert", "");
        if (key.equals(lastAlertKey)) {
          return; // newest alert already shown
        }
        lastAlertKey = key;
        String alertText = event.optString("alert", "");
        if (alertText.isEmpty()) {
          alertText = event.optString("transcript", "Scanner alert");
        }
        final String toastText =
            alertText.length() > 120 ? alertText.substring(0, 117) + "..." : alertText;
        carContext
            .getMainExecutor()
            .execute(
                () -> CarToast.makeText(carContext, toastText, CarToast.LENGTH_LONG).show());
        return;
      }
    } catch (Exception ex) {
      Log.w(TAG, "alert poll failed: " + ex);
    }
  }
}
