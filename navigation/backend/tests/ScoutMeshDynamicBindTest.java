// Copyright 2026 Scout Project Contributors
// Licensed under the Apache License, Version 2.0
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Tests for dynamic mesh IP allocation and endpoint selection. */
public final class ScoutMeshDynamicBindTest {
  private static int passed = 0;
  private static int failed = 0;
  private static final List<String> failures = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    String mode = args.length > 0 ? args[0] : "all";
    if ("endpoints".equals(mode) || "all".equals(mode)) {
      runEndpointTests();
    }
    if ("allocate-default".equals(mode) || "all".equals(mode)) {
      runAllocateDefaultCidrTests();
    }
    if ("allocate-custom".equals(mode)) {
      runAllocateCustomCidrTests();
    }
    if ("allocate-exhaust".equals(mode)) {
      runAllocateExhaustTests();
    }
    System.out.printf("ScoutMeshDynamicBindTest[%s]: %d passed, %d failed%n", mode, passed, failed);
    if (failed > 0) {
      for (String f : failures) System.err.println("FAIL: " + f);
      System.exit(1);
    }
    System.out.println("OK");
  }

  private static void runEndpointTests() {
    assertTrue("192.168 private", ScoutMeshControl.isPrivateIpv4ForTest("192.168.1.10"));
    assertTrue("10/8 private", ScoutMeshControl.isPrivateIpv4ForTest("10.0.0.5"));
    assertTrue("172.16 private", ScoutMeshControl.isPrivateIpv4ForTest("172.16.0.1"));
    assertTrue("cgnat", ScoutMeshControl.isPrivateIpv4ForTest("100.64.1.1"));
    assertTrue("loopback", ScoutMeshControl.isPrivateIpv4ForTest("127.0.0.1"));
    assertTrue("v4mapped", ScoutMeshControl.isPrivateIpv4ForTest("::ffff:192.168.0.2"));
    assertFalse("public", ScoutMeshControl.isPrivateIpv4ForTest("8.8.8.8"));
    assertFalse("blank", ScoutMeshControl.isPrivateIpv4ForTest(""));
    assertFalse("null", ScoutMeshControl.isPrivateIpv4ForTest(null));

    assertEq("preferred", "10.1.2.3:9999",
        ScoutMeshControl.resolveEndpoint("10.1.2.3:9999", "192.168.1.50", "192.168.1.154:51820", "97.1.2.3:51820", false));
    assertEq("lan private", "192.168.1.154:51820",
        ScoutMeshControl.resolveEndpoint(null, "192.168.1.155", "192.168.1.154:51820", "97.188.103.160:51820", false));
    assertEq("lan loopback", "192.168.1.154:51820",
        ScoutMeshControl.resolveEndpoint("", "127.0.0.1", "192.168.1.154:51820", "97.188.103.160:51820", false));
    assertEq("public client", "97.188.103.160:51820",
        ScoutMeshControl.resolveEndpoint(null, "8.8.8.8", "192.168.1.154:51820", "97.188.103.160:51820", false));
    assertEq("private field ep", "10.66.0.1:51820",
        ScoutMeshControl.resolveEndpoint(null, "10.0.0.5", "", "10.66.0.1:51820", false));
    assertEq("empty", "", ScoutMeshControl.resolveEndpoint(null, "8.8.8.8", "", "", false));
    String got = ScoutMeshControl.resolveEndpoint("192.168.1.154", "1.2.3.4", "", "", false);
    assertTrue("default port", got.startsWith("192.168.1.154:"));
    String json = ScoutMeshControl.endpointCandidatesJson("192.168.1.154:51820", "192.168.1.1");
    assertTrue("array", json.startsWith("[") && json.endsWith("]"));
    assertTrue("contains selected", json.contains("192.168.1.154:51820"));
  }

  private static void runAllocateDefaultCidrTests() throws Exception {
    requireEnv("10.66.0.0/16", "10.66.0.1");
    Path state = Path.of(System.getenv("SCOUT_MESH_STATE_DIR"));
    Files.createDirectories(state);
    resetAllocator(state);

    String android = ScoutMeshControl.allocateAddressForTest("android");
    String linux = ScoutMeshControl.allocateAddressForTest("linux");
    assertTrue("android alloc", android != null && android.endsWith("/32"));
    assertTrue("linux alloc", linux != null && linux.endsWith("/32"));
    assertTrue("android pool", android.startsWith("10.66.1."));
    assertTrue("linux pool", linux.startsWith("10.66.2."));
    assertFalse("distinct", android.equals(linux));

    resetAllocator(state);
    claim(state, "seed", "android", "10.66.1.2/32");
    assertEq("next after seed", "10.66.1.3/32", ScoutMeshControl.allocateAddressForTest("android"));

    resetAllocator(state);
    for (int i = 0; i < 5; i++) {
      String a = ScoutMeshControl.allocateAddressForTest("linux");
      assertTrue("alloc"+i, a != null);
      assertFalse("not hub", a.startsWith("10.66.0.1/"));
      claim(state, "h"+i, "linux", a);
    }
  }

  private static void runAllocateCustomCidrTests() throws Exception {
    requireEnv("10.99.0.0/16", "10.99.0.1");
    Path state = Path.of(System.getenv("SCOUT_MESH_STATE_DIR"));
    Files.createDirectories(state);
    resetAllocator(state);
    String linux = ScoutMeshControl.allocateAddressForTest("linux");
    String android = ScoutMeshControl.allocateAddressForTest("android");
    assertTrue("custom linux", linux != null && linux.startsWith("10.99.2."));
    assertTrue("custom android", android != null && android.startsWith("10.99.1."));
    assertFalse("no 10.66", linux.contains("10.66") || android.contains("10.66"));
  }

  private static void runAllocateExhaustTests() throws Exception {
    requireEnv("10.77.0.0/30", "10.77.0.1");
    Path state = Path.of(System.getenv("SCOUT_MESH_STATE_DIR"));
    Files.createDirectories(state);
    resetAllocator(state);
    String a1 = ScoutMeshControl.allocateAddressForTest("linux");
    assertTrue("first", a1 != null);
    claim(state, "only", "linux", a1);
    assertTrue("exhausted", ScoutMeshControl.allocateAddressForTest("linux") == null);
  }

  private static void requireEnv(String cidr, String hub) {
    assertEq("CIDR", cidr, System.getenv("SCOUT_MESH_CIDR"));
    String h = System.getenv("SCOUT_MESH_HUB_ADDRESS");
    assertTrue("HUB", h != null && (h.equals(hub) || h.startsWith(hub)));
    assertTrue("STATE", System.getenv("SCOUT_MESH_STATE_DIR") != null);
  }

  private static void resetAllocator(Path state) throws Exception {
    Files.writeString(state.resolve("ip_allocator.tsv"),
        "# device_id\tplatform\taddress\tpublic_key\tupdated_at\n", StandardCharsets.UTF_8);
  }

  private static void claim(Path state, String id, String platform, String addr) throws Exception {
    Path alloc = state.resolve("ip_allocator.tsv");
    Files.writeString(alloc, Files.readString(alloc) + id + "\t" + platform + "\t" + addr + "\tpk\tnow\n",
        StandardCharsets.UTF_8);
  }

  private static void assertEq(String name, String expected, String actual) {
    if ((expected == null && actual == null) || (expected != null && expected.equals(actual))) { passed++; return; }
    failed++; failures.add(name + ": expected=[" + expected + "] actual=[" + actual + "]");
  }
  private static void assertTrue(String name, boolean cond) {
    if (cond) passed++; else { failed++; failures.add(name); }
  }
  private static void assertFalse(String name, boolean cond) { assertTrue(name, !cond); }
}
