// Copyright 2026 Scout Project Contributors
// Licensed under the Apache License, Version 2.0
import com.sun.net.httpserver.HttpExchange;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Paywall / subscription gate for mesh clients (especially Android APs).
 *
 * <p>Mesh join (entry_token) only grants transport. Paid navigation APIs require a
 * device-bound subscription token in {@code X-Scout-Subscription} (or configured
 * header). Admin requests bypass via {@link ScoutAdminAuth}.
 *
 * <p>Invariant: mesh transport ≠ entitlement.
 */
public final class ScoutSubscriptionAuth {
  public static final String DEFAULT_HEADER = "X-Scout-Subscription";

  /** Diagnostics / enroll only — never full nav. */
  public static final Set<String> PAYWALL_EXEMPT =
      Set.of(
          "/api/health",
          "/api/public/share/eta",
          "/api/mesh/enroll",
          "/api/mesh/profile",
          "/api/admin/status");

  private static final ReentrantLock LOCK = new ReentrantLock();
  private static final SecureRandom RANDOM = new SecureRandom();

  private ScoutSubscriptionAuth() {}

  public static boolean required() {
    return ScoutMeshControl.subscriptionRequired();
  }

  public static String headerName() {
    return ScoutMeshControl.subscriptionHeader();
  }

  public static boolean isExemptPath(String path) {
    return path != null && PAYWALL_EXEMPT.contains(path);
  }

  /**
   * True when subscription is not required, path is exempt, caller is admin, or a
   * valid device-bound subscription token is present.
   */
  public static boolean isAuthorized(HttpExchange exchange, String path) {
    if (!required()) {
      return true;
    }
    if (isExemptPath(path)) {
      return true;
    }
    if (ScoutAdminAuth.isAuthorizedAdmin(exchange, path)
        || ScoutAdminAuth.isAuthorizedAdminAny(exchange)) {
      return true;
    }
    String token = extractToken(exchange);
    if (token.isBlank()) {
      return false;
    }
    SubRecord rec = findByToken(token);
    if (rec == null || !rec.active) {
      return false;
    }
    if (rec.expiresAtEpochSec > 0 && Instant.now().getEpochSecond() > rec.expiresAtEpochSec) {
      return false;
    }
    // Optional device binding: if client sends X-Scout-Device-Id it must match.
    String deviceHeader = firstHeader(exchange, "X-Scout-Device-Id");
    if (deviceHeader != null && !deviceHeader.isBlank()) {
      String id = deviceHeader.trim();
      if (!id.equals(rec.deviceId)) {
        return false;
      }
    }
    return true;
  }

  public static String paywallJson(String reason) {
    return "{"
        + "\"status\":\"paywall\","
        + "\"error\":\""
        + jsonEsc(reason == null ? "subscription_required" : reason)
        + "\","
        + "\"subscription\":{"
        + "\"required\":true,"
        + "\"header\":\""
        + jsonEsc(headerName())
        + "\","
        + "\"hint\":\"Unregistered Android clients are mesh access points only; present an active device-bound subscription token after purchase/registration.\""
        + "}"
        + "}";
  }

  /** Issue or rotate a device-bound subscription token (admin operation). */
  public static IssueResult issue(String deviceId, long ttlSeconds) {
    String id = sanitizeDeviceId(deviceId);
    if (id == null) {
      return IssueResult.error(400, "invalid_device_id");
    }
    LOCK.lock();
    try {
      ensureStore();
      Map<String, SubRecord> all = loadAll();
      // Revoke prior tokens for device
      for (SubRecord r : all.values()) {
        if (id.equals(r.deviceId)) {
          r.active = false;
        }
      }
      String token = "sub_" + randomToken();
      long exp =
          ttlSeconds > 0
              ? Instant.now().getEpochSecond() + ttlSeconds
              : 0L; // 0 = no expiry
      SubRecord rec = new SubRecord(token, id, true, exp, Instant.now().toString());
      all.put(token, rec);
      saveAll(all);
      String body =
          "{"
              + "\"status\":\"ok\","
              + "\"device_id\":\""
              + jsonEsc(id)
              + "\","
              + "\"token\":\""
              + jsonEsc(token)
              + "\","
              + "\"header\":\""
              + jsonEsc(headerName())
              + "\","
              + "\"expires_at_epoch_sec\":"
              + exp
              + ","
              + "\"active\":true"
              + "}";
      return IssueResult.ok(body);
    } catch (Exception ex) {
      return IssueResult.error(500, "issue_failed");
    } finally {
      LOCK.unlock();
    }
  }

  /** Revoke all subscription tokens for a device (e.g. mesh peer removed). */
  public static int revokeDevice(String deviceId) {
    String id = sanitizeDeviceId(deviceId);
    if (id == null) {
      return 0;
    }
    LOCK.lock();
    try {
      ensureStore();
      Map<String, SubRecord> all = loadAll();
      int n = 0;
      for (SubRecord r : all.values()) {
        if (id.equals(r.deviceId) && r.active) {
          r.active = false;
          n++;
        }
      }
      if (n > 0) {
        saveAll(all);
      }
      return n;
    } catch (Exception ex) {
      return 0;
    } finally {
      LOCK.unlock();
    }
  }

