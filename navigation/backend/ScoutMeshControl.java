// Copyright 2026 Scout Project Contributors
// Licensed under the Apache License, Version 2.0
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Scout Mesh control plane helpers: WireGuard peer enrollment for the product
 * mesh (non-Tailscale). Issues client profiles after entry-token checks and
 * writes hub peer fragments under stack/mesh/peers/.
 */
public final class ScoutMeshControl {
  private static final ReentrantLock LOCK = new ReentrantLock();
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

  private ScoutMeshControl() {}

  public static boolean enabled() {
    return truthy(env("SCOUT_MESH_ENABLED", "false"));
  }

  public static String meshCidr() {
    return env("SCOUT_MESH_CIDR", "10.66.0.0/16");
  }

  public static String hubAddress() {
    // May be "10.66.0.1" or "10.66.0.1/32" depending on install_hub.
    return stripCidr(env("SCOUT_MESH_HUB_ADDRESS", "10.66.0.1"));
  }

  public static String hubAddressCidr() {
    String raw = env("SCOUT_MESH_HUB_ADDRESS", "10.66.0.1/32").trim();
    if (raw.contains("/")) {
      return raw;
    }
    return raw + "/32";
  }

  public static String endpoint() {
    return env("SCOUT_MESH_ENDPOINT", "");
  }

  public static String hubPublicKey() {
    String fromEnv = env("SCOUT_MESH_HUB_PUBLIC_KEY", "").trim();
    if (!fromEnv.isEmpty()) {
      return fromEnv;
    }
    Path p = stateDir().resolve("hub.publickey");
    try {
      if (Files.isRegularFile(p)) {
        return Files.readString(p, StandardCharsets.UTF_8).trim();
      }
    } catch (IOException ignored) {
      // fall through
    }
    return "";
  }

  public static int listenPort() {
    try {
      return Integer.parseInt(env("SCOUT_MESH_LISTEN_PORT", "51820").trim());
    } catch (NumberFormatException ex) {
      return 51820;
    }
  }

  public static int keepalive() {
    try {
      return Integer.parseInt(env("SCOUT_MESH_KEEPALIVE", "25").trim());
    } catch (NumberFormatException ex) {
      return 25;
    }
  }

  public static String backendBaseUrl() {
    String explicit = env("SCOUT_MESH_BACKEND_BASE_URL", "").trim();
    if (!explicit.isEmpty()) {
      return explicit.replaceAll("/$", "");
    }
    String port = env("JAVA_BACKEND_PORT", env("BACKEND_PORT", "18080"));
    return "http://" + hubAddress() + ":" + port;
  }

  public static String subscriptionHeader() {
    return env("SCOUT_SUBSCRIPTION_HEADER", "X-Scout-Subscription");
  }

  public static boolean subscriptionRequired() {
    return truthy(env("SCOUT_SUBSCRIPTION_REQUIRED", "true"));
  }

  /** Public metadata for bootstrap / status (no secrets). */
  public static String publicMeshJson() {
    boolean on = enabled();
    String endpoint = endpoint();
    String hubKey = hubPublicKey();
    StringBuilder sb = new StringBuilder();
    sb.append('{');
    sb.append("\"enabled\":").append(on ? "true" : "false").append(',');
    sb.append("\"provider\":\"scout_mesh_wireguard\",");
    sb.append("\"cidr\":\"").append(jsonEsc(meshCidr())).append("\",");
    sb.append("\"hub_address\":\"").append(jsonEsc(hubAddress())).append("\",");
    sb.append("\"listen_port\":").append(listenPort()).append(',');
    sb.append("\"endpoint\":\"").append(jsonEsc(endpoint)).append("\",");
    sb.append("\"hub_public_key_present\":").append(hubKey.isEmpty() ? "false" : "true").append(',');
    sb.append("\"backend_base_url\":\"").append(jsonEsc(backendBaseUrl())).append("\",");
    sb.append("\"enroll_path\":\"/api/mesh/enroll\",");
    sb.append("\"profile_path\":\"/api/mesh/profile\",");
    sb.append("\"subscription\":{");
    sb.append("\"required\":").append(subscriptionRequired() ? "true" : "false").append(',');
    sb.append("\"header\":\"").append(jsonEsc(subscriptionHeader())).append("\"");
    sb.append('}');
    sb.append('}');
    return sb.toString();
  }

