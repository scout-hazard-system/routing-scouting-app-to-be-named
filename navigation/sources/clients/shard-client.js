// Scout hazard shard client (browser / WebView / Node 18+). Zero dependencies.
//
// Privacy contract: the device's position is used ONLY here, on the device,
// to compute coarse geohash cells. Only cell ids are sent to the hub
// (GET /v1/hazards?shards=...); the hub refuses coordinate parameters.
// Requesting the 3x3 neighbourhood also blurs which cell the device is in.
//
//   import { cellsAround, fetchHazards } from "./shard-client.js";
//   const cells = cellsAround(pos.coords.latitude, pos.coords.longitude);
//   const { shards } = await fetchHazards("https://<edge>/hazards", cells);

export const PRECISION = 4;
const BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";

export function encode(lat, lon, precision = PRECISION) {
  const latR = [-90, 90], lonR = [-180, 180];
  let even = true, bit = 0, ch = 0, out = "";
  while (out.length < precision) {
    const [r, v] = even ? [lonR, lon] : [latR, lat];
    const mid = (r[0] + r[1]) / 2;
    if (v >= mid) { ch = (ch << 1) | 1; r[0] = mid; } else { ch <<= 1; r[1] = mid; }
    even = !even;
    if (++bit === 5) { out += BASE32[ch]; bit = 0; ch = 0; }
  }
  return out;
}

export function bounds(cell) {
  const latR = [-90, 90], lonR = [-180, 180];
  let even = true;
  for (const c of cell) {
    const v = BASE32.indexOf(c);
    for (let s = 4; s >= 0; s--) {
      const r = even ? lonR : latR;
      const mid = (r[0] + r[1]) / 2;
      if ((v >> s) & 1) r[0] = mid; else r[1] = mid;
      even = !even;
    }
  }
  return [latR[0], lonR[0], latR[1], lonR[1]];
}

/** The device's cell plus its 8 neighbours (same order as the hub's Python). */
export function cellsAround(lat, lon, precision = PRECISION) {
  const cell = encode(lat, lon, precision);
  const [a, b, c, d] = bounds(cell);
  const dlat = c - a, dlon = d - b, clat = (a + c) / 2, clon = (b + d) / 2;
  const out = [];
  for (const i of [-1, 0, 1]) {
    for (const j of [-1, 0, 1]) {
      const la = Math.max(-89.999, Math.min(89.999, clat + i * dlat));
      const lo = ((((clon + j * dlon + 180) % 360) + 360) % 360) - 180;
      const g = encode(la, lo, precision);
      if (!out.includes(g)) out.push(g);
    }
  }
  return out;
}

/** Fetch current hazards for cell ids only. Never pass coordinates here. */
export async function fetchHazards(baseUrl, cells, { signal } = {}) {
  if (!Array.isArray(cells) || cells.some((c) => !/^[0-9b-hjkmnp-z]{4}$/.test(c))) {
    throw new Error("fetchHazards: expected geohash-4 cell ids");
  }
  const url = `${baseUrl.replace(/\/+$/, "")}/v1/hazards?shards=${cells.join(",")}`;
  const res = await fetch(url, { signal, headers: { Accept: "application/json" } });
  if (!res.ok) throw new Error(`hazards ${res.status}`);
  return res.json();
}
