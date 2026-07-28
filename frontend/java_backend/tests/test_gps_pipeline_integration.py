import json
import os
import socket
import subprocess
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


class GpsPipelineIntegrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repo_dir = Path("/home/gibi/Desktop/frontend/java_backend")
        cls.server_source = cls.repo_dir / "ScannerBackendServer.java"
        cls.build_dir = Path(tempfile.mkdtemp(prefix="scanner-backend-test-build-"))
        cls.port = _free_port()
        cls.log_file = Path(tempfile.mkstemp(prefix="scanner-backend-test-log-", suffix=".log")[1])
        cls.pipeline_log = Path(tempfile.mkstemp(prefix="scanner-backend-pipeline-log-", suffix=".log")[1])
        cls.base_url = f"http://127.0.0.1:{cls.port}"

        compile_cmd = ["javac", "-d", str(cls.build_dir), str(cls.server_source)]
        compile_result = subprocess.run(compile_cmd, cwd=str(cls.repo_dir), capture_output=True, text=True)
        if compile_result.returncode != 0:
            raise RuntimeError(
                f"Backend compilation failed:\nSTDOUT:\n{compile_result.stdout}\nSTDERR:\n{compile_result.stderr}"
            )

        env = os.environ.copy()
        env["JAVA_BACKEND_HOST"] = "127.0.0.1"
        env["JAVA_BACKEND_PORT"] = str(cls.port)
        env["PIPELINE_LOG_PATH"] = str(cls.pipeline_log)

        log_handle = open(cls.log_file, "w", encoding="utf-8")
        cls._log_handle = log_handle
        cls.proc = subprocess.Popen(
            ["java", "-cp", str(cls.build_dir), "ScannerBackendServer"],
            cwd=str(cls.repo_dir),
            env=env,
            stdout=log_handle,
            stderr=log_handle,
        )
        cls._wait_for_health()

    @classmethod
    def tearDownClass(cls):
        if getattr(cls, "proc", None) is not None:
            cls.proc.terminate()
            try:
                cls.proc.wait(timeout=8)
            except subprocess.TimeoutExpired:
                cls.proc.kill()
                cls.proc.wait(timeout=5)
        if getattr(cls, "_log_handle", None):
            cls._log_handle.close()

    @classmethod
    def _wait_for_health(cls):
        deadline = time.time() + 12.0
        last_err = None
        while time.time() < deadline:
            try:
                health = cls._request_json("GET", "/api/health")
                if health.get("status") == "ok":
                    return
            except Exception as exc:  # noqa: BLE001
                last_err = exc
            time.sleep(0.2)
        raise RuntimeError(f"Backend did not become healthy in time: {last_err}")

    @classmethod
    def _request_json(cls, method: str, path: str, payload: dict | None = None):
        url = cls.base_url + path
        body = None
        headers = {"Accept": "application/json"}
        if payload is not None:
            headers["Content-Type"] = "application/json"
            body = json.dumps(payload).encode("utf-8")
        req = Request(url, data=body, method=method, headers=headers)
        with urlopen(req, timeout=8.0) as resp:
            return json.loads(resp.read().decode("utf-8"))

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
        self.assertEqual(route.get("engine"), "standalone_matrix_router")
        self.assertIn("route_points", route)
        self.assertGreaterEqual(len(route["route_points"]), 2)
        start = route["route_points"][0]
        end = route["route_points"][-1]
        self.assertAlmostEqual(start["lat"], 47.6205, places=3)
        self.assertAlmostEqual(start["lon"], -122.3493, places=3)
        self.assertAlmostEqual(end["lat"], 47.6220, places=3)
        self.assertAlmostEqual(end["lon"], -122.3410, places=3)


if __name__ == "__main__":
    unittest.main()
