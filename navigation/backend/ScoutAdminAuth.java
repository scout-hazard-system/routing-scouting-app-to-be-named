// Copyright 2026 Scout Project Contributors
// Licensed under the Apache License, Version 2.0
import com.sun.net.httpserver.HttpExchange;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Out-of-band admin authentication for machines that must NOT join Scout Mesh /
 * WireGuard / Tailscale (e.g. a Windows remote-job PC).
 *
 * <p>Model:
 *
 * <ul>
 *   <li>Long-lived admin device token (header {@code X-Scout-Admin-Token})
 *   <li>Optional CIDR allowlist ({@code SCOUT_ADMIN_ALLOW_CIDRS}) — empty means
 *       token-only (still secret-gated)
 *   <li>Only a small set of admin endpoints accept this bypass; mesh membership
 *       is never granted
 * </ul>
 */
public final class ScoutAdminAuth {
  public static final String DEFAULT_HEADER = "X-Scout-Admin-Token";

  /** Endpoints callable with a valid admin token without being on the mesh. */
public static final Set<String> ADMIN_ENDPOINTS =
      Set.of(
          "/api/platform/dev/stack/manage",
          "/api/platform/providers/status",
          "/api/platform/llm/status",
          "/api/map/status",
          "/api/map/shard",
          "/api/health",
          "/api/mesh/profile",
          "/api/mesh/peer/revoke",
          "/api/admin/status",
          "/api/admin/gate/analyze",
          "/api/admin/subscription/issue",
          "/api/admin/subscription/revoke",
          "/api/admin/tokens/status");

  private static final SecureRandom RANDOM = new SecureRandom();

  private ScoutAdminAuth() {}

  public static boolean enabled() {
    return !adminToken().isBlank();
  }

  public static String headerName() {
    return env("SCOUT_ADMIN_TOKEN_HEADER", DEFAULT_HEADER).trim();
  }

  public static String adminToken() {
    String fromEnv = env("SCOUT_ADMIN_TOKEN", "").trim();
    if (!fromEnv.isBlank()) {
      return fromEnv;
    }
    Path file = tokenFilePath();
    try {
      if (Files.isRegularFile(file)) {
        return Files.readString(file, StandardCharsets.UTF_8).trim();
      }
    } catch (Exception ignored) {
      // fall through
    }
    return "";
  }

  public static boolean isAdminEndpoint(String path) {
    return path != null && ADMIN_ENDPOINTS.contains(path);
  }

  /**
   * Returns true when the request carries a valid admin token and (if configured)
   * comes from an allowed CIDR. Does not grant mesh access.
   */
  public static boolean isAuthorizedAdmin(HttpExchange exchange, String path) {
    if (!enabled() || !isAdminEndpoint(path)) {
      return false;
    }
    String expected = adminToken();
    if (expected.isBlank()) {
      return false;
    }
    String received = extractToken(exchange);
    if (received.isBlank() || !constantTimeEquals(expected, received)) {
      return false;
    }
    List<Cidr> allow = adminAllowCidrs();
    if (allow.isEmpty()) {
      // Token-only mode. Prefer setting SCOUT_ADMIN_ALLOW_CIDRS in production.
      return true;
    }
    String remote = remoteAddress(exchange);
    return matchesAnyCidr(remote, allow);
  }

  /**
   * Admin token valid for any path (subscription bypass, revoke, etc.). Still
   * subject to optional CIDR allowlist. Does not grant mesh membership.
   */
  public static boolean isAuthorizedAdminAny(HttpExchange exchange) {
    if (!enabled()) {
      return false;
    }
    String expected = adminToken();
    if (expected.isBlank()) {
      return false;
    }
    String received = extractToken(exchange);
    if (received.isBlank() || !constantTimeEquals(expected, received)) {
      return false;
    }
    List<Cidr> allow = adminAllowCidrs();
    if (allow.isEmpty()) {
      return true;
    }
    return matchesAnyCidr(remoteAddress(exchange), allow);
  }

  public static String publicStatusJson() {
    boolean on = enabled();
    return "{"
        + "\"enabled\":"
        + (on ? "true" : "false")
        + ","
        + "\"header\":\""
        + jsonEsc(headerName())
        + "\","
        + "\"vpn_required\":false,"
        + "\"mesh_required\":false,"
        + "\"endpoints\":"
        + stringSetJson(ADMIN_ENDPOINTS)
        + ","
        + "\"allow_cidrs_configured\":"
        + (adminAllowCidrs().isEmpty() ? "false" : "true")
        + ","
        + "\"hint\":\"Windows/job PCs administer the stack with X-Scout-Admin-Token over HTTPS; do not install WireGuard/Tailscale on those machines.\""
        + "}";
  }

