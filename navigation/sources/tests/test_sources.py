"""Offline tests for scout_sources: python -m unittest discover -s navigation/sources/tests"""

import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scout_sources import providers as P  # noqa: E402
from scout_sources.events import HazardEvent, verify  # noqa: E402
from scout_sources.net import Region, redact  # noqa: E402
from scout_sources import runner, shards  # noqa: E402
from scout_sources.runner import Demand, Seen, ShardStore, coverage, poll_once  # noqa: E402

PHX = Region(33.4484, -112.0740, 50)

NWS = {"features": [{"id": "urn:1", "geometry": None, "properties": {
    "id": "urn:1", "event": "Dust Storm Warning", "headline": "Dust storm on I-10",
    "severity": "Severe", "onset": "2026-10-05T20:00:00Z", "expires": "2026-10-05T22:00:00Z",
    "sent": "2026-10-05T19:55:00Z", "areaDesc": "Maricopa"}}]}

S511 = [
    {"ID": "AZ-1", "RoadwayName": "I-10", "DirectionOfTravel": "Eastbound", "Description": "Crash, right lane blocked",
     "EventType": "accidentsAndIncidents", "Severity": "Major", "IsFullClosure": False,
     "Latitude": 33.45, "Longitude": -112.06, "StartDate": 1791230000, "LastUpdated": 1791230600},
    {"ID": "AZ-2", "RoadwayName": "I-17", "EventType": "closures", "IsFullClosure": True,
     "Latitude": 35.19, "Longitude": -111.65},  # Flagstaff: outside the Phoenix region
]

WZDX_REG = [
    {"state": "arizona", "active": "True", "needapikey": "False", "format": "geojson", "version": "4",
     "url": {"url": "https://example.test/az/wzdx?access_token=SECRET"}},
    {"state": "arizona", "active": "True", "needapikey": "True", "format": "geojson", "url": {"url": "https://keyed"}},
]
WZDX_FEED = {"features": [{"id": "wz-1", "geometry": {"type": "LineString", "coordinates": [[-112.07, 33.45], [-112.06, 33.46]]},
                           "properties": {"core_details": {"event_type": "work-zone", "road_names": ["I-10 E"], "direction": "eastbound",
                                                           "description": "Lane shift", "update_date": "2026-10-01T00:00:00Z"},
                                          "start_date": "2026-09-01T00:00:00Z", "vehicle_impact": "some-lanes-closed"}}]}

TOMTOM = {"incidents": [{"type": "Feature", "geometry": {"type": "Point", "coordinates": [-112.05, 33.44]},
                         "properties": {"id": "tt-1", "iconCategory": 1, "magnitudeOfDelay": 3,
                                        "events": [{"description": "Accident"}], "from": "7th St", "to": "16th St",
                                        "roadNumbers": ["I-10"], "delay": 420}}]}


def fake_get_json(url, params=None, headers=None, timeout=20.0):
    if "weather.gov" in url:
        return NWS
    if "/api/v2/get/event" in url:
        assert params and params.get("key") == "k-az"
        return S511
    if "69qe-yiui" in url:
        return WZDX_REG
    if "example.test/az/wzdx" in url:
        return WZDX_FEED
    if "tomtom.com" in url:
        return TOMTOM
    raise AssertionError(f"unexpected url {url}")


class ProvidersTest(unittest.TestCase):
    def setUp(self):
        self.p = mock.patch.object(P, "get_json", side_effect=fake_get_json)
        self.p.start()
        self.env = mock.patch.dict(os.environ, {"SCOUT_511_KEY_AZ": "k-az", "SCOUT_TOMTOM_KEY": "k-tt",
                                                "SCOUT_SOURCES_SIGNING_KEY": "unit-test-key"})
        self.env.start()

    def tearDown(self):
        self.p.stop()
        self.env.stop()

    def test_nws(self):
        (e,) = P.nws(PHX)
        self.assertEqual((e.kind, e.severity, e.title), ("weather", "major", "Dust Storm Warning"))

    def test_state511_maps_and_filters_region(self):
        evs = P.state511(PHX, ["AZ"])
        self.assertEqual([e.native_id for e in evs], ["AZ-1"])  # Flagstaff closure filtered out
        e = evs[0]
        self.assertEqual((e.kind, e.severity, e.road, e.provider), ("incident", "major", "I-10", "state511:AZ"))
        self.assertTrue(e.starts_at.startswith("2026-"))
        self.assertNotIn("key", e.source_url)

    def test_state511_skips_states_without_keys(self):
        self.assertEqual(P.state511(PHX, ["UT"]), [])

    def test_wzdx_keyless_only_and_token_redacted(self):
        (e,) = P.wzdx(PHX, ["AZ"])
        self.assertEqual((e.kind, e.road, e.direction), ("work_zone", "I-10 E", "eastbound"))
        self.assertNotIn("SECRET", e.source_url)

    def test_tomtom(self):
        (e,) = P.tomtom(PHX)
        self.assertEqual((e.kind, e.severity, e.road), ("incident", "major", "I-10"))

    def test_tomtom_tiles_stay_under_cap_and_cover_region(self):
        import math
        for radius in (20, 50, 80, 150):
            reg = Region(33.4484, -112.0740, radius)
            tiles = P.tomtom_tiles(reg)
            k = 111.0 * math.cos(math.radians(reg.lat))
            for a, b, c, d in tiles:
                self.assertLess((c - a) * k * (d - b) * 111.0, 10000.0)
            self.assertAlmostEqual(min(t[0] for t in tiles), reg.bbox()[0])
            self.assertAlmostEqual(max(t[3] for t in tiles), reg.bbox()[3])

    def test_tomtom_without_key_is_inert(self):
        with mock.patch.dict(os.environ, {"SCOUT_TOMTOM_KEY": ""}):
            self.assertEqual(P.tomtom(PHX), [])


