import { describe, expect, it } from "vitest";
import { snapSceneCenter } from "../gateway/lib";
import {
  BASE_RADIUS_M,
  BOX_PAD,
  baseRangeForDistance,
  chunkCentre,
  chunkKey,
  detailRadiusForDistance,
  detailRangeForDistance,
  desiredChunks,
  fogRangeForDistance
} from "../src/chunkGrid";

const K_LAT = 110_540;
const K_LON = (lat: number) => 111_320 * Math.cos((lat * Math.PI) / 180);

describe("chunk grid", () => {
  it("chunk centres are fixed points of the gateway's snap (shared edge cache)", () => {
    for (const radiusM of [BASE_RADIUS_M, ...([700, 1_500] as const)]) {
      for (const [i, j] of [
        [0, 0],
        [1, 0],
        [0, -1],
        [3, -2],
        [-7, 5],
        [12, 9]
      ]) {
        const c = chunkCentre(radiusM, i, j);
        const snapped = snapSceneCenter(c.lat, c.lon, radiusM);
        expect(snapped.lat).toBe(c.lat);
        expect(snapped.lon).toBe(c.lon);
      }
    }
  });

  it("keys are unique per radius and cell", () => {
    expect(chunkKey(700, 1, 2)).not.toBe(chunkKey(1_500, 1, 2));
    expect(chunkKey(700, 1, 2)).not.toBe(chunkKey(700, 2, 1));
  });

  it("covers the demanded disc with no point left outside any chunk box", () => {
    const radiusM = 700;
    const centre = { lat: 33.4, lon: -112.07 };
    const rangeM = 3_000;
    const chunks = desiredChunks({ radiusM, centre, rangeM });
    expect(chunks.length).toBeGreaterThan(0);

    const kLon = K_LON(centre.lat);
    for (let dz = -rangeM; dz <= rangeM; dz += 150) {
      for (let dx = -rangeM; dx <= rangeM; dx += 150) {
        if (Math.hypot(dx, dz) > rangeM) continue;
        const lat = centre.lat - dz / K_LAT;
        const lon = centre.lon + dx / kLon;
        const inside = chunks.some(
          (c) =>
            Math.abs((lon - c.lon) * kLon) <= radiusM * BOX_PAD + 1 &&
            Math.abs((lat - c.lat) * K_LAT) <= radiusM * BOX_PAD + 1
        );
        expect(inside).toBe(true);
      }
    }
  });

  it("returns chunks nearest-first and only within reach of the disc", () => {
    const chunks = desiredChunks({ radiusM: 1_500, centre: { lat: 33.4, lon: -112.07 }, rangeM: 5_000 });
    for (let i = 1; i < chunks.length; i++) expect(chunks[i].distM).toBeGreaterThanOrEqual(chunks[i - 1].distM);
    for (const c of chunks) expect(c.distM).toBeLessThanOrEqual(5_000 + 1_500 + 1);
  });

  it("honours the frustum visibility filter", () => {
    const args = { radiusM: 700, centre: { lat: 33.4, lon: -112.07 }, rangeM: 3_000 };
    const all = desiredChunks(args);
    expect(desiredChunks({ ...args, visible: () => true })).toHaveLength(all.length);
    expect(desiredChunks({ ...args, visible: () => false })).toHaveLength(0);
    const half = desiredChunks({ ...args, visible: (_lat, _lon, halfM) => halfM > 0 });
    expect(half).toHaveLength(all.length);
  });
});

describe("layers of detail", () => {
  it("picks detail radius by camera distance", () => {
    expect(detailRadiusForDistance(300)).toBe(700);
    expect(detailRadiusForDistance(2_199)).toBe(700);
    expect(detailRadiusForDistance(2_200)).toBe(1_500);
    expect(detailRadiusForDistance(7_999)).toBe(1_500);
    expect(detailRadiusForDistance(8_000)).toBeNull();
    expect(detailRadiusForDistance(15_000)).toBeNull();
  });

  it("grows the detail footprint with distance", () => {
    expect(detailRangeForDistance(1_000, 700)).toBe(1_000 * 1.7 + 700);
  });

  it("drops the base layer only when the detail layer covers the fog line", () => {
    expect(baseRangeForDistance(1_000)).toBeNull();
    expect(baseRangeForDistance(2_000)).toBe(2_000 * 2.4);
    expect(baseRangeForDistance(20_000)).toBe(26_000);
  });

  it("fog always hides whatever the layers loaded", () => {
    for (const d of [400, 1_500, 2_800, 6_000, 10_000, 14_000]) {
      const fog = fogRangeForDistance(d);
      expect(fog).toBeGreaterThanOrEqual(1_500);
      const base = baseRangeForDistance(d);
      if (base !== null) expect(fog).toBeGreaterThanOrEqual(base - 1e-9);
      const detail = detailRadiusForDistance(d);
      if (detail !== null) expect(fog).toBeGreaterThanOrEqual(detailRangeForDistance(d, detail) - 1e-9);
    }
  });
});
