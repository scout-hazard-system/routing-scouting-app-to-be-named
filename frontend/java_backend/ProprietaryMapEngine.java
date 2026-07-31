import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;

/**
 * Proprietary server-side map engine. Assimilates OpenStreetMap data into its
 * own cell model (z15 web-mercator grid) with a jurisdiction-sharded disk cache.
 * Primary source: planet-scale PMTiles extract (PlanetTileStore). Fallback:
 * Overpass API. Serves compact vector scenes for the on-device 3D renderer and
 * renders verification PNGs server-side with Java2D.
 *
 * Map data (c) OpenStreetMap contributors, ODbL.
 */
final class ProprietaryMapEngine {
  private ProprietaryMapEngine() {}

  static final int CELL_ZOOM = 15;
  /**
   * Zoom ladder for multi-resolution scenes. All levels are served by the same
   * planet PMTiles extract (it contains z0..z15), layered from street detail
   * (z15) out to global scale (z3). The resolution filter picks the ladder
   * level whose tile grid covers the requested radius.
   */
  static final int[] ZOOM_LADDER = {15, 13, 11, 9, 7, 5, 3};
  private static final double EARTH_CIRCUMFERENCE_M = 40075016.686;
  static final String ATTRIBUTION = "\u00a9 OpenStreetMap contributors";

  private static final Path CACHE_DIR = Path.of(
      System.getenv().getOrDefault(
          "MAP_CACHE_DIR",
          System.getProperty("user.home", "/tmp") + "/.scanner_stream/map_cache"));
  private static final Path SHARDS_DIR = CACHE_DIR.resolve("shards");
  private static final String OVERPASS_API_URL =
      System.getenv().getOrDefault("OVERPASS_API_URL", "https://overpass-api.de/api/interpreter");
  private static final String USER_AGENT =
      System.getenv().getOrDefault("EXTERNAL_HTTP_USER_AGENT", "scanner-stream-backend/0.1 (self-hosted)");
  private static final int OVERPASS_TIMEOUT_MS = 20000;
  private static final long OVERPASS_MIN_INTERVAL_MS = 1000L;
  private static final long OVERPASS_FAILURE_BACKOFF_MS = 60000L;
  private static final int CELL_LRU_SIZE = 256;
  private static final int SCENE_MAX_TILES_PER_AXIS = 5;
  private static final int RENDER_MAX_TILES_PER_AXIS = 6;
  private static final double DEFAULT_SCENE_RADIUS_M = 700.0;
  private static final double MAX_SCENE_RADIUS_M = 8000000.0;
  private static final int SHARD_PREFETCH_DEFAULT_MAX_TILES = 1500;
  private static final long GPS_WARM_INTERVAL_MS = 7000L;

  private static final Object OVERPASS_LOCK = new Object();
  private static long lastOverpassRequestMs = 0L;
  private static volatile long overpassBackoffUntilMs = 0L;
  static final AtomicLong overpassFetchCount = new AtomicLong();
  static final AtomicLong overpassFailureCount = new AtomicLong();
  private static volatile String lastOverpassError = "";

  private static final ConcurrentHashMap<Long, Object> CELL_LOCKS = new ConcurrentHashMap<>();
  private static final LinkedHashMap<Long, MapModel.CellData> CELL_LRU =
      new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, MapModel.CellData> eldest) {
          return size() > CELL_LRU_SIZE;
        }
      };

  private static volatile double warmLat = Double.NaN;
  private static volatile double warmLon = Double.NaN;
  private static volatile boolean warmThreadStarted = false;

  // Shard prefetch job state (single job at a time).
  private static final Object PREFETCH_LOCK = new Object();
  private static volatile String prefetchState = "";
  private static volatile String prefetchPhase = "idle"; // idle|running|done|error
  private static volatile int prefetchRequested = 0;
  private static volatile int prefetchFetched = 0;
  private static volatile int prefetchCached = 0;
  private static volatile int prefetchMisses = 0;

  static {
    System.setProperty("java.awt.headless", "true");
  }

  /** Called once from server startup: starts the GPS shard-warming thread. */
  static synchronized void init() {
    if (warmThreadStarted) {
      return;
    }
<<<<<<< HEAD
=======
    // Prime planet tile store up front so map status reflects true source
    // readiness even before the first uncached scene/render request.
    PlanetTileStore.ready();
>>>>>>> feature/integrate-waze-and-service-hardening
    warmThreadStarted = true;
    Thread warm = new Thread(() -> {
      while (true) {
        try {
          Thread.sleep(GPS_WARM_INTERVAL_MS);
          double lat = warmLat;
          double lon = warmLon;
          if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
            continue;
          }
          int cx = MapModel.lonToTileX(lon, CELL_ZOOM);
          int cy = MapModel.latToTileY(lat, CELL_ZOOM);
          for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
              getCell(CELL_ZOOM, cx + dx, cy + dy, true, false);
            }
          }
          // Keep one tile of every ladder level warm so zooming out is instant.
          for (int z : ZOOM_LADDER) {
            if (z == CELL_ZOOM) {
              continue;
            }
            getCell(z, MapModel.lonToTileX(lon, z), MapModel.latToTileY(lat, z), true, false);
          }
        } catch (InterruptedException ex) {
          return;
        } catch (Exception ignored) {
          // warming is best-effort
        }
      }
    }, "map-shard-warm");
    warm.setDaemon(true);
    warm.start();
  }

  /** Feed the latest GPS fix so the engine keeps nearby shard cells warm. */
  static void updateGps(double lat, double lon) {
    if (Double.isFinite(lat) && Double.isFinite(lon)) {
      warmLat = lat;
      warmLon = lon;
    }
  }

  // ---- Cell loading pipeline: LRU -> shard disk -> planet extract -> Overpass ----

  private static long cellKey(int z, int x, int y) {
    return ((long) z << 58) | ((long) x << 29) | (y & 0x1FFFFFFFL);
  }
<<<<<<< HEAD
=======
  private static int zoomFromCellKey(long key) {
    return (int) (key >>> 58);
  }

  private static void evictFinerZoomLayers(int zoom) {
    synchronized (CELL_LRU) {
      var it = CELL_LRU.entrySet().iterator();
      while (it.hasNext()) {
        if (zoomFromCellKey(it.next().getKey()) > zoom) {
          it.remove();
        }
      }
    }
  }
