import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ScannerBackendServer {
  private static final String DEFAULT_HOST = "0.0.0.0";
  private static final int DEFAULT_PORT = 8080;
  private static final int RECENT_EVENT_LIMIT = 120;
  private static final int SNAPSHOT_EVENT_RETURN_LIMIT = 30;
  private static final int MOBILE_EVENT_RETURN_LIMIT = 12;
  private static final long STREAM_POLL_MILLIS = 350L;
  private static final long CLIENT_ROUTE_TTL_MS =
      parseLongOrDefault(System.getenv("CLIENT_ROUTE_TTL_MS"), 300000L);
  private static final int CLIENT_MAILBOX_MAX_MESSAGES =
      parseIntOrDefault(System.getenv("CLIENT_MAILBOX_MAX_MESSAGES"), 120);
  private static final int CLIENT_PULL_DEFAULT_LIMIT =
      parseIntOrDefault(System.getenv("CLIENT_PULL_DEFAULT_LIMIT"), 20);
  private static final int CLIENT_PULL_MAX_LIMIT =
      parseIntOrDefault(System.getenv("CLIENT_PULL_MAX_LIMIT"), 80);
  private static final String EVENT_PREFIX = "[EVENT_JSON] ";
  private static final Pattern EVENT_TYPE_PATTERN = Pattern.compile("\"event_type\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern KIND_PATTERN = Pattern.compile("\"kind\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern TS_PATTERN = Pattern.compile("\"ts\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern TRANSCRIPT_PATTERN = Pattern.compile("\"transcript\"\\s*:\\s*\"([^\"]*)\"");
  private static final Pattern ALERT_PATTERN = Pattern.compile("\"alert\"\\s*:\\s*\"([^\"]*)\"");
  private static final Pattern OSRM_DISTANCE_PATTERN =
      Pattern.compile("\"distance\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");
  private static final Pattern OSRM_DURATION_PATTERN =
      Pattern.compile("\"duration\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");
  private static final Pattern ALERT_COORD_PATTERN =
      Pattern.compile("\\b(-?\\d{1,2}\\.\\d+)\\s*[, ]\\s*(-?\\d{1,3}\\.\\d+)\\b");
  private static final String WEATHER_PROVIDER = System.getenv().getOrDefault("WEATHER_PROVIDER", "mock");
  private static final String WAZE_DEEPLINK_BASE_URL =
      System.getenv().getOrDefault("WAZE_DEEPLINK_BASE_URL", "https://waze.com/ul");
  private static final String WAZE_EMBED_BASE_URL =
      System.getenv().getOrDefault("WAZE_EMBED_BASE_URL", "https://embed.waze.com/iframe");
  private static final String WAZE_HAZARDS_API_URL =
      System.getenv().getOrDefault("WAZE_HAZARDS_API_URL", "");
  private static final String WAZE_HAZARDS_API_AUTH_HEADER =
      System.getenv().getOrDefault("WAZE_HAZARDS_API_AUTH_HEADER", "Authorization");
  private static final String WAZE_HAZARDS_API_AUTH_PREFIX =
      System.getenv().getOrDefault("WAZE_HAZARDS_API_AUTH_PREFIX", "Bearer ");
  private static final String WAZE_HAZARDS_API_KEY =
      System.getenv().getOrDefault("WAZE_HAZARDS_API_KEY", "");
  private static final String NOMINATIM_SEARCH_URL =
      System.getenv().getOrDefault("NOMINATIM_SEARCH_URL", "https://nominatim.openstreetmap.org/search");
  private static final double GEOCODE_BIAS_RADIUS_DEGREES =
      parseDouble(System.getenv("GEOCODE_BIAS_RADIUS_DEGREES"), 0.35);
  private static final String OSRM_ROUTE_BASE_URL =
      System.getenv().getOrDefault("OSRM_ROUTE_BASE_URL", "https://router.project-osrm.org/route/v1/driving");
  private static final String EXTERNAL_HTTP_USER_AGENT =
      System.getenv().getOrDefault("EXTERNAL_HTTP_USER_AGENT", "scanner-stream-backend/0.1 (self-hosted)");
  private static final int EXTERNAL_HTTP_TIMEOUT_MS =
      parseIntOrDefault(System.getenv("EXTERNAL_HTTP_TIMEOUT_MS"), 6000);
  private static final long OSRM_CACHE_TTL_MS =
      parseLongOrDefault(System.getenv("OSRM_CACHE_TTL_MS"), 15000L);
  private static final int OSRM_CACHE_MAX_ENTRIES =
      parseIntOrDefault(System.getenv("OSRM_CACHE_MAX_ENTRIES"), 512);
  private static final long LLM_STATUS_CACHE_TTL_MS =
      parseLongOrDefault(System.getenv("LLM_STATUS_CACHE_TTL_MS"), 5000L);
  private static final String SELECTOR_PYTHON_BIN =
      System.getenv().getOrDefault("SELECTOR_PYTHON_BIN", "/home/gibi/Desktop/cop_pipeline/bin/python3");
  private static final String SELECTOR_SCRIPT_PATH =
      System.getenv().getOrDefault("SELECTOR_SCRIPT_PATH", "/home/gibi/Desktop/channel_selector.py");
  private static final String BROADCASTIFY_CATALOG_SCRIPT_PATH =
      System.getenv().getOrDefault("BROADCASTIFY_CATALOG_SCRIPT_PATH", "/home/gibi/Desktop/broadcastify_catalog_service.py");
  private static final String BROADCASTIFY_CHANNELS_FILE =
      System.getenv().getOrDefault("BROADCASTIFY_CHANNELS_FILE", "/home/gibi/Desktop/config/broadcastify_channels.sample.json");
  private static final String BROADCASTIFY_SELECTOR_CITY =
      System.getenv().getOrDefault("BROADCASTIFY_SELECTOR_CITY", "Sample City");
  private static final String BROADCASTIFY_SELECTOR_COUNTY =
      System.getenv().getOrDefault("BROADCASTIFY_SELECTOR_COUNTY", "Sample County");
  private static final String BROADCASTIFY_SELECTOR_STATE =
      System.getenv().getOrDefault("BROADCASTIFY_SELECTOR_STATE", "Sample State");
  private static final boolean BROADCASTIFY_SELECTOR_LOCK_STATE =
      "true".equalsIgnoreCase(
          System.getenv().getOrDefault("BROADCASTIFY_SELECTOR_LOCK_STATE", "false"));
  private static final String BROADCASTIFY_SELECTOR_DESIRED_TYPES =
      System.getenv().getOrDefault("BROADCASTIFY_SELECTOR_DESIRED_TYPES", "law,dispatch");
  private static final int BROADCASTIFY_SELECTOR_TOP_K =
      parseIntOrDefault(System.getenv("BROADCASTIFY_SELECTOR_TOP_K"), 8);
  private static final int BROADCASTIFY_SELECTOR_PRINT_TOP =
      parseIntOrDefault(System.getenv("BROADCASTIFY_SELECTOR_PRINT_TOP"), 3);
  private static final String BROADCASTIFY_SELECTOR_USE_OLLAMA_RERANK =
      System.getenv().getOrDefault("BROADCASTIFY_SELECTOR_USE_OLLAMA_RERANK", "false");
  private static final String BROADCASTIFY_SELECTOR_OLLAMA_MODEL =
      System.getenv().getOrDefault("BROADCASTIFY_SELECTOR_OLLAMA_MODEL", "llama3.1");
  private static final String BROADCASTIFY_SELECTOR_OLLAMA_URL =
      System.getenv().getOrDefault("BROADCASTIFY_SELECTOR_OLLAMA_URL", "http://localhost:11434/api/generate");
  private static final String BROADCASTIFY_SELECTOR_OLLAMA_TIMEOUT =
      System.getenv().getOrDefault("BROADCASTIFY_SELECTOR_OLLAMA_TIMEOUT", "8.0");
  private static final String BROADCASTIFY_SELECTOR_OLLAMA_WEIGHT =
      System.getenv().getOrDefault("BROADCASTIFY_SELECTOR_OLLAMA_WEIGHT", "0.2");
  private static final int HELPER_PROCESS_TIMEOUT_SECONDS =
      parseIntOrDefault(System.getenv("BACKEND_HELPER_TIMEOUT_SECONDS"), 90);
  private static final String OLLAMA_TAGS_URL =
      System.getenv().getOrDefault("OLLAMA_TAGS_URL", "http://localhost:11434/api/tags");
  private static final String LLM_BASE_MODEL =
      System.getenv().getOrDefault("LLM_BASE_MODEL", "llama3.1");
  private static final String[] SCOUT_MODELS = {"scout-alert", "scout-intel", "scout-rank"};
  private static final Pattern MODEL_NAME_PATTERN = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
  private static final int METRICS_MAX_ITEMS =
      parseIntOrDefault(System.getenv("BACKEND_METRICS_MAX_ITEMS"), 12);
  private static final int GPS_TRACK_MAX_POINTS =
      parseIntOrDefault(System.getenv("BACKEND_GPS_TRACK_MAX_POINTS"), 2000);
  private static final Path ADDRESS_CATALOG_STORE_PATH =
      Path.of(
          System.getenv()
              .getOrDefault(
                  "ADDRESS_CATALOG_STORE_PATH",
                  "/home/gibi/Desktop/config/address_catalog_store.tsv"));
  private static final Path LOG_PATH =
      Path.of(System.getenv().getOrDefault("PIPELINE_LOG_PATH", "/tmp/pipeline_live_doordash.log"));
  private static final String HOST = System.getenv().getOrDefault("JAVA_BACKEND_HOST", DEFAULT_HOST);
  private static final int PORT = parsePort(System.getenv("JAVA_BACKEND_PORT"), DEFAULT_PORT);
  private static final Map<String, TimingStats> REQUEST_STATS = new ConcurrentHashMap<>();
  private static final Map<String, TimingStats> HELPER_STATS = new ConcurrentHashMap<>();
  private static final Map<String, AddressCatalogEntry> ADDRESS_CATALOG = new ConcurrentHashMap<>();
  private static final Map<String, TimedStringValue> OSRM_ROUTE_CACHE = new ConcurrentHashMap<>();
  private static final Map<String, TimedStringValue> OSRM_ALT_CACHE = new ConcurrentHashMap<>();
  private static volatile TimedStringValue llmStatusCache = null;
  private static final Object ADDRESS_CATALOG_IO_LOCK = new Object();
  private static final Object GPS_LOCK = new Object();
  private static final Object CLIENT_ROUTE_LOCK = new Object();
  private static final Deque<GpsPoint> GPS_TRACK = new ArrayDeque<>();
  private static final Map<String, GpsPoint> GPS_BY_USER = new ConcurrentHashMap<>();
  private static final Map<String, ClientRoute> CLIENT_ROUTES = new ConcurrentHashMap<>();
  private static final Map<String, Deque<ClientMessage>> CLIENT_MAILBOX = new ConcurrentHashMap<>();
  private static volatile GpsPoint latestGpsPoint = null;

  public static void main(String[] args) throws IOException {
    loadAddressCatalogFromDisk();
    HttpServer server = HttpServer.create(new InetSocketAddress(HOST, PORT), 0);
    registerContext(server, "/api/health", new HealthHandler());
    registerContext(server, "/api/pipeline/snapshot", new SnapshotHandler());
    registerContext(server, "/api/pipeline/stream", new StreamHandler());
    registerContext(server, "/api/route/weather", new WeatherHandler());
    registerContext(server, "/api/platform/weather/forecast", new PlatformWeatherHandler());
    registerContext(server, "/api/platform/waze/route", new WazeRouteHandler());
    registerContext(server, "/api/platform/route/local", new LocalRouteHandler());
    registerContext(server, "/api/platform/route/options", new RouteOptionsHandler());
    registerContext(server, "/api/platform/geocode", new GeocodeHandler());
    registerContext(server, "/api/platform/address-catalog/resolve", new AddressCatalogResolveHandler());
    registerContext(server, "/api/platform/address-catalog/upsert", new AddressCatalogUpsertHandler());
    registerContext(server, "/api/platform/address-catalog/export", new AddressCatalogExportHandler());
    registerContext(server, "/api/platform/alerts/clusters", new AlertClustersHandler());
    registerContext(server, "/api/gps/update", new GpsUpdateHandler());
    registerContext(server, "/api/gps/latest", new GpsLatestHandler());
    registerContext(server, "/api/gps/track", new GpsTrackHandler());
    registerContext(server, "/api/gps/triangulation", new GpsTriangulationHandler());
    registerContext(server, "/api/platform/broadcastify/select", new BroadcastifySelectHandler());
    registerContext(server, "/api/platform/broadcastify/catalog", new BroadcastifyCatalogHandler());
    registerContext(server, "/api/platform/providers/status", new ProviderStatusHandler());
    registerContext(server, "/api/platform/llm/status", new LlmStatusHandler());
    registerContext(server, "/api/mobile/bootstrap", new MobileBootstrapHandler());
    registerContext(server, "/api/mobile/snapshot", new MobileSnapshotHandler());
    registerContext(server, "/api/mobile/stream", new MobileStreamHandler());
    registerContext(server, "/api/mobile/client/register", new MobileClientRegisterHandler());
    registerContext(server, "/api/mobile/client/send", new MobileClientSendHandler());
    registerContext(server, "/api/mobile/client/pull", new MobileClientPullHandler());
    registerContext(server, "/api/mobile/clients", new MobileClientsHandler());
    registerContext(server, "/api/map/scene", new MapSceneHandler());
    registerContext(server, "/api/map/render", new MapRenderHandler());
    registerContext(server, "/api/map/status", new MapStatusHandler());
    registerContext(server, "/api/map/shard", new MapShardHandler());
    ProprietaryMapEngine.init();
    server.setExecutor(Executors.newCachedThreadPool());
    System.out.printf(
        Locale.ROOT,
        "[java-backend] serving on http://%s:%d%n[java-backend] pipeline log source: %s%n",
        HOST,
        PORT,
        LOG_PATH);
    server.start();
  }
  private static final class ClientRoute {
    private final String clientId;
    private final String userId;
    private final String source;
    private final String sessionId;
    private final String remoteAddr;
    private final long lastSeenMs;

    private ClientRoute(
        String clientId,
        String userId,
        String source,
        String sessionId,
        String remoteAddr,
        long lastSeenMs) {
      this.clientId = clientId;
      this.userId = userId;
      this.source = source;
      this.sessionId = sessionId;
      this.remoteAddr = remoteAddr;
      this.lastSeenMs = lastSeenMs;
    }
  }

  private static final class ClientMessage {
    private final String payloadJson;
    private final long enqueuedAtMs;

    private ClientMessage(String payloadJson, long enqueuedAtMs) {
      this.payloadJson = payloadJson;
      this.enqueuedAtMs = enqueuedAtMs;
    }
  }

  private static final class TimedStringValue {
    private final String value;
    private final long expiresAtMs;

    private TimedStringValue(String value, long expiresAtMs) {
      this.value = value;
      this.expiresAtMs = expiresAtMs;
    }
  }
  private static final class RouteNode {
    private final double lat;
    private final double lon;

    private RouteNode(double lat, double lon) {
      this.lat = lat;
      this.lon = lon;
    }
  }
  private static final class AddressCatalogEntry {
    private final String queryKey;
    private final String displayName;
    private final String source;
    private final double lat;
    private final double lon;
    private final long updatedAtMs;

    private AddressCatalogEntry(
        String queryKey, String displayName, String source, double lat, double lon, long updatedAtMs) {
      this.queryKey = queryKey;
      this.displayName = displayName;
      this.source = source;
      this.lat = lat;
      this.lon = lon;
      this.updatedAtMs = updatedAtMs;
    }
  }

  private static final class RouteAlternative {
    private final List<RouteNode> nodes;
    private final double distanceMeters;
    private final double durationSeconds;
    private final boolean hasTollHint;
    private final boolean hasFerryHint;

    private RouteAlternative(
        List<RouteNode> nodes, double distanceMeters, double durationSeconds, boolean hasTollHint, boolean hasFerryHint) {
      this.nodes = nodes;
      this.distanceMeters = distanceMeters;
      this.durationSeconds = durationSeconds;
      this.hasTollHint = hasTollHint;
      this.hasFerryHint = hasFerryHint;
    }
  }
  private static final class GpsPoint {
    private final String ts;
    private final String userId;
    private final String source;
    private final long seq;
    private final double lat;
    private final double lon;
    private final double accuracy;
    private final double speed;
    private final double heading;
    private final long receivedAtMs;

    private GpsPoint(
        String ts,
        String userId,
        String source,
        long seq,
        double lat,
        double lon,
        double accuracy,
        double speed,
        double heading,
        long receivedAtMs) {
      this.ts = ts;
      this.userId = userId;
      this.source = source;
      this.seq = seq;
      this.lat = lat;
      this.lon = lon;
      this.accuracy = accuracy;
      this.speed = speed;
      this.heading = heading;
      this.receivedAtMs = receivedAtMs;
    }
  }

  private static final class TimingStats {
    private final AtomicLong count = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    private final AtomicLong totalMs = new AtomicLong();
    private final AtomicLong maxMs = new AtomicLong();

    private void record(long durationMs, boolean success) {
      count.incrementAndGet();
      if (!success) {
        errorCount.incrementAndGet();
      }
      totalMs.addAndGet(durationMs);
      maxMs.getAndUpdate(prev -> Math.max(prev, durationMs));
    }
  }

  private static String timingStatsToJson(Map<String, TimingStats> statsMap) {
    StringBuilder sb = new StringBuilder("{");
    List<Map.Entry<String, TimingStats>> entries = new ArrayList<>(statsMap.entrySet());
    entries.sort(Comparator.comparing(Map.Entry::getKey));
    int emitted = 0;
    for (Map.Entry<String, TimingStats> entry : entries) {
      if (emitted >= METRICS_MAX_ITEMS) {
        break;
      }
      if (emitted > 0) {
        sb.append(",");
      }
      TimingStats stats = entry.getValue();
      long count = stats.count.get();
      long total = stats.totalMs.get();
      long avg = count == 0 ? 0 : Math.round((double) total / (double) count);
      sb.append("\"").append(jsonEscape(entry.getKey())).append("\":{")
          .append("\"count\":").append(count).append(",")
          .append("\"errors\":").append(stats.errorCount.get()).append(",")
          .append("\"avg_ms\":").append(avg).append(",")
          .append("\"max_ms\":").append(stats.maxMs.get())
          .append("}");
      emitted += 1;
    }
    sb.append("}");
    return sb.toString();
  }
  private static void registerContext(HttpServer server, String path, HttpHandler handler) {
    server.createContext(path, exchange -> {
      long started = System.nanoTime();
      boolean success = false;
      try {
        handler.handle(exchange);
        success = true;
      } catch (Exception ex) {
        success = false;
        try {
          writeJson(exchange, 500, "{\"error\":\"internal_error\"}");
        } catch (IOException ignored) {
        }
      } finally {
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        REQUEST_STATS.computeIfAbsent(path, k -> new TimingStats()).record(durationMs, success);
      }
    });
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
  private static int parseIntOrDefault(String value, int fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(value);
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
        for (String raw : extractEventPayloads(line)) {
          String eventType = extractStringField(raw, EVENT_TYPE_PATTERN);
          String kind = extractStringField(raw, KIND_PATTERN);
          out.addLast(new EventInfo(raw, eventType, kind));
          while (out.size() > maxEvents) {
            out.removeFirst();
          }
        }
      }
    } catch (IOException ignored) {
      return List.of();
    }
    return new ArrayList<>(out);
  }

  private static List<String> extractEventPayloads(String line) {
    List<String> payloads = new ArrayList<>();
    if (line == null || line.isEmpty()) {
      return payloads;
    }
    int searchFrom = 0;
    while (true) {
      int marker = line.indexOf(EVENT_PREFIX, searchFrom);
      if (marker < 0) {
        break;
      }
      int payloadStart = marker + EVENT_PREFIX.length();
      int nextMarker = line.indexOf(EVENT_PREFIX, payloadStart);
      String payload = (nextMarker >= 0 ? line.substring(payloadStart, nextMarker) : line.substring(payloadStart)).trim();
      if (!payload.isEmpty()) {
        payloads.add(payload);
      }
      if (nextMarker < 0) {
        break;
      }
      searchFrom = nextMarker;
    }
    return payloads;
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
        + "\"waze\":\"/api/platform/waze/route\","
        + "\"geocode\":\"/api/platform/geocode\","
        + "\"address_catalog_resolve\":\"/api/platform/address-catalog/resolve\","
        + "\"address_catalog_upsert\":\"/api/platform/address-catalog/upsert\","
        + "\"local_route\":\"/api/platform/route/local\","
        + "\"route_options\":\"/api/platform/route/options\","
        + "\"alert_clusters\":\"/api/platform/alerts/clusters\","
        + "\"client_register\":\"/api/mobile/client/register\","
        + "\"client_send\":\"/api/mobile/client/send\","
        + "\"client_pull\":\"/api/mobile/client/pull\","
        + "\"clients\":\"/api/mobile/clients\","
        + "\"map_scene\":\"/api/map/scene\","
        + "\"map_render\":\"/api/map/render\","
        + "\"map_status\":\"/api/map/status\","
        + "\"map_shard\":\"/api/map/shard\""
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
  private static String buildStandaloneLocalRouteJson(Map<String, String> query) {
    GpsPoint latest = latestGpsPoint;
    double originLat = parseDouble(query.getOrDefault("origin_lat", ""), Double.NaN);
    double originLon = parseDouble(query.getOrDefault("origin_lon", ""), Double.NaN);
    double destLat = parseDouble(query.getOrDefault("dest_lat", ""), Double.NaN);
    double destLon = parseDouble(query.getOrDefault("dest_lon", ""), Double.NaN);

    if (!Double.isFinite(originLat) || !Double.isFinite(originLon)) {
      if (latest != null) {
        originLat = latest.lat;
        originLon = latest.lon;
      }
    }
    if (!Double.isFinite(destLat) || !Double.isFinite(destLon)) {
      if (latest != null) {
        destLat = latest.lat;
        destLon = latest.lon;
      }
    }
    if (!Double.isFinite(originLat)
        || !Double.isFinite(originLon)
        || !Double.isFinite(destLat)
        || !Double.isFinite(destLon)) {
      return "{\"error\":\"invalid_route_coordinates\"}";
    }
    if (originLat < -90
        || originLat > 90
        || destLat < -90
        || destLat > 90
        || originLon < -180
        || originLon > 180
        || destLon < -180
        || destLon > 180) {
      return "{\"error\":\"out_of_range_route_coordinates\"}";
    }

    String condition = query.getOrDefault("condition", "idle");
    String engine = "direct_line_fallback";
    List<RouteNode> routeNodes = null;
    Double externalMeters = null;
    String osrmBody = fetchOsrmRouteBody(originLat, originLon, destLat, destLon);
    if (osrmBody != null) {
      List<RouteNode> osrmNodes = parseOsrmCoordinates(osrmBody);
      if (osrmNodes != null) {
        routeNodes = osrmNodes;
        externalMeters = parseOsrmDistanceMeters(osrmBody);
        engine = "osrm_openstreetmap";
      }
    }
    if (routeNodes == null) {
      // OSRM unreachable: return the straight origin->destination segment so the
      // client still has real endpoint geometry to render.
      routeNodes =
          List.of(new RouteNode(originLat, originLon), new RouteNode(destLat, destLon));
    }
    double meters = externalMeters != null ? externalMeters : approximateRouteMeters(routeNodes);
    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"status\":\"ok\","
        + "\"engine\":\"" + jsonEscape(engine) + "\","
        + "\"condition\":\"" + jsonEscape(condition) + "\","
        + "\"origin\":{\"lat\":" + trimDouble(originLat) + ",\"lon\":" + trimDouble(originLon) + "},"
        + "\"destination\":{\"lat\":" + trimDouble(destLat) + ",\"lon\":" + trimDouble(destLon) + "},"
        + "\"distance_m\":" + trimDouble(meters) + ","
        + "\"route_points\":" + routeNodesToJson(routeNodes)
        + "}";
  }

  private static double approximateRouteMeters(List<RouteNode> nodes) {
    if (nodes.size() < 2) {
      return 0.0;
    }
    double total = 0.0;
    for (int i = 1; i < nodes.size(); i++) {
      RouteNode a = nodes.get(i - 1);
      RouteNode b = nodes.get(i);
      total += haversineMeters(a.lat, a.lon, b.lat, b.lon);
    }
    return total;
  }

  private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
    double r = 6371000.0;
    double dLat = Math.toRadians(lat2 - lat1);
    double dLon = Math.toRadians(lon2 - lon1);
    double a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2)
                * Math.sin(dLon / 2);
    double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return r * c;
  }

  private static String routeNodesToJson(List<RouteNode> nodes) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < nodes.size(); i++) {
      if (i > 0) {
        sb.append(",");
      }
      RouteNode node = nodes.get(i);
      sb.append("{\"lat\":")
          .append(trimDouble(node.lat))
          .append(",\"lon\":")
          .append(trimDouble(node.lon))
          .append("}");
    }
    sb.append("]");
    return sb.toString();
  }

  private static String normalizeCatalogQuery(String raw) {
    if (raw == null) {
      return "";
    }
    String lower = raw.toLowerCase(Locale.ROOT);
    StringBuilder sb = new StringBuilder(lower.length());
    boolean previousWhitespace = false;
    for (int i = 0; i < lower.length(); i++) {
      char ch = lower.charAt(i);
      if (Character.isWhitespace(ch)) {
        if (!previousWhitespace) {
          sb.append(' ');
          previousWhitespace = true;
        }
      } else {
        sb.append(ch);
        previousWhitespace = false;
      }
    }
    int start = 0;
    int end = sb.length();
    while (start < end && sb.charAt(start) == ' ') {
      start++;
    }
    while (end > start && sb.charAt(end - 1) == ' ') {
      end--;
    }
    return start == 0 && end == sb.length() ? sb.toString() : sb.substring(start, end);
  }

  private static String addressCatalogEntryToJson(AddressCatalogEntry entry) {
    return "{"
        + "\"query\":\"" + jsonEscape(entry.queryKey) + "\","
        + "\"display_name\":\"" + jsonEscape(entry.displayName) + "\","
        + "\"source\":\"" + jsonEscape(entry.source) + "\","
        + "\"lat\":" + trimDouble(entry.lat) + ","
        + "\"lon\":" + trimDouble(entry.lon) + ","
        + "\"updated_at_ms\":" + entry.updatedAtMs
        + "}";
  }
  private static String addressCatalogEntriesToJson(List<AddressCatalogEntry> entries) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < entries.size(); i++) {
      if (i > 0) {
        sb.append(",");
      }
      sb.append(addressCatalogEntryToJson(entries.get(i)));
    }
    sb.append("]");
    return sb.toString();
  }

  private static int catalogTokenOverlapScore(String queryKey, String entryKey) {
    if (queryKey.isEmpty() || entryKey.isEmpty()) {
      return 0;
    }
    Set<String> queryTokens = new HashSet<>(Arrays.asList(queryKey.split(" ")));
    Set<String> entryTokens = new HashSet<>(Arrays.asList(entryKey.split(" ")));
    queryTokens.remove("");
    entryTokens.remove("");
    if (queryTokens.isEmpty() || entryTokens.isEmpty()) {
      return 0;
    }
    int overlap = 0;
    for (String token : queryTokens) {
      if (entryTokens.contains(token)) {
        overlap++;
      }
    }
    if (overlap == 0) {
      return 0;
    }
    return overlap * 40;
  }

  private static double catalogBiasScore(
      AddressCatalogEntry entry, double biasLat, double biasLon, boolean hasBias) {
    if (!hasBias) {
      return 0.0;
    }
    double meters = haversineMeters(entry.lat, entry.lon, biasLat, biasLon);
    // Nearby results get a meaningful boost; distant ones decay smoothly.
    return Math.max(0.0, 1200.0 / (1.0 + (meters / 1000.0)));
  }

  private static double catalogMatchScore(
      String queryKey, AddressCatalogEntry entry, double biasLat, double biasLon, boolean hasBias) {
    if (entry == null) {
      return 0.0;
    }
    String entryKey = entry.queryKey;
    if (queryKey.equals(entryKey)) {
      return 2000.0 + catalogBiasScore(entry, biasLat, biasLon, hasBias);
    }
    double score = 0.0;
    if (entryKey.startsWith(queryKey) || queryKey.startsWith(entryKey)) {
      score += 240.0;
    }
    if (entryKey.contains(queryKey) || queryKey.contains(entryKey)) {
      score += 140.0;
    }
    score += catalogTokenOverlapScore(queryKey, entryKey);
    score += catalogBiasScore(entry, biasLat, biasLon, hasBias);
    return score;
  }

  private static String buildAddressCatalogResolveJson(Map<String, String> query) {
    String q = query.getOrDefault("q", "").trim();
    String key = normalizeCatalogQuery(q);
    if (key.isEmpty()) {
      return "{\"error\":\"missing_query\"}";
    }
    double biasLat = parseDouble(query.get("lat"), Double.NaN);
    double biasLon = parseDouble(query.get("lon"), Double.NaN);
    boolean hasBias =
        Double.isFinite(biasLat)
            && Double.isFinite(biasLon)
            && Math.abs(biasLat) <= 90.0
            && Math.abs(biasLon) <= 180.0;
    List<Map.Entry<AddressCatalogEntry, Double>> scored = new ArrayList<>();
    for (AddressCatalogEntry entry : ADDRESS_CATALOG.values()) {
      double score = catalogMatchScore(key, entry, biasLat, biasLon, hasBias);
      if (score > 0.0) {
        scored.add(Map.entry(entry, score));
      }
    }
    if (scored.isEmpty()) {
      return "{"
          + "\"ts\":\"" + Instant.now().toString() + "\","
          + "\"status\":\"miss\","
          + "\"bias_applied\":" + hasBias + ","
          + "\"results\":[]"
          + "}";
    }
    scored.sort(
        (a, b) -> {
          int byScore = Double.compare(b.getValue(), a.getValue());
          if (byScore != 0) {
            return byScore;
          }
          return Long.compare(b.getKey().updatedAtMs, a.getKey().updatedAtMs);
        });
    List<AddressCatalogEntry> results = new ArrayList<>();
    for (int i = 0; i < scored.size() && i < 5; i++) {
      results.add(scored.get(i).getKey());
    }
    AddressCatalogEntry best = results.get(0);
    String matchType = key.equals(best.queryKey) ? "exact" : "fuzzy";
    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"status\":\"ok\","
        + "\"match\":\"" + matchType + "\","
        + "\"bias_applied\":" + hasBias + ","
        + "\"entry\":" + addressCatalogEntryToJson(best) + ","
        + "\"results\":" + addressCatalogEntriesToJson(results)
        + "}";
  }

  private static String buildAddressCatalogExportJson() {
    List<AddressCatalogEntry> entries = new ArrayList<>(ADDRESS_CATALOG.values());
    entries.sort((a, b) -> Long.compare(b.updatedAtMs, a.updatedAtMs));
    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"status\":\"ok\","
        + "\"catalog_size\":" + entries.size() + ","
        + "\"entries\":" + addressCatalogEntriesToJson(entries)
        + "}";
  }

  private static String buildAddressCatalogUpsertJson(Map<String, String> query, String body) {
    String queryRaw = extractStringFieldByName(body, "query", query.getOrDefault("query", ""));
    String display =
        extractStringFieldByName(
            body, "display_name", query.getOrDefault("display_name", queryRaw));
    String source =
        extractStringFieldByName(body, "source", query.getOrDefault("source", "unknown_client"));
    double lat = parseDouble(query.getOrDefault("lat", ""), Double.NaN);
    double lon = parseDouble(query.getOrDefault("lon", ""), Double.NaN);
    if (!Double.isFinite(lat)) {
      lat = extractDoubleField(body, "lat", Double.NaN);
    }
    if (!Double.isFinite(lon)) {
      lon = extractDoubleField(body, "lon", Double.NaN);
    }
    String key = normalizeCatalogQuery(queryRaw);
    if (key.isEmpty() || !Double.isFinite(lat) || !Double.isFinite(lon)) {
      return "{\"error\":\"invalid_catalog_entry\"}";
    }
    if (display == null || display.isBlank()) {
      display = queryRaw;
    }
    AddressCatalogEntry entry =
        new AddressCatalogEntry(key, display.trim(), source.trim(), lat, lon, System.currentTimeMillis());
    ADDRESS_CATALOG.put(key, entry);
    persistAddressCatalogToDisk();
    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"status\":\"ok\","
        + "\"catalog_size\":" + ADDRESS_CATALOG.size() + ","
        + "\"entry\":" + addressCatalogEntryToJson(entry)
        + "}";
  }

  private static String encodeCatalogField(String value) {
    return urlEncode(value == null ? "" : value);
  }

  private static String decodeCatalogField(String value) {
    if (value == null) {
      return "";
    }
    try {
      return decodeComponent(value);
    } catch (Exception ex) {
      return "";
    }
  }

  private static void loadAddressCatalogFromDisk() {
    synchronized (ADDRESS_CATALOG_IO_LOCK) {
      try {
        if (!Files.exists(ADDRESS_CATALOG_STORE_PATH)) {
          return;
        }
        List<String> lines = Files.readAllLines(ADDRESS_CATALOG_STORE_PATH, StandardCharsets.UTF_8);
        int loaded = 0;
        for (String line : lines) {
          if (line == null || line.isBlank() || line.startsWith("#")) {
            continue;
          }
          String[] cols = line.split("\t", -1);
          if (cols.length < 6) {
            continue;
          }
          String queryKey = normalizeCatalogQuery(decodeCatalogField(cols[0]));
          String displayName = decodeCatalogField(cols[1]).trim();
          String source = decodeCatalogField(cols[2]).trim();
          double lat = parseDouble(cols[3], Double.NaN);
          double lon = parseDouble(cols[4], Double.NaN);
          long updatedAtMs = parseLongOrDefault(cols[5], 0L);
          if (queryKey.isEmpty() || !Double.isFinite(lat) || !Double.isFinite(lon)) {
            continue;
          }
          if (displayName.isBlank()) {
            displayName = queryKey;
          }
          if (source.isBlank()) {
            source = "persisted";
          }
          ADDRESS_CATALOG.put(
              queryKey, new AddressCatalogEntry(queryKey, displayName, source, lat, lon, updatedAtMs));
          loaded++;
        }
        System.out.printf(
            Locale.ROOT,
            "[java-backend] address catalog loaded: %d entries from %s%n",
            loaded,
            ADDRESS_CATALOG_STORE_PATH);
      } catch (Exception ex) {
        System.err.printf(
            Locale.ROOT,
            "[java-backend] address catalog load failed (%s): %s%n",
            ADDRESS_CATALOG_STORE_PATH,
            ex.getMessage());
      }
    }
  }

  private static void persistAddressCatalogToDisk() {
    synchronized (ADDRESS_CATALOG_IO_LOCK) {
      try {
        Path parent = ADDRESS_CATALOG_STORE_PATH.getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        List<AddressCatalogEntry> entries = new ArrayList<>(ADDRESS_CATALOG.values());
        entries.sort((a, b) -> Long.compare(b.updatedAtMs, a.updatedAtMs));
        StringBuilder sb = new StringBuilder();
        sb.append("# query\tdisplay_name\tsource\tlat\tlon\tupdated_at_ms\n");
        for (AddressCatalogEntry entry : entries) {
          sb.append(encodeCatalogField(entry.queryKey))
              .append('\t')
              .append(encodeCatalogField(entry.displayName))
              .append('\t')
              .append(encodeCatalogField(entry.source))
              .append('\t')
              .append(trimDouble(entry.lat))
              .append('\t')
              .append(trimDouble(entry.lon))
              .append('\t')
              .append(entry.updatedAtMs)
              .append('\n');
        }
        Files.writeString(ADDRESS_CATALOG_STORE_PATH, sb.toString(), StandardCharsets.UTF_8);
      } catch (Exception ex) {
        System.err.printf(
            Locale.ROOT,
            "[java-backend] address catalog persist failed (%s): %s%n",
            ADDRESS_CATALOG_STORE_PATH,
            ex.getMessage());
      }
    }
  }

  private static String fetchOsrmRouteAlternativesBody(
      double originLat, double originLon, double destLat, double destLon) {
    String cacheKey = osrmCacheKey(originLat, originLon, destLat, destLon);
    String cached = getCachedString(OSRM_ALT_CACHE, cacheKey);
    if (cached != null) {
      return cached;
    }
    String url =
        OSRM_ROUTE_BASE_URL
            + "/"
            + trimDouble(originLon)
            + ","
            + trimDouble(originLat)
            + ";"
            + trimDouble(destLon)
            + ","
            + trimDouble(destLat)
            + "?overview=full&geometries=geojson&alternatives=true&steps=false";
    String body = httpGetExternal(url);
    if (body == null || !body.contains("\"code\":\"Ok\"")) {
      return null;
    }
    putCachedString(OSRM_ALT_CACHE, cacheKey, body, OSRM_CACHE_TTL_MS, OSRM_CACHE_MAX_ENTRIES);
    putCachedString(OSRM_ROUTE_CACHE, cacheKey, body, OSRM_CACHE_TTL_MS, OSRM_CACHE_MAX_ENTRIES);
    return body;
  }

  private static String osrmCacheKey(
      double originLat, double originLon, double destLat, double destLon) {
    return String.format(
        Locale.ROOT,
        "%.5f,%.5f->%.5f,%.5f",
        originLat,
        originLon,
        destLat,
        destLon);
  }

  private static String getCachedString(Map<String, TimedStringValue> cache, String key) {
    TimedStringValue value = cache.get(key);
    if (value == null) {
      return null;
    }
    long now = System.currentTimeMillis();
    if (now > value.expiresAtMs) {
      cache.remove(key, value);
      return null;
    }
    return value.value;
  }

  private static String getCachedValue(TimedStringValue value) {
    if (value == null) {
      return null;
    }
    long now = System.currentTimeMillis();
    if (now > value.expiresAtMs) {
      return null;
    }
    return value.value;
  }

  private static void putCachedString(
      Map<String, TimedStringValue> cache,
      String key,
      String value,
      long ttlMs,
      int maxEntries) {
    long now = System.currentTimeMillis();
    cache.put(key, new TimedStringValue(value, now + Math.max(1000L, ttlMs)));
    if (cache.size() <= maxEntries) {
      return;
    }
    int removed = 0;
    for (Map.Entry<String, TimedStringValue> entry : cache.entrySet()) {
      if (entry.getValue().expiresAtMs <= now || cache.size() > maxEntries) {
        cache.remove(entry.getKey());
        removed++;
      }
      if (cache.size() <= maxEntries || removed >= 64) {
        break;
      }
    }
  }

  private static String extractJsonArrayContent(String rawJson, String fieldName) {
    String key = "\"" + fieldName + "\"";
    int keyIndex = rawJson.indexOf(key);
    if (keyIndex < 0) {
      return null;
    }
    int arrayStart = rawJson.indexOf('[', keyIndex + key.length());
    if (arrayStart < 0) {
      return null;
    }
    int depth = 0;
    boolean inString = false;
    for (int i = arrayStart; i < rawJson.length(); i++) {
      char ch = rawJson.charAt(i);
      if (ch == '"' && (i == 0 || rawJson.charAt(i - 1) != '\\')) {
        inString = !inString;
      }
      if (inString) {
        continue;
      }
      if (ch == '[') {
        depth++;
      } else if (ch == ']') {
        depth--;
        if (depth == 0) {
          return rawJson.substring(arrayStart + 1, i);
        }
      }
    }
    return null;
  }

  private static List<String> splitTopLevelObjects(String arrayBody) {
    List<String> out = new ArrayList<>();
    if (arrayBody == null || arrayBody.isBlank()) {
      return out;
    }
    int depth = 0;
    boolean inString = false;
    int objectStart = -1;
    for (int i = 0; i < arrayBody.length(); i++) {
      char ch = arrayBody.charAt(i);
      if (ch == '"' && (i == 0 || arrayBody.charAt(i - 1) != '\\')) {
        inString = !inString;
      }
      if (inString) {
        continue;
      }
      if (ch == '{') {
        if (depth == 0) {
          objectStart = i;
        }
        depth++;
      } else if (ch == '}') {
        depth--;
        if (depth == 0 && objectStart >= 0) {
          out.add(arrayBody.substring(objectStart, i + 1));
          objectStart = -1;
        }
      }
    }
    return out;
  }

  private static List<RouteAlternative> parseOsrmAlternatives(String osrmBody) {
    String routesArray = extractJsonArrayContent(osrmBody, "routes");
    List<RouteAlternative> alternatives = new ArrayList<>();
    for (String routeObject : splitTopLevelObjects(routesArray)) {
      List<RouteNode> nodes = parseOsrmCoordinates(routeObject);
      if (nodes == null || nodes.size() < 2) {
        continue;
      }
      Double dist = parseOsrmDistanceMeters(routeObject);
      Double duration = parseOsrmDurationSeconds(routeObject);
      alternatives.add(
          new RouteAlternative(
              nodes,
              dist != null ? dist : approximateRouteMeters(nodes),
              duration != null ? duration : 0.0,
              routeObject.contains("toll"),
              routeObject.contains("ferry")));
    }
    return alternatives;
  }

  private static Double parseOsrmDurationSeconds(String osrmBody) {
    Matcher matcher = OSRM_DURATION_PATTERN.matcher(osrmBody);
    if (matcher.find()) {
      try {
        return Double.parseDouble(matcher.group(1));
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private static String routeAlternativesToJson(List<RouteAlternative> alternatives) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < alternatives.size(); i++) {
      if (i > 0) {
        sb.append(",");
      }
      RouteAlternative alt = alternatives.get(i);
      sb.append("{")
          .append("\"index\":").append(i).append(",")
          .append("\"distance_m\":").append(trimDouble(alt.distanceMeters)).append(",")
          .append("\"duration_s\":").append(trimDouble(alt.durationSeconds)).append(",")
          .append("\"has_toll_hint\":").append(alt.hasTollHint).append(",")
          .append("\"has_ferry_hint\":").append(alt.hasFerryHint).append(",")
          .append("\"route_points\":").append(routeNodesToJson(alt.nodes))
          .append("}");
    }
    sb.append("]");
    return sb.toString();
  }

  private static String fetchWazeHazardsJson(double originLat, double originLon, double destLat, double destLon) {
    if (WAZE_HAZARDS_API_URL.isBlank()) {
      return "{\"status\":\"unconfigured\",\"provider\":\"waze\",\"hazards\":[]}";
    }
    double minLat = Math.min(originLat, destLat);
    double maxLat = Math.max(originLat, destLat);
    double minLon = Math.min(originLon, destLon);
    double maxLon = Math.max(originLon, destLon);
    String url =
        WAZE_HAZARDS_API_URL
            + "?bbox="
            + trimDouble(minLon)
            + ","
            + trimDouble(minLat)
            + ","
            + trimDouble(maxLon)
            + ","
            + trimDouble(maxLat);
    Map<String, String> extraHeaders = new HashMap<>();
    if (!WAZE_HAZARDS_API_KEY.isBlank()) {
      String authHeader = WAZE_HAZARDS_API_AUTH_HEADER.isBlank() ? "Authorization" : WAZE_HAZARDS_API_AUTH_HEADER;
      String authPrefix = WAZE_HAZARDS_API_AUTH_PREFIX == null ? "" : WAZE_HAZARDS_API_AUTH_PREFIX;
      extraHeaders.put(authHeader, authPrefix + WAZE_HAZARDS_API_KEY);
    }
    String raw = httpGetExternal(url, extraHeaders);
    if (raw == null || !looksLikeJson(raw)) {
      return "{\"status\":\"unavailable\",\"provider\":\"waze\",\"hazards\":[]}";
    }
    return "{"
        + "\"status\":\"ok\","
        + "\"provider\":\"waze\","
        + "\"raw\":" + raw
        + "}";
  }

  private static String buildRouteOptionsJson(Map<String, String> query) {
    GpsPoint latest = latestGpsPoint;
    double originLat = parseDouble(query.getOrDefault("origin_lat", ""), Double.NaN);
    double originLon = parseDouble(query.getOrDefault("origin_lon", ""), Double.NaN);
    double destLat = parseDouble(query.getOrDefault("dest_lat", ""), Double.NaN);
    double destLon = parseDouble(query.getOrDefault("dest_lon", ""), Double.NaN);
    if ((!Double.isFinite(originLat) || !Double.isFinite(originLon)) && latest != null) {
      originLat = latest.lat;
      originLon = latest.lon;
    }
    if ((!Double.isFinite(destLat) || !Double.isFinite(destLon)) && latest != null) {
      destLat = latest.lat;
      destLon = latest.lon;
    }
    if (!Double.isFinite(originLat)
        || !Double.isFinite(originLon)
        || !Double.isFinite(destLat)
        || !Double.isFinite(destLon)) {
      return "{\"error\":\"invalid_route_coordinates\"}";
    }
    List<RouteAlternative> alternatives = List.of();
    String osrmBody = fetchOsrmRouteAlternativesBody(originLat, originLon, destLat, destLon);
    if (osrmBody != null) {
      alternatives = parseOsrmAlternatives(osrmBody);
    }
    if (alternatives.isEmpty()) {
      List<RouteNode> fallback =
          List.of(new RouteNode(originLat, originLon), new RouteNode(destLat, destLon));
      alternatives =
          List.of(
              new RouteAlternative(
                  fallback, approximateRouteMeters(fallback), 0.0, false, false));
    }
    String hazards = fetchWazeHazardsJson(originLat, originLon, destLat, destLon);
    Map<String, String> clusterQuery = new HashMap<>();
    // Route options typically span larger geographies than local incident maps;
    // use a coarser default grid so multi-state previews stay readable.
    clusterQuery.put("grid_deg", "1.00");
    String alertClusters = buildAlertClustersJson(clusterQuery);
    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"status\":\"ok\","
        + "\"origin\":{\"lat\":" + trimDouble(originLat) + ",\"lon\":" + trimDouble(originLon) + "},"
        + "\"destination\":{\"lat\":" + trimDouble(destLat) + ",\"lon\":" + trimDouble(destLon) + "},"
        + "\"alternatives\":" + routeAlternativesToJson(alternatives) + ","
        + "\"waze_hazards\":" + hazards + ","
        + "\"alert_clusters\":" + alertClusters
        + "}";
  }

  private static String buildAlertClustersJson(Map<String, String> query) {
    double gridDeg = parseDouble(query.getOrDefault("grid_deg", ""), 0.60);
    if (!Double.isFinite(gridDeg) || gridDeg <= 0.01) {
      gridDeg = 0.60;
    }
    if (gridDeg > 5.0) {
      gridDeg = 5.0;
    }
    Map<String, Integer> buckets = new HashMap<>();
    for (EventInfo event : readEventLines(RECENT_EVENT_LIMIT * 3)) {
      if (!"alert_triggered".equals(event.eventType)) {
        continue;
      }
      Matcher matcher = ALERT_COORD_PATTERN.matcher(event.rawJson);
      if (!matcher.find()) {
        continue;
      }
      double lat;
      double lon;
      try {
        lat = Double.parseDouble(matcher.group(1));
        lon = Double.parseDouble(matcher.group(2));
      } catch (NumberFormatException ex) {
        continue;
      }
      int latBucket = (int) Math.floor(lat / gridDeg);
      int lonBucket = (int) Math.floor(lon / gridDeg);
      String key = latBucket + ":" + lonBucket;
      buckets.merge(key, 1, Integer::sum);
    }
    StringBuilder clusters = new StringBuilder("[");
    int idx = 0;
    for (Map.Entry<String, Integer> e : buckets.entrySet()) {
      String[] parts = e.getKey().split(":");
      if (parts.length != 2) {
        continue;
      }
      int latBucket = parseIntOrDefault(parts[0], 0);
      int lonBucket = parseIntOrDefault(parts[1], 0);
      double centerLat = (latBucket + 0.5) * gridDeg;
      double centerLon = (lonBucket + 0.5) * gridDeg;
      if (idx++ > 0) {
        clusters.append(",");
      }
      clusters.append("{")
          .append("\"lat\":").append(trimDouble(centerLat)).append(",")
          .append("\"lon\":").append(trimDouble(centerLon)).append(",")
          .append("\"count\":").append(e.getValue())
          .append("}");
    }
    clusters.append("]");
    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"status\":\"ok\","
        + "\"grid_deg\":" + trimDouble(gridDeg) + ","
        + "\"clusters\":" + clusters
        + "}";
  }

  private static String appendAlertClustersToSceneJson(String sceneJson, double radiusM) {
    if (sceneJson == null || sceneJson.isBlank()) {
      return sceneJson;
    }
    double gridDeg = Math.max(0.15, Math.min(2.5, radiusM / 220000.0));
    Map<String, String> query = new HashMap<>();
    query.put("grid_deg", trimDouble(gridDeg));
    String clustersPayload = buildAlertClustersJson(query);
    String clustersArray = extractJsonArrayContent(clustersPayload, "clusters");
    if (clustersArray == null) {
      clustersArray = "";
    }
    int insertAt = sceneJson.lastIndexOf('}');
    if (insertAt <= 0) {
      return sceneJson;
    }
    String prefix = sceneJson.substring(0, insertAt);
    String suffix = sceneJson.substring(insertAt);
    String separator = prefix.endsWith("{") ? "" : ",";
    return prefix + separator + "\"alert_clusters\":[" + clustersArray + "]" + suffix;
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
    boolean hazardsConfigured = !WAZE_HAZARDS_API_URL.isBlank();
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
        + "\"hazards_api_configured\":" + hazardsConfigured + ","
        + "\"ready\":true,"
        + "\"notes\":\"Waze route URLs are generated server-side for frontend consumption"
            + (hazardsConfigured ? "; hazards API configured." : "; hazards API URL not configured.") + "\""
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
  private static final class BroadcastifyCatalogHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      String catalogJson = runBroadcastifyCatalog(query);
      writeJson(exchange, helperResponseStatus(catalogJson), catalogJson);
    }
  }
  private static String runBroadcastifySelector(Map<String, String> query) {
    String lat = query.getOrDefault("lat", "");
    String lon = query.getOrDefault("lon", "");
    if (lat.isBlank() || lon.isBlank()) {
      // Route selection from the streaming device's GPS (posted via
      // /api/gps/update) instead of server-side defaults whenever a device
      // fix is available.
      GpsPoint deviceGps = latestGpsPoint;
      if (deviceGps != null) {
        lat = trimDouble(deviceGps.lat);
        lon = trimDouble(deviceGps.lon);
      }
    }
    boolean hasCoords = !lat.isBlank() && !lon.isBlank();
    String city = query.getOrDefault("city", "").trim();
    String county = query.getOrDefault("county", "").trim();
    String state = query.getOrDefault("state", "").trim();
    if (!hasCoords) {
      if (city.isBlank()) {
        city = BROADCASTIFY_SELECTOR_CITY;
      }
      if (county.isBlank()) {
        county = BROADCASTIFY_SELECTOR_COUNTY;
      }
    }
    if (state.isBlank() && hasCoords) {
      double latNum = parseDouble(lat, Double.NaN);
      double lonNum = parseDouble(lon, Double.NaN);
      if (Double.isFinite(latNum) && Double.isFinite(lonNum)) {
        String inferredState = MapModel.stateFor(latNum, lonNum);
        if (inferredState != null && !inferredState.isBlank() && !"XX".equalsIgnoreCase(inferredState)) {
          state = inferredState;
        }
      }
    }
    if (state.isBlank() && (BROADCASTIFY_SELECTOR_LOCK_STATE || !hasCoords)) {
      state = BROADCASTIFY_SELECTOR_STATE;
    }
    List<String> cmd = new ArrayList<>();
    cmd.add(SELECTOR_PYTHON_BIN);
    cmd.add(SELECTOR_SCRIPT_PATH);
    cmd.add("--channels-file");
    cmd.add(BROADCASTIFY_CHANNELS_FILE);
    if (!lat.isBlank()) {
      cmd.add("--lat");
      cmd.add(lat);
    }
    if (!lon.isBlank()) {
      cmd.add("--lon");
      cmd.add(lon);
    }
    if (!city.isBlank()) {
      cmd.add("--city");
      cmd.add(city);
    }
    if (!county.isBlank()) {
      cmd.add("--county");
      cmd.add(county);
    }
    if (!state.isBlank()) {
      cmd.add("--state");
      cmd.add(state);
    }
    cmd.add("--desired-types");
    cmd.add(BROADCASTIFY_SELECTOR_DESIRED_TYPES);
    cmd.add("--top-k");
    cmd.add(String.valueOf(BROADCASTIFY_SELECTOR_TOP_K));
    cmd.add("--print-top");
    cmd.add(String.valueOf(BROADCASTIFY_SELECTOR_PRINT_TOP));
    cmd.add("--output-format");
    cmd.add("json");
    if ("true".equalsIgnoreCase(BROADCASTIFY_SELECTOR_USE_OLLAMA_RERANK)) {
      cmd.add("--use-ollama-rerank");
      cmd.add("--ollama-model");
      cmd.add(BROADCASTIFY_SELECTOR_OLLAMA_MODEL);
      cmd.add("--ollama-url");
      cmd.add(BROADCASTIFY_SELECTOR_OLLAMA_URL);
      cmd.add("--ollama-timeout");
      cmd.add(BROADCASTIFY_SELECTOR_OLLAMA_TIMEOUT);
      cmd.add("--ollama-weight");
      cmd.add(BROADCASTIFY_SELECTOR_OLLAMA_WEIGHT);
    } else {
      cmd.add("--no-use-ollama-rerank");
    }

    return runHelperCommand(cmd, "selector");
  }
  private static String runBroadcastifyCatalog(Map<String, String> query) {
    String region = query.getOrDefault("region", "").trim();
    List<String> cmd = new ArrayList<>();
    cmd.add(SELECTOR_PYTHON_BIN);
    cmd.add(BROADCASTIFY_CATALOG_SCRIPT_PATH);
    cmd.add("--manifest");
    cmd.add(BROADCASTIFY_CHANNELS_FILE);
    cmd.add("--output-format");
    cmd.add("json");
    if (!region.isBlank()) {
      cmd.add("--region");
      cmd.add(region);
    }

    return runHelperCommand(cmd, "catalog");
  }

  private static String runHelperCommand(List<String> cmd, String label) {
    Path outputPath = null;
    long started = System.nanoTime();
    boolean success = false;
    try {
      outputPath = Files.createTempFile("scanner-backend-" + label + "-", ".out");
      ProcessBuilder pb = new ProcessBuilder(cmd);
      pb.redirectErrorStream(true);
      pb.redirectOutput(outputPath.toFile());
      Process process = pb.start();
      boolean finished = process.waitFor(HELPER_PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        Files.deleteIfExists(outputPath);
        success = false;
        return "{"
            + "\"error\":\"" + jsonEscape(label) + "_timeout\","
            + "\"timeout_seconds\":" + HELPER_PROCESS_TIMEOUT_SECONDS
            + "}";
      }
      String output = Files.readString(outputPath, StandardCharsets.UTF_8).trim();
      int exitCode = process.exitValue();
      Files.deleteIfExists(outputPath);
      if (exitCode != 0) {
        success = false;
        return helperErrorJson(label + "_exit_nonzero", output, exitCode);
      }
      if (output.isBlank()) {
        success = false;
        return helperErrorJson(label + "_empty_output", "", null);
      }
      if (!looksLikeJson(output)) {
        success = false;
        return helperErrorJson(label + "_invalid_json_output", output, null);
      }
      success = true;
      return output;
    } catch (Exception ex) {
      if (outputPath != null) {
        try {
          Files.deleteIfExists(outputPath);
        } catch (IOException ignored) {
        }
      }
      return "{"
          + "\"error\":\"" + jsonEscape(label) + "_execution_failed\","
          + "\"details\":\"" + jsonEscape(ex.toString()) + "\""
          + "}";
    } finally {
      long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      HELPER_STATS.computeIfAbsent(label, k -> new TimingStats()).record(durationMs, success);
    }
  }

  private static void streamEventsFromLog(OutputStream os, boolean mobileCompact, String clientId) throws IOException {
    long offset = Files.exists(LOG_PATH) ? Files.size(LOG_PATH) : 0L;
    while (true) {
      if (clientId != null && !clientId.isBlank()) {
        flushClientMailboxToStream(os, clientId);
      }
      if (!Files.exists(LOG_PATH)) {
        sleepQuietly(STREAM_POLL_MILLIS);
        continue;
      }
      try (RandomAccessFile raf = new RandomAccessFile(LOG_PATH.toFile(), "r")) {
        long size = raf.length();
        if (size < offset) {
          offset = 0L;
        }
        if (size == offset) {
          sleepQuietly(STREAM_POLL_MILLIS);
          continue;
        }
        raf.seek(offset);
        String line;
        while ((line = raf.readLine()) != null) {
          String decodedLine = new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
          for (String payload : extractEventPayloads(decodedLine)) {
            String eventJson = payload;
            if (mobileCompact) {
              String eventType = extractStringField(payload, EVENT_TYPE_PATTERN);
              String kind = extractStringField(payload, KIND_PATTERN);
              eventJson = compactMobileEventJson(new EventInfo(payload, eventType, kind));
            }
            os.write(("data: " + eventJson + "\n\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
          }
        }
        offset = raf.getFilePointer();
      }
      if (clientId != null && !clientId.isBlank()) {
        flushClientMailboxToStream(os, clientId);
      }
    }
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

  private static void writeBinary(HttpExchange exchange, int statusCode, byte[] payload, String contentType)
      throws IOException {
    Headers headers = exchange.getResponseHeaders();
    headers.set("Content-Type", contentType);
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
              + "\"weather_provider\":\"" + jsonEscape(WEATHER_PROVIDER) + "\","
              + "\"metrics\":{"
              + "\"request_timing\":" + timingStatsToJson(REQUEST_STATS) + ","
              + "\"helper_timing\":" + timingStatsToJson(HELPER_STATS)
              + "}"
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
  private static final class LocalRouteHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      String routeJson = buildStandaloneLocalRouteJson(query);
      writeJson(exchange, helperResponseStatus(routeJson), routeJson);
    }
  }

  private static final class RouteOptionsHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      String payload = buildRouteOptionsJson(query);
      writeJson(exchange, helperResponseStatus(payload), payload);
    }
  }

  private static final class AddressCatalogResolveHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      String payload = buildAddressCatalogResolveJson(query);
      writeJson(exchange, helperResponseStatus(payload), payload);
    }
  }

  private static final class AddressCatalogUpsertHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      String method = exchange.getRequestMethod();
      if (!"POST".equals(method) && !"GET".equals(method)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      String body = "POST".equals(method) ? readRequestBody(exchange) : "";
      String payload = buildAddressCatalogUpsertJson(query, body);
      writeJson(exchange, helperResponseStatus(payload), payload);
    }
  }

  private static final class AddressCatalogExportHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      writeJson(exchange, 200, buildAddressCatalogExportJson());
    }
  }

  private static final class AlertClustersHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      String payload = buildAlertClustersJson(query);
      writeJson(exchange, 200, payload);
    }
  }

  private static final class GeocodeHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      String q = query.getOrDefault("q", "").trim();
      if (q.isEmpty()) {
        writeJson(exchange, 400, "{\"error\":\"missing_query\"}");
        return;
      }
      // Optional caller position (lat/lon) biases results: first try a bounded
      // viewbox search near the caller, then fall back to a global search.
      double biasLat = parseDouble(query.get("lat"), Double.NaN);
      double biasLon = parseDouble(query.get("lon"), Double.NaN);
      boolean hasBias =
          Double.isFinite(biasLat)
              && Double.isFinite(biasLon)
              && Math.abs(biasLat) <= 90.0
              && Math.abs(biasLon) <= 180.0;
      String baseUrl = NOMINATIM_SEARCH_URL + "?format=json&limit=5&q=" + urlEncode(q);
      String body = null;
      boolean bounded = false;
      if (hasBias) {
        // Nominatim viewbox is lon,lat pairs: lonMin,latMin,lonMax,latMax.
        String viewbox =
            trimDouble(biasLon - GEOCODE_BIAS_RADIUS_DEGREES)
                + ","
                + trimDouble(biasLat - GEOCODE_BIAS_RADIUS_DEGREES)
                + ","
                + trimDouble(biasLon + GEOCODE_BIAS_RADIUS_DEGREES)
                + ","
                + trimDouble(biasLat + GEOCODE_BIAS_RADIUS_DEGREES);
        body = httpGetExternal(baseUrl + "&viewbox=" + viewbox + "&bounded=1");
        bounded = body != null && looksLikeJson(body) && !"[]".equals(body.trim());
      }
      if (!bounded) {
        body = httpGetExternal(baseUrl);
      }
      if (body == null || !looksLikeJson(body)) {
        writeJson(
            exchange,
            502,
            "{\"status\":\"error\",\"error\":\"geocode_unavailable\",\"provider\":\"nominatim\"}");
        return;
      }
      writeJson(
          exchange,
          200,
          "{"
              + "\"ts\":\"" + Instant.now().toString() + "\","
              + "\"status\":\"ok\","
              + "\"provider\":\"nominatim\","
              + "\"query\":\"" + jsonEscape(q) + "\","
              + "\"bounded\":" + bounded + ","
              + "\"results\":" + body
              + "}");
    }
  }

  private static final class LlmStatusHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      writeJson(exchange, 200, llmStatusJson());
    }
  }

  private static String llmStatusJson() {
    String cachedStatus = getCachedValue(llmStatusCache);
    if (cachedStatus != null) {
      return cachedStatus;
    }
    String body = httpGetExternal(OLLAMA_TAGS_URL);
    boolean ollamaUp = body != null && looksLikeJson(body);
    java.util.Set<String> installed = new java.util.HashSet<>();
    if (ollamaUp) {
      Matcher matcher = MODEL_NAME_PATTERN.matcher(body);
      while (matcher.find()) {
        String name = matcher.group(1);
        installed.add(name);
        if (name.endsWith(":latest")) {
          installed.add(name.substring(0, name.length() - ":latest".length()));
        }
      }
    }
    StringBuilder models = new StringBuilder("{");
    boolean complete = true;
    for (int i = 0; i < SCOUT_MODELS.length; i++) {
      boolean present = installed.contains(SCOUT_MODELS[i]);
      complete = complete && present;
      if (i > 0) {
        models.append(",");
      }
      models.append("\"").append(SCOUT_MODELS[i]).append("\":").append(present);
    }
    models.append("}");
    String payload = "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"status\":\"ok\","
        + "\"ollama_up\":" + ollamaUp + ","
        + "\"tags_url\":\"" + jsonEscape(OLLAMA_TAGS_URL) + "\","
        + "\"base_model\":\"" + jsonEscape(LLM_BASE_MODEL) + "\","
        + "\"base_model_installed\":" + installed.contains(LLM_BASE_MODEL) + ","
        + "\"models\":" + models + ","
        + "\"complete\":" + (ollamaUp && complete)
        + "}";
    llmStatusCache = new TimedStringValue(payload, System.currentTimeMillis() + LLM_STATUS_CACHE_TTL_MS);
    return payload;
  }

  private static String httpGetExternal(String urlString) {
    return httpGetExternal(urlString, null);
  }

  private static String httpGetExternal(String urlString, Map<String, String> extraHeaders) {
    java.net.HttpURLConnection connection = null;
    try {
      connection = (java.net.HttpURLConnection) new java.net.URL(urlString).openConnection();
      connection.setConnectTimeout(EXTERNAL_HTTP_TIMEOUT_MS);
      connection.setReadTimeout(EXTERNAL_HTTP_TIMEOUT_MS);
      connection.setRequestProperty("User-Agent", EXTERNAL_HTTP_USER_AGENT);
      connection.setRequestProperty("Accept", "application/json");
      if (extraHeaders != null) {
        for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
          if (header.getKey() == null || header.getKey().isBlank() || header.getValue() == null || header.getValue().isBlank()) {
            continue;
          }
          connection.setRequestProperty(header.getKey(), header.getValue());
        }
      }
      int status = connection.getResponseCode();
      if (status < 200 || status >= 300) {
        return null;
      }
      try (java.io.InputStream in = connection.getInputStream()) {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
    } catch (Exception ex) {
      return null;
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  private static String fetchOsrmRouteBody(
      double originLat, double originLon, double destLat, double destLon) {
    String cacheKey = osrmCacheKey(originLat, originLon, destLat, destLon);
    String cached = getCachedString(OSRM_ROUTE_CACHE, cacheKey);
    if (cached != null) {
      return cached;
    }
    String url =
        OSRM_ROUTE_BASE_URL
            + "/"
            + trimDouble(originLon)
            + ","
            + trimDouble(originLat)
            + ";"
            + trimDouble(destLon)
            + ","
            + trimDouble(destLat)
            + "?overview=full&geometries=geojson&alternatives=false&steps=false";
    String body = httpGetExternal(url);
    if (body == null || !body.contains("\"code\":\"Ok\"")) {
      return null;
    }
    putCachedString(OSRM_ROUTE_CACHE, cacheKey, body, OSRM_CACHE_TTL_MS, OSRM_CACHE_MAX_ENTRIES);
    return body;
  }

  private static List<RouteNode> parseOsrmCoordinates(String osrmBody) {
    int keyIdx = osrmBody.indexOf("\"coordinates\":[[");
    if (keyIdx < 0) {
      return null;
    }
    int start = keyIdx + "\"coordinates\":[[".length();
    int end = osrmBody.indexOf("]]", start);
    if (end < 0) {
      return null;
    }
    String coords = osrmBody.substring(start, end);
    String[] pairs = coords.split("\\],\\[");
    List<RouteNode> nodes = new ArrayList<>();
    for (String pair : pairs) {
      String[] parts = pair.split(",");
      if (parts.length < 2) {
        continue;
      }
      try {
        double lon = Double.parseDouble(parts[0].trim());
        double lat = Double.parseDouble(parts[1].trim());
        nodes.add(new RouteNode(lat, lon));
      } catch (NumberFormatException ignored) {
        // skip malformed coordinate pair
      }
    }
    return nodes.size() >= 2 ? nodes : null;
  }

  private static Double parseOsrmDistanceMeters(String osrmBody) {
    Matcher matcher = OSRM_DISTANCE_PATTERN.matcher(osrmBody);
    if (matcher.find()) {
      try {
        return Double.parseDouble(matcher.group(1));
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }
  private static boolean looksLikeJson(String raw) {
    if (raw == null) {
      return false;
    }
    String trimmed = raw.trim();
    return (trimmed.startsWith("{") && trimmed.endsWith("}"))
        || (trimmed.startsWith("[") && trimmed.endsWith("]"));
  }

  private static String helperErrorJson(String errorCode, String details, Integer exitCode) {
    StringBuilder sb = new StringBuilder("{")
        .append("\"error\":\"").append(jsonEscape(errorCode)).append("\"");
    if (exitCode != null) {
      sb.append(",\"exit_code\":").append(exitCode.intValue());
    }
    if (details != null && !details.isBlank()) {
      sb.append(",\"details\":\"").append(jsonEscape(details)).append("\"");
    }
    sb.append("}");
    return sb.toString();
  }

  private static int helperResponseStatus(String payload) {
    if (payload == null || payload.isBlank()) {
      return 502;
    }
    String trimmed = payload.trim();
    if (trimmed.startsWith("{\"error\"") || trimmed.startsWith("{ \"error\"")) {
      return 502;
    }
    return 200;
  }

  private static void pruneStaleClientRoutes() {
    long now = System.currentTimeMillis();
    synchronized (CLIENT_ROUTE_LOCK) {
      for (Map.Entry<String, ClientRoute> entry : CLIENT_ROUTES.entrySet()) {
        if (now - entry.getValue().lastSeenMs > CLIENT_ROUTE_TTL_MS) {
          CLIENT_ROUTES.remove(entry.getKey());
        }
      }
    }
  }

  private static String remoteAddressFromExchange(HttpExchange exchange) {
    try {
      if (exchange.getRemoteAddress() == null || exchange.getRemoteAddress().getAddress() == null) {
        return "";
      }
      return exchange.getRemoteAddress().getAddress().getHostAddress();
    } catch (Exception ex) {
      return "";
    }
  }

  private static ClientRoute upsertClientRoute(
      String clientId, String userId, String source, String sessionId, String remoteAddr) {
    long now = System.currentTimeMillis();
    ClientRoute route =
        new ClientRoute(
            clientId,
            userId == null || userId.isBlank() ? "unknown_user" : userId,
            source == null || source.isBlank() ? "unknown_source" : source,
            sessionId == null ? "" : sessionId,
            remoteAddr == null ? "" : remoteAddr,
            now);
    CLIENT_ROUTES.put(clientId, route);
    return route;
  }

  private static void enqueueClientMessage(String clientId, String payloadJson) {
    Deque<ClientMessage> mailbox = CLIENT_MAILBOX.computeIfAbsent(clientId, key -> new ArrayDeque<>());
    synchronized (mailbox) {
      mailbox.addLast(new ClientMessage(payloadJson, System.currentTimeMillis()));
      while (mailbox.size() > CLIENT_MAILBOX_MAX_MESSAGES) {
        mailbox.removeFirst();
      }
    }
  }

  private static List<ClientMessage> drainClientMailbox(String clientId, int limit) {
    Deque<ClientMessage> mailbox = CLIENT_MAILBOX.get(clientId);
    if (mailbox == null || limit <= 0) {
      return List.of();
    }
    List<ClientMessage> out = new ArrayList<>();
    synchronized (mailbox) {
      while (out.size() < limit && !mailbox.isEmpty()) {
        out.add(mailbox.removeFirst());
      }
    }
    return out;
  }

  private static String clientMessagesToJson(List<ClientMessage> messages) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < messages.size(); i++) {
      if (i > 0) {
        sb.append(",");
      }
      sb.append(messages.get(i).payloadJson);
    }
    sb.append("]");
    return sb.toString();
  }

  private static String clientRouteToJson(ClientRoute route) {
    return "{"
        + "\"client_id\":\"" + jsonEscape(route.clientId) + "\","
        + "\"user_id\":\"" + jsonEscape(route.userId) + "\","
        + "\"source\":\"" + jsonEscape(route.source) + "\","
        + "\"session_id\":\"" + jsonEscape(route.sessionId) + "\","
        + "\"remote_addr\":\"" + jsonEscape(route.remoteAddr) + "\","
        + "\"last_seen_ms\":" + route.lastSeenMs
        + "}";
  }

  private static String allClientRoutesJson() {
    pruneStaleClientRoutes();
    List<ClientRoute> routes = new ArrayList<>(CLIENT_ROUTES.values());
    routes.sort(Comparator.comparing(route -> route.clientId));
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < routes.size(); i++) {
      if (i > 0) {
        sb.append(",");
      }
      sb.append(clientRouteToJson(routes.get(i)));
    }
    sb.append("]");
    return sb.toString();
  }

  private static String relayMessagePayloadJson(String from, String kind, String message) {
    return "{"
        + "\"ts\":\"" + Instant.now().toString() + "\","
        + "\"event_type\":\"server_relay\","
        + "\"from\":\"" + jsonEscape(from == null ? "server" : from) + "\","
        + "\"kind\":\"" + jsonEscape(kind == null || kind.isBlank() ? "notice" : kind) + "\","
        + "\"message\":\"" + jsonEscape(message == null ? "" : message) + "\""
        + "}";
  }

  private static void flushClientMailboxToStream(OutputStream os, String clientId) throws IOException {
    if (clientId == null || clientId.isBlank()) {
      return;
    }
    List<ClientMessage> messages = drainClientMailbox(clientId, CLIENT_PULL_MAX_LIMIT);
    for (ClientMessage msg : messages) {
      os.write(("data: " + msg.payloadJson + "\n\n").getBytes(StandardCharsets.UTF_8));
      os.flush();
    }
  }
  private static final class BroadcastifySelectHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      String selectorJson = runBroadcastifySelector(query);
      writeJson(exchange, helperResponseStatus(selectorJson), selectorJson);
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

  private static final class MobileClientRegisterHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      String method = exchange.getRequestMethod();
      if (!"POST".equals(method) && !"GET".equals(method)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      String body = "POST".equals(method) ? readRequestBody(exchange) : "";
      String clientId = extractStringFieldByName(body, "client_id", query.getOrDefault("client_id", "")).trim();
      if (clientId.isBlank()) {
        writeJson(exchange, 400, "{\"error\":\"missing_client_id\"}");
        return;
      }
      String userId = extractStringFieldByName(body, "user_id", query.getOrDefault("user_id", ""));
      String source = extractStringFieldByName(body, "source", query.getOrDefault("source", "mobile_client"));
      String sessionId = extractStringFieldByName(body, "session_id", query.getOrDefault("session_id", ""));
      ClientRoute route = upsertClientRoute(clientId, userId, source, sessionId, remoteAddressFromExchange(exchange));
      writeJson(
          exchange,
          200,
          "{"
              + "\"status\":\"ok\","
              + "\"ts\":\"" + Instant.now().toString() + "\","
              + "\"route\":" + clientRouteToJson(route) + ","
              + "\"pull_endpoint\":\"/api/mobile/client/pull?client_id=" + urlEncode(clientId) + "\""
              + "}");
    }
  }

  private static final class MobileClientSendHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      String method = exchange.getRequestMethod();
      if (!"POST".equals(method) && !"GET".equals(method)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      String body = "POST".equals(method) ? readRequestBody(exchange) : "";
      String clientId = extractStringFieldByName(body, "client_id", query.getOrDefault("client_id", "")).trim();
      if (clientId.isBlank()) {
        writeJson(exchange, 400, "{\"error\":\"missing_client_id\"}");
        return;
      }
      String from = extractStringFieldByName(body, "from", query.getOrDefault("from", "server"));
      String kind = extractStringFieldByName(body, "kind", query.getOrDefault("kind", "notice"));
      String message = extractStringFieldByName(body, "message", query.getOrDefault("message", ""));
      if (message.isBlank()) {
        writeJson(exchange, 400, "{\"error\":\"missing_message\"}");
        return;
      }
      String payload = relayMessagePayloadJson(from, kind, message);
      enqueueClientMessage(clientId, payload);
      writeJson(
          exchange,
          200,
          "{"
              + "\"status\":\"queued\","
              + "\"ts\":\"" + Instant.now().toString() + "\","
              + "\"client_id\":\"" + jsonEscape(clientId) + "\""
              + "}");
    }
  }

  private static final class MobileClientPullHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      String clientId = query.getOrDefault("client_id", "").trim();
      if (clientId.isBlank()) {
        writeJson(exchange, 400, "{\"error\":\"missing_client_id\"}");
        return;
      }
      int limit = parseIntOrDefault(query.getOrDefault("limit", String.valueOf(CLIENT_PULL_DEFAULT_LIMIT)), CLIENT_PULL_DEFAULT_LIMIT);
      if (limit < 1) {
        limit = 1;
      }
      if (limit > CLIENT_PULL_MAX_LIMIT) {
        limit = CLIENT_PULL_MAX_LIMIT;
      }
      List<ClientMessage> messages = drainClientMailbox(clientId, limit);
      writeJson(
          exchange,
          200,
          "{"
              + "\"status\":\"ok\","
              + "\"ts\":\"" + Instant.now().toString() + "\","
              + "\"client_id\":\"" + jsonEscape(clientId) + "\","
              + "\"count\":" + messages.size() + ","
              + "\"messages\":" + clientMessagesToJson(messages)
              + "}");
    }
  }

  private static final class MobileClientsHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      writeJson(
          exchange,
          200,
          "{"
              + "\"status\":\"ok\","
              + "\"ts\":\"" + Instant.now().toString() + "\","
              + "\"ttl_ms\":" + CLIENT_ROUTE_TTL_MS + ","
              + "\"clients\":" + allClientRoutesJson()
              + "}");
    }
  }

  private static final class GpsUpdateHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      String method = exchange.getRequestMethod();
      if (!"POST".equals(method) && !"GET".equals(method)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      String body = "POST".equals(method) ? readRequestBody(exchange) : "";
      GpsPoint point = gpsPointFromInputs(query, body);
      if (point == null) {
        writeJson(exchange, 400, "{\"error\":\"invalid_gps_payload\"}");
        return;
      }
      appendGpsPoint(point);
      ProprietaryMapEngine.updateGps(point.lat, point.lon);
      List<GpsPoint> recent = copyRecentTrack(40);
      writeJson(
          exchange,
          200,
          "{"
              + "\"status\":\"ok\","
              + "\"received_at\":\"" + Instant.now().toString() + "\","
              + "\"active_users\":" + GPS_BY_USER.size() + ","
              + "\"point\":" + gpsPointToJson(point) + ","
              + "\"track\":" + gpsTrackToJson(recent)
              + "}");
    }
  }

  private static final class MapSceneHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      double lat = parseDouble(query.getOrDefault("lat", ""), Double.NaN);
      double lon = parseDouble(query.getOrDefault("lon", ""), Double.NaN);
      if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
        GpsPoint latest = latestGpsPoint;
        if (latest == null) {
          writeJson(exchange, 400, "{\"error\":\"missing_coordinates\"}");
          return;
        }
        lat = latest.lat;
        lon = latest.lon;
      }
      double radiusM = parseDouble(query.getOrDefault("radius_m", ""), 700.0);
      int zoom = parseIntOrDefault(query.getOrDefault("zoom", ""), 0); // 0 = auto (resolution filter)
      String sceneJson = ProprietaryMapEngine.sceneJson(lat, lon, radiusM, zoom);
      writeJson(exchange, 200, appendAlertClustersToSceneJson(sceneJson, radiusM));
    }
  }

  private static final class MapRenderHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      double lat = parseDouble(query.getOrDefault("lat", ""), Double.NaN);
      double lon = parseDouble(query.getOrDefault("lon", ""), Double.NaN);
      if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
        GpsPoint latest = latestGpsPoint;
        if (latest == null) {
          writeJson(exchange, 400, "{\"error\":\"missing_coordinates\"}");
          return;
        }
        lat = latest.lat;
        lon = latest.lon;
      }
      double mpp = parseDouble(query.getOrDefault("mpp", ""), 1.2);
      double heading = parseDouble(query.getOrDefault("heading", ""), 0.0);
      double tilt = parseDouble(query.getOrDefault("tilt", ""), 45.0);
      int w = parseIntOrDefault(query.getOrDefault("w", ""), 720);
      int h = parseIntOrDefault(query.getOrDefault("h", ""), 1280);
      double destLat = parseDouble(query.getOrDefault("dest_lat", ""), Double.NaN);
      double destLon = parseDouble(query.getOrDefault("dest_lon", ""), Double.NaN);
      double[] routePts = null;
      Double destLatBox = null;
      Double destLonBox = null;
      if (Double.isFinite(destLat) && Double.isFinite(destLon)) {
        destLatBox = destLat;
        destLonBox = destLon;
        String osrmBody = fetchOsrmRouteBody(lat, lon, destLat, destLon);
        if (osrmBody != null) {
          List<RouteNode> nodes = parseOsrmCoordinates(osrmBody);
          if (nodes != null) {
            routePts = new double[nodes.size() * 2];
            for (int i = 0; i < nodes.size(); i++) {
              routePts[i * 2] = nodes.get(i).lat;
              routePts[i * 2 + 1] = nodes.get(i).lon;
            }
          }
        }
      }
      byte[] png = ProprietaryMapEngine.renderPng(
          lat, lon, mpp, heading, tilt, w, h, routePts, destLatBox, destLonBox);
      writeBinary(exchange, 200, png, "image/png");
    }
  }

  private static final class MapStatusHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      writeJson(exchange, 200, ProprietaryMapEngine.statusJson());
    }
  }

  private static final class MapShardHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      if ("1".equals(query.getOrDefault("status", ""))) {
        writeJson(exchange, 200, "{\"status\":\"ok\",\"prefetch\":" + ProprietaryMapEngine.prefetchStatusJson() + "}");
        return;
      }
      String state = query.getOrDefault("state", "").trim();
      if (state.isEmpty()) {
        writeJson(exchange, 400, "{\"error\":\"missing_state\"}");
        return;
      }
      int maxTiles = parseIntOrDefault(query.getOrDefault("max_tiles", ""), 0);
      String result = ProprietaryMapEngine.startShardPrefetch(state, maxTiles);
      writeJson(exchange, result.contains("\"error\"") ? 400 : 200, result);
    }
  }

  private static final class GpsLatestHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      GpsPoint latest = latestGpsPoint;
      if (latest == null) {
        writeJson(exchange, 200, "{\"status\":\"empty\",\"active_users\":0}");
        return;
      }
      writeJson(
          exchange,
          200,
          "{"
              + "\"status\":\"ok\","
              + "\"active_users\":" + GPS_BY_USER.size() + ","
              + "\"point\":" + gpsPointToJson(latest)
              + "}");
    }
  }

  private static final class GpsTrackHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      URI uri = exchange.getRequestURI();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      int limit = parseIntOrDefault(query.getOrDefault("limit", "120"), 120);
      if (limit < 1) {
        limit = 1;
      }
      if (limit > GPS_TRACK_MAX_POINTS) {
        limit = GPS_TRACK_MAX_POINTS;
      }
      List<GpsPoint> recent = copyRecentTrack(limit);
      writeJson(
          exchange,
          200,
          "{"
              + "\"status\":\"ok\","
              + "\"active_users\":" + GPS_BY_USER.size() + ","
              + "\"count\":" + recent.size() + ","
              + "\"points\":" + gpsTrackToJson(recent)
              + "}");
    }
  }

  private static final class GpsTriangulationHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      List<GpsPoint> points = new ArrayList<>(GPS_BY_USER.values());
      points.sort(Comparator.comparing(p -> p.userId));
      if (points.size() < 2) {
        writeJson(
            exchange,
            200,
            "{"
                + "\"status\":\"insufficient_users\","
                + "\"active_users\":" + points.size() + ","
                + "\"required_users\":2"
                + "}");
        return;
      }
      double latSum = 0.0;
      double lonSum = 0.0;
      double accuracySum = 0.0;
      for (GpsPoint p : points) {
        latSum += p.lat;
        lonSum += p.lon;
        accuracySum += p.accuracy;
      }
      double estLat = latSum / points.size();
      double estLon = lonSum / points.size();
      double avgAccuracy = accuracySum / points.size();
      writeJson(
          exchange,
          200,
          "{"
              + "\"status\":\"ok\","
              + "\"method\":\"multi_user_centroid_seed\","
              + "\"active_users\":" + points.size() + ","
              + "\"estimated_lat\":" + trimDouble(estLat) + ","
              + "\"estimated_lon\":" + trimDouble(estLon) + ","
              + "\"average_accuracy_m\":" + trimDouble(avgAccuracy) + ","
              + "\"contributors\":" + gpsTrackToJson(points)
              + "}");
    }
  }

  private static final class StreamHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!isGet(exchange)) {
        writeJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        return;
      }
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      String clientId = query.getOrDefault("client_id", "").trim();
      if (!clientId.isBlank()) {
        String userId = query.getOrDefault("user_id", "");
        String source = query.getOrDefault("source", "pipeline_stream");
        String sessionId = query.getOrDefault("session_id", "");
        upsertClientRoute(clientId, userId, source, sessionId, remoteAddressFromExchange(exchange));
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

      try {
        streamEventsFromLog(os, false, clientId);
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
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      String clientId = query.getOrDefault("client_id", "").trim();
      if (!clientId.isBlank()) {
        String userId = query.getOrDefault("user_id", "");
        String source = query.getOrDefault("source", "mobile_stream");
        String sessionId = query.getOrDefault("session_id", "");
        upsertClientRoute(clientId, userId, source, sessionId, remoteAddressFromExchange(exchange));
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
      try {
        streamEventsFromLog(os, true, clientId);
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

  private static String readRequestBody(HttpExchange exchange) throws IOException {
    return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
  }

  private static GpsPoint gpsPointFromInputs(Map<String, String> query, String body) {
    double lat = parseDouble(query.getOrDefault("lat", ""), Double.NaN);
    double lon = parseDouble(query.getOrDefault("lon", ""), Double.NaN);
    if (!Double.isFinite(lat)) {
      lat = extractDoubleField(body, "lat", Double.NaN);
    }
    if (!Double.isFinite(lon)) {
      lon = extractDoubleField(body, "lon", Double.NaN);
    }
    if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
      return null;
    }
    if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
      return null;
    }

    String ts = extractStringFieldByName(body, "ts", Instant.now().toString());
    String source = extractStringFieldByName(body, "source", query.getOrDefault("source", "frontend_browser"));
    String userId = extractStringFieldByName(body, "user_id", query.getOrDefault("user_id", "default"));
    long seq = extractLongField(body, "seq", parseLongOrDefault(query.getOrDefault("seq", "0"), 0L));
    double accuracy = extractDoubleField(body, "accuracy", parseDouble(query.getOrDefault("accuracy", ""), 0.0));
    double speed = extractDoubleField(body, "speed", parseDouble(query.getOrDefault("speed", ""), 0.0));
    double heading = extractDoubleField(body, "heading", parseDouble(query.getOrDefault("heading", ""), 0.0));
    if (!Double.isFinite(accuracy) || accuracy < 0) accuracy = 0.0;
    if (!Double.isFinite(speed) || speed < 0) speed = 0.0;
    if (!Double.isFinite(heading)) heading = 0.0;
    if (userId == null || userId.isBlank()) userId = "default";
    if (source == null || source.isBlank()) source = "unknown";

    return new GpsPoint(
        ts,
        userId,
        source,
        seq,
        lat,
        lon,
        accuracy,
        speed,
        heading,
        System.currentTimeMillis());
  }

  private static void appendGpsPoint(GpsPoint point) {
    latestGpsPoint = point;
    GPS_BY_USER.put(point.userId, point);
    synchronized (GPS_LOCK) {
      GPS_TRACK.addLast(point);
      while (GPS_TRACK.size() > GPS_TRACK_MAX_POINTS) {
        GPS_TRACK.removeFirst();
      }
    }
  }

  private static List<GpsPoint> copyRecentTrack(int limit) {
    List<GpsPoint> out = new ArrayList<>();
    synchronized (GPS_LOCK) {
      int skip = Math.max(0, GPS_TRACK.size() - limit);
      int idx = 0;
      for (GpsPoint p : GPS_TRACK) {
        if (idx++ < skip) continue;
        out.add(p);
      }
    }
    return out;
  }

  private static String gpsPointToJson(GpsPoint p) {
    return "{"
        + "\"ts\":\"" + jsonEscape(p.ts) + "\","
        + "\"user_id\":\"" + jsonEscape(p.userId) + "\","
        + "\"source\":\"" + jsonEscape(p.source) + "\","
        + "\"seq\":" + p.seq + ","
        + "\"lat\":" + trimDouble(p.lat) + ","
        + "\"lon\":" + trimDouble(p.lon) + ","
        + "\"accuracy\":" + trimDouble(p.accuracy) + ","
        + "\"speed\":" + trimDouble(p.speed) + ","
        + "\"heading\":" + trimDouble(p.heading) + ","
        + "\"received_at_ms\":" + p.receivedAtMs
        + "}";
  }

  private static String gpsTrackToJson(List<GpsPoint> points) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < points.size(); i++) {
      if (i > 0) sb.append(",");
      sb.append(gpsPointToJson(points.get(i)));
    }
    sb.append("]");
    return sb.toString();
  }

  private static long parseLongOrDefault(String raw, long fallback) {
    if (raw == null || raw.isBlank()) return fallback;
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private static long extractLongField(String json, String fieldName, long fallback) {
    Pattern pattern = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*(-?\\d+)");
    Matcher matcher = pattern.matcher(json == null ? "" : json);
    if (!matcher.find()) return fallback;
    try {
      return Long.parseLong(matcher.group(1));
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private static double extractDoubleField(String json, String fieldName, double fallback) {
    Pattern pattern = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
    Matcher matcher = pattern.matcher(json == null ? "" : json);
    if (!matcher.find()) return fallback;
    try {
      return Double.parseDouble(matcher.group(1));
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private static String extractStringFieldByName(String json, String fieldName, String fallback) {
    Pattern pattern = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"([^\"]*)\"");
    Matcher matcher = pattern.matcher(json == null ? "" : json);
    if (matcher.find()) {
      return matcher.group(1);
    }
    return fallback;
  }

  private static String trimDouble(double value) {
    if (!Double.isFinite(value)) {
      return "0";
    }
    return String.format(Locale.ROOT, "%.7f", value);
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