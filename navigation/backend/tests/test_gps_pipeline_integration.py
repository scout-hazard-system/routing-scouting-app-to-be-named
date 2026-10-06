# Copyright 2026 Scout Project Contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


def _repo_root() -> Path:
    return Path(__file__).resolve().parents[3]


def _channel_selector():
    """navigation/pipeline/channel_selector.py, the module pipeline.py and the hub selector share."""
    pipeline_dir = str(_repo_root() / "navigation" / "pipeline")
    if pipeline_dir not in sys.path:
        sys.path.insert(0, pipeline_dir)
    import channel_selector

    return channel_selector


_COMPILED: dict[str, Path] = {}


def _compiled_backend(repo_root: Path) -> Path:
    """Compile BackendServer.java once per test process and reuse the classes."""
    key = str(repo_root)
    if key in _COMPILED:
        return _COMPILED[key]
    repo_dir = repo_root / "navigation" / "backend"
    build_dir = Path(tempfile.mkdtemp(prefix="scanner-backend-test-build-"))
    compile_cmd = ["javac", "-d", str(build_dir)] + [
        str(p) for p in sorted(repo_dir.glob("*.java"))
    ]
    compile_result = subprocess.run(compile_cmd, cwd=str(repo_dir), capture_output=True, text=True)
    if compile_result.returncode != 0:
        raise RuntimeError(
            f"Backend compilation failed:\nSTDOUT:\n{compile_result.stdout}\nSTDERR:\n{compile_result.stderr}"
        )
    _COMPILED[key] = build_dir
    return build_dir