  public static String publicPolicyJson() {
    return "{"
        + "\"required\":"
        + (required() ? "true" : "false")
        + ","
        + "\"header\":\""
        + jsonEsc(headerName())
        + "\","
        + "\"device_header\":\"X-Scout-Device-Id\","
        + "\"exempt_paths\":"
        + stringSetJson(PAYWALL_EXEMPT)
        + ","
        + "\"hint\":\"Mesh join is not a subscription. Android APs need a device-bound token for paid nav APIs.\""
        + "}";
  }

  private static SubRecord findByToken(String token) {
    LOCK.lock();
    try {
      if (!Files.isRegularFile(storePath())) {
        return null;
      }
      return loadAll().get(token);
    } catch (Exception ex) {
      return null;
    } finally {
      LOCK.unlock();
    }
  }

  private static void ensureStore() throws Exception {
    Path dir = storePath().getParent();
    if (dir != null) {
      Files.createDirectories(dir);
    }
    if (!Files.isRegularFile(storePath())) {
      Files.writeString(
          storePath(),
          "# token\tdevice_id\tactive\texpires_at_epoch_sec\tupdated_at\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE);
    }
  }

  private static Path storePath() {
    String override = env("SCOUT_SUBSCRIPTION_STORE", "").trim();
    if (!override.isEmpty()) {
      return Path.of(override);
    }
    // Same directory as the admin token and mesh allocator (was a
    // user.home/Desktop guess that diverged from SCOUT_REPO_ROOT).
    return ScoutPaths.meshStateDir().resolve("subscriptions.tsv");
  }

  private static Map<String, SubRecord> loadAll() throws Exception {
    Map<String, SubRecord> out = new LinkedHashMap<>();
    Path path = storePath();
    if (!Files.isRegularFile(path)) {
      return out;
    }
    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      String[] p = line.split("\t");
      if (p.length < 5) {
        continue;
      }
      boolean active = "1".equals(p[2]) || "true".equalsIgnoreCase(p[2]);
      long exp = 0L;
      try {
        exp = Long.parseLong(p[3]);
      } catch (NumberFormatException ignored) {
        exp = 0L;
      }
      out.put(p[0], new SubRecord(p[0], p[1], active, exp, p[4]));
    }
    return out;
  }

  private static void saveAll(Map<String, SubRecord> all) throws Exception {
    List<String> lines = new ArrayList<>();
    lines.add("# token\tdevice_id\tactive\texpires_at_epoch_sec\tupdated_at");
    for (SubRecord r : all.values()) {
      lines.add(
          r.token
              + "\t"
              + r.deviceId
              + "\t"
              + (r.active ? "1" : "0")
              + "\t"
              + r.expiresAtEpochSec
              + "\t"
              + r.updatedAt);
    }
    Files.writeString(
        storePath(),
        String.join("\n", lines) + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
  }

  private static String extractToken(HttpExchange exchange) {
    String header = firstHeader(exchange, headerName());
    if (header != null && !header.isBlank()) {
      String v = header.trim();
      if (v.regionMatches(true, 0, "Bearer ", 0, 7)) {
        return v.substring(7).trim();
      }
      return v;
    }
    return "";
  }

  private static String firstHeader(HttpExchange exchange, String name) {
    try {
      return exchange.getRequestHeaders().getFirst(name);
    } catch (Exception ex) {
      return null;
    }
  }

  private static String sanitizeDeviceId(String raw) {
    if (raw == null) {
      return null;
    }
    String id = raw.trim();
    if (id.isEmpty() || id.length() > 128) {
      return null;
    }
    for (int i = 0; i < id.length(); i++) {
      char c = id.charAt(i);
      if (!(Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-' || c == ':')) {
        return null;
      }
    }
    return id;
  }

  private static String randomToken() {
    byte[] raw = new byte[32];
    RANDOM.nextBytes(raw);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }

  private static String env(String key, String def) {
    String v = System.getenv(key);
    return v == null || v.isBlank() ? def : v;
  }

  private static String jsonEsc(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(value.length() + 8);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
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

  private static String stringSetJson(Set<String> values) {
    StringBuilder sb = new StringBuilder("[");
    boolean first = true;
    List<String> sorted = new ArrayList<>(values);
    sorted.sort(String::compareTo);
    for (String v : sorted) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append('"').append(jsonEsc(v)).append('"');
    }
    sb.append(']');
    return sb.toString();
  }

  private static final class SubRecord {
    final String token;
    final String deviceId;
    boolean active;
    final long expiresAtEpochSec;
    final String updatedAt;

    SubRecord(String token, String deviceId, boolean active, long expiresAtEpochSec, String updatedAt) {
      this.token = token;
      this.deviceId = deviceId;
      this.active = active;
      this.expiresAtEpochSec = expiresAtEpochSec;
      this.updatedAt = updatedAt;
    }
  }

  public static final class IssueResult {
    public final int status;
    public final String body;

    private IssueResult(int status, String body) {
      this.status = status;
      this.body = body;
    }

    static IssueResult ok(String body) {
      return new IssueResult(200, body);
    }

    static IssueResult error(int status, String code) {
      return new IssueResult(status, "{\"status\":\"error\",\"error\":\"" + jsonEsc(code) + "\"}");
    }
  }
}
