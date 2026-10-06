// Public gateway rules for the Scout user frontend (Cloudflare Pages Functions).
//
// The map server on the Dell also serves admin, mesh, mobile, assistant, pipeline
// and radio-transcript data. None of that may leave through the public site, so
// this module is an allowlist: a handful of read-only routes, validated inputs,
// and responses rebuilt from the fields the user frontend needs (never passed
// through wholesale). Pure functions only, so it is unit-testable.

export const CELL_RE = /^[0-9b-hjkmnp-z]{4}$/;
export const MAX_CELLS = 9; // the 3x3 neighbourhood the client computes on-device
const BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";

/** Keys that must never reach the public: transcripts, agent output, third-party raw feeds, provenance internals. */
const STRIP_KEYS = new Set([
  "alert_clusters",
  "alerts",
  "transcript",
  "waze_hazards",
  "waze_route",
  "signature",
  "content_hash",
  "ingest_host",
  "source_url",
  "user_id",
  "token"
]);

export type Route =
  | { kind: "health" }
  | { kind: "tile"; z: number; x: number; y: number }
  | { kind: "geocode"; q: string; lat?: number; lon?: number }
  | { kind: "route"; originLat: number; originLon: number; destLat: number; destLon: number }
  | { kind: "alerts"; cells: string[] };

export type Rejection = { status: number; error: string };

const num = (v: string | null): number | undefined => {
  if (v === null || v.trim() === "") return undefined;
  const n = Number(v);
  return Number.isFinite(n) ? n : undefined;
};
const isLat = (n: number | undefined): n is number => n !== undefined && n >= -90 && n <= 90;
const isLon = (n: number | undefined): n is number => n !== undefined && n >= -180 && n <= 180;

/** Map a request path + query to an allowed route, or a rejection. `path` excludes the /api prefix. */
export function parseRoute(path: string, q: URLSearchParams): Route | Rejection {
  const p = path.replace(/\/+$/, "") || "/";
  if (p === "/health") return { kind: "health" };

  const tile = /^\/tiles\/(\d{1,2})\/(\d{1,7})\/(\d{1,7})\.png$/.exec(p);
  if (tile) {
    const [z, x, y] = [Number(tile[1]), Number(tile[2]), Number(tile[3])];
    const n = 2 ** z;
    if (z < 3 || z > 19 || x >= n || y >= n) return { status: 400, error: "bad_tile" };
    return { kind: "tile", z, x, y };
  }

  if (p === "/geocode") {
    const text = (q.get("q") ?? "").trim();
    if (!text || text.length > 200) return { status: 400, error: "bad_query" };
    const lat = num(q.get("lat"));
    const lon = num(q.get("lon"));
    // bias is optional; the client sends its cell centre, never its fix
    if ((lat === undefined) !== (lon === undefined) || (lat !== undefined && (!isLat(lat) || !isLon(lon)))) {
      return { status: 400, error: "bad_bias" };
    }
    return { kind: "geocode", q: text, ...(lat !== undefined ? { lat, lon } : {}) };
  }

  if (p === "/route") {
    const o = [num(q.get("origin_lat")), num(q.get("origin_lon"))];
    const d = [num(q.get("dest_lat")), num(q.get("dest_lon"))];
    if (!isLat(o[0]) || !isLon(o[1]) || !isLat(d[0]) || !isLon(d[1])) return { status: 400, error: "bad_coordinates" };
    return { kind: "route", originLat: o[0], originLon: o[1], destLat: d[0], destLon: d[1] };
  }

  if (p === "/alerts") {
    for (const k of q.keys()) if (k !== "cells") return { status: 400, error: "coordinates_not_accepted" };
    const cells = [...new Set((q.get("cells") ?? "").split(",").map((c) => c.trim().toLowerCase()).filter(Boolean))];
    if (cells.length === 0 || cells.length > MAX_CELLS || !cells.every((c) => CELL_RE.test(c))) {
      return { status: 400, error: "bad_cells" };
    }
    return { kind: "alerts", cells };
  }

  return { status: 404, error: "not_found" };
}

export function isRejection(r: Route | Rejection): r is Rejection {
  return "error" in r;
}

/** Web-mercator tile -> centre point and metres-per-pixel at 256 px. */
export function tileCenter(z: number, x: number, y: number): { lat: number; lon: number; mpp: number } {
  const n = 2 ** z;
  const lon = ((x + 0.5) / n) * 360 - 180;
  const lat = (Math.atan(Math.sinh(Math.PI * (1 - (2 * (y + 0.5)) / n))) * 180) / Math.PI;
  const mpp = (156543.03392 * Math.cos((lat * Math.PI) / 180)) / n;
  return { lat, lon, mpp };
}