class _BackendFixture:
    """One BackendServer.java process on a loopback port, with a chosen GPS policy."""

    def __init__(self, repo_root: Path, port: int, *, accept_client_gps: bool | None):
        self.repo_root = repo_root
        self.repo_dir = repo_root / "navigation" / "backend"
        self.port = port
        self.base_url = f"http://127.0.0.1:{port}"
        self.build_dir = _compiled_backend(repo_root)
        self.log_file = Path(tempfile.mkstemp(prefix="scanner-backend-test-log-", suffix=".log")[1])
        self.pipeline_log = Path(tempfile.mkstemp(prefix="scanner-backend-pipeline-log-", suffix=".log")[1])
        self.map_cache_dir = Path(tempfile.mkdtemp(prefix="scanner-backend-map-cache-"))
        self.accept_client_gps = accept_client_gps
        self.proc: subprocess.Popen | None = None
        self._log_handle = None

    def _env(self) -> dict:
        env = os.environ.copy()
        env["JAVA_BACKEND_HOST"] = "127.0.0.1"
        env["JAVA_BACKEND_PORT"] = str(self.port)
        env["SCOUT_REPO_ROOT"] = str(self.repo_root)  # helper scripts resolve from the repo root, not cwd
        env["PIPELINE_LOG_PATH"] = str(self.pipeline_log)
        env["SELECTOR_PYTHON_BIN"] = sys.executable
        env["BROADCASTIFY_CHANNELS_FILE"] = str(
            self.repo_root / "stack/config/broadcastify_channels.national.manifest.json"
        )
        env["BROADCASTIFY_SELECTOR_USE_OLLAMA_RERANK"] = "false"
        env["BROADCASTIFY_SELECTOR_LOCK_STATE"] = "false"
        env["SCOUT_SUBSCRIPTION_REQUIRED"] = "false"
        env["MAP_CACHE_DIR"] = str(self.map_cache_dir)
        env["NOMINATIM_REVERSE_URL"] = "http://127.0.0.1:9/reverse"  # never reach a third party from tests
        # None means "unset": the shipped default is coordinate-free.
        if self.accept_client_gps is None:
            env.pop("SCOUT_ACCEPT_CLIENT_GPS", None)
        else:
            env["SCOUT_ACCEPT_CLIENT_GPS"] = "true" if self.accept_client_gps else "false"
        return env

    def start(self) -> None:
        self._log_handle = open(self.log_file, "w", encoding="utf-8")
        self.proc = subprocess.Popen(
            ["java", "-cp", str(self.build_dir), "BackendServer"],
            cwd=str(self.repo_dir),
            env=self._env(),
            stdout=self._log_handle,
            stderr=self._log_handle,
        )
        self.wait_for_health()

    def stop(self) -> None:
        if self.proc is not None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=8)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait(timeout=5)
            self.proc = None
        if self._log_handle is not None:
            self._log_handle.close()
            self._log_handle = None

    def log_tail(self, limit: int = 4000) -> str:
        return self.log_file.read_text(encoding="utf-8", errors="replace")[-limit:]

    def wait_for_health(self) -> None:
        deadline = time.time() + 12.0
        last_err = None
        while time.time() < deadline:
            try:
                health = self.request_json("GET", "/api/health")
                if health.get("status") == "ok":
                    return
            except Exception as exc:  # noqa: BLE001
                last_err = exc
            time.sleep(0.2)
        raise RuntimeError(f"Backend did not become healthy in time: {last_err}\n{self.log_tail()}")

    def request_json(self, method: str, path: str, payload: dict | None = None):
        """Raises HTTPError on 4xx/5xx, matching the pre-existing test style."""
        url = self.base_url + path
        body = None
        headers = {"Accept": "application/json"}
        if payload is not None:
            headers["Content-Type"] = "application/json"
            body = json.dumps(payload).encode("utf-8")
        req = Request(url, data=body, method=method, headers=headers)
        with urlopen(req, timeout=30.0) as resp:
            return json.loads(resp.read().decode("utf-8"))

    def request_raw(self, method: str, path: str, payload: dict | None = None):
        """Returns (status, parsed_body, raw_bytes) without raising on 4xx/5xx."""
        url = self.base_url + path
        body = None
        headers = {"Accept": "application/json"}
        if payload is not None:
            headers["Content-Type"] = "application/json"
            body = json.dumps(payload).encode("utf-8")
        req = Request(url, data=body, method=method, headers=headers)
        try:
            with urlopen(req, timeout=30.0) as resp:
                raw = resp.read()
                status = resp.status
        except HTTPError as exc:
            raw = exc.read()
            status = exc.code
        parsed: dict
        try:
            parsed = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            parsed = {"_raw": raw[:512].decode("utf-8", "replace")}
        return status, parsed, raw

    def request_bytes(self, path: str, timeout: float = 30.0):
        req = Request(self.base_url + path, method="GET")
        with urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.headers.get("Content-Type", ""), resp.read()


class _BackendTestCase(unittest.TestCase):
    accept_client_gps: bool | None = True

    @classmethod
    def setUpClass(cls):
        cls.repo_root = _repo_root()
        cls.repo_dir = cls.repo_root / "navigation" / "backend"
        cls.port = _free_port()
        cls.base_url = f"http://127.0.0.1:{cls.port}"
        cls.pipeline_log = None
        cls.map_cache_dir = None
        cls.server = _BackendFixture(cls.repo_root, cls.port, accept_client_gps=cls.accept_client_gps)
        cls.pipeline_log = cls.server.pipeline_log
        cls.map_cache_dir = cls.server.map_cache_dir
        cls.server.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.stop()

    @classmethod
    def _wait_for_health(cls):
        cls.server.wait_for_health()

    @classmethod
    def _request_json(cls, method: str, path: str, payload: dict | None = None):
        return cls.server.request_json(method, path, payload)

    @classmethod
    def _request_raw(cls, method: str, path: str, payload: dict | None = None):
        return cls.server.request_raw(method, path, payload)

    @classmethod
    def _request_bytes(cls, path: str, timeout: float = 30.0):
        return cls.server.request_bytes(path, timeout)