>>>>>>> feature/integrate-waze-and-service-hardening

  static MapModel.CellData getCell(int z, int x, int y, boolean allowNetwork, boolean allowOverpass) {
    long key = cellKey(z, x, y);
    synchronized (CELL_LRU) {
      MapModel.CellData cached = CELL_LRU.get(key);
      if (cached != null) {
        return cached;
      }
    }
    Object lock = CELL_LOCKS.computeIfAbsent(key, k -> new Object());
    synchronized (lock) {
      synchronized (CELL_LRU) {
        MapModel.CellData cached = CELL_LRU.get(key);
        if (cached != null) {
          return cached;
        }
      }
      MapModel.CellData cell = loadCellFromDisk(z, x, y);
      if (cell == null && allowNetwork) {
        cell = fetchCellFromPlanet(z, x, y);
      }
      if (cell == null && allowNetwork && allowOverpass && z == CELL_ZOOM) {
        cell = fetchCellFromOverpass(x, y);
      }
      if (cell != null) {
        synchronized (CELL_LRU) {
          CELL_LRU.put(key, cell);
        }
      }
      return cell;
    }
  }

  private static Path shardDirFor(int z, int x, int y) {
    if (z < 10) {
      // Low-zoom tiles span many jurisdictions: shard by zoom level instead.
      return SHARDS_DIR.resolve(String.format(Locale.ROOT, "_z%02d", z));
    }
    double lat = MapModel.tileToLat(y + 0.5, z);
    double lon = MapModel.tileToLon(x + 0.5, z);
    return SHARDS_DIR.resolve(MapModel.stateFor(lat, lon));
  }

  private static Path tilePath(int z, int x, int y) {
    return shardDirFor(z, x, y).resolve(z + "_" + x + "_" + y + ".mvt.gz");
  }

  private static Path overpassCellPath(int x, int y) {
    return shardDirFor(CELL_ZOOM, x, y).resolve("cell_" + CELL_ZOOM + "_" + x + "_" + y + ".json");
  }

  private static MapModel.CellData loadCellFromDisk(int z, int x, int y) {
    try {
      Path tile = tilePath(z, x, y);
      if (Files.exists(tile)) {
        MapModel.CellData cell = PlanetTileStore.decodeTile(Files.readAllBytes(tile), z, x, y);
        if (cell != null) {
          return cell;
        }
      }
      if (z == CELL_ZOOM) {
        Path json = overpassCellPath(x, y);
        if (Files.exists(json)) {
          return cellFromNormalizedJson(Files.readString(json, StandardCharsets.UTF_8), x, y);
        }
      }
    } catch (Exception ignored) {
      // fall through to network sources
    }
    return null;
  }

  private static MapModel.CellData fetchCellFromPlanet(int z, int x, int y) {
    byte[] stored = PlanetTileStore.fetchTile(z, x, y);
    if (stored == null) {
      return null;
    }
    MapModel.CellData cell = PlanetTileStore.decodeTile(stored, z, x, y);
    if (cell == null) {
      return null;
    }
    try {
      Path path = tilePath(z, x, y);
      Files.createDirectories(path.getParent());
      Files.write(path, stored);
    } catch (IOException ignored) {
      // cache write failures are non-fatal
    }
    return cell;
  }

  // ---- Overpass fallback ----

  private static MapModel.CellData fetchCellFromOverpass(int x, int y) {
    if (System.currentTimeMillis() < overpassBackoffUntilMs) {
      return null;
    }
    double south = MapModel.tileToLat(y + 1.0, CELL_ZOOM);
    double north = MapModel.tileToLat(y, CELL_ZOOM);
    double west = MapModel.tileToLon(x, CELL_ZOOM);
    double east = MapModel.tileToLon(x + 1.0, CELL_ZOOM);
    String bbox = south + "," + west + "," + north + "," + east;
    String query = "[out:json][timeout:20];("
        + "way[\"highway\"](" + bbox + ");"
        + "way[\"building\"](" + bbox + ");"
        + "way[\"landuse\"](" + bbox + ");"
        + "way[\"leisure\"](" + bbox + ");"
        + "way[\"natural\"](" + bbox + ");"
        + "node[\"amenity\"][\"name\"](" + bbox + ");"
        + "node[\"shop\"][\"name\"](" + bbox + ");"
        + ");out geom 4000;";
    String body;
    synchronized (OVERPASS_LOCK) {
      long wait = lastOverpassRequestMs + OVERPASS_MIN_INTERVAL_MS - System.currentTimeMillis();
      if (wait > 0) {
        try {
          Thread.sleep(wait);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          return null;
        }
      }
      lastOverpassRequestMs = System.currentTimeMillis();
      body = httpGet(OVERPASS_API_URL + "?data=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
    }
    overpassFetchCount.incrementAndGet();
    if (body == null) {
      overpassFailureCount.incrementAndGet();
      overpassBackoffUntilMs = System.currentTimeMillis() + OVERPASS_FAILURE_BACKOFF_MS;
      lastOverpassError = "overpass_unreachable";
      return null;
    }
    try {
      MapModel.CellData cell = parseOverpassCell(body, x, y);
      persistNormalizedCell(cell);
      return cell;
    } catch (Exception ex) {
      overpassFailureCount.incrementAndGet();
      lastOverpassError = "overpass_parse_failed: " + ex.getMessage();
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private static MapModel.CellData parseOverpassCell(String body, int x, int y) {
    MapModel.CellData cell = new MapModel.CellData(CELL_ZOOM, x, y);
    cell.source = "overpass";
    Object rootObj = MiniJson.parse(body);
    if (!(rootObj instanceof Map)) {
      return cell;
    }
    Object elementsObj = ((Map<String, Object>) rootObj).get("elements");
    if (!(elementsObj instanceof List)) {
      return cell;
    }
    for (Object elObj : (List<Object>) elementsObj) {
      if (!(elObj instanceof Map)) {
        continue;
      }
      Map<String, Object> el = (Map<String, Object>) elObj;
      String type = String.valueOf(el.get("type"));
      Map<String, Object> tags = el.get("tags") instanceof Map
          ? (Map<String, Object>) el.get("tags")
          : java.util.Collections.emptyMap();
      if ("node".equals(type)) {
        String name = strTag(tags, "name");
        if (name.isEmpty() || !(el.get("lat") instanceof Number) || !(el.get("lon") instanceof Number)) {
          continue;
        }
        MapModel.Poi poi = new MapModel.Poi();
        poi.name = name;
        String kind = strTag(tags, "amenity");
        if (kind.isEmpty()) {
          kind = strTag(tags, "shop");
        }
        poi.kind = kind;
        poi.lat = ((Number) el.get("lat")).doubleValue();
        poi.lon = ((Number) el.get("lon")).doubleValue();
        cell.pois.add(poi);
        continue;
      }
      if (!"way".equals(type) || !(el.get("geometry") instanceof List)) {
        continue;
      }
      List<Object> geometry = (List<Object>) el.get("geometry");
      double[] pts = new double[geometry.size() * 2];
      int n = 0;
      for (Object gObj : geometry) {
        if (gObj instanceof Map) {
          Map<String, Object> g = (Map<String, Object>) gObj;
          if (g.get("lat") instanceof Number && g.get("lon") instanceof Number) {
            pts[n * 2] = ((Number) g.get("lat")).doubleValue();
            pts[n * 2 + 1] = ((Number) g.get("lon")).doubleValue();
            n++;
          }
        }
      }
      if (n < 2) {
        continue;
      }
      if (n * 2 != pts.length) {
        double[] trimmed = new double[n * 2];
        System.arraycopy(pts, 0, trimmed, 0, n * 2);
        pts = trimmed;
      }
      String highway = strTag(tags, "highway");
      if (!highway.isEmpty()) {
        String clazz = overpassRoadClass(highway);
        if (clazz == null) {
          continue;
        }
        MapModel.Road road = new MapModel.Road();
        road.clazz = clazz;
        road.name = strTag(tags, "name").isEmpty() ? strTag(tags, "ref") : strTag(tags, "name");
        road.oneway = "yes".equals(strTag(tags, "oneway"));
        road.pts = pts;
        cell.roads.add(road);
        continue;
      }
      if (tags.containsKey("building")) {
        MapModel.Building b = new MapModel.Building();
        b.heightM = overpassHeight(tags);
        b.pts = pts;
        cell.buildings.add(b);
        continue;
      }
      String kind = overpassAreaKind(tags);
      if (kind != null && n >= 3) {
        MapModel.Area a = new MapModel.Area();
        a.kind = kind;
        a.pts = pts;
        cell.areas.add(a);
      }
    }
    return cell;
  }

  private static String overpassRoadClass(String highway) {
    switch (highway) {
      case "motorway":
      case "motorway_link":
      case "trunk":
      case "trunk_link":
        return "motorway";
      case "primary":
      case "primary_link":
        return "primary";
      case "secondary":
      case "secondary_link":
        return "secondary";
      case "tertiary":
      case "tertiary_link":
        return "tertiary";
      case "residential":
      case "unclassified":
      case "living_street":
      case "road":
      case "pedestrian":
        return "residential";
      case "service":
        return "service";
      case "footway":
      case "path":
      case "cycleway":
      case "track":
      case "steps":
        return "path";
      default:
        return null;
    }
  }

  private static double overpassHeight(Map<String, Object> tags) {
    String heightRaw = strTag(tags, "height").replace("m", "").trim();
    if (!heightRaw.isEmpty()) {
      try {
        double h = Double.parseDouble(heightRaw);
        if (h > 0 && h <= 700) {
          return h;
        }
      } catch (NumberFormatException ignored) {
        // fall through
      }
    }
    String levelsRaw = strTag(tags, "building:levels").trim();
    if (!levelsRaw.isEmpty()) {
      try {
        double levels = Double.parseDouble(levelsRaw);
        if (levels > 0 && levels < 200) {
          return levels * 3.2;
        }
      } catch (NumberFormatException ignored) {
        // fall through
      }
    }
    return 6.0;
  }

  private static String overpassAreaKind(Map<String, Object> tags) {
    String leisure = strTag(tags, "leisure");
    String landuse = strTag(tags, "landuse");
    String natural = strTag(tags, "natural");
    if ("water".equals(natural) || "bay".equals(natural) || "basin".equals(landuse) || "reservoir".equals(landuse)) {
      return "water";
    }
    if ("beach".equals(natural) || "sand".equals(natural)) {
      return "sand";
    }
    Set<String> parkish = Set.of(
        "park", "garden", "pitch", "playground", "golf_course", "nature_reserve", "dog_park",
        "recreation_ground", "stadium");
    if (parkish.contains(leisure)) {
      return "park";
    }
    Set<String> parkLanduse = Set.of(
        "grass", "forest", "meadow", "recreation_ground", "village_green", "cemetery", "farmland",
        "orchard", "vineyard");
    if (parkLanduse.contains(landuse) || "wood".equals(natural) || "scrub".equals(natural)
        || "wetland".equals(natural)) {
      return "park";
    }
    Set<String> lotLanduse = Set.of("commercial", "industrial", "retail", "military");
    if (lotLanduse.contains(landuse)) {
      return "lot";
    }
    return null;
  }

  private static String strTag(Map<String, Object> tags, String key) {
    Object v = tags.get(key);
    return v == null ? "" : String.valueOf(v);
  }

  // ---- Normalized cell JSON (Overpass-sourced cells persisted on disk) ----

  private static void persistNormalizedCell(MapModel.CellData cell) {
    try {
      Path path = overpassCellPath(cell.x, cell.y);
      Files.createDirectories(path.getParent());
      Files.writeString(path, cellToNormalizedJson(cell), StandardCharsets.UTF_8);
    } catch (IOException ignored) {
      // cache write failures are non-fatal
    }
  }

  private static String cellToNormalizedJson(MapModel.CellData cell) {
    StringBuilder sb = new StringBuilder(1 << 14);
    sb.append("{\"z\":").append(cell.zoom)
        .append(",\"x\":").append(cell.x)
        .append(",\"y\":").append(cell.y)
        .append(",\"source\":\"").append(cell.source).append("\",\"roads\":[");
    for (int i = 0; i < cell.roads.size(); i++) {
      MapModel.Road r = cell.roads.get(i);
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{\"c\":\"").append(esc(r.clazz)).append("\",\"n\":\"").append(esc(r.name))
          .append("\",\"ow\":").append(r.oneway ? 1 : 0).append(",\"p\":");
      appendPts(sb, r.pts);
      sb.append('}');
    }
    sb.append("],\"buildings\":[");
    for (int i = 0; i < cell.buildings.size(); i++) {
      MapModel.Building b = cell.buildings.get(i);
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{\"h\":").append(round1(b.heightM)).append(",\"p\":");
      appendPts(sb, b.pts);
      sb.append('}');
    }
    sb.append("],\"areas\":[");
    for (int i = 0; i < cell.areas.size(); i++) {
      MapModel.Area a = cell.areas.get(i);
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{\"k\":\"").append(esc(a.kind)).append("\",\"p\":");
      appendPts(sb, a.pts);
      sb.append('}');
    }
    sb.append("],\"pois\":[");
    for (int i = 0; i < cell.pois.size(); i++) {
      MapModel.Poi p = cell.pois.get(i);
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{\"n\":\"").append(esc(p.name)).append("\",\"k\":\"").append(esc(p.kind))
          .append("\",\"lat\":").append(round6(p.lat)).append(",\"lon\":").append(round6(p.lon)).append('}');
    }
    sb.append("]}");
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  private static MapModel.CellData cellFromNormalizedJson(String json, int x, int y) {
    Object rootObj = MiniJson.parse(json);
    if (!(rootObj instanceof Map)) {
      return null;
    }
    Map<String, Object> root = (Map<String, Object>) rootObj;
    MapModel.CellData cell = new MapModel.CellData(CELL_ZOOM, x, y);
    cell.source = String.valueOf(root.getOrDefault("source", "overpass"));
    if (root.get("roads") instanceof List) {
      for (Object o : (List<Object>) root.get("roads")) {
        if (!(o instanceof Map)) {
          continue;
        }
        Map<String, Object> m = (Map<String, Object>) o;
        MapModel.Road r = new MapModel.Road();
        r.clazz = String.valueOf(m.getOrDefault("c", "residential"));
        r.name = String.valueOf(m.getOrDefault("n", ""));
        r.oneway = m.get("ow") instanceof Number && ((Number) m.get("ow")).intValue() == 1;
        r.pts = toDoubleArray(m.get("p"));
        if (r.pts != null && r.pts.length >= 4) {
          cell.roads.add(r);
        }
      }
    }
    if (root.get("buildings") instanceof List) {
      for (Object o : (List<Object>) root.get("buildings")) {
        if (!(o instanceof Map)) {
          continue;
        }
        Map<String, Object> m = (Map<String, Object>) o;
        MapModel.Building b = new MapModel.Building();
        b.heightM = m.get("h") instanceof Number ? ((Number) m.get("h")).doubleValue() : 6.0;
        b.pts = toDoubleArray(m.get("p"));
        if (b.pts != null && b.pts.length >= 6) {
          cell.buildings.add(b);
        }
      }
    }
    if (root.get("areas") instanceof List) {
      for (Object o : (List<Object>) root.get("areas")) {
        if (!(o instanceof Map)) {
          continue;
        }
        Map<String, Object> m = (Map<String, Object>) o;
        MapModel.Area a = new MapModel.Area();
        a.kind = String.valueOf(m.getOrDefault("k", "other"));
        a.pts = toDoubleArray(m.get("p"));
        if (a.pts != null && a.pts.length >= 6) {
          cell.areas.add(a);
        }
      }
    }
    if (root.get("pois") instanceof List) {
      for (Object o : (List<Object>) root.get("pois")) {
        if (!(o instanceof Map)) {
          continue;
        }
        Map<String, Object> m = (Map<String, Object>) o;
        if (!(m.get("lat") instanceof Number) || !(m.get("lon") instanceof Number)) {
          continue;
        }
        MapModel.Poi p = new MapModel.Poi();
        p.name = String.valueOf(m.getOrDefault("n", ""));
        p.kind = String.valueOf(m.getOrDefault("k", ""));
        p.lat = ((Number) m.get("lat")).doubleValue();
        p.lon = ((Number) m.get("lon")).doubleValue();
        cell.pois.add(p);
      }
    }
    return cell;
  }

  @SuppressWarnings("unchecked")
  private static double[] toDoubleArray(Object o) {
    if (!(o instanceof List)) {
      return null;
    }
    List<Object> list = (List<Object>) o;
    double[] out = new double[list.size()];
    for (int i = 0; i < out.length; i++) {
      Object v = list.get(i);
      if (!(v instanceof Number)) {
        return null;
      }
      out[i] = ((Number) v).doubleValue();
    }
    return out;
  }

  // ---- Scene JSON for the on-device 3D renderer ----

  /**
   * Resolution filter: picks the ladder zoom whose tile grid covers the
   * requested radius, so zoomed-out scenes use generalized low-zoom planet
   * layers instead of thousands of street-level cells.
   */
  static int zoomForRadius(double lat, double radiusM) {
    double cosLat = Math.max(0.08, Math.cos(Math.toRadians(lat)));
    for (int z : ZOOM_LADDER) {
      double tileSpanM = EARTH_CIRCUMFERENCE_M * cosLat / (1L << z);
<<<<<<< HEAD
      if (tileSpanM * SCENE_MAX_TILES_PER_AXIS >= 2.0 * radiusM) {
=======
      if (tileSpanM * sceneMaxTilesPerAxis(z) >= 2.0 * radiusM) {
>>>>>>> feature/integrate-waze-and-service-hardening
        return z;
      }
    }
    return ZOOM_LADDER[ZOOM_LADDER.length - 1];
  }

<<<<<<< HEAD
=======
  private static int sceneMaxTilesPerAxis(int zoom) {
    // Keep close-in requests lighter for mobile rendering, but allow broader
    // route-scale views to include more surrounding context.
    return zoom >= 14 ? SCENE_MAX_TILES_PER_AXIS : 7;
  }

>>>>>>> feature/integrate-waze-and-service-hardening
  private static int snapToLadder(int zoom) {
    int best = ZOOM_LADDER[0];
    int bestDiff = Integer.MAX_VALUE;
    for (int z : ZOOM_LADDER) {
      int diff = Math.abs(z - zoom);
      if (diff < bestDiff) {
        bestDiff = diff;
        best = z;
      }
    }
    return best;
  }

<<<<<<< HEAD
  /** Per-zoom feature class filter (roads thin out as the map zooms out). */
  private static boolean includeRoadAtZoom(String clazz, int z) {
    if (z >= 14) {
=======
  /** Per-zoom road filter. Minor classes are dropped earlier so low-zoom views emphasize major roads. */
  private static boolean includeRoadAtZoom(String clazz, int z) {
    if (z >= 15) {
>>>>>>> feature/integrate-waze-and-service-hardening
      return true;
    }
    switch (clazz) {
      case "motorway":
        return true;
      case "primary":
<<<<<<< HEAD
        return z >= 7;
      case "secondary":
      case "rail":
        return z >= 10;
      case "tertiary":
        return z >= 11;
      case "residential":
        return z >= 12;
      default: // service, path
        return false;
=======
        return z >= 11;
      case "secondary":
      case "rail":
        return z >= 12;
      case "tertiary":
        return z >= 13;
      case "residential":
        return z >= 14;
      default: // service, path
        return z >= 15;
>>>>>>> feature/integrate-waze-and-service-hardening
    }
  }

  private static boolean includeBuildingsAtZoom(int z) {
<<<<<<< HEAD
    return z >= 13;
  }

  private static boolean includeAreaAtZoom(String kind, int z) {
    if (z >= 13) {
      return true;
    }
    return "water".equals(kind) || (z >= 9 && "park".equals(kind));
=======
    return z >= 12;
  }

  private static boolean includeAreaAtZoom(String kind, int z) {
    return true;
>>>>>>> feature/integrate-waze-and-service-hardening
  }

  /** Smallest feature bbox span (degrees) worth emitting at this zoom (~sub-pixel cull). */
  private static double minFeatureSpanDeg(int zoom) {
<<<<<<< HEAD
    return zoom >= 14 ? 0.0 : (360.0 / (1 << zoom)) / 300.0;
=======
    return zoom >= 14
        ? (360.0 / (1 << zoom)) / 1200.0
        : (360.0 / (1 << zoom)) / 500.0;
>>>>>>> feature/integrate-waze-and-service-hardening
  }

  /** Minimum spacing (degrees) between emitted points at this zoom. */
  private static double minPointSpacingDeg(int zoom) {
    if (zoom >= 14) {
<<<<<<< HEAD
      return 0.0;
    }
    return (360.0 / (1 << zoom)) / (zoom >= 13 ? 400.0 : 250.0);
=======
      return (360.0 / (1 << zoom)) / 1600.0;
    }
    return (360.0 / (1 << zoom)) / (zoom >= 13 ? 650.0 : 420.0);
>>>>>>> feature/integrate-waze-and-service-hardening
  }

  /** Coordinate decimals needed at this zoom (fewer digits = lighter payloads). */
  private static int coordDecimals(int zoom) {
    if (zoom >= 14) {
      return 6;
    }
    if (zoom >= 11) {
      return 5;
    }
    if (zoom >= 7) {
      return 4;
    }
    return 3;
  }

  private static boolean spansEnough(double[] pts, double minSpanDeg) {
    if (minSpanDeg <= 0) {
      return true;
    }
    double minLat = Double.MAX_VALUE;
    double maxLat = -Double.MAX_VALUE;
    double minLon = Double.MAX_VALUE;
    double maxLon = -Double.MAX_VALUE;
    for (int i = 0; i + 1 < pts.length; i += 2) {
      minLat = Math.min(minLat, pts[i]);
      maxLat = Math.max(maxLat, pts[i]);
      minLon = Math.min(minLon, pts[i + 1]);
      maxLon = Math.max(maxLon, pts[i + 1]);
    }
    return (maxLat - minLat) >= minSpanDeg || (maxLon - minLon) >= minSpanDeg;
  }

  /** Drops points closer than minSpacing to the previously kept point (keeps endpoints). */
  private static double[] decimate(double[] pts, double minSpacingDeg) {
    if (minSpacingDeg <= 0 || pts.length <= 6) {
      return pts;
    }
    double[] out = new double[pts.length];
    int n = 0;
    double lastLat = 0;
    double lastLon = 0;
    int lastIdx = pts.length - 2;
    for (int i = 0; i + 1 < pts.length; i += 2) {
      double lat = pts[i];
      double lon = pts[i + 1];
      boolean keep = n == 0
          || i == lastIdx
          || Math.abs(lat - lastLat) + Math.abs(lon - lastLon) >= minSpacingDeg;
      if (keep) {
        out[n * 2] = lat;
        out[n * 2 + 1] = lon;
        lastLat = lat;
        lastLon = lon;
        n++;
      }
    }
    if (n * 2 == pts.length) {
      return pts;
    }
    double[] trimmed = new double[n * 2];
    System.arraycopy(out, 0, trimmed, 0, n * 2);
    return trimmed;
  }

  private static boolean includePoiAtZoom(String kind, int z) {
    if (z >= 14) {
      return true;
    }
    // Zoomed out: only place labels (cities/towns from the places layer).
    return kind != null && kind.startsWith("place");
  }

  static String sceneJson(double lat, double lon, double radiusM) {
    return sceneJson(lat, lon, radiusM, 0);
  }

  static String sceneJson(double lat, double lon, double radiusM, int zoomOverride) {
    double radius = Double.isFinite(radiusM) && radiusM > 50 ? Math.min(radiusM, MAX_SCENE_RADIUS_M) : DEFAULT_SCENE_RADIUS_M;
    int zoom = zoomOverride >= 3 && zoomOverride <= CELL_ZOOM
        ? snapToLadder(zoomOverride)
        : zoomForRadius(lat, radius);
<<<<<<< HEAD
    List<MapModel.CellData> cells = collectCells(lat, lon, radius, SCENE_MAX_TILES_PER_AXIS, zoom);
=======
    evictFinerZoomLayers(zoom);
    List<MapModel.CellData> cells = collectCells(lat, lon, radius, sceneMaxTilesPerAxis(zoom), zoom);
>>>>>>> feature/integrate-waze-and-service-hardening
    double latPad = (radius * 1.2) / 110540.0;
    double lonPad = (radius * 1.2) / (111320.0 * Math.max(0.2, Math.cos(Math.toRadians(lat))));
    double minLat = lat - latPad;
    double maxLat = lat + latPad;
    double minLon = lon - lonPad;
    double maxLon = lon + lonPad;

    StringBuilder sb = new StringBuilder(1 << 16);
    sb.append("{\"status\":\"ok\",\"ts\":\"").append(Instant.now()).append("\",");
    sb.append("\"center\":{\"lat\":").append(round6(lat)).append(",\"lon\":").append(round6(lon)).append("},");
    sb.append("\"radius_m\":").append(Math.round(radius)).append(",");
    sb.append("\"zoom\":").append(zoom).append(",");
    sb.append("\"attribution\":\"").append(esc(ATTRIBUTION)).append("\",");
    sb.append("\"cells\":[");
    int roadCount = 0;
    int buildingCount = 0;
    int areaCount = 0;
    int poiCount = 0;
    for (int i = 0; i < cells.size(); i++) {
      MapModel.CellData c = cells.get(i);
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{\"z\":").append(c.zoom).append(",\"x\":").append(c.x).append(",\"y\":").append(c.y)
          .append(",\"source\":\"").append(esc(c.source)).append("\"}");
    }
    double minSpan = minFeatureSpanDeg(zoom);
    double minSpacing = minPointSpacingDeg(zoom);
    int decimals = coordDecimals(zoom);
    sb.append("],\"areas\":[");
    boolean first = true;
    for (MapModel.CellData c : cells) {
      for (MapModel.Area a : c.areas) {
<<<<<<< HEAD
        // Parks/landuse need a much larger footprint than water to survive zoom-out.
        double spanNeeded = "water".equals(a.kind) ? (zoom <= 12 ? minSpan * 3 : minSpan) : minSpan * 10;
        if (!includeAreaAtZoom(a.kind, zoom)
            || !anyPointInBox(a.pts, minLat, minLon, maxLat, maxLon)
=======
        double spanNeeded = minSpan;
        if (!includeAreaAtZoom(a.kind, zoom)
            || !intersectsView(a.pts, minLat, minLon, maxLat, maxLon)
>>>>>>> feature/integrate-waze-and-service-hardening
            || !spansEnough(a.pts, spanNeeded)) {
          continue;
        }
        double[] pts = decimate(a.pts, minSpacing);
        if (pts.length < 6) {
          continue;
        }
        if (!first) {
          sb.append(',');
        }
        first = false;
        areaCount++;
        sb.append("{\"k\":\"").append(esc(a.kind)).append("\",\"p\":");
        appendPts(sb, pts, decimals);
        sb.append('}');
      }
    }
    sb.append("],\"roads\":[");
    first = true;
    for (MapModel.CellData c : cells) {
      for (MapModel.Road r : c.roads) {
        if (!includeRoadAtZoom(r.clazz, zoom)
<<<<<<< HEAD
            || !anyPointInBox(r.pts, minLat, minLon, maxLat, maxLon)
            || !spansEnough(r.pts, minSpan)) {
=======
            || !intersectsView(r.pts, minLat, minLon, maxLat, maxLon)) {
>>>>>>> feature/integrate-waze-and-service-hardening
          continue;
        }
        double[] pts = decimate(r.pts, minSpacing);
        if (pts.length < 4) {
          continue;
        }
        if (!first) {
          sb.append(',');
        }
        first = false;
        roadCount++;
        sb.append("{\"c\":\"").append(esc(r.clazz)).append("\",\"n\":\"").append(esc(r.name))
            .append("\",\"ow\":").append(r.oneway ? 1 : 0).append(",\"p\":");
        appendPts(sb, pts, decimals);
        sb.append('}');
      }
    }
    sb.append("],\"buildings\":[");
    first = true;
    for (MapModel.CellData c : cells) {
      if (!includeBuildingsAtZoom(zoom)) {
        break;
      }
      for (MapModel.Building b : c.buildings) {
<<<<<<< HEAD
        if (!anyPointInBox(b.pts, minLat, minLon, maxLat, maxLon)) {
=======
        if (!intersectsView(b.pts, minLat, minLon, maxLat, maxLon)
            || !spansEnough(b.pts, minSpan)) {
          continue;
        }
        double[] pts = decimate(b.pts, minSpacing);
        if (pts.length < 6) {
>>>>>>> feature/integrate-waze-and-service-hardening
          continue;
        }
        if (!first) {
          sb.append(',');
        }
        first = false;
        buildingCount++;
        sb.append("{\"h\":").append(round1(b.heightM)).append(",\"p\":");
<<<<<<< HEAD
        appendPts(sb, b.pts);
=======
        appendPts(sb, pts, decimals);
>>>>>>> feature/integrate-waze-and-service-hardening
        sb.append('}');
      }
    }
    sb.append("],\"pois\":[");
    first = true;
    for (MapModel.CellData c : cells) {
      for (MapModel.Poi p : c.pois) {
        if (!includePoiAtZoom(p.kind, zoom)
            || p.lat < minLat || p.lat > maxLat || p.lon < minLon || p.lon > maxLon) {
          continue;
        }
        if (!first) {
          sb.append(',');
        }
        first = false;
        poiCount++;
        sb.append("{\"n\":\"").append(esc(p.name)).append("\",\"k\":\"").append(esc(p.kind))
            .append("\",\"lat\":").append(round6(p.lat)).append(",\"lon\":").append(round6(p.lon)).append('}');
      }
    }
    sb.append("],\"counts\":{\"roads\":").append(roadCount)
        .append(",\"buildings\":").append(buildingCount)
        .append(",\"areas\":").append(areaCount)
        .append(",\"pois\":").append(poiCount).append("}}");
    return sb.toString();
  }

  private static List<MapModel.CellData> collectCells(
      double lat, double lon, double radiusM, int maxPerAxis, int zoom) {
    double latPad = radiusM / 110540.0;
    double lonPad = radiusM / (111320.0 * Math.max(0.2, Math.cos(Math.toRadians(lat))));
    int xMin = MapModel.lonToTileX(lon - lonPad, zoom);
    int xMax = MapModel.lonToTileX(lon + lonPad, zoom);
    int yMin = MapModel.latToTileY(lat + latPad, zoom);
    int yMax = MapModel.latToTileY(lat - latPad, zoom);
    while (xMax - xMin + 1 > maxPerAxis) {
      if ((xMax - xMin) % 2 == 0) {
        xMax--;
      } else {
        xMin++;
      }
    }
    while (yMax - yMin + 1 > maxPerAxis) {
      if ((yMax - yMin) % 2 == 0) {
        yMax--;
      } else {
        yMin++;
      }
    }
    List<MapModel.CellData> cells = new ArrayList<>();
    for (int x = xMin; x <= xMax; x++) {
      for (int y = yMin; y <= yMax; y++) {
        MapModel.CellData cell = getCell(zoom, x, y, true, zoom == CELL_ZOOM);
        if (cell != null) {
          cells.add(cell);
        }
      }
    }
    return cells;
  }

  private static boolean anyPointInBox(double[] pts, double minLat, double minLon, double maxLat, double maxLon) {
    if (pts == null) {
      return false;
    }
    for (int i = 0; i + 1 < pts.length; i += 2) {
      if (pts[i] >= minLat && pts[i] <= maxLat && pts[i + 1] >= minLon && pts[i + 1] <= maxLon) {
        return true;
      }
    }
    return false;
  }

<<<<<<< HEAD
=======
  private static boolean bboxIntersectsBox(double[] pts, double minLat, double minLon, double maxLat, double maxLon) {
    if (pts == null || pts.length < 2) {
      return false;
    }
    double featureMinLat = Double.MAX_VALUE;
    double featureMaxLat = -Double.MAX_VALUE;
    double featureMinLon = Double.MAX_VALUE;
    double featureMaxLon = -Double.MAX_VALUE;
    for (int i = 0; i + 1 < pts.length; i += 2) {
      featureMinLat = Math.min(featureMinLat, pts[i]);
      featureMaxLat = Math.max(featureMaxLat, pts[i]);
      featureMinLon = Math.min(featureMinLon, pts[i + 1]);
      featureMaxLon = Math.max(featureMaxLon, pts[i + 1]);
    }
    return featureMaxLat >= minLat && featureMinLat <= maxLat
        && featureMaxLon >= minLon && featureMinLon <= maxLon;
  }

  private static boolean intersectsView(double[] pts, double minLat, double minLon, double maxLat, double maxLon) {
    return anyPointInBox(pts, minLat, minLon, maxLat, maxLon)
        || bboxIntersectsBox(pts, minLat, minLon, maxLat, maxLon);
  }

>>>>>>> feature/integrate-waze-and-service-hardening
  // ---- Server-side verification renderer (Java2D) ----

  static byte[] renderPng(
      double lat,
      double lon,
      double metersPerPixel,
      double headingDeg,
      double tiltDeg,
      int w,
      int h,
      double[] routePts,
      Double destLat,
      Double destLon) throws IOException {
    double mpp = Double.isFinite(metersPerPixel) && metersPerPixel > 0.05 ? Math.min(metersPerPixel, 30000) : 1.2;
    int width = Math.max(64, Math.min(w, 1600));
    int height = Math.max(64, Math.min(h, 1600));
    double tilt = Math.max(0, Math.min(70, tiltDeg));
    double viewRadius = mpp * Math.hypot(width, height) * 0.7 + 80;
    int zoom = zoomForRadius(lat, viewRadius);
    List<MapModel.CellData> cells = collectCells(lat, lon, viewRadius, RENDER_MAX_TILES_PER_AXIS, zoom);

    double cosTilt = Math.cos(Math.toRadians(tilt));
    double sinTilt = Math.sin(Math.toRadians(tilt));
    double headRad = Math.toRadians(headingDeg);
    double cosH = Math.cos(headRad);
    double sinH = Math.sin(headRad);
    double mPerDegLat = 110540.0;
    double mPerDegLon = 111320.0 * Math.max(0.2, Math.cos(Math.toRadians(lat)));

    BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g.setColor(new Color(0xED, 0xEA, 0xE3));
      g.fillRect(0, 0, width, height);

      // Projection: ground (lat, lon, height) -> screen.
      // xr,yr = heading-rotated local meters; screenY compressed by tilt; height lifts.
      Projector proj = new Projector(lat, lon, mPerDegLat, mPerDegLon, cosH, sinH, cosTilt, sinTilt, mpp, width, height);

      // 1. Areas
      Map<String, Color> areaColors = Map.of(
          "park", new Color(0xC8, 0xE6, 0xB7),
          "water", new Color(0xA5, 0xC8, 0xE8),
          "sand", new Color(0xEF, 0xE3, 0xB8),
          "lot", new Color(0xD9, 0xD5, 0xCC),
          "civic", new Color(0xE5, 0xD9, 0xEC));
      for (MapModel.CellData cell : cells) {
        for (MapModel.Area a : cell.areas) {
          Color color = areaColors.get(a.kind);
          if (color == null) {
            continue;
          }
          Path2D.Double path = proj.toPath(a.pts, 0, true);
          g.setColor(color);
          g.fill(path);
        }
      }

      // 2. Roads: casings then fills, minor classes first.
      String[] classOrder = {"path", "rail", "service", "residential", "tertiary", "secondary", "primary", "motorway"};
      Map<String, Double> widthM = Map.of(
          "motorway", 16.0, "primary", 12.0, "secondary", 10.0, "tertiary", 9.0,
          "residential", 7.0, "service", 4.0, "path", 2.0, "rail", 2.5);
      Map<String, Color> fillColors = new HashMap<>();
      fillColors.put("motorway", new Color(0xFF, 0xC9, 0x66));
      fillColors.put("primary", new Color(0xFF, 0xE0, 0xA8));
      fillColors.put("secondary", new Color(0xFF, 0xF2, 0xCC));
      fillColors.put("tertiary", Color.WHITE);
      fillColors.put("residential", Color.WHITE);
      fillColors.put("service", new Color(0xF4, 0xF2, 0xEE));
      fillColors.put("path", new Color(0xD8, 0xCD, 0xBE));
      fillColors.put("rail", new Color(0x9A, 0x94, 0x8C));
      Color casing = new Color(0xB5, 0xB0, 0xA6);
      for (String clazz : classOrder) {
        float px = (float) Math.max(1.0, widthM.get(clazz) / mpp);
        boolean minor = "path".equals(clazz) || "rail".equals(clazz);
        if (!minor) {
          g.setColor(casing);
          g.setStroke(new BasicStroke(px + Math.max(1.5f, px * 0.3f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
          for (MapModel.CellData cell : cells) {
            for (MapModel.Road r : cell.roads) {
              if (clazz.equals(r.clazz)) {
                g.draw(proj.toPath(r.pts, 0, false));
              }
            }
          }
        }
        g.setColor(fillColors.get(clazz));
        BasicStroke stroke = "rail".equals(clazz)
            ? new BasicStroke(px, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] {8f, 6f}, 0f)
            : new BasicStroke(px, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        g.setStroke(stroke);
        for (MapModel.CellData cell : cells) {
          for (MapModel.Road r : cell.roads) {
            if (clazz.equals(r.clazz)) {
              g.draw(proj.toPath(r.pts, 0, false));
            }
          }
        }
      }

      // 3. Buildings: depth-sorted extrusion (painter's algorithm, far first).
      List<MapModel.Building> buildings = new ArrayList<>();
      for (MapModel.CellData cell : cells) {
        buildings.addAll(cell.buildings);
      }
      buildings.sort(Comparator.comparingDouble(b -> -proj.depthOf(b.pts)));
      Color roof = new Color(0xD6, 0xCF, 0xC2);
      Color outline = new Color(0x9A, 0x92, 0x84);
      for (MapModel.Building b : buildings) {
        int n = b.pts.length / 2;
        if (sinTilt > 0.01) {
          for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double[] p1 = proj.project(b.pts[i * 2], b.pts[i * 2 + 1], 0);
            double[] p2 = proj.project(b.pts[j * 2], b.pts[j * 2 + 1], 0);
            double[] p3 = proj.project(b.pts[j * 2], b.pts[j * 2 + 1], b.heightM);
            double[] p4 = proj.project(b.pts[i * 2], b.pts[i * 2 + 1], b.heightM);
            // Only draw front-facing walls (screen-space normal check).
            if (p2[0] - p1[0] == 0 && p2[1] - p1[1] == 0) {
              continue;
            }
            double angle = Math.atan2(p2[1] - p1[1], p2[0] - p1[0]);
            float shade = (float) (0.62 + 0.30 * Math.abs(Math.sin(angle)));
            Path2D.Double wall = new Path2D.Double();
            wall.moveTo(p1[0], p1[1]);
            wall.lineTo(p2[0], p2[1]);
            wall.lineTo(p3[0], p3[1]);
            wall.lineTo(p4[0], p4[1]);
            wall.closePath();
            g.setColor(new Color((int) (0xB4 * shade), (int) (0xAC * shade), (int) (0x9E * shade)));
            g.fill(wall);
          }
        }
        Path2D.Double top = proj.toPath(b.pts, b.heightM, true);
        g.setColor(roof);
        g.fill(top);
        g.setColor(outline);
        g.setStroke(new BasicStroke(1f));
        g.draw(top);
      }

      // 4. Route
      if (routePts != null && routePts.length >= 4) {
        Path2D.Double route = proj.toPath(routePts, 0.5, false);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(route);
        g.setColor(new Color(0x2B, 0x6B, 0xE6));
        g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(route);
      }

      // 5. Markers
      double[] devicePt = proj.project(lat, lon, 0);
      g.setColor(new Color(0x2B, 0x6B, 0xE6));
      g.fillOval((int) devicePt[0] - 8, (int) devicePt[1] - 8, 16, 16);
      g.setColor(Color.WHITE);
      g.setStroke(new BasicStroke(2.5f));
      g.drawOval((int) devicePt[0] - 8, (int) devicePt[1] - 8, 16, 16);
      if (destLat != null && destLon != null) {
        double[] destPt = proj.project(destLat, destLon, 0);
        g.setColor(new Color(0xD8, 0x3C, 0x3C));
        g.fillOval((int) destPt[0] - 7, (int) destPt[1] - 7, 14, 14);
        g.setColor(Color.WHITE);
        g.drawOval((int) destPt[0] - 7, (int) destPt[1] - 7, 14, 14);
      }

      // 6. Road labels (deduped by name)
      g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
      Set<String> labeled = new HashSet<>();
      for (MapModel.CellData cell : cells) {
        for (MapModel.Road r : cell.roads) {
          if (r.name.isEmpty() || labeled.contains(r.name) || labeled.size() >= 14) {
            continue;
          }
          int mid = (r.pts.length / 4) * 2;
          double[] pt = proj.project(r.pts[mid], r.pts[mid + 1], 0);
          if (pt[0] < 20 || pt[0] > width - 60 || pt[1] < 20 || pt[1] > height - 20) {
            continue;
          }
          labeled.add(r.name);
          g.setColor(new Color(255, 255, 255, 200));
          g.drawString(r.name, (float) pt[0] + 1, (float) pt[1] + 1);
          g.setColor(new Color(0x4A, 0x45, 0x3D));
          g.drawString(r.name, (float) pt[0], (float) pt[1]);
        }
      }

      // 7. Attribution (ODbL)
      g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
      String attribution = ATTRIBUTION;
      int attrWidth = g.getFontMetrics().stringWidth(attribution);
      g.setColor(new Color(255, 255, 255, 190));
      g.fillRect(width - attrWidth - 10, height - 16, attrWidth + 10, 16);
      g.setColor(new Color(0x55, 0x50, 0x48));
      g.drawString(attribution, width - attrWidth - 5, height - 4);
    } finally {
      g.dispose();
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
    ImageIO.write(img, "png", out);
    return out.toByteArray();
  }

  private static final class Projector {
    private final double lat0;
    private final double lon0;
    private final double mPerDegLat;
    private final double mPerDegLon;
    private final double cosH;
    private final double sinH;
    private final double cosTilt;
    private final double sinTilt;
    private final double mpp;
    private final double halfW;
    private final double halfH;

    Projector(
        double lat0, double lon0, double mPerDegLat, double mPerDegLon,
        double cosH, double sinH, double cosTilt, double sinTilt,
        double mpp, int w, int h) {
      this.lat0 = lat0;
      this.lon0 = lon0;
      this.mPerDegLat = mPerDegLat;
      this.mPerDegLon = mPerDegLon;
      this.cosH = cosH;
      this.sinH = sinH;
      this.cosTilt = cosTilt;
      this.sinTilt = sinTilt;
      this.mpp = mpp;
      this.halfW = w / 2.0;
      this.halfH = h / 2.0 + (h * 0.12 * sinTilt); // shift center down slightly when tilted
    }

    double[] project(double lat, double lon, double heightM) {
      double e = (lon - lon0) * mPerDegLon;
      double n = (lat - lat0) * mPerDegLat;
      double xr = e * cosH - n * sinH;
      double yr = e * sinH + n * cosH;
      double sx = halfW + xr / mpp;
      double sy = halfH - (yr * cosTilt) / mpp - (heightM * sinTilt) / mpp;
      return new double[] {sx, sy};
    }

    /** Depth for painter's algorithm: rotated forward distance of the centroid. */
    double depthOf(double[] pts) {
      double sumLat = 0;
      double sumLon = 0;
      int n = pts.length / 2;
      for (int i = 0; i < n; i++) {
        sumLat += pts[i * 2];
        sumLon += pts[i * 2 + 1];
      }
      double e = (sumLon / n - lon0) * mPerDegLon;
      double nn = (sumLat / n - lat0) * mPerDegLat;
      return e * sinH + nn * cosH;
    }

    Path2D.Double toPath(double[] pts, double heightM, boolean close) {
      Path2D.Double path = new Path2D.Double();
      int n = pts.length / 2;
      for (int i = 0; i < n; i++) {
        double[] p = project(pts[i * 2], pts[i * 2 + 1], heightM);
        if (i == 0) {
          path.moveTo(p[0], p[1]);
        } else {
          path.lineTo(p[0], p[1]);
        }
      }
      if (close) {
        path.closePath();
      }
      return path;
    }
  }

  // ---- Status + jurisdiction shard prefetch ----

  static String statusJson() {
<<<<<<< HEAD
=======
    // Opportunistically probe PMTiles readiness (PlanetTileStore has internal
    // backoff, so this remains lightweight when upstream is unavailable).
    PlanetTileStore.ready();
>>>>>>> feature/integrate-waze-and-service-hardening
    StringBuilder sb = new StringBuilder(1 << 12);
    sb.append("{\"status\":\"ok\",\"ts\":\"").append(Instant.now()).append("\",");
    sb.append("\"cell_zoom\":").append(CELL_ZOOM).append(",");
    sb.append("\"zoom_ladder\":[");
    for (int i = 0; i < ZOOM_LADDER.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(ZOOM_LADDER[i]);
    }
    sb.append("],");
    sb.append("\"cache_dir\":\"").append(esc(CACHE_DIR.toString())).append("\",");
    int lruSize;
    synchronized (CELL_LRU) {
      lruSize = CELL_LRU.size();
    }
    sb.append("\"cells_in_memory\":").append(lruSize).append(",");
    sb.append("\"planet\":").append(PlanetTileStore.statusJson()).append(",");
    sb.append("\"overpass\":{\"url\":\"").append(esc(OVERPASS_API_URL)).append("\",")
        .append("\"fetches\":").append(overpassFetchCount.get()).append(",")
        .append("\"failures\":").append(overpassFailureCount.get()).append(",")
        .append("\"last_error\":\"").append(esc(lastOverpassError)).append("\"},");
    sb.append("\"shards\":[");
    boolean first = true;
    try {
      if (Files.isDirectory(SHARDS_DIR)) {
        List<Path> shardDirs = new ArrayList<>();
        try (var stream = Files.list(SHARDS_DIR)) {
          stream.filter(Files::isDirectory).forEach(shardDirs::add);
        }
        shardDirs.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path dir : shardDirs) {
          long planetTiles = 0;
          long overpassCells = 0;
          long bytes = 0;
          try (var stream = Files.list(dir)) {
            for (Path f : (Iterable<Path>) stream::iterator) {
              String name = f.getFileName().toString();
              try {
                bytes += Files.size(f);
              } catch (IOException ignored) {
                // skip
              }
              if (name.endsWith(".mvt.gz")) {
                planetTiles++;
              } else if (name.endsWith(".json")) {
                overpassCells++;
              }
            }
          }
          if (!first) {
            sb.append(',');
          }
          first = false;
          sb.append("{\"state\":\"").append(esc(dir.getFileName().toString())).append("\",")
              .append("\"planet_tiles\":").append(planetTiles).append(",")
              .append("\"overpass_cells\":").append(overpassCells).append(",")
              .append("\"bytes\":").append(bytes).append('}');
        }
      }
    } catch (IOException ignored) {
      // report what we have
    }
    sb.append("],\"prefetch\":").append(prefetchStatusJson());
    sb.append(",\"attribution\":\"").append(esc(ATTRIBUTION)).append("\"}");
    return sb.toString();
  }

  static String prefetchStatusJson() {
    return "{\"phase\":\"" + esc(prefetchPhase) + "\","
        + "\"state\":\"" + esc(prefetchState) + "\","
        + "\"requested\":" + prefetchRequested + ","
        + "\"fetched\":" + prefetchFetched + ","
        + "\"already_cached\":" + prefetchCached + ","
        + "\"misses\":" + prefetchMisses + "}";
  }

  /**
   * Starts (or reports) an async jurisdiction shard prefetch. Tiles are pulled
   * from the planet extract around the state's center outward, capped so a
   * single request stays light.
   */
  static String startShardPrefetch(String stateCode, int maxTiles) {
    double[] bounds = MapModel.stateBounds(stateCode);
    if (bounds == null) {
      return "{\"status\":\"error\",\"error\":\"unknown_state\"}";
    }
    String normalized = stateCode.trim().toUpperCase(Locale.ROOT);
    int cap = maxTiles > 0 ? Math.min(maxTiles, 20000) : SHARD_PREFETCH_DEFAULT_MAX_TILES;
    synchronized (PREFETCH_LOCK) {
      if ("running".equals(prefetchPhase)) {
        return "{\"status\":\"already_running\",\"prefetch\":" + prefetchStatusJson() + "}";
      }
      prefetchPhase = "running";
      prefetchState = normalized;
      prefetchRequested = 0;
      prefetchFetched = 0;
      prefetchCached = 0;
      prefetchMisses = 0;
      Thread worker = new Thread(() -> runShardPrefetch(bounds, cap), "map-shard-prefetch");
      worker.setDaemon(true);
      worker.start();
    }
    return "{\"status\":\"started\",\"prefetch\":" + prefetchStatusJson() + "}";
  }

  private static void runShardPrefetch(double[] bounds, int cap) {
    try {
      int total = 0;
      // Phase 1: low-zoom pyramid over the state so zoomed-out (regional and
      // global) views are answered from the shard cache.
      int[] pyramidZooms = {5, 7, 9, 11};
      int pyramidBudget = Math.min(cap, 400);
      int pyramidDone = 0;
      for (int z : pyramidZooms) {
        int pxMin = MapModel.lonToTileX(bounds[1], z);
        int pxMax = MapModel.lonToTileX(bounds[3], z);
        int pyMin = MapModel.latToTileY(bounds[2], z);
        int pyMax = MapModel.latToTileY(bounds[0], z);
        for (int x = pxMin; x <= pxMax && pyramidDone < pyramidBudget; x++) {
          for (int y = pyMin; y <= pyMax && pyramidDone < pyramidBudget; y++) {
            pyramidDone++;
            prefetchRequested = ++total;
            if (Files.exists(tilePath(z, x, y))) {
              prefetchCached++;
              continue;
            }
            byte[] stored = PlanetTileStore.fetchTile(z, x, y);
            if (stored == null) {
              prefetchMisses++;
            } else {
              try {
                Path path = tilePath(z, x, y);
                Files.createDirectories(path.getParent());
                Files.write(path, stored);
                prefetchFetched++;
              } catch (IOException ex) {
                prefetchMisses++;
              }
              Thread.sleep(60);
            }
          }
        }
      }
      // Phase 2: street-detail (z15) ring walk outward from the state center.
      double centerLat = (bounds[0] + bounds[2]) / 2.0;
      double centerLon = (bounds[1] + bounds[3]) / 2.0;
      int cx = MapModel.lonToTileX(centerLon, CELL_ZOOM);
      int cy = MapModel.latToTileY(centerLat, CELL_ZOOM);
      int xMin = MapModel.lonToTileX(bounds[1], CELL_ZOOM);
      int xMax = MapModel.lonToTileX(bounds[3], CELL_ZOOM);
      int yMin = MapModel.latToTileY(bounds[2], CELL_ZOOM);
      int yMax = MapModel.latToTileY(bounds[0], CELL_ZOOM);
      int done = 0;
      for (int ring = 0; done < cap; ring++) {
        boolean anyInBounds = false;
        for (int dx = -ring; dx <= ring && done < cap; dx++) {
          for (int dy = -ring; dy <= ring && done < cap; dy++) {
            if (Math.max(Math.abs(dx), Math.abs(dy)) != ring) {
              continue;
            }
            int x = cx + dx;
            int y = cy + dy;
            if (x < xMin || x > xMax || y < yMin || y > yMax) {
              continue;
            }
            anyInBounds = true;
            done++;
            prefetchRequested = ++total;
            if (Files.exists(tilePath(CELL_ZOOM, x, y))) {
              prefetchCached++;
              continue;
            }
            byte[] stored = PlanetTileStore.fetchTile(CELL_ZOOM, x, y);
            if (stored == null) {
              prefetchMisses++;
            } else {
              try {
                Path path = tilePath(CELL_ZOOM, x, y);
                Files.createDirectories(path.getParent());
                Files.write(path, stored);
                prefetchFetched++;
              } catch (IOException ex) {
                prefetchMisses++;
              }
              Thread.sleep(150); // stay polite to the build host
            }
          }
        }
        if (!anyInBounds && ring > 2) {
          break;
        }
      }
      prefetchPhase = "done";
    } catch (Exception ex) {
      prefetchPhase = "error";
    }
  }

  // ---- Small helpers ----

  private static String httpGet(String urlString) {
    java.net.HttpURLConnection connection = null;
    try {
      connection = (java.net.HttpURLConnection) new java.net.URL(urlString).openConnection();
      connection.setConnectTimeout(OVERPASS_TIMEOUT_MS);
      connection.setReadTimeout(OVERPASS_TIMEOUT_MS);
      connection.setRequestProperty("User-Agent", USER_AGENT);
      connection.setRequestProperty("Accept", "application/json");
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

  private static void appendPts(StringBuilder sb, double[] pts) {
    appendPts(sb, pts, 6);
  }

  private static void appendPts(StringBuilder sb, double[] pts, int decimals) {
    double scale = Math.pow(10, decimals);
    sb.append('[');
    for (int i = 0; i < pts.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(Math.round(pts[i] * scale) / scale);
    }
    sb.append(']');
  }

  private static double round6(double v) {
    return Math.round(v * 1e6) / 1e6;
  }

  private static double round1(double v) {
    return Math.round(v * 10) / 10.0;
  }

  private static String esc(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(value.length() + 8);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"' || c == '\\') {
        sb.append('\\').append(c);
      } else if (c < 0x20) {
        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /** Minimal recursive-descent JSON parser (Map/List/String/Double/Boolean/null). */
  static final class MiniJson {
    private final String s;
    private int i;

    private MiniJson(String s) {
      this.s = s;
    }

    static Object parse(String s) {
      MiniJson p = new MiniJson(s);
      p.ws();
      Object v = p.value();
      return v;
    }

    private Object value() {
      char c = peek();
      if (c == '{') {
        return object();
      }
      if (c == '[') {
        return array();
      }
      if (c == '"') {
        return string();
      }
      if (c == 't') {
        expect("true");
        return Boolean.TRUE;
      }
      if (c == 'f') {
        expect("false");
        return Boolean.FALSE;
      }
      if (c == 'n') {
        expect("null");
        return null;
      }
      return number();
    }

    private Map<String, Object> object() {
      Map<String, Object> out = new HashMap<>();
      i++; // {
      ws();
      if (peek() == '}') {
        i++;
        return out;
      }
      while (true) {
        ws();
        String key = string();
        ws();
        i++; // :
        ws();
        out.put(key, value());
        ws();
        char c = peek();
        i++;
        if (c == '}') {
          return out;
        }
        // else comma, continue
      }
    }

    private List<Object> array() {
      List<Object> out = new ArrayList<>();
      i++; // [
      ws();
      if (peek() == ']') {
        i++;
        return out;
      }
      while (true) {
        ws();
        out.add(value());
        ws();
        char c = peek();
        i++;
        if (c == ']') {
          return out;
        }
      }
    }

    private String string() {
      StringBuilder sb = new StringBuilder();
      i++; // opening quote
      while (i < s.length()) {
        char c = s.charAt(i++);
        if (c == '"') {
          return sb.toString();
        }
        if (c == '\\' && i < s.length()) {
          char e = s.charAt(i++);
          switch (e) {
            case 'n':
              sb.append('\n');
              break;
            case 't':
              sb.append('\t');
              break;
            case 'r':
              sb.append('\r');
              break;
            case 'b':
              sb.append('\b');
              break;
            case 'f':
              sb.append('\f');
              break;
            case 'u':
              if (i + 4 <= s.length()) {
                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                i += 4;
              }
              break;
            default:
              sb.append(e);
          }
        } else {
          sb.append(c);
        }
      }
      return sb.toString();
    }

    private Double number() {
      int start = i;
      while (i < s.length()) {
        char c = s.charAt(i);
        if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
          i++;
        } else {
          break;
        }
      }
      try {
        return Double.parseDouble(s.substring(start, i));
      } catch (NumberFormatException ex) {
        return 0.0;
      }
    }

    private void expect(String word) {
      i += word.length();
    }

    private char peek() {
      return i < s.length() ? s.charAt(i) : '\0';
    }

    private void ws() {
      while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
        i++;
      }
    }
  }
}
