package dev.warp.stream;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared preferences bridge between the phone UI (MainActivity) and the
 * Android Auto car app. The activity persists the backend base URL and the
 * current routing destination here; the car session reads them so both
 * surfaces stay in sync without binding to each other.
 */
public final class AppPrefs {
  public static final String USB_TUNNEL_BASE_URL = "http://127.0.0.1:18080";
  public static final String TAILSCALE_BASE_URL = "http://100.78.191.61:18080";
  public static final String DEFAULT_BASE_URL = TAILSCALE_BASE_URL;
  public static final String FALLBACK_BASE_URL = "http://192.168.1.39:18080";

  private static final String PREFS_NAME = "scanner_stream_prefs";
  private static final String KEY_BASE_URL = "base_url";
  private static final String KEY_PREFER_TAILSCALE = "prefer_tailscale";
  private static final String KEY_DEST_LAT = "dest_lat";
  private static final String KEY_DEST_LON = "dest_lon";
  private static final String KEY_DEST_LABEL = "dest_label";
  private static final String KEY_ACTIVE_TRACKING_ENABLED = "active_tracking_enabled";
  private static final String KEY_TRACKING_CONSENT_RESOLVED = "tracking_consent_resolved";
  private static final String KEY_ANALYTICS_ENABLED = "analytics_enabled";
  private static final long BASE_URL_PROBE_CACHE_MS = 15000L;
  private static final int BASE_URL_PROBE_TIMEOUT_MS = 900;
  private static volatile String cachedReachableBaseUrl = null;
  private static volatile long cachedReachableAtMs = 0L;
  private static final Object BASE_URL_PROBE_LOCK = new Object();

  private AppPrefs() {}

  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  private static String normalizeBaseUrl(String raw) {
    if (raw == null) {
      return DEFAULT_BASE_URL;
    }
    String value = raw.trim();
    if (value.isEmpty()) {
      return DEFAULT_BASE_URL;
    }
    if (value.endsWith("/")) {
      value = value.substring(0, value.length() - 1);
    }
    if (value.contains("localhost")) {
      return USB_TUNNEL_BASE_URL;
    }
    return value;
  }

  public static void saveBaseUrl(Context context, String baseUrl) {
    String normalized = normalizeBaseUrl(baseUrl);
    if (normalized == null || normalized.isEmpty()) {
      return;
    }
    prefs(context).edit().putString(KEY_BASE_URL, normalized).apply();
  }

  public static boolean preferTailscale(Context context) {
    return prefs(context).getBoolean(KEY_PREFER_TAILSCALE, false);
  }

  public static void setPreferTailscale(Context context, boolean enabled) {
    prefs(context).edit().putBoolean(KEY_PREFER_TAILSCALE, enabled).apply();
    synchronized (BASE_URL_PROBE_LOCK) {
      cachedReachableBaseUrl = null;
      cachedReachableAtMs = 0L;
    }
  }

  public static String baseUrl(Context context) {
    String stored = prefs(context).getString(KEY_BASE_URL, DEFAULT_BASE_URL);
    String normalized = normalizeBaseUrl(stored);
    if (normalized == null || normalized.isEmpty()) {
      normalized = DEFAULT_BASE_URL;
    }
    String cached = cachedReachableBaseUrl;
    long age = System.currentTimeMillis() - cachedReachableAtMs;
    if (cached != null && age >= 0 && age < BASE_URL_PROBE_CACHE_MS) {
      return cached;
    }
    return normalized;
  }

  public static String resolveReachableBaseUrl(Context context) {
    long now = System.currentTimeMillis();
    String cached = cachedReachableBaseUrl;
    long age = now - cachedReachableAtMs;
    if (cached != null && age >= 0 && age < BASE_URL_PROBE_CACHE_MS) {
      return cached;
    }
    synchronized (BASE_URL_PROBE_LOCK) {
      long innerAge = System.currentTimeMillis() - cachedReachableAtMs;
      if (cachedReachableBaseUrl != null && innerAge >= 0 && innerAge < BASE_URL_PROBE_CACHE_MS) {
        return cachedReachableBaseUrl;
      }
      String stored = normalizeBaseUrl(prefs(context).getString(KEY_BASE_URL, DEFAULT_BASE_URL));
      List<String> chain = buildProbeChain(stored, preferTailscale(context));
      String selected = chain.get(0);
      for (String candidate : chain) {
        if (isBackendHealthy(candidate)) {
          selected = candidate;
          break;
        }
      }
      cachedReachableBaseUrl = selected;
      cachedReachableAtMs = System.currentTimeMillis();
      if (!selected.equals(stored)) {
        prefs(context).edit().putString(KEY_BASE_URL, selected).apply();
      }
      return selected;
    }
  }

