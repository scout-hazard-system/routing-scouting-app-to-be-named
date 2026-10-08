// Turns one SceneData payload into raw mesh buffers: areas, road ribbons
// (casing + fill, mitred so corners have no gaps), extruded buildings with
// per-face vertex colours (light roofs, sun-shaded walls) and label anchors.
//
// Pure geometry: no renderer, no DOM — runs in chunkBuild.worker.ts during
// normal use and directly in tests / as a main-thread fallback.
import * as THREE from "three";
import { mergeGeometries } from "three/examples/jsm/utils/BufferGeometryUtils.js";
import type { SceneData } from "./sceneData";

export interface BuildContext {
  origin: { lat: number; lon: number };
  kLat: number;
  kLon: number;
}

export interface BuildOptions {
  /** Road width multiplier: wide scenes scale roads so they don't vanish at pixel width. */
  widthScale: number;
  maxBuildings?: number;
}

export interface BuiltArea {
  color: number;
  pos: Float32Array;
}

export interface BuiltRoad {
  color: number;
  /** Draw order: styleOrder*2 (casing) then +1 (fill), so each casing underpins its own fill. */
  order: number;
  pos: Float32Array;
}

export interface BuiltBuildings {
  pos: Float32Array;
  normals: Float32Array;
  colors: Float32Array;
}

export interface BuiltLabel {
  text: string;
  /** 0 = place, 1 = road name, 2 = other point of interest (lower draws first). */
  rank: number;
  x: number;
  z: number;
}

export interface BuiltChunk {
  areas: BuiltArea[];
  roads: BuiltRoad[];
  buildings: BuiltBuildings | null;
  labels: BuiltLabel[];
}

const AREA_COLOR: [RegExp, number][] = [
  [/water|river|lake|reservoir|ocean|bay|basin/, 0x0f2c3d],
  [/park|forest|wood|grass|meadow|garden|nature|golf|green|cemetery|pitch|playground/, 0x10291d],
  [/sand|beach|desert|bare|scrub/, 0x2a2618],
  [/industrial|commercial|retail|parking|railway|military|airport|aerodrome/, 0x161c22],
  [/./, 0x111820]
];
// [width m, colour, style order] — higher classes draw above lower ones
const ROAD_STYLE: [RegExp, number, number, number][] = [
  [/motorway/, 18, 0x00c9c7, 6],
  [/trunk/, 15, 0x3fd8d5, 5],
  [/primary/, 12, 0xd6dee3, 4],
  [/secondary/, 10, 0xaab8c2, 3],
  [/tertiary/, 8, 0x8797a3, 2],
  [/rail/, 4, 0x6b5a7a, 2],
  [/path|foot|cycle|track|steps|pedestrian|bridleway/, 2.5, 0x3a4650, 0],
  [/./, 6, 0x5d6b78, 1]
];
const CASING_COLOR = 0x04070a;
const CASING_GROWTH = 0.3; // casing is fill width * (1 + this), min +3 m
const SUN = (() => {
  const v = new THREE.Vector3(-3_000, 6_000, 2_000).normalize();
  return [v.x, v.y, v.z] as const;
})();
const ROOF_COLOR: readonly [number, number, number] = [0.62, 0.72, 0.8];
const WALL_COLOR: readonly [number, number, number] = [0.3, 0.39, 0.47];

function areaColor(kind: string): number {
  for (const [re, c] of AREA_COLOR) if (re.test(kind)) return c;
  return 0x111820;
}

function roadStyle(cls: string): { w: number; color: number; order: number } {
  for (const [re, w, color, order] of ROAD_STYLE) if (re.test(cls)) return { w, color, order };
  return { w: 6, color: 0x5d6b78, order: 1 };
}

/** lat/lon -> local metres (x east, z south) around the session origin. */
function toXZ(lat: number, lon: number, ctx: BuildContext): [number, number] {
  return [(lon - ctx.origin.lon) * ctx.kLon, -(lat - ctx.origin.lat) * ctx.kLat];
}

