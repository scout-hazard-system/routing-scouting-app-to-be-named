import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exports cached proprietary map shards into plain-text, per-cell files.
 *
 * Output format:
 * - one UTF-8 .txt file per map cell
 * - first line: metadata JSON
 * - subsequent lines: JSONL feature rows (road/building/area/poi)
 *
 * This keeps data sharded and lightweight for VLM side-loading during drive time.
 */
final class ExportMapTextShards {
  private static final Pattern PLANET_TILE_PATTERN = Pattern.compile("^(\\d+)_(\\d+)_(\\d+)\\.mvt\\.gz$");
  private static final Pattern OVERPASS_CELL_PATTERN = Pattern.compile("^cell_(\\d+)_(\\d+)_(\\d+)\\.json$");
  private static final int DEFAULT_MAX_POINTS_PER_FEATURE = 80;
  private static final int ZHS_H_RESOLUTION = 9;
  private static final int ZHS_S_LEVEL = 12;

  private ExportMapTextShards() {}

  public static void main(String[] args) throws Exception {
    Path shardRoot =
        args.length > 0
            ? Paths.get(args[0])
            : Path.of(System.getProperty("user.home"), ".scanner_stream", "map_cache", "shards");
    Path outputRoot =
        args.length > 1
            ? Paths.get(args[1])
            : Path.of(System.getProperty("user.home"), "Desktop", "vlm_text_map_shards");
    int maxPoints =
        args.length > 2
            ? parsePositiveInt(args[2], DEFAULT_MAX_POINTS_PER_FEATURE)
            : DEFAULT_MAX_POINTS_PER_FEATURE;

    if (!Files.isDirectory(shardRoot)) {
      throw new IOException("Shard root is not a directory: " + shardRoot);
    }
    Files.createDirectories(outputRoot);

    long processed = 0;
    long failed = 0;
    long copiedOverpass = 0;
    long decodedPlanet = 0;

    try (var states = Files.list(shardRoot)) {
      for (Path stateDir : (Iterable<Path>) states.filter(Files::isDirectory)::iterator) {
        String state = stateDir.getFileName().toString();
        try (var files = Files.list(stateDir)) {
          for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
            processed++;
            String filename = file.getFileName().toString();
            Matcher planetMatcher = PLANET_TILE_PATTERN.matcher(filename);
            Matcher overpassMatcher = OVERPASS_CELL_PATTERN.matcher(filename);
            try {
              if (planetMatcher.matches()) {
                int z = Integer.parseInt(planetMatcher.group(1));
                int x = Integer.parseInt(planetMatcher.group(2));
                int y = Integer.parseInt(planetMatcher.group(3));
                MapModel.CellData cell = PlanetTileStore.decodeTile(Files.readAllBytes(file), z, x, y);
                if (cell == null) {
                  failed++;
                  continue;
                }
                writeCellText(cell, state, outputRoot, maxPoints, "planet");
                decodedPlanet++;
                continue;
              }
              if (overpassMatcher.matches()) {
                int z = Integer.parseInt(overpassMatcher.group(1));
                int x = Integer.parseInt(overpassMatcher.group(2));
                int y = Integer.parseInt(overpassMatcher.group(3));
                writeOverpassText(file, state, outputRoot, z, x, y);
                copiedOverpass++;
              }
            } catch (Exception ex) {
              failed++;
            }
          }
        }
      }
    }