  /** Create a new random admin token file if missing (hub operator helper). */
  public static String ensureTokenFile() throws Exception {
    Path file = tokenFilePath();
    Files.createDirectories(file.getParent());
    if (Files.isRegularFile(file)) {
      return Files.readString(file, StandardCharsets.UTF_8).trim();
    }
    byte[] raw = new byte[32];
    RANDOM.nextBytes(raw);
    String token = "sat_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    Files.writeString(file, token + "\n", StandardCharsets.UTF_8);
    try {
      file.toFile().setReadable(false, false);
      file.toFile().setWritable(false, false);
      file.toFile().setReadable(true, true);
      file.toFile().setWritable(true, true);
    } catch (Exception ignored) {
      // best effort perms
    }
    return token;
  }

  private static Path tokenFilePath() {
    String override = env("SCOUT_ADMIN_TOKEN_FILE", "").trim();
    if (!override.isEmpty()) {
      return Path.of(override);
    }
    return ScoutPaths.meshStateDir().resolve("admin_token");
  }

  private static String extractToken(HttpExchange exchange) {
    String header = exchange.getRequestHeaders().getFirst(headerName());
    if (header != null && !header.isBlank()) {
      String v = header.trim();
      if (v.regionMatches(true, 0, "Bearer ", 0, 7)) {
        return v.substring(7).trim();
      }
      return v;
    }
    // Also accept query for constrained PowerShell one-liners (discouraged).
    try {
      String q = exchange.getRequestURI() != null ? exchange.getRequestURI().getRawQuery() : null;
      if (q != null) {
        for (String part : q.split("&")) {
          int eq = part.indexOf('=');
          if (eq <= 0) {
            continue;
          }
          String k = java.net.URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8);
          if ("admin_token".equals(k) || "scout_admin_token".equals(k)) {
            return java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8).trim();
          }
        }
      }
    } catch (Exception ignored) {
      // ignore
    }
    return "";
  }

  private static String remoteAddress(HttpExchange exchange) {
    try {
      if (exchange.getRemoteAddress() != null && exchange.getRemoteAddress().getAddress() != null) {
        return exchange.getRemoteAddress().getAddress().getHostAddress();
      }
    } catch (Exception ignored) {
      // ignore
    }
    return "";
  }

  private static List<Cidr> adminAllowCidrs() {
    String raw = env("SCOUT_ADMIN_ALLOW_CIDRS", "").trim();
    List<Cidr> out = new ArrayList<>();
    if (raw.isBlank()) {
      return out;
    }
    for (String part : raw.split(",")) {
      String token = part == null ? "" : part.trim();
      if (token.isBlank()) {
        continue;
      }
      Cidr c = Cidr.parse(token);
      if (c != null) {
        out.add(c);
      }
    }
    return out;
  }

  private static boolean matchesAnyCidr(String ip, List<Cidr> cidrs) {
    if (ip == null || ip.isBlank()) {
      return false;
    }
    // strip IPv6-mapped IPv4
    String normalized = ip;
    if (normalized.startsWith("::ffff:")) {
      normalized = normalized.substring(7);
    }
    for (Cidr c : cidrs) {
      if (c.contains(normalized)) {
        return true;
      }
    }
    return false;
  }

  private static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null) {
      return false;
    }
    byte[] x = a.getBytes(StandardCharsets.UTF_8);
    byte[] y = b.getBytes(StandardCharsets.UTF_8);
    if (x.length != y.length) {
      // still compare to reduce trivial timing branch on length for short secrets
      return MessageDigest.isEqual(x, x) && false;
    }
    return MessageDigest.isEqual(x, y);
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

  /** Minimal IPv4 CIDR helper (admin allowlist). */
  static final class Cidr {
    final int network;
    final int mask;

    Cidr(int network, int mask) {
      this.network = network;
      this.mask = mask;
    }

    static Cidr parse(String raw) {
      try {
        String value = raw.trim();
        int slash = value.indexOf('/');
        String host = slash >= 0 ? value.substring(0, slash) : value;
        int prefix = slash >= 0 ? Integer.parseInt(value.substring(slash + 1)) : 32;
        if (prefix < 0 || prefix > 32) {
          return null;
        }
        int ip = ipv4(host);
        int mask = prefix == 0 ? 0 : 0xFFFFFFFF << (32 - prefix);
        return new Cidr(ip & mask, mask);
      } catch (Exception ex) {
        return null;
      }
    }

    boolean contains(String ip) {
      try {
        int v = ipv4(ip);
        return (v & mask) == network;
      } catch (Exception ex) {
        return false;
      }
    }

    private static int ipv4(String host) {
      String[] p = host.split("\\.");
      if (p.length != 4) {
        throw new IllegalArgumentException("not_ipv4");
      }
      int a = Integer.parseInt(p[0]);
      int b = Integer.parseInt(p[1]);
      int c = Integer.parseInt(p[2]);
      int d = Integer.parseInt(p[3]);
      return (a << 24) | (b << 16) | (c << 8) | d;
    }
  }
}