/** Flat [lat, lon, ...] -> a Shape in (east, north) metres. */
function shape(p: number[], ctx: BuildContext): THREE.Shape | null {
  if (!p || p.length < 6) return null;
  const pts: THREE.Vector2[] = [];
  for (let i = 0; i + 1 < p.length; i += 2) {
    const [x, z] = toXZ(p[i], p[i + 1], ctx);
    pts.push(new THREE.Vector2(x, -z));
  }
  return pts.length >= 3 ? new THREE.Shape(pts) : null;
}

/**
 * Append a polyline ribbon of full width w (metres) to pos as a continuous
 * mitred strip: joins are bridged instead of left as per-segment quads, so
 * corners no longer show gaps. `y` is the layer height.
 */
export function appendRibbon(flat: number[], w: number, out: number[], y: number, ctx: BuildContext): void {
  const pts: number[] = []; // world x, z pairs, consecutive duplicates dropped
  for (let i = 0; i + 1 < flat.length; i += 2) {
    const [x, z] = toXZ(flat[i], flat[i + 1], ctx);
    const n = pts.length;
    if (n >= 2 && Math.hypot(x - pts[n - 2], z - pts[n - 1]) < 0.01) continue;
    pts.push(x, z);
  }
  const m = pts.length / 2;
  if (m < 2) return;
  const half = w / 2;

  // Per-vertex offset points (left/right of travel direction).
  const left = new Float64Array(m * 2);
  const right = new Float64Array(m * 2);
  const segNormal = (k: number): [number, number] => {
    const a = k * 2;
    const dx = pts[a + 2] - pts[a];
    const dz = pts[a + 3] - pts[a + 1];
    const len = Math.hypot(dx, dz) || 1;
    return [-dz / len, dx / len];
  };
  for (let i = 0; i < m; i++) {
    let nx: number;
    let nz: number;
    let scale = half;
    if (i === 0) {
      [nx, nz] = segNormal(0);
    } else if (i === m - 1) {
      [nx, nz] = segNormal(m - 2);
    } else {
      const [ax, az] = segNormal(i - 1);
      const [bx, bz] = segNormal(i);
      let sx = ax + bx;
      let sz = az + bz;
      const sl = Math.hypot(sx, sz);
      if (sl < 1e-6) {
        // 180° turn: fall back to the incoming normal
        nx = ax;
        nz = az;
      } else {
        sx /= sl;
        sz /= sl;
        const cosHalf = Math.max(0.35, sx * bx + sz * bz); // miter stretch, clamped
        if (half / cosHalf > half * 4) {
          nx = bx;
          nz = bz; // spike: round the join off instead
        } else {
          nx = sx;
          nz = sz;
          scale = half / cosHalf;
        }
      }
    }
    const x = pts[i * 2];
    const z = pts[i * 2 + 1];
    left[i * 2] = x + nx * scale;
    left[i * 2 + 1] = z + nz * scale;
    right[i * 2] = x - nx * scale;
    right[i * 2 + 1] = z - nz * scale;
  }
  for (let i = 0; i + 1 < m; i++) {
    const a = i * 2;
    const b = (i + 1) * 2;
    out.push(
      left[a], y, left[a + 1],
      right[a], y, right[a + 1],
      left[b], y, left[b + 1],
      right[a], y, right[a + 1],
      right[b], y, right[b + 1],
      left[b], y, left[b + 1]
    );
  }
}

const MAX_POI_LABELS = 14;
const MAX_ROAD_LABELS = 4;

