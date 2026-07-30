package dev.warp.scannerstream;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Shared preferences bridge between the phone UI (MainActivity) and the
 * Android Auto car app. The activity persists the backend base URL and the
 * current routing destination here; the car session reads them so both
 * surfaces stay in sync without binding to each other.
 */
public final class AppPrefs {
  public static final String DEFAULT_BASE_URL = "http://127.0.0.1:18080";

  private static final String PREFS_NAME = "scanner_stream_prefs";
  private static final String KEY_BASE_URL = "base_url";
  private static final String KEY_DEST_LAT = "dest_lat";
  private static final String KEY_DEST_LON = "dest_lon";
  private static final String KEY_DEST_LABEL = "dest_label";

  private AppPrefs() {}

  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  public static void saveBaseUrl(Context context, String baseUrl) {
    if (baseUrl == null || baseUrl.isEmpty()) {
      return;
    }
    prefs(context).edit().putString(KEY_BASE_URL, baseUrl).apply();
  }

  public static String baseUrl(Context context) {
    return prefs(context).getString(KEY_BASE_URL, DEFAULT_BASE_URL);
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
}
