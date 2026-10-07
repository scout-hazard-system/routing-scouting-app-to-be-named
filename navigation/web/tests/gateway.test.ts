import { describe, expect, it } from "vitest";
import { RateLimiter, isRejection, parseRoute, scrub, summarizeHazards, tileCenter, upstreamFor } from "../gateway/lib";

const q = (s = "") => new URLSearchParams(s);

describe("allowlist", () => {
  it("rejects everything that is not a public route", () => {
    for (const p of [
      "/admin/status",
      "/platform/alerts/clusters",
      "/map/scene",
      "/mesh/enroll",
      "/mobile/snapshot",
      "/platform/assistant/chat",
      "/platform/dev/stack/manage",
      "/pipeline/stream",
      "/gps/update",
      "/platform/broadcastify/select",
      "/../admin/status"
    ]) {
      const r = parseRoute(p, q());
      expect(isRejection(r) && r.status).toBe(404);
    }
  });

  it("maps the public routes to fixed upstream paths", () => {
    const r = parseRoute("/route", q("origin_lat=33.4&origin_lon=-112.0&dest_lat=33.5&dest_lon=-111.9"));
    expect(isRejection(r)).toBe(false);
    expect(upstreamFor(r as never)).toMatch(/^\/api\/platform\/route\/options\?origin_lat=33\.4/);
    const g = parseRoute("/geocode", q("q=Tempe"));
    expect(upstreamFor(g as never)).toBe("/api/platform/geocode?q=Tempe");
    const a = parseRoute("/alerts", q("cells=9tbq,9TBR"));
    expect(upstreamFor(a as never)).toBe("/api/platform/hazards?shards=9tbq%2C9tbr");
  });

  it("alerts accept only cell ids, never coordinates", () => {
    expect((parseRoute("/alerts", q("cells=9tbq&lat=33.4")) as { error: string }).error).toBe("coordinates_not_accepted");
    expect((parseRoute("/alerts", q("cells=33.44,-112.07")) as { error: string }).error).toBe("bad_cells");
    expect((parseRoute("/alerts", q("cells=" + Array(10).fill(0).map((_, i) => "9tb" + "bcdefghjkm"[i]).join(","))) as { error: string }).error).toBe("bad_cells");
  });

  it("validates tiles, geocode and route inputs", () => {
    expect(isRejection(parseRoute("/tiles/12/700/1600.png", q()))).toBe(false);
    expect((parseRoute("/tiles/2/1/1.png", q()) as { error: string }).error).toBe("bad_tile");
    expect((parseRoute("/tiles/5/40/1.png", q()) as { error: string }).error).toBe("bad_tile");
    expect((parseRoute("/geocode", q("q=" + "x".repeat(201))) as { error: string }).error).toBe("bad_query");
    expect((parseRoute("/geocode", q("q=a&lat=33")) as { error: string }).error).toBe("bad_bias");
    expect((parseRoute("/route", q("origin_lat=95&origin_lon=0&dest_lat=0&dest_lon=0")) as { error: string }).error).toBe("bad_coordinates");
  });
});

describe("responses", () => {
  it("scrub removes transcripts, agent output and provenance at any depth", () => {
    const out = scrub({
      routes: [{ distance_m: 1, route_points: [[1, 2]], alert_clusters: [{ transcript: "radio" }] }],
      alert_clusters: [{ alerts: [{ transcript: "radio" }] }],
      waze_hazards: [1],
      origin: { lat: 1, lon: 2, signature: "s" }
    });
    const text = JSON.stringify(out);
    expect(text).not.toMatch(/transcript|alert_clusters|waze|signature/);
    expect(text).toContain("route_points");
  });

  it("summarizes hazards per cell without ids, titles, descriptions or signatures", () => {
    const payload = {
      shards: {
        "9tbq": [
          { event_id: "a", provider: "tomtom", kind: "closure", severity: "major", road: "I-10", title: "Crash", description: "secret detail", signature: "s", lat: 33.45, lon: -112.07 },
          { event_id: "b", provider: "state511:AZ", kind: "construction", severity: "minor", road: "I-10" },
          { event_id: "c", provider: "wzdx:arizona", kind: "work_zone", severity: "unknown", road: "SR-51" }
        ],
        "9tbr": []
      }
    };
    const [c, ...rest] = summarizeHazards(payload, ["9tbq", "9tbr"]);
    expect(rest).toHaveLength(0);
    expect(c).toMatchObject({ cell: "9tbq", count: 3, worst: "major", roads: ["I-10", "SR-51"] });
    expect(c.sources).toEqual(["AZ 511", "TomTom", "USDOT work zones"]);
    expect(JSON.stringify(c)).not.toMatch(/secret|Crash|signature|33\.45/);
  });

  it("tile centres follow web mercator", () => {
    const c = tileCenter(0, 0, 0);
    expect(c.lat).toBeCloseTo(0, 6);
    expect(c.lon).toBeCloseTo(0, 6);
    expect(c.mpp).toBeCloseTo(156543.03, 1);
    expect(upstreamFor({ kind: "tile", z: 12, x: 700, y: 1600 })).toMatch(/tilt=0&w=256&h=256&tile=1$/);
  });

  it("rate limiter allows the limit per window", () => {
    const rl = new RateLimiter(2, 1000);
    expect([rl.allow("ip", 0), rl.allow("ip", 1), rl.allow("ip", 2), rl.allow("ip", 1001)]).toEqual([true, true, false, true]);
  });
});

describe("3D scenes", () => {
  it("snaps centre, clamps radius, snaps zoom to the ladder, and maps to /api/map/scene", () => {
    const r = parseRoute("/scene", q("lat=33.44841&lon=-112.07401&radius_m=99999&zoom=14"));
    expect(isRejection(r)).toBe(false);
    const s = r as { kind: "scene"; lat: number; lon: number; radiusM: number; zoom: number };
    expect(s.radiusM).toBe(20000);
    expect(s.zoom).toBe(13);
    // two nearby viewers land on the same snapped scene
    const r2 = parseRoute("/scene", q("lat=33.4492&lon=-112.0705&radius_m=20000&zoom=13")) as typeof s;
    expect([r2.lat, r2.lon]).toEqual([s.lat, s.lon]);
    expect(upstreamFor(s)).toMatch(/^\/api\/map\/scene\?lat=[-\d.]+&lon=[-\d.]+&radius_m=20000&zoom=13$/);
  });

  it("rejects bad coordinates and still strips transcripts from scenes", () => {
    expect((parseRoute("/scene", q("lat=91&lon=0")) as { error: string }).error).toBe("bad_coordinates");
    const out = JSON.stringify(scrub({ roads: [{ c: "primary", p: [1, 2, 3, 4] }], alert_clusters: [{ alerts: [{ transcript: "radio" }] }] }));
    expect(out).not.toMatch(/transcript|alert_clusters/);
    expect(out).toContain("primary");
  });
});