/** Build every mesh buffer for one scene chunk. */
export function buildChunk(data: SceneData, ctx: BuildContext, opts: BuildOptions): BuiltChunk {
  const out: BuiltChunk = { areas: [], roads: [], buildings: null, labels: [] };

  // ---- Areas: flat, merged per colour ----
  const areaGeos = new Map<number, THREE.BufferGeometry[]>();
  for (const a of data.areas ?? []) {
    const s = shape(a.p, ctx);
    if (!s) continue;
    const g = new THREE.ShapeGeometry(s);
    g.rotateX(-Math.PI / 2);
    const c = areaColor(a.k);
    const list = areaGeos.get(c) ?? [];
    list.push(g);
    areaGeos.set(c, list);
  }
  for (const [color, geos] of areaGeos) {
    const merged = mergeGeometries(geos.map((g) => g.toNonIndexed()));
    geos.forEach((g) => g.dispose());
    if (!merged) continue;
    out.areas.push({ color, pos: toFloat32(merged.getAttribute("position")) });
    merged.dispose();
  }

  // ---- Roads: casing + fill ribbons per class ----
  const widthScale = Math.max(1, opts.widthScale);
  const ribbonBuf = new Map<string, { color: number; order: number; pos: number[] }>();
  const roadLabels: BuiltLabel[] = [];
  for (const r of data.roads ?? []) {
    const style = roadStyle(r.c);
    const fillOrder = style.order * 2 + 1;
    const casingOrder = style.order * 2;
    const w = style.w * widthScale;
    const push = (color: number, order: number, width: number) => {
      const key = `${order}:${color}`;
      const buf = ribbonBuf.get(key) ?? { color, order, pos: [] };
      appendRibbon(r.p, width, buf.pos, 0, ctx);
      ribbonBuf.set(key, buf);
    };
    push(CASING_COLOR, casingOrder, w + Math.max(3, w * CASING_GROWTH));
    push(style.color, fillOrder, w);
    if (r.n && style.order >= 4 && roadLabels.length < MAX_ROAD_LABELS && r.p.length >= 4) {
      const mid = Math.floor(r.p.length / 4) * 2;
      const [x, z] = toXZ(r.p[mid], r.p[mid + 1], ctx);
      roadLabels.push({ text: r.n, rank: 1, x, z });
    }
  }
  for (const { color, order, pos } of ribbonBuf.values()) {
    if (pos.length === 0) continue;
    out.roads.push({ color, order, pos: Float32Array.from(pos) });
  }

  // ---- Buildings: extruded, one merged mesh with per-face vertex colours ----
  const bGeos: THREE.BufferGeometry[] = [];
  for (const b of (data.buildings ?? []).slice(0, opts.maxBuildings ?? 8_000)) {
    const s = shape(b.p, ctx);
    if (!s) continue;
    const g = new THREE.ExtrudeGeometry(s, { depth: Math.max(3, Math.min(b.h || 6, 400)), bevelEnabled: false });
    g.rotateX(-Math.PI / 2);
    g.deleteAttribute("uv");
    bGeos.push(g);
  }
  if (bGeos.length) {
    const merged = mergeGeometries(bGeos);
    bGeos.forEach((g) => g.dispose());
    if (merged) {
      merged.computeVertexNormals(); // non-indexed → flat face normals
      const pos = merged.getAttribute("position");
      const normals = merged.getAttribute("normal");
      const colors = new Float32Array(pos.count * 3);
      for (let f = 0; f < pos.count; f += 3) {
        const nx = normals.getX(f);
        const ny = normals.getY(f);
        const nz = normals.getZ(f);
        let rgb: readonly [number, number, number];
        if (ny > 0.55) {
          rgb = ROOF_COLOR;
        } else {
          // Walls pick up the sun's horizontal direction: lit faces brighter, others sunk.
          const lit = Math.max(0, nx * SUN[0] + ny * SUN[1] + nz * SUN[2]);
          const shade = 0.55 + 0.45 * lit;
          rgb = [WALL_COLOR[0] * shade, WALL_COLOR[1] * shade, WALL_COLOR[2] * shade];
        }
        for (let v = 0; v < 3; v++) {
          colors[(f + v) * 3] = rgb[0];
          colors[(f + v) * 3 + 1] = rgb[1];
          colors[(f + v) * 3 + 2] = rgb[2];
        }
      }
      out.buildings = {
        pos: toFloat32(pos),
        normals: toFloat32(normals),
        colors
      };
      merged.dispose();
    }
  }

  // ---- Labels: places first, then road names, then other points of interest ----
  const pois = [...(data.pois ?? [])].sort((a, b) => {
    const rank = (k: string) => (k.startsWith("place") ? 0 : 2);
    return rank(a.k) - rank(b.k);
  });
  for (const p of pois) {
    if (out.labels.length >= MAX_POI_LABELS + MAX_ROAD_LABELS) break;
    const [x, z] = toXZ(p.lat, p.lon, ctx);
    out.labels.push({ text: p.n, rank: p.k.startsWith("place") ? 0 : 2, x, z });
  }
  out.labels.push(...roadLabels);
  return out;
}

function toFloat32(attr: { array: ArrayLike<number> }): Float32Array {
  return new Float32Array(attr.array);
}