class GpsPipelineIntegrationTests(_BackendTestCase):
    """Vehicle-local mode: SCOUT_ACCEPT_CLIENT_GPS=true keeps the GPS features alive."""

    def test_pipeline_policy_probe_sees_vehicle_local(self):
        probe = _channel_selector().hub_accepts_client_gps(self.base_url + "/api/gps/policy")
        self.assertIs(probe, True)

    def test_update_and_latest(self):
        update = self._request_json(
            "POST",
            "/api/gps/update",
            {
                "user_id": "integration-u1",
                "lat": 47.6205,
                "lon": -122.3493,
                "accuracy": 5.2,
                "seq": 10,
                "source": "integration_test",
            },
        )
        self.assertEqual(update.get("status"), "ok")
        self.assertGreaterEqual(update.get("active_users", 0), 1)
        self.assertEqual(update["point"]["user_id"], "integration-u1")
        self.assertAlmostEqual(update["point"]["lat"], 47.6205, places=4)
        self.assertIn("track", update)
        self.assertGreaterEqual(len(update["track"]), 1)

        latest = self._request_json("GET", "/api/gps/latest")
        self.assertEqual(latest.get("status"), "ok")
        self.assertGreaterEqual(latest.get("active_users", 0), 1)
        self.assertEqual(latest["point"]["user_id"], "integration-u1")
        self.assertAlmostEqual(latest["point"]["lon"], -122.3493, places=4)

    def test_track_limit_and_order(self):
        for i in range(3):
            self._request_json(
                "POST",
                "/api/gps/update",
                {
                    "user_id": "integration-track",
                    "lat": 47.61 + (i * 0.001),
                    "lon": -122.33 - (i * 0.001),
                    "seq": i + 1,
                    "source": "integration_test",
                },
            )
        track = self._request_json("GET", "/api/gps/track?limit=2")
        self.assertEqual(track.get("status"), "ok")
        self.assertEqual(track.get("count"), 2)
        self.assertGreaterEqual(track.get("active_users", 0), 1)
        pts = track["points"]
        self.assertEqual(len(pts), 2)
        self.assertLess(pts[0]["seq"], pts[1]["seq"])

    def test_triangulation_needs_multiple_users(self):
        self._request_json(
            "POST",
            "/api/gps/update",
            {
                "user_id": "single-user-only",
                "lat": 40.7128,
                "lon": -74.0060,
                "seq": 1,
            },
        )
        tri = self._request_json("GET", "/api/gps/triangulation")
        if tri.get("status") == "ok":
            self.assertGreaterEqual(tri.get("active_users", 0), 2)
        else:
            self.assertEqual(tri.get("status"), "insufficient_users")

    def test_triangulation_multi_user_seed(self):
        self._request_json(
            "POST",
            "/api/gps/update",
            {"user_id": "tri-u1", "lat": 47.6000, "lon": -122.3400, "accuracy": 7.0, "seq": 1},
        )
        self._request_json(
            "POST",
            "/api/gps/update",
            {"user_id": "tri-u2", "lat": 47.6020, "lon": -122.3380, "accuracy": 9.0, "seq": 1},
        )
        tri = self._request_json("GET", "/api/gps/triangulation")
        self.assertEqual(tri.get("status"), "ok")
        self.assertEqual(tri.get("method"), "multi_user_centroid_seed")
        self.assertGreaterEqual(tri.get("active_users", 0), 2)
        contributors = tri.get("contributors", [])
        self.assertGreaterEqual(len(contributors), 2)
        contributor_ids = {c.get("user_id") for c in contributors}
        self.assertIn("tri-u1", contributor_ids)
        self.assertIn("tri-u2", contributor_ids)
        contributor_lats = [float(c["lat"]) for c in contributors if "lat" in c]
        contributor_lons = [float(c["lon"]) for c in contributors if "lon" in c]
        self.assertTrue(contributor_lats)
        self.assertTrue(contributor_lons)
        est_lat = float(tri["estimated_lat"])
        est_lon = float(tri["estimated_lon"])
        self.assertGreaterEqual(est_lat, min(contributor_lats))
        self.assertLessEqual(est_lat, max(contributor_lats))
        self.assertGreaterEqual(est_lon, min(contributor_lons))
        self.assertLessEqual(est_lon, max(contributor_lons))

    def test_invalid_update_rejected(self):
        with self.assertRaises(HTTPError) as ctx:
            self._request_json(
                "POST",
                "/api/gps/update",
                {"user_id": "bad", "lat": 91.0, "lon": 0.0},
            )
        self.assertEqual(ctx.exception.code, 400)

    def test_local_route_endpoint_returns_geometry(self):
        self._request_json(
            "POST",
            "/api/gps/update",
            {"user_id": "route-seed", "lat": 47.6205, "lon": -122.3493, "seq": 1},
        )
        route = self._request_json(
            "GET",
            "/api/platform/route/local?origin_lat=47.6205&origin_lon=-122.3493&dest_lat=47.6220&dest_lon=-122.3410&condition=driving_streaming",
        )
        self.assertEqual(route.get("status"), "ok")
        self.assertIn(
            route.get("engine"), ("osrm_openstreetmap", "direct_line_fallback")
        )
        self.assertIn("route_points", route)
        self.assertGreaterEqual(len(route["route_points"]), 2)
        self.assertNotIn("street_segments", route)
        start = route["route_points"][0]
        end = route["route_points"][-1]
        # OSRM snaps endpoints to the road network, so allow ~1km tolerance.
        self.assertAlmostEqual(start["lat"], 47.6205, places=2)
        self.assertAlmostEqual(start["lon"], -122.3493, places=2)
        self.assertAlmostEqual(end["lat"], 47.6220, places=2)
        self.assertAlmostEqual(end["lon"], -122.3410, places=2)

    def test_map_status_shape(self):
        status = self._request_json("GET", "/api/map/status")
        self.assertEqual(status.get("status"), "ok")
        self.assertEqual(status.get("cell_zoom"), 15)
        self.assertEqual(status.get("zoom_ladder"), [15, 13, 11, 9, 7, 5, 3])
        self.assertIn("planet", status)
        self.assertIn("overpass", status)
        self.assertIn("shards", status)
        self.assertIn("prefetch", status)
        self.assertIn("OpenStreetMap", status.get("attribution", ""))

    def test_map_scene_structure(self):
        # Network-dependent: tolerate empty feature sets when the planet
        # extract and Overpass are both unreachable, but the shape must hold.
        scene = self._request_json("GET", "/api/map/scene?lat=48.494&lon=-122.612&radius_m=400")
        self.assertEqual(scene.get("status"), "ok")
        self.assertEqual(scene.get("zoom"), 15)
        for key in ("cells", "roads", "buildings", "areas", "pois", "counts"):
            self.assertIn(key, scene)
        self.assertIn("OpenStreetMap", scene.get("attribution", ""))
        if scene["counts"]["roads"] > 0:
            road = scene["roads"][0]
            self.assertIn("c", road)
            self.assertIn("p", road)
            self.assertEqual(len(road["p"]) % 2, 0)
            self.assertGreaterEqual(len(road["p"]), 4)
            self.assertEqual({c["source"] for c in scene["cells"]} - {"planet", "overpass"}, set())

    def test_map_scene_requires_coordinates_when_no_gps(self):
        # lat/lon omitted falls back to latest GPS; a prior test may have
        # seeded GPS, so accept either 200 (fallback used) or 400.
        try:
            scene = self._request_json("GET", "/api/map/scene")
            self.assertEqual(scene.get("status"), "ok")
        except HTTPError as err:
            self.assertEqual(err.code, 400)

    def test_map_render_returns_png(self):
        status, content_type, body = self._request_bytes(
            "/api/map/render?lat=48.494&lon=-122.612&mpp=1.5&heading=0&tilt=45&w=320&h=320"
        )
        self.assertEqual(status, 200)
        self.assertEqual(content_type, "image/png")
        self.assertEqual(body[:8], b"\x89PNG\r\n\x1a\n")
        self.assertGreater(len(body), 1000)

    def test_map_scene_zoom_ladder_by_radius(self):
        # The resolution filter must drop to lower planet zooms as the
        # requested radius grows (multi-resolution global map support).
        scene_region = self._request_json(
            "GET", "/api/map/scene?lat=48.494&lon=-122.612&radius_m=250000"
        )
        self.assertEqual(scene_region.get("status"), "ok")
        self.assertLessEqual(scene_region.get("zoom"), 9)
        scene_global = self._request_json(
            "GET", "/api/map/scene?lat=48.494&lon=-122.612&radius_m=3000000"
        )
        self.assertEqual(scene_global.get("status"), "ok")
        self.assertLessEqual(scene_global.get("zoom"), 5)
        # Zoomed-out scenes never include buildings (resolution filter).
        self.assertEqual(scene_global["counts"]["buildings"], 0)

    def test_map_scene_explicit_zoom_snaps_to_ladder(self):
        scene = self._request_json(
            "GET", "/api/map/scene?lat=48.494&lon=-122.612&radius_m=700&zoom=7"
        )
        self.assertEqual(scene.get("status"), "ok")
        self.assertEqual(scene.get("zoom"), 7)
        scene_snap = self._request_json(
            "GET", "/api/map/scene?lat=48.494&lon=-122.612&radius_m=700&zoom=12"
        )
        self.assertIn(scene_snap.get("zoom"), (11, 13))

    def test_llm_status_shape(self):
        status = self._request_json("GET", "/api/platform/llm/status")
        self.assertEqual(status.get("status"), "ok")
        self.assertIn("ollama_up", status)
        self.assertEqual(status.get("base_model"), "llama3.1")
        models = status.get("models", {})
        for name in ("scout-alert", "scout-intel", "scout-rank"):
            self.assertIn(name, models)
        # "complete" must be a bool consistent with model availability.
        self.assertIsInstance(status.get("complete"), bool)
        if status["ollama_up"] and all(models.values()):
            self.assertTrue(status["complete"])
        else:
            self.assertFalse(status["complete"])

    def test_map_shard_validation(self):
        with self.assertRaises(HTTPError) as ctx:
            self._request_json("GET", "/api/map/shard?state=ZZ")
        self.assertEqual(ctx.exception.code, 400)
        with self.assertRaises(HTTPError) as ctx2:
            self._request_json("GET", "/api/map/shard")
        self.assertEqual(ctx2.exception.code, 400)
        shard_status = self._request_json("GET", "/api/map/shard?status=1")
        self.assertEqual(shard_status.get("status"), "ok")
        self.assertIn("prefetch", shard_status)

    def test_broadcastify_selector_cross_shard_not_locked(self):
        wa = self._request_json("GET", "/api/platform/broadcastify/select?lat=48.5126&lon=-122.6127")
        tx = self._request_json("GET", "/api/platform/broadcastify/select?lat=29.7604&lon=-95.3698")
        self.assertIsInstance(wa.get("selected"), dict)
        self.assertIsInstance(tx.get("selected"), dict)
        wa_state = str(wa["selected"].get("state", "")).strip()
        tx_state = str(tx["selected"].get("state", "")).strip()
        self.assertTrue(wa_state)
        self.assertTrue(tx_state)
        self.assertNotEqual(
            wa_state,
            tx_state,
            "Selector returned same shard/state for far-apart coordinates; expected cross-shard behavior",
        )