  private static List<String> buildProbeChain(String preferred, boolean preferTailscale) {
    Set<String> ordered = new LinkedHashSet<>();
    if (preferred != null && !preferred.isEmpty()) {
      ordered.add(preferred);
    }
    if (preferTailscale) {
      ordered.add(TAILSCALE_BASE_URL);
      ordered.add(DEFAULT_BASE_URL);
      ordered.add(FALLBACK_BASE_URL);
      ordered.add(USB_TUNNEL_BASE_URL);
    } else {
      ordered.add(TAILSCALE_BASE_URL);
      ordered.add(DEFAULT_BASE_URL);
      ordered.add(FALLBACK_BASE_URL);
      ordered.add(USB_TUNNEL_BASE_URL);
    }
    return new ArrayList<>(ordered);
  }

  private static boolean isBackendHealthy(String baseUrl) {
    HttpURLConnection connection = null;
    try {
      URL url = new URL(baseUrl + "/api/health");
      connection = (HttpURLConnection) url.openConnection();
      connection.setConnectTimeout(BASE_URL_PROBE_TIMEOUT_MS);
      connection.setReadTimeout(BASE_URL_PROBE_TIMEOUT_MS);
      connection.setRequestMethod("GET");
      int code = connection.getResponseCode();
      return code >= 200 && code < 300;
    } catch (Exception ignored) {
      return false;
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  public static void saveDestination(Context context, Double lat, Double lon) {
    SharedPreferences.Editor editor = prefs(context).edit();
    if (lat == null || lon == null) {
      editor.remove(KEY_DEST_LAT).remove(KEY_DEST_LON).remove(KEY_DEST_LABEL);
    } else {
      editor.putString(KEY_DEST_LAT, String.valueOf(lat));
      editor.putString(KEY_DEST_LON, String.valueOf(lon));
    }
    editor.apply();
  }

  public static void saveDestinationLabel(Context context, String label) {
    SharedPreferences.Editor editor = prefs(context).edit();
    if (label == null || label.trim().isEmpty()) {
      editor.remove(KEY_DEST_LABEL);
    } else {
      editor.putString(KEY_DEST_LABEL, label.trim());
    }
    editor.apply();
  }

  /** Returns {lat, lon} or null when no destination is set. */
  public static double[] destination(Context context) {
    SharedPreferences p = prefs(context);
    String lat = p.getString(KEY_DEST_LAT, null);
    String lon = p.getString(KEY_DEST_LON, null);
    if (lat == null || lon == null) {
      return null;
    }
    try {
      return new double[] {Double.parseDouble(lat), Double.parseDouble(lon)};
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  public static String destinationLabel(Context context) {
    return prefs(context).getString(KEY_DEST_LABEL, "");
  }

  public static boolean isActiveTrackingEnabled(Context context) {
    return prefs(context).getBoolean(KEY_ACTIVE_TRACKING_ENABLED, true);
  }

  public static boolean isTrackingConsentResolved(Context context) {
    return prefs(context).getBoolean(KEY_TRACKING_CONSENT_RESOLVED, false);
  }

  public static void setTrackingConsent(
      Context context, boolean activeTrackingEnabled, boolean markResolved) {
    prefs(context)
        .edit()
        .putBoolean(KEY_ACTIVE_TRACKING_ENABLED, activeTrackingEnabled)
        .putBoolean(KEY_TRACKING_CONSENT_RESOLVED, markResolved)
        .apply();
  }

  public static boolean isAnalyticsEnabled(Context context) {
    return prefs(context).getBoolean(KEY_ANALYTICS_ENABLED, true);
  }

  public static void setAnalyticsEnabled(Context context, boolean enabled) {
    prefs(context).edit().putBoolean(KEY_ANALYTICS_ENABLED, enabled).apply();
  }
}
