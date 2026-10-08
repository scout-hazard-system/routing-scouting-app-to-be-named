import { describe, expect, it } from "vitest";
import { appendRibbon, buildChunk, type BuildContext } from "../src/chunkBuild";
import type { SceneData } from "../src/sceneData";

const origin = { lat: 33.4, lon: -112.07 };
const ctx: BuildContext = { origin, kLat: 110_540, kLon: 111_320 * Math.cos((origin.lat * Math.PI) / 180) };

const scene = (over: Partial<SceneData> = {}): SceneData => ({
  center: origin,
  radius_m: 700,
  zoom: 15,
  ...over
});

const square = (d = 0.0001): number[] => [
  origin.lat + d,
  origin.lon - d,
  origin.lat + d,
  origin.lon + d,
  origin.lat - d,
  origin.lon + d,
  origin.lat - d,
  origin.lon - d
];

describe("appendRibbon", () => {
  it("needs at least two distinct points", () => {
    const out: number[] = [];
    appendRibbon([origin.lat, origin.lon], 10, out, 0, ctx);
    expect(out).toHaveLength(0);
    appendRibbon([origin.lat, origin.lon, origin.lat, origin.lon], 10, out, 0, ctx);
    expect(out).toHaveLength(0); // duplicate collapsed
    appendRibbon([origin.lat, origin.lon, origin.lat, origin.lon + 0.001], 10, out, 0, ctx);
    expect(out.length).toBe(6 * 3); // one segment, two triangles
  });

  it("puts every vertex at the layer height", () => {
    const out: number[] = [];
    appendRibbon(
      [origin.lat, origin.lon - 0.002, origin.lat, origin.lon, origin.lat + 0.002, origin.lon],
      12,
      out,
      0.7,
      ctx
    );
    for (let i = 1; i < out.length; i += 3) expect(out[i]).toBeCloseTo(0.7, 6);
  });

  it("keeps a mitred corner full width, without gaps", () => {
    const out: number[] = [];
    appendRibbon(
      [origin.lat, origin.lon - 0.001, origin.lat, origin.lon, origin.lat + 0.001, origin.lon],
      20,
      out,
      0,
      ctx
    );
    expect(out.length).toBe(6 * 6); // two segments, four triangles
    // corner vertices spread to both sides of the turn by at least half the width
    const mid = 2;
    const [x, z] = [out[mid * 3], out[mid * 3 + 2]];
    expect(Math.hypot(x, z)).toBeGreaterThan(9);
  });
});

describe("buildChunk", () => {
  it("triangulates areas flat on the ground, relative to the origin", () => {
    const s = scene({ areas: [{ k: "water", p: [origin.lat, origin.lon - 0.002, origin.lat + 0.002, origin.lon, origin.lat - 0.002, origin.lon] }] });
    const chunk = buildChunk(s, ctx, { widthScale: 1 });
    expect(chunk.areas).toHaveLength(1);
    expect(chunk.areas[0].pos.length).toBe(9);
    for (let i = 1; i < chunk.areas[0].pos.length; i += 3) expect(chunk.areas[0].pos[i]).toBeCloseTo(0, 5);
  });

  it("emits a casing and a fill per road class, scaled by widthScale", () => {
    const s = scene({ roads: [{ c: "primary", n: "Main St", p: [origin.lat - 0.002, origin.lon, origin.lat, origin.lon, origin.lat + 0.002, origin.lon] }] });
    const narrow = buildChunk(s, ctx, { widthScale: 1 });
    const wide = buildChunk(s, ctx, { widthScale: 3 });
    expect(narrow.roads).toHaveLength(2); // casing (even order) + fill (odd order)
    expect(new Set(narrow.roads.map((r) => r.order)).size).toBe(2);
    const spread = (chunk: typeof narrow) =>
      Math.max(...chunk.roads.flatMap((r) => Array.from({ length: r.pos.length / 3 }, (_, i) => r.pos[i * 3])));
    expect(spread(wide)).toBeGreaterThan(spread(narrow));
  });

  it("builds shaded buildings with roofs above walls", () => {
    const s = scene({ buildings: [{ h: 12, p: square() }] });
    const chunk = buildChunk(s, ctx, { widthScale: 1 });
    expect(chunk.buildings).not.toBeNull();
    const b = chunk.buildings!;
    expect(b.pos.length % 9).toBe(0);
    expect(b.colors.length).toBe(b.pos.length);
    expect(b.normals.length).toBe(b.pos.length);
    let roofs = 0;
    let walls = 0;
    for (let i = 1; i < b.normals.length; i += 3) {
      if (b.normals[i] > 0.55) roofs++;
      else walls++;
    }
    expect(roofs).toBeGreaterThan(0);
    expect(walls).toBeGreaterThan(0);
  });

  it("ranks place labels above road names above other POIs, at origin-relative positions", () => {
    const s = scene({
      roads: [{ c: "motorway", n: "I-10", p: [origin.lat, origin.lon - 0.002, origin.lat, origin.lon + 0.002] }],
      pois: [
        { n: "Cafe", k: "cafe", lat: origin.lat + 0.001, lon: origin.lon + 0.001 },
        { n: "Tempe", k: "place_town", lat: origin.lat, lon: origin.lon }
      ]
    });
    const labels = buildChunk(s, ctx, { widthScale: 1 }).labels;
    const place = labels.find((l) => l.text === "Tempe")!;
    expect(place.rank).toBe(0);
    expect(place.x).toBeCloseTo(0, 5);
    expect(place.z).toBeCloseTo(0, 5);
    expect(labels.find((l) => l.text === "I-10")!.rank).toBe(1);
    expect(labels.find((l) => l.text === "Cafe")!.rank).toBe(2);
    expect(labels[0].rank).toBe(0); // place drawn first
  });

  it("caps label count so one dense chunk can't flood the overlay", () => {
    const pois = Array.from({ length: 60 }, (_, i) => ({
      n: `p${i}`,
      k: "shop",
      lat: origin.lat + i * 0.0001,
      lon: origin.lon + i * 0.0001
    }));
    const labels = buildChunk(scene({ pois }), ctx, { widthScale: 1 }).labels;
    expect(labels.length).toBeLessThanOrEqual(18);
  });

  it("handles an empty scene without meshes", () => {
    const chunk = buildChunk(scene(), ctx, { widthScale: 1 });
    expect(chunk.areas).toHaveLength(0);
    expect(chunk.roads).toHaveLength(0);
    expect(chunk.buildings).toBeNull();
    expect(chunk.labels).toHaveLength(0);
  });
});
