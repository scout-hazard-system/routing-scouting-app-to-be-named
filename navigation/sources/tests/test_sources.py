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
from scout_sources.runner import Seen, poll_once  # noqa: E402

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
            first = poll_once(PHX, ["AZ"], ["nws", "511", "wzdx", "tomtom"], log_file=log, seen=seen)
            second = poll_once(PHX, ["AZ"], ["nws", "511", "wzdx", "tomtom"], log_file=log, seen=seen)
            self.assertEqual(first["new_or_changed"], 4)
            self.assertEqual(second["new_or_changed"], 0)
            with open(log, encoding="utf-8") as fh:
                lines = fh.read().splitlines()
            self.assertEqual(len(lines), 4)
            self.assertTrue(all(l.startswith("[EVENT_JSON] ") and '"hazard_event"' in l for l in lines))


if __name__ == "__main__":
    unittest.main()
