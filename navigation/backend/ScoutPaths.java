import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Host-independent path resolution for backend state (mesh enrollment,
 * admin token, subscriptions).
 *
 * <p>The backend, the agent box and the workstation are different machines, so
 * nothing here may assume a particular user's home or Desktop. Resolution
 * order (first match wins):
 *
 * <ol>
 *   <li>{@code SCOUT_REPO_ROOT} (set by the systemd wrapper / vehicle_stack.env)
 *   <li>the checkout containing the working directory ({@code stack/mesh} marker)
 *   <li>{@code $XDG_STATE_HOME/scout} or {@code ~/.local/state/scout} — a
 *       standard per-host location when no checkout is present
 * </ol>
 *
 * <p>State lives at {@code <repo>/stack/mesh/state} for (1)/(2) and at
 * {@code <xdg>/mesh/state} for (3); {@code SCOUT_MESH_STATE_DIR} overrides it.
 */
final class ScoutPaths {
  private ScoutPaths() {}

  static Path repoRoot() {
    String fromEnv = env("SCOUT_REPO_ROOT");
    if (!fromEnv.isEmpty()) {
      return Path.of(fromEnv);
    }
    Path candidate = Path.of("").toAbsolutePath().normalize();
    for (int i = 0; i < 6 && candidate != null; i++) {
      if (Files.isDirectory(candidate.resolve("stack/mesh"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    return null;
  }

  /** Per-host state root used when there is no repo checkout. */
  static Path xdgStateRoot() {
    String xdg = env("XDG_STATE_HOME");
    Path base = !xdg.isEmpty()
        ? Path.of(xdg)
        : Path.of(System.getProperty("user.home", "/var/lib/scout"), ".local", "state");
    return base.resolve("scout");
  }

  /** Mesh state directory (hub key, IP allocator, admin token, subscriptions). */
  static Path meshStateDir() {
    String override = env("SCOUT_MESH_STATE_DIR");
    if (!override.isEmpty()) {
      return Path.of(override);
    }
    Path repo = repoRoot();
    return repo != null ? repo.resolve("stack/mesh/state") : xdgStateRoot().resolve("mesh/state");
  }

  /** Mesh peers directory (rendered peer configs). */
  static Path meshPeersDir() {
    String override = env("SCOUT_MESH_PEERS_DIR");
    if (!override.isEmpty()) {
      return Path.of(override);
    }
    Path repo = repoRoot();
    return repo != null ? repo.resolve("stack/mesh/peers") : xdgStateRoot().resolve("mesh/peers");
  }

  private static String env(String key) {
    String v = System.getenv(key);
    return v == null ? "" : v.trim();
  }
}
