// Browser side of the public gateway. Everything goes to same-origin /api/*;
// the Pages Function decides what (if anything) reaches the map server.
import "./shard-client.js"; // UMD: defines globalThis.ScoutShards (same file the hub's frontend ships)

interface ShardsApi {
  cellsAround(lat: number, lon: number): string[];
  cellOf(lat: number, lon: number): string;
  cellCenter(cell: string): { lat: number; lon: number };
}
export const Shards = (globalThis as unknown as { ScoutShards: ShardsApi }).ScoutShards;

export type Severity = "unknown" | "minor" | "moderate" | "major" | "severe";

export interface Cluster {
  cell: string;
  lat: number;
  lon: number;
  count: number;
  worst: Severity;
  kinds: Record<string, number>;
  roads: string[];
  sources: string[];
}

export interface Place {
  name: string;
  lat: number;
  lon: number;
}

export interface RouteOption {
  label: string;
  distanceKm?: number;
  durationMin?: number;
  points: [number, number][];
}

export class ApiError extends Error {
  constructor(public status: number, public code: string) {
    super(code);
  }
}

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`/api${path}`, { headers: { Accept: "application/json" } });
  let body: unknown = null;
  try {
    body = await res.json();
  } catch {
    /* non-JSON */
  }
  if (!res.ok) throw new ApiError(res.status, String((body as { error?: string } | null)?.error ?? res.status));
  return body as T;
}

export async function health(): Promise<boolean> {
  try {
    return (await get<{ online: boolean }>("/health")).online === true;
  } catch {
    return false;
  }
}

export async function alertClusters(cells: string[]): Promise<Cluster[]> {
  return (await get<{ clusters: Cluster[] }>(`/alerts?cells=${cells.join(",")}`)).clusters ?? [];
}

const n = (v: unknown): number | undefined => {
  const x = typeof v === "string" ? Number(v) : v;
  return typeof x === "number" && Number.isFinite(x) ? x : undefined;
};

/** Search places. Bias (optional) is the cell centre, never the device fix. */
export async function geocode(q: string, bias?: { lat: number; lon: number }): Promise<Place[]> {
  const qs = new URLSearchParams({ q });
  if (bias) {
    qs.set("lat", bias.lat.toFixed(3));
    qs.set("lon", bias.lon.toFixed(3));
  }
  const body = await get<Record<string, unknown>>(`/geocode?${qs}`);
  const rows = (Array.isArray(body.results) ? body.results : Array.isArray(body.candidates) ? body.candidates : Array.isArray(body) ? body : []) as Record<string, unknown>[];
  return rows
    .map((r) => ({
      name: String(r.display_name ?? r.label ?? r.name ?? r.address ?? "").slice(0, 160),
      lat: n(r.lat) ?? NaN,
      lon: n(r.lon ?? r.lng) ?? NaN
    }))
    .filter((p) => p.name && Number.isFinite(p.lat) && Number.isFinite(p.lon))
    .slice(0, 8);
}

function toPoints(geom: unknown): [number, number][] {
  if (!Array.isArray(geom)) return [];
  const pts: [number, number][] = [];
  for (const p of geom) {
    if (Array.isArray(p) && p.length >= 2) {
      const a = n(p[0]);
      const b = n(p[1]);
      if (a !== undefined && b !== undefined) pts.push(Math.abs(a) <= 90 ? [a, b] : [b, a]);
    } else if (p && typeof p === "object") {
      const o = p as Record<string, unknown>;
      const lat = n(o.lat);
      const lon = n(o.lon ?? o.lng);
      if (lat !== undefined && lon !== undefined) pts.push([lat, lon]);
    }
  }
  return pts;
}

/** Route search: an explicit user action, so exact endpoints are sent for this request only. */
export async function routeOptions(from: Place, to: Place): Promise<RouteOption[]> {
  const qs = new URLSearchParams({
    origin_lat: from.lat.toFixed(5),
    origin_lon: from.lon.toFixed(5),
    dest_lat: to.lat.toFixed(5),
    dest_lon: to.lon.toFixed(5)
  });
  const body = await get<Record<string, unknown>>(`/route?${qs}`);
  const list = [
    ...(Array.isArray(body.routes) ? body.routes : []),
    ...(Array.isArray(body.alternatives) ? body.alternatives : [])
  ] as Record<string, unknown>[];
  return list
    .map((r, i) => {
      const meters = n(r.distance_m ?? r.distance);
      const seconds = n(r.duration_s ?? r.duration);
      return {
        label: String(r.label ?? r.name ?? (i === 0 ? "Fastest" : `Alternative ${i}`)).slice(0, 60),
        distanceKm: meters !== undefined ? meters / 1000 : n(r.distance_km),
        durationMin: seconds !== undefined ? seconds / 60 : n(r.duration_min),
        points: toPoints(r.route_points ?? r.geometry ?? r.points ?? r.coordinates)
      };
    })
    .filter((r) => r.points.length >= 2);
}
