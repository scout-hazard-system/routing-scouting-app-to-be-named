// Scout hazard shard client (browser / WebView / Node 18+). Zero dependencies.
//
// Privacy contract: the device's position is used ONLY here, on the device,
// to compute coarse geohash cells. Only cell ids are sent to the hub
// (GET /v1/hazards?shards=...); the hub refuses coordinate parameters.
// Requesting the 3x3 neighbourhood also blurs which cell the device is in.
//
// The file is dual-mode so a plain <script> tag works without a bundler and
// `import` still works for tests and other ESM consumers:
//
//   <script src="/shard-client.js"></script>
//   ScoutShards.cellsAround(pos.coords.latitude, pos.coords.longitude);
//
//   import { cellsAround, fetchHazards } from "./shard-client.js";
//   const cells = cellsAround(33.4484, -112.0740);
//   const { shards } = await fetchHazards("https://<edge>/hazards", cells);

(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) {
    module.exports = api;
  }
  if (root) {
    root.ScoutShards = api;
  }
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const PRECISION = 4;
  const BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";
  const CELL_PATTERN = /^[0-9b-hjkmnp-z]{4}$/;

  function encode(lat, lon, precision = PRECISION) {
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

  function bounds(cell) {
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
  function cellsAround(lat, lon, precision = PRECISION) {
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

  /**
   * The single cell a device sits in. Prefer `cellsAround` for hazard requests;
   * this is for coarse centring (map, jurisdiction) where one cell must be chosen.
   */
  function cellOf(lat, lon, precision = PRECISION) {
    return encode(lat, lon, precision);
  }

  /** Centre of a cell, the coarsest position any consumer of a shard should use. */
  function cellCenter(cell) {
    const [minLat, minLon, maxLat, maxLon] = bounds(cell);
    return { lat: (minLat + maxLat) / 2, lon: (minLon + maxLon) / 2 };
  }

  function isCellId(value) {
    return typeof value === "string" && CELL_PATTERN.test(value);
  }

  /**
   * Fetch current hazards for cell ids only. Never pass coordinates here.
   *
   * `baseUrl` may be the hazard service itself ("https://<host>/") or the hub's
   * platform proxy ("https://<host>/api/platform/hazards"); the endpoint suffix is
   * only appended when the caller has not already pointed at a hazards endpoint.
   */
  async function fetchHazards(baseUrl, cells, { signal } = {}) {
    if (!Array.isArray(cells) || !cells.length || cells.some((c) => !isCellId(c))) {
      throw new Error("fetchHazards: expected geohash-4 cell ids");
    }
    let base = String(baseUrl || "").replace(/\/+$/, "");
    if (!/\/hazards$/.test(base)) {
      base = `${base}/v1/hazards`;
    }
    const url = `${base}?shards=${cells.join(",")}`;
    const res = await fetch(url, { signal, headers: { Accept: "application/json" } });
    if (!res.ok) throw new Error(`hazards ${res.status}`);
    return res.json();
  }

  return { PRECISION, BASE32, encode, bounds, cellsAround, cellOf, cellCenter, isCellId, fetchHazards };
});