/** Upstream path + query on the map server for an allowed route (tiles render flat, north-up). */
export function upstreamFor(route: Route): string {
  const qs = (o: Record<string, string | number>) =>
    new URLSearchParams(Object.entries(o).map(([k, v]) => [k, String(v)])).toString();
  switch (route.kind) {
    case "health":
      return "/api/health";
    case "tile": {
      const c = tileCenter(route.z, route.x, route.y);
      return `/api/map/render?${qs({ lat: c.lat.toFixed(6), lon: c.lon.toFixed(6), mpp: c.mpp.toFixed(4), heading: 0, tilt: 0, w: 256, h: 256 })}`;
    }
    case "geocode":
      return `/api/platform/geocode?${qs({ q: route.q, ...(route.lat !== undefined ? { lat: route.lat.toFixed(3), lon: route.lon!.toFixed(3) } : {}) })}`;
    case "route":
      return `/api/platform/route/options?${qs({ origin_lat: route.originLat, origin_lon: route.originLon, dest_lat: route.destLat, dest_lon: route.destLon })}`;
    case "alerts":
      return `/api/platform/hazards?${qs({ shards: route.cells.join(",") })}`;
  }
}

/** Deep copy without any STRIP_KEYS, at any depth. */
export function scrub(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(scrub);
  if (value && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      if (!STRIP_KEYS.has(k)) out[k] = scrub(v);
    }
    return out;
  }
  return value;
}

export function cellCenter(cell: string): { lat: number; lon: number } {
  const lat = [-90, 90];
  const lon = [-180, 180];
  let even = true;
  for (const ch of cell) {
    const v = BASE32.indexOf(ch);
    for (let s = 4; s >= 0; s--) {
      const r = even ? lon : lat;
      const mid = (r[0] + r[1]) / 2;
      if ((v >> s) & 1) r[0] = mid;
      else r[1] = mid;
      even = !even;
    }
  }
  return { lat: (lat[0] + lat[1]) / 2, lon: (lon[0] + lon[1]) / 2 };
}

const SEVERITY = ["unknown", "minor", "moderate", "major", "severe"] as const;
export type Severity = (typeof SEVERITY)[number];

const SOURCE_NAMES: Record<string, string> = {
  nws: "National Weather Service",
  tomtom: "TomTom",
  wzdx: "USDOT work zones",
  state511: "State 511"
};

export function sourceName(provider: string): string {
  const base = provider.split(":")[0];
  const st = provider.includes(":") ? provider.split(":")[1] : "";
  if (base === "state511" && st) return `${st.toUpperCase()} 511`;
  if (base === "wzdx") return SOURCE_NAMES.wzdx;
  return SOURCE_NAMES[base] ?? "Public feed";
}

export interface ClusterSummary {
  cell: string;
  lat: number;
  lon: number;
  count: number;
  worst: Severity;
  kinds: Record<string, number>;
  roads: string[];
  sources: string[];
}

/**
 * Hazard service payload ({shards: {cell: HazardEvent[]}}) -> one public summary per cell.
 * Only counts, the worst severity, kind mix, most-mentioned roads and source names survive:
 * no ids, titles, descriptions, signatures or exact event coordinates.
 */
export function summarizeHazards(payload: unknown, cells: string[]): ClusterSummary[] {
  const shards = (payload as { shards?: Record<string, unknown> } | null)?.shards ?? {};
  const out: ClusterSummary[] = [];
  for (const cell of cells) {
    const list = Array.isArray(shards[cell]) ? (shards[cell] as Record<string, unknown>[]) : [];
    if (list.length === 0) continue;
    let worst = 0;
    const kinds: Record<string, number> = {};
    const roads = new Map<string, number>();
    const sources = new Set<string>();
    for (const e of list) {
      const sev = SEVERITY.indexOf(String(e.severity ?? "unknown") as Severity);
      if (sev > worst) worst = sev;
      const kind = String(e.kind ?? "other").slice(0, 24);
      kinds[kind] = (kinds[kind] ?? 0) + 1;
      const road = String(e.road ?? "").trim().slice(0, 60);
      if (road) roads.set(road, (roads.get(road) ?? 0) + 1);
      if (e.provider) sources.add(sourceName(String(e.provider)));
    }
    const c = cellCenter(cell);
    out.push({
      cell,
      lat: Number(c.lat.toFixed(4)),
      lon: Number(c.lon.toFixed(4)),
      count: list.length,
      worst: SEVERITY[worst],
      kinds,
      roads: [...roads.entries()].sort((a, b) => b[1] - a[1]).slice(0, 5).map(([r]) => r),
      sources: [...sources].sort()
    });
  }
  return out;
}

/** Fixed-window per-key limiter (per isolate; Cloudflare WAF rate rules take over once a zone exists). */
export class RateLimiter {
  private hits = new Map<string, { n: number; t: number }>();
  constructor(private limit: number, private windowMs: number) {}
  allow(key: string, now = Date.now()): boolean {
    const h = this.hits.get(key);
    if (!h || now - h.t >= this.windowMs) {
      this.hits.set(key, { n: 1, t: now });
      if (this.hits.size > 10_000) this.hits.clear();
      return true;
    }
    h.n += 1;
    return h.n <= this.limit;
  }
}