  /**
   * Enroll a device. Returns JSON body and sets httpStatus out-parameter style via
   * EnrollResult.
   */
  public static EnrollResult enroll(String entryToken, String deviceId, String platform, String clientPublicKeyOpt) {
    return enroll(entryToken, deviceId, platform, clientPublicKeyOpt, null, null);
  }

  public static EnrollResult enroll(
      String entryToken,
      String deviceId,
      String platform,
      String clientPublicKeyOpt,
      String preferredEndpoint,
      String clientRemoteIp) {
    if (!enabled()) {
      return EnrollResult.error(503, "mesh_disabled");
    }
    String expected = env("SCOUT_MESH_ENTRY_TOKEN", "").trim();
    if (expected.isEmpty()) {
      return EnrollResult.error(503, "mesh_entry_token_not_configured");
    }
    if (entryToken == null || !expected.equals(entryToken.trim())) {
      return EnrollResult.error(403, "invalid_entry_token");
    }
    String hubKey = hubPublicKey();
    String ep = resolveEndpoint(preferredEndpoint, clientRemoteIp);
    if (hubKey.isEmpty() || ep.isEmpty()) {
      return EnrollResult.error(503, "mesh_hub_not_provisioned");
    }
    String id = sanitizeDeviceId(deviceId);
    if (id == null) {
      return EnrollResult.error(400, "invalid_device_id");
    }
    String plat = platform == null || platform.isBlank() ? "android" : platform.trim().toLowerCase(Locale.ROOT);

    LOCK.lock();
    try {
      ensureDirs();
      // Re-enroll same device → reuse allocated IP, rotate keys.
      AllocatedPeer existing = findPeer(id);
      String clientAddress = existing != null ? existing.address : allocateAddress(plat);
      if (clientAddress == null) {
        return EnrollResult.error(503, "mesh_address_pool_exhausted");
      }

      KeyPair keys;
      if (clientPublicKeyOpt != null && !clientPublicKeyOpt.isBlank()) {
        // Client-generated private key stays on device; we only learn the public key.
        keys = new KeyPair("", clientPublicKeyOpt.trim());
      } else {
        keys = generateKeyPair();
        if (keys == null) {
          return EnrollResult.error(500, "keygen_failed");
        }
      }
      if (keys.publicKey == null || keys.publicKey.isBlank()) {
        return EnrollResult.error(400, "missing_client_public_key");
      }

      writePeerFragment(id, keys.publicKey, clientAddress);
      writeAllocatorRow(id, plat, clientAddress, keys.publicKey);
      maybeApplyPeers();

      StringBuilder mesh = new StringBuilder();
      mesh.append('{');
      mesh.append("\"cidr\":\"").append(jsonEsc(meshCidr())).append("\",");
      mesh.append("\"client_address\":\"").append(jsonEsc(clientAddress)).append("\",");
      mesh.append("\"dns\":[],");
      mesh.append("\"endpoint\":\"").append(jsonEsc(ep)).append("\",");
      mesh.append("\"endpoint_candidates\":").append(endpointCandidatesJson(ep, clientRemoteIp)).append(",");
      mesh.append("\"endpoint_selection\":\"dynamic\",");
      mesh.append("\"server_public_key\":\"").append(jsonEsc(hubKey)).append("\",");
      if (keys.privateKey != null && !keys.privateKey.isBlank()) {
        mesh.append("\"client_private_key\":\"").append(jsonEsc(keys.privateKey)).append("\",");
      } else {
        mesh.append("\"client_private_key\":null,");
      }
      mesh.append("\"client_public_key\":\"").append(jsonEsc(keys.publicKey)).append("\",");
      mesh.append("\"allowed_ips\":[\"").append(jsonEsc(meshCidr())).append("\"],");
      mesh.append("\"persistent_keepalive\":").append(keepalive()).append(',');
      mesh.append("\"backend_base_url\":\"").append(jsonEsc(backendBaseUrl())).append("\",");
      mesh.append("\"interface_name\":\"scoutwg0\"");
      mesh.append('}');

      String body =
          "{"
              + "\"status\":\"ok\","
              + "\"ts\":\""
              + Instant.now()
              + "\","
              + "\"device_id\":\""
              + jsonEsc(id)
              + "\","
              + "\"platform\":\""
              + jsonEsc(plat)
              + "\","
              + "\"mesh\":"
              + mesh
              + ","
              + "\"subscription\":{"
              + "\"required\":"
              + (subscriptionRequired() ? "true" : "false")
              + ","
              + "\"header\":\""
              + jsonEsc(subscriptionHeader())
              + "\","
              + "\"hint\":\"stack APIs require an active subscription token after mesh join\""
              + "}"
              + "}";
      return EnrollResult.ok(body);
    } catch (Exception ex) {
      return EnrollResult.error(500, "enroll_failed:" + ex.getClass().getSimpleName());
    } finally {
      LOCK.unlock();
    }
  }



