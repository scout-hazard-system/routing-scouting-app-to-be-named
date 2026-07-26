import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ScannerBackendServer {
  private static final String DEFAULT_HOST = "0.0.0.0";
  private static final int DEFAULT_PORT = 8080;
  private static final int RECENT_EVENT_LIMIT = 120;
  private static final int SNAPSHOT_EVENT_RETURN_LIMIT = 30;
  private static final int MOBILE_EVENT_RETURN_LIMIT = 12;
  private static final long STREAM_POLL_MILLIS = 350L;
  private static final String EVENT_PREFIX = "[EVENT_JSON] ";
  private static final Pattern EVENT_TYPE_PATTERN = Pattern.compile("\"event_type\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern KIND_PATTERN = Pattern.compile("\"kind\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern TS_PATTERN = Pattern.compile("\"ts\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern TRANSCRIPT_PATTERN = Pattern.compile("\"transcript\"\\s*:\\s*\"([^\"]*)\"");
  private static final Pattern ALERT_PATTERN = Pattern.compile("\"alert\"\\s*:\\s*\"([^\"]*)\"");
  private static final String WEATHER_PROVIDER = System.getenv().getOrDefault("WEATHER_PROVIDER", "mock");
  private static final String WAZE_DEEPLINK_BASE_URL =
      System.getenv().getOrDefault("WAZE_DEEPLINK_BASE_URL", "https://waze.com/ul");
  private static final String WAZE_EMBED_BASE_URL =
      System.getenv().getOrDefault("WAZE_EMBED_BASE_URL", "https://embed.waze.com/iframe");
  private static final Path LOG_PATH =
      Path.of(System.getenv().getOrDefault("PIPELINE_LOG_PATH", "/tmp/pipeline_live_doordash.log"));
  private static final String HOST = System.getenv().getOrDefault("JAVA_BACKEND_HOST", DEFAULT_HOST);
  private static final int PORT = parsePort(System.getenv("JAVA_BACKEND_PORT"), DEFAULT_PORT);

  public static void main(String[] args) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(HOST, PORT), 0);
    server.createContext("/api/health", new HealthHandler());
    server.createContext("/api/pipeline/snapshot", new SnapshotHandler());
    server.createContext("/api/pipeline/stream", new StreamHandler());
    server.createContext("/api/route/weather", new WeatherHandler());
    server.createContext("/api/platform/weather/forecast", new PlatformWeatherHandler());
    server.createContext("/api/platform/waze/route", new WazeRouteHandler());
    server.createContext("/api/platform/providers/status", new ProviderStatusHandler());
    server.createContext("/api/mobile/bootstrap", new MobileBootstrapHandler());
    server.createContext("/api/mobile/snapshot", new MobileSnapshotHandler());
    server.createContext("/api/mobile/stream", new MobileStreamHandler());
    server.setExecutor(Executors.newCachedThreadPool());
    System.out.printf(
        Locale.ROOT,
        "[java-backend] serving on http://%s:%d%n[java-backend] pipeline log source: %s%n",
        HOST,
        PORT,
        LOG_PATH);
    server.start();
  }

  private static final class EventInfo {
    private final String rawJson;
    private final String eventType;
    private final String kind;

    private EventInfo(String rawJson, String eventType, String kind) {
      this.rawJson = rawJson;
      this.eventType = eventType;
      this.kind = kind;
    }
  }

  private static final class SnapshotData {
    private final List<EventInfo> events;
    private final Map<String, Integer> eventTypeCounts;
    private final Map<String, Integer> metrics;

    private SnapshotData(List<EventInfo> events, Map<String, Integer> eventTypeCounts, Map<String, Integer> metrics) {
      this.events = events;
      this.eventTypeCounts = eventTypeCounts;
      this.metrics = metrics;
    }
  }

  private static final class WazeRouteData {
    private final String appUrl;
    private final String embedUrl;
    private final String mode;
    private final String start;
    private final String end;
    private final double lat;
    private final double lon;

    private WazeRouteData(String appUrl, String embedUrl, String mode, String start, String end, double lat, double lon) {
      this.appUrl = appUrl;
      this.embedUrl = embedUrl;
      this.mode = mode;
      this.start = start;
      this.end = end;
      this.lat = lat;
      this.lon = lon;
    }
  }

  private static int parsePort(String maybePort, int fallback) {
    if (maybePort == null || maybePort.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(maybePort);
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private static SnapshotData buildSnapshotData() {
    List<EventInfo> events = readEventLines(RECENT_EVENT_LIMIT);
    Map<String, Integer> eventTypeCounts = new HashMap<>();
    Map<String, Integer> metrics = new HashMap<>();
    metrics.put("captured", 0);
    metrics.put("skipped_silence", 0);
    metrics.put("skipped_clipped", 0);
    metrics.put("llm_alert", 0);
    metrics.put("soft_alert_fallback", 0);

    for (EventInfo event : events) {
      if (!event.eventType.isEmpty()) {
        eventTypeCounts.merge(event.eventType, 1, Integer::sum);
      }
      switch (event.eventType) {
        case "chunk_captured":
          metrics.merge("captured", 1, Integer::sum);
          break;
        case "chunk_skipped_silence":
          metrics.merge("skipped_silence", 1, Integer::sum);
          break;
        case "chunk_skipped_clipped":
          metrics.merge("skipped_clipped", 1, Integer::sum);
          break;
        case "alert_triggered":
          if ("soft_alert_fallback".equals(event.kind)) {
            metrics.merge("soft_alert_fallback", 1, Integer::sum);
          } else {
            metrics.merge("llm_alert", 1, Integer::sum);
          }
          break;
        case "run_summary":
          applyRunSummaryMetrics(metrics, event.rawJson);
          break;
        default:
          break;
      }
    }

    int fromIndex = Math.max(0, events.size() - SNAPSHOT_EVENT_RETURN_LIMIT);
    List<EventInfo> recentEvents = events.subList(fromIndex, events.size());
    return new SnapshotData(recentEvents, eventTypeCounts, metrics);
  }

  private static void applyRunSummaryMetrics(Map<String, Integer> metrics, String rawJson) {
    metrics.put("captured", extractIntField(rawJson, "captured", metrics.getOrDefault("captured", 0)));
    metrics.put(
        "skipped_silence",
        extractIntField(rawJson, "skipped_silence", metrics.getOrDefault("skipped_silence", 0)));
    metrics.put(
        "skipped_clipped",
        extractIntField(rawJson, "skipped_clipped", metrics.getOrDefault("skipped_clipped", 0)));
    metrics.put("llm_alert", extractIntField(rawJson, "llm_alert", metrics.getOrDefault("llm_alert", 0)));
    metrics.put(
        "soft_alert_fallback",
        extractIntField(rawJson, "soft_alert_fallback", metrics.getOrDefault("soft_alert_fallback", 0)));
  }

  private static List<EventInfo> readEventLines(int maxEvents) {
    Deque<EventInfo> out = new ArrayDeque<>(Math.max(maxEvents, 1));
    if (!Files.exists(LOG_PATH)) {
      return List.of();
    }
    try (BufferedReader reader = Files.newBufferedReader(LOG_PATH, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (!line.startsWith(EVENT_PREFIX)) {
          continue;
        }
        String raw = line.substring(EVENT_PREFIX.length()).trim();
        String eventType = extractStringField(raw, EVENT_TYPE_PATTERN);
        String kind = extractStringField(raw, KIND_PATTERN);
        out.addLast(new EventInfo(raw, eventType, kind));
        while (out.size() > maxEvents) {
          out.removeFirst();
        }
      }
    } catch (IOException ignored) {
      return List.of();
    }
    return new ArrayList<>(out);
  }

  private static String extractStringField(String json, Pattern pattern) {
    Matcher matcher = pattern.matcher(json);
    if (matcher.find()) {
      return matcher.group(1);
    }
    return "";
  }

  private static int extractIntField(String json, String fieldName, int fallback) {
    Pattern pattern = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*(-?\\d+)");
    Matcher matcher = pattern.matcher(json);
    if (matcher.find()) {
      try {
        return Integer.parseInt(matcher.group(1));
      } catch (NumberFormatException ignored) {
        return fallback;
      }
    }
    return fallback;
  }

  private static String jsonEscape(String value) {
    StringBuilder escaped = new StringBuilder(value.length() + 16);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\"':
          escaped.append("\\\"");
          break;
        case '\\':
          escaped.append("\\\\");
          break;
        case '\n':
          escaped.append("\\n");
          break;
        case '\r':
          escaped.append("\\r");
          break;
        case '\t':
          escaped.append("\\t");
          break;
        default:
          if (c < 0x20) {
            escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          } else {
            escaped.append(c);
          }
      }
    }
    return escaped.toString();
  }

  private static String metricMapToJson(Map<String, Integer> map) {
    return "{"
        + "\"captured\":" + map.getOrDefault("captured", 0) + ","
        + "\"skipped_silence\":" + map.getOrDefault("skipped_silence", 0) + ","
        + "\"skipped_clipped\":" + map.getOrDefault("skipped_clipped", 0) + ","
        + "\"llm_alert\":" + map.getOrDefault("llm_alert", 0) + ","
        + "\"soft_alert_fallback\":" + map.getOrDefault("soft_alert_fallback", 0)
        + "}";
  }

  private static String eventTypeCountsToJson(Map<String, Integer> map) {
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, Integer> entry : map.entrySet()) {
      if (!first) {
        sb.append(",");
      }
      first = false;
      sb.append("\"").append(jsonEscape(entry.getKey())).append("\":").append(entry.getValue());
    }
    sb.append("}");
    return sb.toString();
  }

  private static String snapshotToJson(SnapshotData data) {
    StringBuilder eventsJson = new StringBuilder("[");
    for (int i = 0; i < data.events.size(); i++) {
      if (i > 0) {
        eventsJson.append(",");
      }
      eventsJson.append(data.events.get(i).rawJson);
    }
    eventsJson.append("]");

    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"log_path\":\"" + jsonEscape(LOG_PATH.toString()) + "\","
        + "\"event_type_counts\":" + eventTypeCountsToJson(data.eventTypeCounts) + ","
        + "\"metrics\":" + metricMapToJson(data.metrics) + ","
        + "\"recentEvents\":" + eventsJson
        + "}";
  }

  private static String mobileBootstrapJson() {
    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"status\":\"ok\","
        + "\"version\":\"v1\","
        + "\"endpoints\":{"
        + "\"snapshot\":\"/api/mobile/snapshot\","
        + "\"stream\":\"/api/mobile/stream\","
        + "\"weather\":\"/api/platform/weather/forecast\","
        + "\"waze\":\"/api/platform/waze/route\""
        + "},"
        + "\"notes\":\"Compact endpoints are intended for low-bandwidth mobile companion clients.\""
        + "}";
  }

  private static String compactMobileEventJson(EventInfo event) {
    String ts = extractStringField(event.rawJson, TS_PATTERN);
    String transcript = extractStringField(event.rawJson, TRANSCRIPT_PATTERN);
    String alert = extractStringField(event.rawJson, ALERT_PATTERN);
    return "{"
        + "\"ts\":\"" + jsonEscape(ts) + "\","
        + "\"event_type\":\"" + jsonEscape(event.eventType) + "\","
        + "\"kind\":\"" + jsonEscape(event.kind) + "\","
        + "\"transcript\":\"" + jsonEscape(transcript) + "\","
        + "\"alert\":\"" + jsonEscape(alert) + "\""
        + "}";
  }

  private static String mobileSnapshotJson() {
    SnapshotData snapshot = buildSnapshotData();
    List<EventInfo> events = snapshot.events;
    int fromIndex = Math.max(0, events.size() - MOBILE_EVENT_RETURN_LIMIT);
    StringBuilder compactEvents = new StringBuilder("[");
    for (int i = fromIndex; i < events.size(); i++) {
      if (i > fromIndex) {
        compactEvents.append(",");
      }
      compactEvents.append(compactMobileEventJson(events.get(i)));
    }
    compactEvents.append("]");
    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"metrics\":" + metricMapToJson(snapshot.metrics) + ","
        + "\"events\":" + compactEvents + ","
        + "\"log_exists\":" + Files.exists(LOG_PATH)
        + "}";
  }

  private static WazeRouteData buildWazeRoute(Map<String, String> query) {
    String start = query.getOrDefault("start", "");
    String end = query.getOrDefault("end", "");
    String latRaw = query.getOrDefault("lat", "");
    String lonRaw = query.getOrDefault("lon", "");
    double lat = parseDouble(latRaw, 34.0522);
    double lon = parseDouble(lonRaw, -118.2437);
    boolean hasCoords = !latRaw.isBlank() && !lonRaw.isBlank();
    String appUrl;
    if (hasCoords) {
      appUrl = WAZE_DEEPLINK_BASE_URL + "?ll=" + urlEncode(latRaw) + "," + urlEncode(lonRaw) + "&navigate=yes";
    } else if (!end.isBlank()) {
      appUrl = WAZE_DEEPLINK_BASE_URL + "?q=" + urlEncode(end) + "&navigate=yes";
    } else {
      appUrl = WAZE_DEEPLINK_BASE_URL + "?ll=" + lat + "," + lon + "&navigate=yes";
    }
    String embedUrl = WAZE_EMBED_BASE_URL + "?zoom=11&lat=" + lat + "&lon=" + lon;
    String mode = hasCoords ? "coords" : (!end.isBlank() ? "destination_query" : "default");
    return new WazeRouteData(appUrl, embedUrl, mode, start, end, lat, lon);
  }

  private static String buildWeatherJson(Map<String, String> query) {
    String start = query.getOrDefault("start", "");
    String end = query.getOrDefault("end", "");
    String provider = query.getOrDefault("provider", WEATHER_PROVIDER);
    String source;
    String notes;
    if ("mock".equalsIgnoreCase(provider)) {
      source = "mock";
      notes = "Mock weather forecast response.";
    } else {
      source = "mock_fallback";
      notes = "Provider '" + provider + "' is not wired yet; using mock fallback.";
    }

    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"start\":\"" + jsonEscape(start) + "\","
        + "\"end\":\"" + jsonEscape(end) + "\","
        + "\"provider\":\"" + jsonEscape(provider) + "\","
        + "\"source\":\"" + jsonEscape(source) + "\","
        + "\"notes\":\"" + jsonEscape(notes) + "\","
        + "\"forecast\":["
        + "{\"segment\":\"start\",\"time\":\"Now\",\"temp\":78,\"condition\":\"clear\"},"
        + "{\"segment\":\"segment-1\",\"time\":\"+20m\",\"temp\":79,\"condition\":\"partly_cloudy\"},"
        + "{\"segment\":\"segment-2\",\"time\":\"+40m\",\"temp\":80,\"condition\":\"windy\"},"
        + "{\"segment\":\"segment-3\",\"time\":\"+60m\",\"temp\":81,\"condition\":\"light_rain\"},"
        + "{\"segment\":\"destination\",\"time\":\"+80m\",\"temp\":79,\"condition\":\"cloudy\"}"
        + "]"
        + "}";
  }

  private static String providerStatusJson() {
    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"providers\":{"
        + "\"weather\":{"
        + "\"configured_provider\":\"" + jsonEscape(WEATHER_PROVIDER) + "\","
        + "\"ready\":" + ("mock".equalsIgnoreCase(WEATHER_PROVIDER)) + ","
        + "\"notes\":\"Set WEATHER_PROVIDER to desired provider and implement provider client in backend.\""
        + "},"
        + "\"waze\":{"
        + "\"deeplink_base_url\":\"" + jsonEscape(WAZE_DEEPLINK_BASE_URL) + "\","
        + "\"embed_base_url\":\"" + jsonEscape(WAZE_EMBED_BASE_URL) + "\","
        + "\"ready\":true,"
        + "\"notes\":\"Waze route URLs are generated server-side for frontend consumption.\""
        + "}"
        + "}"
        + "}";
  }

  private static String wazeRouteToJson(WazeRouteData route) {
    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"mode\":\"" + jsonEscape(route.mode) + "\","
        + "\"start\":\"" + jsonEscape(route.start) + "\","
        + "\"end\":\"" + jsonEscape(route.end) + "\","
        + "\"lat\":" + route.lat + ","
        + "\"lon\":" + route.lon + ","
        + "\"app_url\":\"" + jsonEscape(route.appUrl) + "\","
        + "\"embed_url\":\"" + jsonEscape(route.embedUrl) + "\""
        + "}";
  }

  private static void writeJson(HttpExchange exchange, int statusCode, String body) throws IOException {
    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
    Headers headers = exchange.getResponseHeaders();
    headers.set("Content-Type", "application/json; charset=utf-8");
    headers.set("Cache-Control", "no-store");
    headers.set("Access-Control-Allow-Origin", "*");
    exchange.sendResponseHeaders(statusCode, payload.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(payload);
    }
  }

  private static void writeTextEventStreamHeaders(HttpExchange exchange) throws IOException {
    Headers headers = exchange.getResponseHeaders();
    headers.set("Content-Type", "text/event-stream; charset=utf-8");
    headers.set("Cache-Control", "no-cache, no-store, must-revalidate");
    headers.set("Connection", "keep-alive");
    headers.set("Access-Control-Allow-Origin", "*");
    exchange.sendResponseHeaders(200, 0);
  }

  private static boolean isGet(HttpExchange exchange) {
    return "GET".equals(exchange.getRequestMethod());
  }

  private static final class HealthHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      String body =
          "{"
              + "\"status\":\"ok\","
              + "\"ts\":\"" + Instant.now().toString() + "\","
              + "\"bind_host\":\"" + jsonEscape(HOST) + "\","
              + "\"bind_port\":" + PORT + ","
              + "\"log_exists\":" + Files.exists(LOG_PATH) + ","
              + "\"log_path\":\"" + jsonEscape(LOG_PATH.toString()) + "\","
              + "\"weather_provider\":\"" + jsonEscape(WEATHER_PROVIDER) + "\""
              + "}";
      writeJson(exchange, 200, body);
    }
  }

  private static final class SnapshotHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      SnapshotData data = buildSnapshotData();
      writeJson(exchange, 200, snapshotToJson(data));
    }
  }

  private static final class WeatherHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      writeJson(exchange, 200, buildWeatherJson(query));
    }
  }

  private static final class PlatformWeatherHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      writeJson(exchange, 200, buildWeatherJson(query));
    }
  }

  private static final class WazeRouteHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      WazeRouteData route = buildWazeRoute(query);
      writeJson(exchange, 200, wazeRouteToJson(route));
    }
  }

  private static final class ProviderStatusHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      writeJson(exchange, 200, providerStatusJson());
    }
  }

  private static final class MobileBootstrapHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      writeJson(exchange, 200, mobileBootstrapJson());
    }
  }

  private static final class MobileSnapshotHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      writeJson(exchange, 200, mobileSnapshotJson());
    }
  }

  private static final class StreamHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      writeTextEventStreamHeaders(exchange);
      OutputStream os = exchange.getResponseBody();
      String heartbeat =
          "{"
              + "\"ts\":\"" + Instant.now().toString() + "\","
              + "\"event_type\":\"server_heartbeat\","
              + "\"source\":\"java_backend\","
              + "\"log_path\":\"" + jsonEscape(LOG_PATH.toString()) + "\""
              + "}";
      os.write(("data: " + heartbeat + "\n\n").getBytes(StandardCharsets.UTF_8));
      os.flush();

      if (!Files.exists(LOG_PATH)) {
        String warn =
            "{"
                + "\"ts\":\"" + Instant.now().toString() + "\","
                + "\"event_type\":\"server_warning\","
                + "\"message\":\"log file not found: " + jsonEscape(LOG_PATH.toString()) + "\""
                + "}";
        os.write(("data: " + warn + "\n\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
      }

      long offset = Files.exists(LOG_PATH) ? Files.size(LOG_PATH) : 0L;
      try {
        while (true) {
          if (!Files.exists(LOG_PATH)) {
            sleepQuietly(STREAM_POLL_MILLIS);
            continue;
          }
          long size = Files.size(LOG_PATH);
          if (size < offset) {
            offset = 0L;
          }
          if (size == offset) {
            sleepQuietly(STREAM_POLL_MILLIS);
            continue;
          }
          String chunk = Files.readString(LOG_PATH, StandardCharsets.UTF_8);
          if (offset > chunk.length()) {
            offset = 0L;
          }
          String unread = chunk.substring((int) offset);
          offset = chunk.length();
          for (String line : unread.split("\\R")) {
            if (!line.startsWith(EVENT_PREFIX)) {
              continue;
            }
            String payload = line.substring(EVENT_PREFIX.length()).trim();
            os.write(("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
          }
        }
      } catch (IOException ignored) {
      } finally {
        os.close();
      }
    }
  }

  private static final class MobileStreamHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      writeTextEventStreamHeaders(exchange);
      OutputStream os = exchange.getResponseBody();
      String hello =
          "{"
              + "\"ts\":\"" + Instant.now().toString() + "\","
              + "\"event_type\":\"mobile_stream_ready\","
              + "\"source\":\"java_backend\""
              + "}";
      os.write(("data: " + hello + "\n\n").getBytes(StandardCharsets.UTF_8));
      os.flush();
      long offset = Files.exists(LOG_PATH) ? Files.size(LOG_PATH) : 0L;
      try {
        while (true) {
          if (!Files.exists(LOG_PATH)) {
            sleepQuietly(STREAM_POLL_MILLIS);
            continue;
          }
          long size = Files.size(LOG_PATH);
          if (size < offset) {
            offset = 0L;
          }
          if (size == offset) {
            sleepQuietly(STREAM_POLL_MILLIS);
            continue;
          }
          String chunk = Files.readString(LOG_PATH, StandardCharsets.UTF_8);
          if (offset > chunk.length()) {
            offset = 0L;
          }
          String unread = chunk.substring((int) offset);
          offset = chunk.length();
          for (String line : unread.split("\\R")) {
            if (!line.startsWith(EVENT_PREFIX)) {
              continue;
            }
            String payload = line.substring(EVENT_PREFIX.length()).trim();
            String eventType = extractStringField(payload, EVENT_TYPE_PATTERN);
            String kind = extractStringField(payload, KIND_PATTERN);
            String compact = compactMobileEventJson(new EventInfo(payload, eventType, kind));
            os.write(("data: " + compact + "\n\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
          }
        }
      } catch (IOException ignored) {
      } finally {
        os.close();
      }
    }
  }

  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }

  private static Map<String, String> parseQuery(String rawQuery) {
    Map<String, String> out = new HashMap<>();
    if (rawQuery == null || rawQuery.isBlank()) {
      return out;
    }
    String[] parts = rawQuery.split("&");
    for (String part : parts) {
      int eq = part.indexOf('=');
      if (eq < 0) {
        continue;
      }
      String key = decodeComponent(part.substring(0, eq));
      String val = decodeComponent(part.substring(eq + 1));
      out.put(key, val);
    }
    return out;
  }

  private static String decodeComponent(String s) {
    return URLDecoder.decode(s, StandardCharsets.UTF_8);
  }

  private static String urlEncode(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  private static double parseDouble(String value, double fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }
}