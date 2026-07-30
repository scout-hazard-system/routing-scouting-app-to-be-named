package dev.warp.scannerstream;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

public class RouteOptionsActivity extends AppCompatActivity {
  public static final String EXTRA_BASE_URL = "extra_base_url";
  public static final String EXTRA_ORIGIN_LAT = "extra_origin_lat";
  public static final String EXTRA_ORIGIN_LON = "extra_origin_lon";
  public static final String EXTRA_DEST_LAT = "extra_dest_lat";
  public static final String EXTRA_DEST_LON = "extra_dest_lon";
  public static final String EXTRA_DEST_LABEL = "extra_dest_label";

  private final OkHttpClient client = new OkHttpClient.Builder().build();
  private TextView statusText;
  private TextView summaryText;
  private TextView hazardsText;
  private MapView routeMapView;
  private double destinationLat = Double.NaN;
  private double destinationLon = Double.NaN;
  private String destinationLabel = "Destination";
  private static final class RouteOverlayMeta {
    private final List<GeoPoint> path;
    private final boolean hasTollHint;
    private final boolean hasFerryHint;

    private RouteOverlayMeta(List<GeoPoint> path, boolean hasTollHint, boolean hasFerryHint) {
      this.path = path;
      this.hasTollHint = hasTollHint;
      this.hasFerryHint = hasFerryHint;
    }
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_route_options);
    statusText = findViewById(R.id.routeOptionsStatus);
    summaryText = findViewById(R.id.routeOptionsSummary);
    hazardsText = findViewById(R.id.routeOptionsHazards);
    routeMapView = findViewById(R.id.routeOptionsMapView);
    setupMap();
    Button closeButton = findViewById(R.id.routeOptionsCloseBtn);
    Button startButton = findViewById(R.id.routeOptionsStartBtn);
    closeButton.setOnClickListener(v -> finish());
    startButton.setOnClickListener(v -> startNavigation());
    fetchOptions();
  }

  private void setupMap() {
    routeMapView.setTileSource(TileSourceFactory.MAPNIK);
    routeMapView.setMultiTouchControls(true);
    routeMapView.getController().setZoom(12.5d);
    routeMapView.getController().setCenter(new GeoPoint(37.7749, -122.4194));
  }

  private void fetchOptions() {
    String baseUrl = getIntent().getStringExtra(EXTRA_BASE_URL);
    double originLat = readNumericExtra(EXTRA_ORIGIN_LAT);
    double originLon = readNumericExtra(EXTRA_ORIGIN_LON);
    double destLat = readNumericExtra(EXTRA_DEST_LAT);
    double destLon = readNumericExtra(EXTRA_DEST_LON);
    String destLabel = getIntent().getStringExtra(EXTRA_DEST_LABEL);
    destinationLat = destLat;
    destinationLon = destLon;
    destinationLabel = (destLabel == null || destLabel.isBlank()) ? "Destination" : destLabel;
    if (baseUrl == null || baseUrl.isEmpty() || !Double.isFinite(destLat) || !Double.isFinite(destLon)) {
      statusText.setText("Route options unavailable");
      summaryText.setText("Missing route context.");
      return;
    }
    if (!Double.isFinite(originLat) || !Double.isFinite(originLon)) {
      originLat = destLat;
      originLon = destLon;
    }
    String url =
        baseUrl
            + "/api/platform/route/options?origin_lat="
            + String.format(Locale.ROOT, "%.6f", originLat)
            + "&origin_lon="
            + String.format(Locale.ROOT, "%.6f", originLon)
            + "&dest_lat="
            + String.format(Locale.ROOT, "%.6f", destLat)
            + "&dest_lon="
            + String.format(Locale.ROOT, "%.6f", destLon);
    statusText.setText("Loading route options…");
    final String destinationTitle = destinationLabel;
    final double resolvedOriginLat = originLat;
    final double resolvedOriginLon = originLon;
    final double resolvedDestLat = destLat;
    final double resolvedDestLon = destLon;
    Request request = new Request.Builder().url(url).build();
    client.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                runOnUiThread(
                    () -> {
                      statusText.setText("Route options unavailable");
                      summaryText.setText("Backend request failed: " + e.getMessage());
                      hazardsText.setText("Hazards unavailable");
                    });
              }

              @Override
              public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                  if (!response.isSuccessful() || response.body() == null) {
                    runOnUiThread(
                        () -> {
                          statusText.setText("Route options unavailable");
                          summaryText.setText("HTTP " + response.code());
                          hazardsText.setText("Hazards unavailable");
                        });
                    return;
                  }
                  JSONObject payload = new JSONObject(response.body().string());
                  JSONArray alternatives = payload.optJSONArray("alternatives");
                  JSONObject hazards = payload.optJSONObject("waze_hazards");
                  JSONObject alertClustersPayload = payload.optJSONObject("alert_clusters");
                  List<String> lines = new ArrayList<>();
                  List<RouteOverlayMeta> routeOverlays = new ArrayList<>();
                  List<GeoPoint> alertClusterPoints = new ArrayList<>();
                  int alertClusterCount = 0;
                  lines.add(destinationTitle);
                  lines.add("");
                  if (alternatives != null) {
                    for (int i = 0; i < alternatives.length(); i++) {
                      JSONObject route = alternatives.optJSONObject(i);
                      if (route == null) {
                        continue;
                      }
                      double distanceM = route.optDouble("distance_m", 0.0);
                      double durationS = route.optDouble("duration_s", 0.0);
                      boolean ferry = route.optBoolean("has_ferry_hint", false);
                      boolean toll = route.optBoolean("has_toll_hint", false);
                      lines.add(
                          String.format(
                              Locale.ROOT,
                              "Route %d  •  %.1f km  •  %.0f min  •  ferry %s  •  toll %s",
                              i + 1,
                              distanceM / 1000.0,
                              durationS / 60.0,
                              ferry ? "yes" : "no",
                              toll ? "yes" : "no"));
                      JSONArray points = route.optJSONArray("route_points");
                      List<GeoPoint> path = new ArrayList<>();
                      if (points != null) {
                        for (int p = 0; p < points.length(); p++) {
                          JSONObject point = points.optJSONObject(p);
                          if (point == null) {
                            continue;
                          }
                          double lat = point.optDouble("lat", Double.NaN);
                          double lon = point.optDouble("lon", Double.NaN);
                          if (Double.isFinite(lat) && Double.isFinite(lon)) {
                            path.add(new GeoPoint(lat, lon));
                          }
                        }
                      }
                      if (path.size() >= 2) {
                        routeOverlays.add(new RouteOverlayMeta(path, toll, ferry));
                      }
                    }
                  }
                  if (alertClustersPayload != null) {
                    JSONArray clusters = alertClustersPayload.optJSONArray("clusters");
                    if (clusters != null) {
                      alertClusterCount = clusters.length();
                      for (int i = 0; i < clusters.length(); i++) {
                        JSONObject cluster = clusters.optJSONObject(i);
                        if (cluster == null) {
                          continue;
                        }
                        double lat = cluster.optDouble("lat", Double.NaN);
                        double lon = cluster.optDouble("lon", Double.NaN);
                        if (Double.isFinite(lat) && Double.isFinite(lon)) {
                          alertClusterPoints.add(new GeoPoint(lat, lon));
                        }
                      }
                    }
                  }
                  String hazardLine = "Waze hazards: unavailable";
                  if (hazards != null) {
                    String hazardStatus = hazards.optString("status", "unknown");
                    String provider = hazards.optString("provider", "waze");
                    hazardLine = "Waze hazards: " + hazardStatus + " (" + provider + ")";
                  }
                  String clusterLine = "Alert clusters: " + alertClusterCount;
                  String text = String.join("\n", lines);
                  final String hazardDisplay = hazardLine;
                  final String clusterDisplay = clusterLine;
                  runOnUiThread(
                      () -> {
                        statusText.setText("Choose a route");
                        summaryText.setText(text);
                        hazardsText.setText(hazardDisplay + "  •  " + clusterDisplay);
                        renderRoutes(
                            routeOverlays,
                            alertClusterPoints,
                            resolvedOriginLat,
                            resolvedOriginLon,
                            resolvedDestLat,
                            resolvedDestLon);
                      });
                } catch (Exception e) {
                  runOnUiThread(
                      () -> {
                        statusText.setText("Route options unavailable");
                        summaryText.setText("Parse error: " + e.getMessage());
                        hazardsText.setText("Hazards unavailable");
                      });
                }
              }
            });
  }

  private double readNumericExtra(String key) {
    Bundle extras = getIntent().getExtras();
    if (extras == null || !extras.containsKey(key)) {
      return Double.NaN;
    }
    Object raw = extras.get(key);
    if (raw instanceof Number) {
      return ((Number) raw).doubleValue();
    }
    if (raw instanceof String) {
      try {
        return Double.parseDouble((String) raw);
      } catch (NumberFormatException ignored) {
        return Double.NaN;
      }
    }
    return Double.NaN;
  }

  private void renderRoutes(
      List<RouteOverlayMeta> routeOverlays,
      List<GeoPoint> alertClusterPoints,
      double originLat,
      double originLon,
      double destLat,
      double destLon) {
    routeMapView.getOverlays().clear();
    int[] palette = new int[] {0xFF2B6BE6, 0xFF00AEEF, 0xFF50C878, 0xFFF9A825, 0xFFE53935};
    for (int i = 0; i < routeOverlays.size(); i++) {
      RouteOverlayMeta meta = routeOverlays.get(i);
      Polyline polyline = new Polyline(routeMapView);
      polyline.setPoints(meta.path);
      polyline.getOutlinePaint().setColor(palette[i % palette.length]);
      polyline.getOutlinePaint().setStrokeWidth(i == 0 ? 11f : 7f);
      routeMapView.getOverlays().add(polyline);
      GeoPoint tollPoint = pointAtFraction(meta.path, 0.35);
      if (tollPoint != null) {
        Marker toll = new Marker(routeMapView);
        toll.setPosition(tollPoint);
        toll.setTitle("Route " + (i + 1) + " toll: " + (meta.hasTollHint ? "yes" : "no"));
        toll.setTextIcon("T" + (i + 1));
        toll.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        routeMapView.getOverlays().add(toll);
      }
      GeoPoint ferryPoint = pointAtFraction(meta.path, 0.65);
      if (ferryPoint != null) {
        Marker ferry = new Marker(routeMapView);
        ferry.setPosition(ferryPoint);
        ferry.setTitle("Route " + (i + 1) + " ferry: " + (meta.hasFerryHint ? "yes" : "no"));
        ferry.setTextIcon("F" + (i + 1));
        ferry.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        routeMapView.getOverlays().add(ferry);
      }
    }
    for (int i = 0; i < alertClusterPoints.size(); i++) {
      GeoPoint p = alertClusterPoints.get(i);
      Marker cluster = new Marker(routeMapView);
      cluster.setPosition(p);
      cluster.setTitle("Alert cluster " + (i + 1));
      cluster.setTextIcon("C" + (i + 1));
      cluster.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
      routeMapView.getOverlays().add(cluster);
    }
    Marker origin = new Marker(routeMapView);
    origin.setPosition(new GeoPoint(originLat, originLon));
    origin.setTitle("Start");
    origin.setTextIcon("S");
    origin.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
    Marker destination = new Marker(routeMapView);
    destination.setPosition(new GeoPoint(destLat, destLon));
    destination.setTitle("Destination");
    destination.setTextIcon("D");
    destination.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
    routeMapView.getOverlays().add(origin);
    routeMapView.getOverlays().add(destination);

    double north = Math.max(originLat, destLat);
    double south = Math.min(originLat, destLat);
    double east = Math.max(originLon, destLon);
    double west = Math.min(originLon, destLon);
    for (RouteOverlayMeta meta : routeOverlays) {
      List<GeoPoint> line = meta.path;
      for (GeoPoint point : line) {
        north = Math.max(north, point.getLatitude());
        south = Math.min(south, point.getLatitude());
        east = Math.max(east, point.getLongitude());
        west = Math.min(west, point.getLongitude());
      }
    }
    for (GeoPoint point : alertClusterPoints) {
      north = Math.max(north, point.getLatitude());
      south = Math.min(south, point.getLatitude());
      east = Math.max(east, point.getLongitude());
      west = Math.min(west, point.getLongitude());
    }
    BoundingBox box = new BoundingBox(north, east, south, west);
    routeMapView.zoomToBoundingBox(box, true, 96);
    routeMapView.invalidate();
  }

  private GeoPoint pointAtFraction(List<GeoPoint> points, double fraction) {
    if (points == null || points.isEmpty()) {
      return null;
    }
    double clamped = Math.max(0.0, Math.min(1.0, fraction));
    int index = (int) Math.round((points.size() - 1) * clamped);
    index = Math.max(0, Math.min(points.size() - 1, index));
    return points.get(index);
  }

  private void startNavigation() {
    if (!Double.isFinite(destinationLat) || !Double.isFinite(destinationLon)) {
      statusText.setText("Destination unavailable");
      return;
    }
    String coord = String.format(Locale.ROOT, "%.6f,%.6f", destinationLat, destinationLon);
    Intent navIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + coord));
    navIntent.setPackage("com.google.android.apps.maps");
    navIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    try {
      startActivity(navIntent);
      return;
    } catch (ActivityNotFoundException ignored) {
      // fallback below
    }
    Intent geoIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + coord));
    geoIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    try {
      startActivity(geoIntent);
    } catch (ActivityNotFoundException e) {
      statusText.setText("No maps app available");
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    routeMapView.onResume();
  }

  @Override
  protected void onPause() {
    routeMapView.onPause();
    super.onPause();
  }
}