  /**
   * Remove a mesh peer fragment + allocator row and revoke subscription tokens.
   * Caller must be admin-gated at HTTP layer.
   */
  public static String revokePeer(String deviceId) {
    String id = sanitizeDeviceId(deviceId);
    if (id == null) {
      return "{\"status\":\"error\",\"error\":\"invalid_device_id\"}";
    }
    LOCK.lock();
    try {
      ensureDirs();
      Path fragment = peersDir().resolve(id.replaceAll("[^A-Za-z0-9._-]", "_") + ".conf");
      boolean removedFragment = Files.deleteIfExists(fragment);
      Path path = allocatorPath();
      boolean removedRow = false;
      if (Files.isRegularFile(path)) {
        List<String> lines = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
          if (line.isBlank()) {
            continue;
          }
          if (line.startsWith("#")) {
            lines.add(line);
            continue;
          }
          String[] parts = line.split("\t");
          if (parts.length >= 1 && parts[0].equals(id)) {
            removedRow = true;
            continue;
          }
          lines.add(line);
        }
        Files.writeString(
            path,
            String.join("\n", lines) + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);
      }
      int subs = 0;
      try {
        subs = ScoutSubscriptionAuth.revokeDevice(id);
      } catch (Throwable ignored) {
        // optional
      }
      maybeApplyPeers();
      return "{\"status\":\"ok\",\"device_id\":\""
          + jsonEsc(id)
          + "\",\"peer_fragment_removed\":"
          + (removedFragment ? "true" : "false")
          + ",\"allocator_row_removed\":"
          + (removedRow ? "true" : "false")
          + ",\"subscriptions_revoked\":"
          + subs
          + "}";
    } catch (Exception ex) {
      return "{\"status\":\"error\",\"error\":\"revoke_failed\"}";
    } finally {
      LOCK.unlock();
    }
  }

  public static String profileStatusJson(String deviceId) {
    String id = sanitizeDeviceId(deviceId);
    AllocatedPeer peer = id == null ? null : findPeer(id);
    StringBuilder sb = new StringBuilder();
    sb.append('{');
    sb.append("\"status\":\"ok\",");
    sb.append("\"mesh\":").append(publicMeshJson()).append(',');
    if (peer == null) {
      sb.append("\"enrolled\":false");
    } else {
      sb.append("\"enrolled\":true,");
      sb.append("\"device_id\":\"").append(jsonEsc(peer.deviceId)).append("\",");
      sb.append("\"client_address\":\"").append(jsonEsc(peer.address)).append("\",");
      sb.append("\"client_public_key\":\"").append(jsonEsc(peer.publicKey)).append("\"");
    }
    sb.append('}');
    return sb.toString();
  }

  private static void ensureDirs() throws IOException {
    Files.createDirectories(stateDir());
    Files.createDirectories(peersDir());
  }

  private static Path repoRoot() {
    Path repo = ScoutPaths.repoRoot();
    return repo != null ? repo : Path.of("").toAbsolutePath().normalize();
  }

  private static Path stateDir() {
    return ScoutPaths.meshStateDir();
  }

  private static Path peersDir() {
    return ScoutPaths.meshPeersDir();
  }


  private static Path allocatorPath() {
    return stateDir().resolve("ip_allocator.tsv");
  }

  private static String sanitizeDeviceId(String raw) {
    if (raw == null) {
      return null;
    }
    String id = raw.trim();
    if (id.isEmpty() || !SAFE_ID.matcher(id).matches()) {
      return null;
    }
    return id;
  }

  private static AllocatedPeer findPeer(String deviceId) {
    Path path = allocatorPath();
    if (!Files.isRegularFile(path)) {
      return null;
    }
    try {
      for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        String[] parts = line.split("\t");
        if (parts.length >= 4 && parts[0].equals(deviceId)) {
          return new AllocatedPeer(parts[0], parts[1], parts[2], parts[3]);
        }
      }
    } catch (IOException ignored) {
      return null;
    }
    return null;
  }


  /**
   * Pick a WireGuard UDP endpoint dynamically.
   * Priority: explicit preferred (client) → SCOUT_MESH_ENDPOINT_LAN if client on private LAN →
   * SCOUT_MESH_ENDPOINT (public) → state/endpoint file → empty.
   */
  public static String resolveEndpoint(String preferredEndpoint, String clientRemoteIp) {
    return resolveEndpoint(
        preferredEndpoint,
        clientRemoteIp,
        env("SCOUT_MESH_ENDPOINT_LAN", "").trim(),
        endpoint().trim(),
        true);
  }

  /**
   * Testable endpoint selection with explicit LAN/public candidates.
   * {@code allowStateFallback} reads state/endpoint when both LAN/public empty.
   */
  public static String resolveEndpoint(
      String preferredEndpoint,
      String clientRemoteIp,
      String lanEndpoint,
      String publicEndpoint,
      boolean allowStateFallback) {
    if (preferredEndpoint != null && !preferredEndpoint.isBlank()) {
      return normalizeEndpoint(preferredEndpoint.trim());
    }
    String lan = lanEndpoint == null ? "" : lanEndpoint.trim();
    String pub = publicEndpoint == null ? "" : publicEndpoint.trim();
    if (isPrivateIpv4(clientRemoteIp) && !lan.isEmpty()) {
      return normalizeEndpoint(lan);
    }
    // Same RFC1918 site as LAN endpoint host → prefer LAN binding.
    if (isPrivateIpv4(clientRemoteIp) && !pub.isEmpty()) {
      String pubHost = pub.contains(":") ? pub.substring(0, pub.lastIndexOf(':')) : pub;
      if (isPrivateIpv4(pubHost)) {
        return normalizeEndpoint(pub);
      }
      if (!lan.isEmpty()) {
        return normalizeEndpoint(lan);
      }
    }
    if (!pub.isEmpty()) {
      return normalizeEndpoint(pub);
    }
    if (allowStateFallback) {
      try {
        Path epFile = stateDir().resolve("endpoint");
        if (Files.isRegularFile(epFile)) {
          String fromFile = Files.readString(epFile, StandardCharsets.UTF_8).trim();
          if (!fromFile.isEmpty()) {
            return normalizeEndpoint(fromFile);
          }
        }
      } catch (Exception ignored) {
        // ignore
      }
    }
    return "";
  }

  /** Visible for unit tests — allocates next free /32 under configured CIDR/pools. */
  public static String allocateAddressForTest(String platform) throws IOException {
    return allocateAddress(platform);
  }

  /** Visible for unit tests. */
  public static boolean isPrivateIpv4ForTest(String ip) {
    return isPrivateIpv4(ip);
  }

  public static String endpointCandidatesJson(String selected, String clientRemoteIp) {
    java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
    if (selected != null && !selected.isBlank()) {
      set.add(selected);
    }
    String lan = env("SCOUT_MESH_ENDPOINT_LAN", "").trim();
    String pub = endpoint().trim();
    if (!lan.isEmpty()) {
      set.add(normalizeEndpoint(lan));
    }
    if (!pub.isEmpty()) {
      set.add(normalizeEndpoint(pub));
    }
    StringBuilder sb = new StringBuilder("[");
    boolean first = true;
    for (String e : set) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append('"').append(jsonEsc(e)).append('"');
    }
    sb.append(']');
    return sb.toString();
  }

  private static String normalizeEndpoint(String raw) {
    String v = raw.trim();
    if (v.isEmpty()) {
      return v;
    }
    if (!v.contains(":")) {
      return v + ":" + listenPort();
    }
    return v;
  }

  private static boolean isPrivateIpv4(String ip) {
    if (ip == null || ip.isBlank()) {
      return false;
    }
    String v = ip.trim();
    if (v.startsWith("::ffff:")) {
      v = v.substring(7);
    }
    try {
      Cidr c = Cidr.parse(v + "/32");
      if (c == null) {
        return false;
      }
      int x = c.network;
      int b1 = (x >>> 24) & 0xff;
      int b2 = (x >>> 16) & 0xff;
      if (b1 == 10) {
        return true;
      }
      if (b1 == 192 && b2 == 168) {
        return true;
      }
      if (b1 == 172 && b2 >= 16 && b2 <= 31) {
        return true;
      }
      if (b1 == 100 && b2 >= 64 && b2 <= 127) {
        return true; // Tailscale CGNAT
      }
      if (b1 == 127) {
        return true; // loopback enrolls should still prefer LAN endpoint
      }
      return false;
    } catch (Exception ex) {
      return false;
    }
  }


  /**
   * Dynamically bind a free /32 inside SCOUT_MESH_CIDR.
   *
   * <p>Pool selection (all configurable — no hardcoded 10.66 required when CIDR/env set):
   * <ul>
   *   <li>{@code SCOUT_MESH_ANDROID_SUBNET} — e.g. 10.66.1.0/24 (default: 3rd octet .1 within /16)
   *   <li>{@code SCOUT_MESH_PEER_SUBNET} — e.g. 10.66.2.0/24 for linux/other (default: 3rd octet .2)
   *   <li>If CIDR is tighter than /16, allocate any free host in the whole CIDR (hub reserved)
   * </ul>
   */
  private static String allocateAddress(String platform) throws IOException {
    Cidr mesh = Cidr.parse(meshCidr());
    if (mesh == null) {
      return null;
    }
    String hubHost = hubAddress();
    Path path = allocatorPath();
    Map<String, Boolean> used = new LinkedHashMap<>();
    used.put(hubHost + "/32", true);
    used.put(hubAddressCidr(), true);
    if (Files.isRegularFile(path)) {
      for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        String[] parts = line.split("\t");
        if (parts.length >= 3) {
          String a = parts[2].trim();
          used.put(a, true);
          if (!a.contains("/")) {
            used.put(a + "/32", true);
          }
        }
      }
    }

    Cidr pool = null;
    String poolEnv =
        "android".equals(platform)
            ? env("SCOUT_MESH_ANDROID_SUBNET", "").trim()
            : env("SCOUT_MESH_PEER_SUBNET", "").trim();
    if (!poolEnv.isEmpty()) {
      pool = Cidr.parse(poolEnv);
    } else if (mesh.prefix <= 16) {
      // Default split: android → x.y.1.0/24, other → x.y.2.0/24 inside the /16 (or wider).
      int third = "android".equals(platform) ? 1 : 2;
      int base = (mesh.network & 0xFFFF0000) | (third << 8);
      pool = new Cidr(base, 0xFFFFFF00, 24);
    } else {
      pool = mesh;
    }
    if (pool == null) {
      return null;
    }

    int startHost = 2; // .0 network, .1 often gateway-ish; hub may be .1
    int maxHost = (1 << (32 - pool.prefix)) - 2; // broadcast excluded
    if (maxHost < startHost) {
      startHost = 1;
    }
    for (int offset = startHost; offset <= maxHost; offset++) {
      int ip = pool.network + offset;
      if (!pool.containsIp(ip) || !mesh.containsIp(ip)) {
        continue;
      }
      String host = ipv4String(ip);
      if (host.equals(hubHost)) {
        continue;
      }
      String addr = host + "/32";
      if (!used.containsKey(addr) && !used.containsKey(host)) {
        return addr;
      }
    }
    // Fallback: scan entire mesh CIDR for any free host.
    int meshStart = 1;
    int meshMax = (1 << (32 - mesh.prefix)) - 2;
    for (int offset = meshStart; offset <= meshMax; offset++) {
      int ip = mesh.network + offset;
      String host = ipv4String(ip);
      if (host.equals(hubHost)) {
        continue;
      }
      String addr = host + "/32";
      if (!used.containsKey(addr) && !used.containsKey(host)) {
        return addr;
      }
    }
    return null;
  }

  private static String stripCidr(String raw) {
    if (raw == null) {
      return "";
    }
    String v = raw.trim();
    int slash = v.indexOf('/');
    return slash >= 0 ? v.substring(0, slash) : v;
  }

  private static String ipv4String(int ip) {
    return ((ip >>> 24) & 0xff)
        + "."
        + ((ip >>> 16) & 0xff)
        + "."
        + ((ip >>> 8) & 0xff)
        + "."
        + (ip & 0xff);
  }

  /** IPv4 CIDR helper for dynamic mesh allocation. */
  static final class Cidr {
    final int network;
    final int mask;
    final int prefix;

    Cidr(int network, int mask, int prefix) {
      this.network = network;
      this.mask = mask;
      this.prefix = prefix;
    }

    static Cidr parse(String raw) {
      try {
        if (raw == null || raw.isBlank()) {
          return null;
        }
        String value = raw.trim();
        int slash = value.indexOf('/');
        String host = slash >= 0 ? value.substring(0, slash) : value;
        int pref = slash >= 0 ? Integer.parseInt(value.substring(slash + 1)) : 32;
        if (pref < 0 || pref > 32) {
          return null;
        }
        int ip = parseIpv4(host);
        int m = pref == 0 ? 0 : 0xFFFFFFFF << (32 - pref);
        return new Cidr(ip & m, m, pref);
      } catch (Exception ex) {
        return null;
      }
    }

    boolean containsIp(int ip) {
      return (ip & mask) == network;
    }

    private static int parseIpv4(String host) {
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


  private static void writeAllocatorRow(String deviceId, String platform, String address, String pubKey)
      throws IOException {
    Path path = allocatorPath();
    List<String> lines = new ArrayList<>();
    if (Files.isRegularFile(path)) {
      for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
        if (line.isBlank()) {
          continue;
        }
        String[] parts = line.split("\t");
        if (parts.length >= 1 && parts[0].equals(deviceId)) {
          continue;
        }
        lines.add(line);
      }
    } else {
      lines.add("# device_id\tplatform\taddress\tpublic_key\tupdated_at");
    }
    lines.add(
        deviceId
            + "\t"
            + platform
            + "\t"
            + address
            + "\t"
            + pubKey
            + "\t"
            + Instant.now());
    Files.writeString(
        path,
        String.join("\n", lines) + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
  }

  private static void writePeerFragment(String deviceId, String publicKey, String clientAddress)
      throws IOException {
    String safeFile = deviceId.replaceAll("[^A-Za-z0-9._-]", "_");
    Path fragment = peersDir().resolve(safeFile + ".conf");
    String body =
        "\n# device "
            + deviceId
            + "\n[Peer]\nPublicKey = "
            + publicKey
            + "\nAllowedIPs = "
            + clientAddress
            + "\n";
    Files.writeString(
        fragment,
        body,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
  }

  private static void maybeApplyPeers() {
    if (!truthy(env("SCOUT_MESH_AUTO_APPLY_PEERS", "true"))) {
      return;
    }
    Path script = repoRoot().resolve("stack/mesh/apply_peers.sh");
    if (!Files.isRegularFile(script)) {
      return;
    }
    try {
      ProcessBuilder pb = new ProcessBuilder("sudo", "-n", script.toString());
      pb.redirectErrorStream(true);
      Process p = pb.start();
      // best effort; enrollment still succeeds if apply needs a password
      p.waitFor();
    } catch (Exception ignored) {
      // operator can run apply_peers.sh manually
    }
  }

  private static KeyPair generateKeyPair() {
    // Prefer wg tooling for correct clamping.
    try {
      Process gen = new ProcessBuilder("wg", "genkey").start();
      String priv;
      try (BufferedReader r =
          new BufferedReader(new InputStreamReader(gen.getInputStream(), StandardCharsets.UTF_8))) {
        priv = r.readLine();
      }
      int code = gen.waitFor();
      if (code != 0 || priv == null || priv.isBlank()) {
        return generateKeyPairFallback();
      }
      Process pub =
          new ProcessBuilder("wg", "pubkey").start();
      pub.getOutputStream().write((priv + "\n").getBytes(StandardCharsets.UTF_8));
      pub.getOutputStream().close();
      String pubKey;
      try (BufferedReader r =
          new BufferedReader(new InputStreamReader(pub.getInputStream(), StandardCharsets.UTF_8))) {
        pubKey = r.readLine();
      }
      if (pub.waitFor() != 0 || pubKey == null || pubKey.isBlank()) {
        return generateKeyPairFallback();
      }
      return new KeyPair(priv.trim(), pubKey.trim());
    } catch (Exception ex) {
      return generateKeyPairFallback();
    }
  }

  /**
   * Fallback X25519-ish random key material. Prefer `wg` on the hub. Keys are
   * clamped per WireGuard conventions.
   */
  private static KeyPair generateKeyPairFallback() {
    try {
      byte[] priv = new byte[32];
      RANDOM.nextBytes(priv);
      priv[0] &= 248;
      priv[31] &= 127;
      priv[31] |= 64;
      // Without a Curve25519 scalar mult implementation we cannot derive pubkey
      // correctly here. Refuse rather than ship broken peers.
      if (!commandExists("wg")) {
        return null;
      }
      String privB64 = Base64.getEncoder().encodeToString(priv);
      Process pub = new ProcessBuilder("wg", "pubkey").start();
      pub.getOutputStream().write((privB64 + "\n").getBytes(StandardCharsets.UTF_8));
      pub.getOutputStream().close();
      String pubKey;
      try (BufferedReader r =
          new BufferedReader(new InputStreamReader(pub.getInputStream(), StandardCharsets.UTF_8))) {
        pubKey = r.readLine();
      }
      if (pub.waitFor() != 0 || pubKey == null) {
        return null;
      }
      return new KeyPair(privB64, pubKey.trim());
    } catch (Exception ex) {
      return null;
    }
  }

  private static boolean commandExists(String name) {
    try {
      Process p = new ProcessBuilder("which", name).start();
      return p.waitFor() == 0;
    } catch (Exception ex) {
      return false;
    }
  }

  private static String env(String key, String def) {
    String v = System.getenv(key);
    return v == null || v.isBlank() ? def : v;
  }

  private static boolean truthy(String raw) {
    if (raw == null) {
      return false;
    }
    String v = raw.trim().toLowerCase(Locale.ROOT);
    return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("on");
  }

  private static String jsonEsc(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder escaped = new StringBuilder(value.length() + 8);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"':
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

  public static final class EnrollResult {
    public final int status;
    public final String body;

    private EnrollResult(int status, String body) {
      this.status = status;
      this.body = body;
    }

    static EnrollResult ok(String body) {
      return new EnrollResult(200, body);
    }

    static EnrollResult error(int status, String code) {
      return new EnrollResult(status, "{\"status\":\"error\",\"error\":\"" + jsonEsc(code) + "\"}");
    }
  }

  private static final class KeyPair {
    final String privateKey;
    final String publicKey;

    KeyPair(String privateKey, String publicKey) {
      this.privateKey = privateKey;
      this.publicKey = publicKey;
    }
  }

  private static final class AllocatedPeer {
    final String deviceId;
    final String platform;
    final String address;
    final String publicKey;

    AllocatedPeer(String deviceId, String platform, String address, String publicKey) {
      this.deviceId = deviceId;
      this.platform = platform;
      this.address = address;
      this.publicKey = publicKey;
    }
  }
}
