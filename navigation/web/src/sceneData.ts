// Vector scene payload as the map engine serves it over /api/map/scene
// (via the public gateway), plus the small shared types of the 3D view.
// Kept separate from scene3d.ts so the chunk builder and tests can use them
// without importing the renderer.

export interface SceneData {
  center: { lat: number; lon: number };
  radius_m: number;
  zoom: number;
  areas?: { k: string; p: number[] }[];
  roads?: { c: string; n?: string; ow?: number; p: number[] }[];
  buildings?: { h: number; p: number[] }[];
  pois?: { n: string; k: string; lat: number; lon: number }[];
}

export interface Beacon {
  lat: number;
  lon: number;
  count: number;
  color: string;
}

export interface ViewRequest {
  lat: number;
  lon: number;
  radiusM: number;
}