class AccountabilityTest(unittest.TestCase):
    def test_hash_signature_and_tamper(self):
        e = HazardEvent(provider="nws", native_id="x", kind="weather", title="t").finalize(b"k")
        d = e.to_dict()
        self.assertTrue(verify(d, b"k"))
        self.assertFalse(verify(d, b"other-key"))
        d["title"] = "changed"
        self.assertFalse(verify(d, b"k"))

    def test_event_id_stable_content_hash_tracks_changes(self):
        a = HazardEvent(provider="p", native_id="1", kind="incident", title="A").finalize(b"k")
        b = HazardEvent(provider="p", native_id="1", kind="incident", title="B").finalize(b"k")
        self.assertEqual(a.event_id, b.event_id)
        self.assertNotEqual(a.content_hash, b.content_hash)

    def test_redact_strips_query(self):
        self.assertEqual(redact("https://h/p?key=SECRET&x=1"), "https://h/p")

    def test_poll_emits_only_new_or_changed(self):
        with mock.patch.object(P, "get_json", side_effect=fake_get_json), \
             mock.patch.dict(os.environ, {"SCOUT_511_KEY_AZ": "k-az", "SCOUT_TOMTOM_KEY": "k-tt"}), \
             tempfile.TemporaryDirectory() as d:
            log = os.path.join(d, "p.log")
            seen = Seen(Path(d) / "seen.json")
            runner._last_call.clear()
            store = ShardStore(Path(d) / "shards.json")
            first = poll_once(PHX, ["AZ"], ["nws", "511", "wzdx", "tomtom"], log_file=log, seen=seen, store=store)
            second = poll_once(PHX, ["AZ"], ["nws", "511", "wzdx", "tomtom"], log_file=log, seen=seen, store=store)
            self.assertEqual(first["new_or_changed"], 4)
            self.assertEqual(second["new_or_changed"], 0)
            with open(log, encoding="utf-8") as fh:
                lines = fh.read().splitlines()
            self.assertEqual(len(lines), 4)
            self.assertTrue(all(l.startswith("[EVENT_JSON] ") and '"hazard_event"' in l for l in lines))



class ShardPrivacyTest(unittest.TestCase):
    def setUp(self):
        import threading
        from http.server import ThreadingHTTPServer
        from scout_sources import serve as S
        self.tmp = tempfile.TemporaryDirectory()
        self.env = mock.patch.dict(os.environ, {"SCOUT_STATE_DIR": self.tmp.name})
        self.env.start()
        store = ShardStore()
        store.update([HazardEvent(provider="tomtom", native_id="1", kind="incident", title="Crash I-10",
                                  lat=33.45, lon=-112.06).finalize(b"k"),
                      HazardEvent(provider="tomtom", native_id="2", kind="closure", title="Far away",
                                  lat=40.76, lon=-111.89).finalize(b"k")])
        store.save()
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), S.make_handler(S._State()))
        self.port = self.httpd.server_address[1]
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def tearDown(self):
        self.httpd.shutdown()
        self.env.stop()
        self.tmp.cleanup()

    def get(self, q):
        import json as J
        import urllib.error
        import urllib.request
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{self.port}{q}", timeout=5) as r:
                return r.status, J.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, J.loads(e.read())

    def test_coordinates_are_refused(self):
        for q in ("/v1/hazards?lat=33.4&lon=-112", "/v1/hazards?shards=9tbq&gps=33.4,-112",
                  "/v1/hazards?shards=9tbq&location=x", "/v1/hazards?shards=9tbq&bbox=1,2,3,4"):
            code, body = self.get(q)
            self.assertEqual((code, body["error"]), (400, "coordinates_not_accepted"), q)

    def test_shards_return_only_requested_cells_and_record_demand(self):
        cells = shards.neighbors(shards.encode(33.4484, -112.074))
        code, body = self.get("/v1/hazards?shards=" + ",".join(cells))
        self.assertEqual(code, 200)
        titles = [e["title"] for evs in body["shards"].values() for e in evs]
        self.assertEqual(titles, ["Crash I-10"])  # the Salt Lake City event is not leaked
        self.assertIn("9tbq", Demand().map)  # coarse demand only, persisted for ingestion

    def test_bad_or_too_many_shards(self):
        self.assertEqual(self.get("/v1/hazards?shards=9tb")[0], 400)
        self.assertEqual(self.get("/v1/hazards?shards=" + ",".join(["9tbq"] * 3 + [f"9tb{c}" for c in "0123456789bcdefghjkmnpqrstuvwxyz"]))[0], 400)

    def test_coverage_prefers_demand_then_configured(self):
        with mock.patch.dict(os.environ, {"SCOUT_SOURCES_SHARDS": "9tbq"}):
            d = Demand()
            d.touch(["dr5r"])
            self.assertEqual(coverage(d).cells, ["dr5r", "9tbq"])



class ClientAssetTest(unittest.TestCase):
    def test_frontend_copy_matches_canonical(self):
        """navigation/frontend ships its own copy (PyInstaller bundles only that dir)."""
        root = Path(__file__).resolve().parents[3]
        canonical = (root / "navigation/sources/clients/shard-client.js").read_bytes().replace(b"\r\n", b"\n")
        shipped = (root / "navigation/frontend/shard-client.js").read_bytes().replace(b"\r\n", b"\n")
        self.assertEqual(canonical, shipped, "copy navigation/sources/clients/shard-client.js to navigation/frontend/")


if __name__ == "__main__":
    unittest.main()