    System.out.println(
        "{"
            + "\"status\":\"ok\","
            + "\"shard_root\":\""
            + escapeJson(shardRoot.toString())
            + "\","
            + "\"output_root\":\""
            + escapeJson(outputRoot.toString())
            + "\","
            + "\"processed_files\":"
            + processed
            + ",\"decoded_planet_tiles\":"
            + decodedPlanet
            + ",\"copied_overpass_cells\":"
            + copiedOverpass
            + ",\"failed\":"
            + failed
            + ",\"max_points_per_feature\":"
            + maxPoints
            + "}");
  }

  private static String zhsForCell(int z, int x, int y) {
    double lat = MapModel.tileToLat(y + 0.5, z);
    double lon = MapModel.tileToLon(x + 0.5, z);
    long[] axial = h3LikeAxial(lat, lon, ZHS_H_RESOLUTION);
    long[] faceIj = s2LikeFaceIj(lat, lon, ZHS_S_LEVEL);
    String zKey = "z/" + z + "/" + x + "/" + y;
    String hKey =
        "h3/"
            + ZHS_H_RESOLUTION
            + "/"
            + encodeSignedBase36(axial[0])
            + "/"
            + encodeSignedBase36(axial[1]);
    String sKey =
        "s2/"
            + ZHS_S_LEVEL
            + "/f"
            + faceIj[0]
            + "/"
            + Long.toString(faceIj[1], 36)
            + "/"
            + Long.toString(faceIj[2], 36);
    String unified = zKey + "|" + hKey + "|" + sKey;
    return "{"
        + "\"key\":\"" + escapeJson(unified) + "\","
        + "\"z_key\":\"" + escapeJson(zKey) + "\","
        + "\"h_key\":\"" + escapeJson(hKey) + "\","
        + "\"s_key\":\"" + escapeJson(sKey) + "\""
        + "}";
  }

  private static long[] h3LikeAxial(double lat, double lon, int resolution) {
    double clampedLat = Math.max(-85.0, Math.min(85.0, lat));
    double latRad = Math.toRadians(clampedLat);
    double lonRad = Math.toRadians(lon);
    double metersY = 6371000.0 * latRad;
    double metersX = 6371000.0 * lonRad * Math.cos(latRad);
    double edgeMeters = 1100000.0 / Math.pow(Math.sqrt(7.0), Math.max(0, resolution));
    edgeMeters = Math.max(1.0, edgeMeters);
    double q = (Math.sqrt(3.0) / 3.0 * metersX - 1.0 / 3.0 * metersY) / edgeMeters;
    double r = (2.0 / 3.0 * metersY) / edgeMeters;
    return axialRound(q, r);
  }

  private static long[] axialRound(double q, double r) {
    double x = q;
    double z = r;
    double y = -x - z;
    long rx = Math.round(x);
    long ry = Math.round(y);
    long rz = Math.round(z);
    double dx = Math.abs(rx - x);
    double dy = Math.abs(ry - y);
    double dz = Math.abs(rz - z);
    if (dx > dy && dx > dz) {
      rx = -ry - rz;
    } else if (dy > dz) {
      ry = -rx - rz;
    } else {
      rz = -rx - ry;
    }
    return new long[] {rx, rz};
  }

  private static long[] s2LikeFaceIj(double lat, double lon, int level) {
    double latRad = Math.toRadians(Math.max(-89.999999, Math.min(89.999999, lat)));
    double lonRad = Math.toRadians(lon);
    double x = Math.cos(latRad) * Math.cos(lonRad);
    double y = Math.cos(latRad) * Math.sin(lonRad);
    double z = Math.sin(latRad);
    double ax = Math.abs(x);
    double ay = Math.abs(y);
    double az = Math.abs(z);
    int face;
    double u;
    double v;
    if (ax >= ay && ax >= az) {
      if (x >= 0) {
        face = 0;
        u = y / x;
        v = z / x;
      } else {
        face = 3;
        u = z / x;
        v = y / x;
      }
    } else if (ay >= az) {
      if (y >= 0) {
        face = 1;
        u = -x / y;
        v = z / y;
      } else {
        face = 4;
        u = z / y;
        v = -x / y;
      }
    } else {
      if (z >= 0) {
        face = 2;
        u = -x / z;
        v = -y / z;
      } else {
        face = 5;
        u = -y / z;
        v = -x / z;
      }
    }
    double s = 0.5 + (Math.atan(u) / Math.PI);
    double t = 0.5 + (Math.atan(v) / Math.PI);
    int n = 1 << Math.min(30, Math.max(0, level));
    long i = Math.max(0L, Math.min((long) n - 1L, (long) Math.floor(s * n)));
    long j = Math.max(0L, Math.min((long) n - 1L, (long) Math.floor(t * n)));
    return new long[] {face, i, j};
  }

  private static String encodeSignedBase36(long value) {
    if (value < 0) {
      return "n" + Long.toString(-value, 36);
    }
    return "p" + Long.toString(value, 36);
  }

  private static void writeOverpassText(
      Path sourceJson, String state, Path outputRoot, int z, int x, int y) throws IOException {
    Path outDir = outputRoot.resolve(state).resolve("z" + z).resolve(Integer.toString(x));
    Files.createDirectories(outDir);
    Path out = outDir.resolve(y + ".txt");
    String raw = Files.readString(sourceJson, StandardCharsets.UTF_8);
    StringBuilder sb = new StringBuilder(raw.length() + 256);
    String zhs = zhsForCell(z, x, y);
    sb.append("{\"meta\":{")
        .append("\"state\":\"").append(escapeJson(state)).append("\",")
        .append("\"z\":").append(z).append(",")
        .append("\"x\":").append(x).append(",")
        .append("\"y\":").append(y).append(",")
        .append("\"zhs\":").append(zhs).append(",")
        .append("\"source\":\"overpass\",")
        .append("\"format\":\"normalized_json_raw\"")
        .append("}}")
        .append('\n');
    sb.append(raw.trim()).append('\n');
    Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
  }

  private static void writeCellText(
      MapModel.CellData cell, String state, Path outputRoot, int maxPointsPerFeature, String sourceHint)
      throws IOException {
    Path outDir = outputRoot.resolve(state).resolve("z" + cell.zoom).resolve(Integer.toString(cell.x));
    Files.createDirectories(outDir);
    Path out = outDir.resolve(cell.y + ".txt");

    StringBuilder sb = new StringBuilder(1 << 14);
    String zhs = zhsForCell(cell.zoom, cell.x, cell.y);
    sb.append("{\"meta\":{")
        .append("\"state\":\"").append(escapeJson(state)).append("\",")
        .append("\"z\":").append(cell.zoom).append(",")
        .append("\"x\":").append(cell.x).append(",")
        .append("\"y\":").append(cell.y).append(",")
        .append("\"zhs\":").append(zhs).append(",")
        .append("\"source\":\"").append(escapeJson(cell.source == null ? sourceHint : cell.source)).append("\",")
        .append("\"roads\":").append(cell.roads.size()).append(",")
        .append("\"buildings\":").append(cell.buildings.size()).append(",")
        .append("\"areas\":").append(cell.areas.size()).append(",")
        .append("\"pois\":").append(cell.pois.size())
        .append("}}")
        .append('\n');

    for (MapModel.Road road : cell.roads) {
      sb.append("{\"type\":\"road\",\"class\":\"")
          .append(escapeJson(road.clazz))
          .append("\",\"name\":\"")
          .append(escapeJson(road.name))
          .append("\",\"oneway\":")
          .append(road.oneway)
          .append(",\"pts\":\"")
          .append(escapeJson(ptsToCompactString(road.pts, maxPointsPerFeature)))
          .append("\"}")
          .append('\n');
    }
    for (MapModel.Building building : cell.buildings) {
      sb.append("{\"type\":\"building\",\"height_m\":")
          .append(round1(building.heightM))
          .append(",\"pts\":\"")
          .append(escapeJson(ptsToCompactString(building.pts, maxPointsPerFeature)))
          .append("\"}")
          .append('\n');
    }
    for (MapModel.Area area : cell.areas) {
      sb.append("{\"type\":\"area\",\"kind\":\"")
          .append(escapeJson(area.kind))
          .append("\",\"pts\":\"")
          .append(escapeJson(ptsToCompactString(area.pts, maxPointsPerFeature)))
          .append("\"}")
          .append('\n');
    }
    for (MapModel.Poi poi : cell.pois) {
      sb.append("{\"type\":\"poi\",\"name\":\"")
          .append(escapeJson(poi.name))
          .append("\",\"kind\":\"")
          .append(escapeJson(poi.kind))
          .append("\",\"lat\":")
          .append(round6(poi.lat))
          .append(",\"lon\":")
          .append(round6(poi.lon))
          .append("}")
          .append('\n');
    }
    Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
  }

  private static int parsePositiveInt(String raw, int fallback) {
    try {
      int n = Integer.parseInt(raw);
      return n > 0 ? n : fallback;
    } catch (Exception ignored) {
      return fallback;
    }
  }

  private static String ptsToCompactString(double[] pts, int maxPoints) {
    if (pts == null || pts.length < 2) {
      return "";
    }
    int total = pts.length / 2;
    int keep = Math.max(2, Math.min(total, maxPoints));
    int step = (int) Math.ceil((double) total / (double) keep);
    StringBuilder sb = new StringBuilder(keep * 22);
    int emitted = 0;
    for (int i = 0; i < total; i += step) {
      if (emitted > 0) {
        sb.append(';');
      }
      sb.append(round6(pts[i * 2])).append(',').append(round6(pts[i * 2 + 1]));
      emitted++;
      if (emitted >= keep) {
        break;
      }
    }
    if (emitted < keep && total > 0) {
      if (emitted > 0) {
        sb.append(';');
      }
      sb.append(round6(pts[(total - 1) * 2])).append(',').append(round6(pts[(total - 1) * 2 + 1]));
    }
    return sb.toString();
  }

  private static String round1(double v) {
    return String.format(Locale.ROOT, "%.1f", v);
  }

  private static String round6(double v) {
    return String.format(Locale.ROOT, "%.6f", v);
  }

  private static String escapeJson(String in) {
    if (in == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(in.length() + 16);
    for (int i = 0; i < in.length(); i++) {
      char c = in.charAt(i);
      switch (c) {
        case '"':
          sb.append("\\\"");
          break;
        case '\\':
          sb.append("\\\\");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\t':
          sb.append("\\t");
          break;
        default:
          if (c < 0x20) {
            sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
      }
    }
    return sb.toString();
  }
}