class GpsPolicyIntegrationTests(_BackendTestCase):
    """Hub mode: with no SCOUT_ACCEPT_CLIENT_GPS the backend takes no positions at all."""

    accept_client_gps = None  # leave the variable unset -> shipped default

    GPS_SAMPLE = {
        "user_id": "should-not-be-stored",
        "lat": 33.4484,
        "lon": -112.0740,
        "accuracy": 8.0,
        "seq": 1,
        "source": "integration_test",
    }

    def test_policy_reports_coordinate_free(self):
        status, body, _ = self._request_raw("GET", "/api/gps/policy")
        self.assertEqual(status, 200)
        self.assertFalse(body.get("accepts_client_gps"))
        self.assertEqual(body.get("mode"), "coordinate_free")
        self.assertEqual(body.get("shard_precision"), 4)
        self.assertTrue(body.get("hazards_path"))

    def test_gps_intake_is_refused(self):
        status, body, _ = self._request_raw("POST", "/api/gps/update", self.GPS_SAMPLE)
        self.assertEqual(status, 410)
        self.assertEqual(body.get("error"), "gps_not_accepted")
        # The refusal must not echo the position back.
        self.assertNotIn(str(self.GPS_SAMPLE["lat"]), json.dumps(body))
        self.assertNotIn(str(self.GPS_SAMPLE["lon"]), json.dumps(body))

    def test_gps_replay_endpoints_are_refused(self):
        for path in ("/api/gps/latest", "/api/gps/track", "/api/gps/triangulation"):
            with self.subTest(path=path):
                status, body, _ = self._request_raw("GET", path)
                self.assertEqual(status, 410)
                self.assertEqual(body.get("error"), "gps_not_accepted")

    def test_refused_positions_never_become_selection_or_route_defaults(self):
        # Nothing was stored, so the server must fall back to configured defaults
        # instead of silently using the refused fix.
        status, body, _ = self._request_raw("GET", "/api/map/scene")
        self.assertEqual(status, 400)
        self.assertEqual(body.get("error"), "missing_coordinates")

        status, body, _ = self._request_raw("GET", "/api/platform/route/options?dest_lat=33.1&dest_lon=-112.1")
        self.assertEqual(status, 400)
        self.assertEqual(body.get("error"), "invalid_route_coordinates")

    def test_selector_accepts_a_coarse_shard(self):
        status, body, _ = self._request_raw("GET", "/api/platform/broadcastify/select?shard=9tbq")
        self.assertEqual(status, 200)
        selected = body.get("selected")
        self.assertIsInstance(selected, dict)
        self.assertTrue(str(selected.get("name", "")).strip())

    def test_selector_refuses_a_bad_shard(self):
        status, body, _ = self._request_raw("GET", "/api/platform/broadcastify/select?shard=nope")
        self.assertEqual(status, 400)
        self.assertEqual(body.get("error"), "bad_shard")

    def test_selector_ignores_client_coordinates_in_hub_mode(self):
        # lat/lon are no longer an accepted input on a coordinate-free hub: the client must send
        # its own cell. A coordinate-bearing request therefore has to rank exactly like a
        # parameterless one, proving the position never reached the selector.
        status, with_coords, _ = self._request_raw(
            "GET", "/api/platform/broadcastify/select?lat=48.5126&lon=-122.6127"
        )
        self.assertEqual(status, 200)
        status, without, _ = self._request_raw("GET", "/api/platform/broadcastify/select")
        self.assertEqual(status, 200)
        self.assertEqual(
            str(with_coords.get("selected", {}).get("id", "")),
            str(without.get("selected", {}).get("id", "")),
            "selector honoured client coordinates on a coordinate-free hub",
        )

    def test_hazards_refuse_coordinate_parameters(self):
        for bad in ("lat=33.4&lon=-112.0", "gps=1", "bbox=1,2,3,4", "latitude=33.4"):
            with self.subTest(query=bad):
                status, body, _ = self._request_raw("GET", "/api/platform/hazards?shards=9tbq&" + bad)
                self.assertEqual(status, 400)
                self.assertEqual(body.get("error"), "coordinates_not_accepted")

    def test_hazards_reject_malformed_shards(self):
        for bad in ("", "9tb", "9tbqa", "9tb!!", "a" * 40):
            with self.subTest(shards=bad):
                status, body, _ = self._request_raw("GET", "/api/platform/hazards?shards=" + bad)
                self.assertEqual(status, 400)
                self.assertEqual(body.get("error"), "bad_shards")

    def test_hazards_accept_cells_and_report_upstream_unavailable(self):
        # No hazard shard service runs in this suite, so a well-formed request must fail
        # honestly rather than silently returning an empty answer.
        status, body, _ = self._request_raw("GET", "/api/platform/hazards?shards=9tbq,9tbr,9tbp")
        self.assertEqual(status, 502)
        self.assertEqual(body.get("error"), "hazards_unavailable")

    def test_jurisdiction_resolves_from_cell_centre(self):
        status, body, _ = self._request_raw("GET", "/api/platform/jurisdiction?shard=9tbq")
        self.assertEqual(status, 200)
        self.assertEqual(body.get("cell"), "9tbq")
        self.assertEqual(body.get("precision"), "cell_center")
        # 9tbq is central Phoenix: the centre must come back as lat ~33.49, lon ~-111.97 (not swapped).
        self.assertAlmostEqual(body["center"]["lat"], 33.486, delta=0.01)
        self.assertAlmostEqual(body["center"]["lon"], -111.973, delta=0.01)

    def test_pipeline_policy_probe_sees_coordinate_free(self):
        cs = _channel_selector()
        self.assertIs(cs.hub_accepts_client_gps(self.base_url + "/api/gps/policy"), False)
        # An unreachable hub is "unknown", which the pipeline treats as no GPS.
        self.assertIsNone(cs.hub_accepts_client_gps("http://127.0.0.1:9/api/gps/policy", timeout_seconds=0.5))

    def test_pipeline_shard_centre_matches_hub(self):
        status, body, _ = self._request_raw("GET", "/api/platform/jurisdiction?shard=9tbq")
        self.assertEqual(status, 200)
        lat, lon = _channel_selector().shard_center("9tbq")
        self.assertAlmostEqual(lat, body["center"]["lat"], places=4)
        self.assertAlmostEqual(lon, body["center"]["lon"], places=4)
        self.assertIsNone(_channel_selector().shard_center("33.44,-112.07"))

    def test_selector_cli_shard_ranks_like_the_hub(self):
        # Phoenix (9tbq) is also the catalogue's tie-break winner, so Seattle (c23n) is the
        # case that proves the cell centre, not the fallback order, drove the ranking.
        catalog = str(self.repo_root / "stack/config/broadcastify_channels.national.manifest.json")
        for cell, state in (("9tbq", "AZ"), ("c23n", "WA")):
            with self.subTest(cell=cell):
                out = subprocess.run(
                    [sys.executable, str(self.repo_root / "navigation/pipeline/channel_selector.py"),
                     "--channels-file", catalog, "--shard", cell, "--output-format", "json"],
                    capture_output=True, text=True, timeout=60,
                )
                self.assertEqual(out.returncode, 0, out.stderr)
                cli = json.loads(out.stdout)
                status, hub, _ = self._request_raw("GET", "/api/platform/broadcastify/select?shard=" + cell)
                self.assertEqual(status, 200)
                self.assertEqual(cli["selected"].get("state"), state)
                self.assertEqual(str(cli["selected"]["id"]), str(hub["selected"]["id"]))

    def test_jurisdiction_refuses_coordinates_and_bad_cells(self):
        for query, error in (
            ("shard=9tbq&lat=33.4&lon=-112.0", "coordinates_not_accepted"),
            ("lat=33.4&lon=-112.0", "coordinates_not_accepted"),
            ("shard=9tbq,9tbr", "bad_shard"),
            ("shard=nope", "bad_shard"),
            ("", "bad_shard"),
        ):
            with self.subTest(query=query):
                status, body, _ = self._request_raw("GET", "/api/platform/jurisdiction?" + query)
                self.assertEqual(status, 400)
                self.assertEqual(body.get("error"), error)


if __name__ == "__main__":
    unittest.main()
