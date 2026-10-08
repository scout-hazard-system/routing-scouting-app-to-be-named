// Grid math for streaming the 3D map as chunks instead of one scene.
//
// The gateway snaps every scene centre to a grid a quarter of the radius wide
// (gateway/lib.ts snapSceneCenter) so neighbouring viewers share one cached
// request. Chunks sit ON that grid: chunk (i, j) is grid cell (i*STRIDE, j*STRIDE),
// i.e. centres are STRIDE * radius/4 = 2*radius apart, while the engine pads each
// scene to ±1.2*radius and emits whole features that touch that box. Adjacent
// chunk boxes therefore overlap and the plane is covered with no gaps — and since
// the request centres are already grid points, the gateway's snap is a no-op and
// every viewer's chunks hit the same edge cache.

/** Base layer radius: one chunk is a z13 district scene (~200 KB gzipped). */
export const BASE_RADIUS_M = 4_000;
/** Detail layer radii: z15 street scenes with buildings. */
export const DETAIL_RADII_M = [700, 1_500] as const;
/** Grid cells between chunk centres → chunk spacing = STRIDE * radius/4 = 2*radius. */
export const STRIDE = 8;
/** Server-side scene half-extent as a multiple of the radius (ProprietaryMapEngine pads ±1.2r). */
export const BOX_PAD = 1.2;

export interface LatLon {
  lat: number;
  lon: number;
}

export interface DesiredChunk {
  radiusM: number;
  i: number;
  j: number;
  lat: number;
  lon: number;
  /** Metres from the view centre (drives fetch priority). */
  distM: number;
}

const latStepDeg = (radiusM: number) => radiusM / 4 / 110_540;
const lonStepDeg = (radiusM: number, lat: number) =>
  radiusM / 4 / (111_320 * Math.max(0.2, Math.cos((lat * Math.PI) / 180)));
/** The gateway rounds snapped centres to 5 decimals; mirror it so re-snapping is a no-op. */
const snap5 = (v: number) => Number(v.toFixed(5));

/** Centre of chunk (i, j): a point that survives snapSceneCenter() unchanged. */
export function chunkCentre(radiusM: number, i: number, j: number): LatLon {
  const lat = snap5(i * STRIDE * latStepDeg(radiusM));
  return { lat, lon: snap5(j * STRIDE * lonStepDeg(radiusM, lat)) };
}

export function chunkKey(radiusM: number, i: number, j: number): string {
  return `${radiusM}:${i}:${j}`;
}

export interface DesiredOptions {
  radiusM: number;
  centre: LatLon;
  /** Disc radius around the view centre that must be covered. */
  rangeM: number;
  /** Optional visibility test (camera frustum) in degrees + box half-extent in metres. */
  visible?: (lat: number, lon: number, halfM: number) => boolean;
}

/** Every chunk whose box contributes to covering the disc, nearest to the view centre first. */
export function desiredChunks(o: DesiredOptions): DesiredChunk[] {
  const { radiusM, centre, rangeM } = o;
  const sLat = STRIDE * latStepDeg(radiusM);
  const kLat = 110_540;
  const kLon = 111_320 * Math.cos((centre.lat * Math.PI) / 180);
  const iMin = Math.floor((centre.lat - rangeM / kLat) / sLat);
  const iMax = Math.ceil((centre.lat + rangeM / kLat) / sLat);
  const out: DesiredChunk[] = [];
  for (let i = iMin; i <= iMax; i++) {
    const rowLat = snap5(i * sLat);
    const sLon = STRIDE * lonStepDeg(radiusM, rowLat);
    const rowKLon = 111_320 * Math.cos((rowLat * Math.PI) / 180);
    const jMin = Math.floor((centre.lon - rangeM / rowKLon) / sLon);
    const jMax = Math.ceil((centre.lon + rangeM / rowKLon) / sLon);
    for (let j = jMin; j <= jMax; j++) {
      const lon = snap5(j * sLon);
      const distM = Math.hypot((rowLat - centre.lat) * kLat, (lon - centre.lon) * rowKLon);
      // A centre up to range+radius away still contributes its box edge to the disc.
      if (distM > rangeM + radiusM) continue;
      if (o.visible && !o.visible(rowLat, lon, radiusM * BOX_PAD * Math.SQRT2)) continue;
      out.push({ radiusM, i, j, lat: rowLat, lon, distM });
    }
  }
  out.sort((a, b) => a.distM - b.distM);
  return out;
}

/**
 * Detail layer: street-level (z15) chunks stacked over the base while the camera
 * is close enough to benefit; they fade out as the view widens, base underneath
 * keeps the map whole.
 */
export function detailRadiusForDistance(d: number): number | null {
  if (d < 2_200) return DETAIL_RADII_M[0];
  if (d < 8_000) return DETAIL_RADII_M[1];
  return null;
}

export function detailRangeForDistance(d: number, radiusM: number): number {
  // Capped: past a few km the base layer is what the eye resolves anyway, and
  // street chunks are the expensive ones (~z15), so don't flood the queue.
  return Math.min(d * 1.7 + radiusM, 4_500);
}

/**
 * Base layer coverage: the ground the camera can actually see, bounded so a
 * close-up view doesn't pull district-sized chunks for ground the fog hides.
 * null = close enough that the detail layer alone covers the fog line.
 */
export function baseRangeForDistance(d: number): number | null {
  const range = d * 2.4;
  if (range < 3_500) return null;
  return Math.min(range, 26_000);
}

/** Distance at which the fog has fully hidden the ground: nothing beyond it is loaded. */
export function fogRangeForDistance(d: number): number {
  const base = baseRangeForDistance(d) ?? 0;
  const detail = detailRadiusForDistance(d);
  const detailRange = detail !== null ? detailRangeForDistance(d, detail) : 0;
  return Math.max(1_500, base, detailRange);
}
