package dev.warp.scannerstream;

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

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_route_options);
    statusText = findViewById(R.id.routeOptionsStatus);
    summaryText = findViewById(R.id.routeOptionsSummary);
    Button closeButton = findViewById(R.id.routeOptionsCloseBtn);
    closeButton.setOnClickListener(v -> finish());
    fetchOptions();
  }

  private void fetchOptions() {
    String baseUrl = getIntent().getStringExtra(EXTRA_BASE_URL);
    double originLat = getIntent().getDoubleExtra(EXTRA_ORIGIN_LAT, Double.NaN);
    double originLon = getIntent().getDoubleExtra(EXTRA_ORIGIN_LON, Double.NaN);
    double destLat = getIntent().getDoubleExtra(EXTRA_DEST_LAT, Double.NaN);
    double destLon = getIntent().getDoubleExtra(EXTRA_DEST_LON, Double.NaN);
    String destLabel = getIntent().getStringExtra(EXTRA_DEST_LABEL);
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
    final String destinationTitle = destLabel == null || destLabel.isBlank() ? "Destination" : destLabel;
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
                        });
                    return;
                  }
                  JSONObject payload = new JSONObject(response.body().string());
                  JSONArray alternatives = payload.optJSONArray("alternatives");
                  JSONObject hazards = payload.optJSONObject("waze_hazards");
                  List<String> lines = new ArrayList<>();
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
                              "Alt %d: %.1f km • %.0f min • ferries=%s • tolls=%s",
                              i + 1,
                              distanceM / 1000.0,
                              durationS / 60.0,
                              ferry ? "yes" : "no",
                              toll ? "yes" : "no"));
                    }
                  }
                  if (hazards != null) {
                    String hazardStatus = hazards.optString("status", "unknown");
                    lines.add("");
                    lines.add("Waze hazards: " + hazardStatus);
                  }
                  String text = String.join("\n", lines);
                  runOnUiThread(
                      () -> {
                        statusText.setText("Route options loaded");
                        summaryText.setText(text);
                      });
                } catch (Exception e) {
                  runOnUiThread(
                      () -> {
                        statusText.setText("Route options unavailable");
                        summaryText.setText("Parse error: " + e.getMessage());
                      });
                }
              }
            });
  }
}
