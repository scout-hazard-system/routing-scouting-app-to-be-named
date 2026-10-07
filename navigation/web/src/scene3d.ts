// Web port of the Android Map3dView: draws the map engine's vector scenes
// (/api/map/scene, via the public gateway) in 3D. Extruded buildings at their real heights,
// roads as ribbons sized by class, water/park/land areas, the route, and hazard clusters as
// beacons. Pan / rotate / tilt / zoom with MapControls; a new scene is requested at the matching
// zoom-ladder rung whenever the view leaves the loaded one.
import * as THREE from "three";
import { MapControls } from "three/examples/jsm/controls/MapControls.js";
import { mergeGeometries } from "three/examples/jsm/utils/BufferGeometryUtils.js";

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

const BG = 0x05090c;
const GROUND = 0x0b1217;
const AREA_COLOR: [RegExp, number][] = [
  [/water|river|lake|reservoir|ocean|bay|basin/, 0x0f2c3d],
  [/park|forest|wood|grass|meadow|garden|nature|golf|green|cemetery|pitch|playground/, 0x10291d],
  [/sand|beach|desert|bare|scrub/, 0x2a2618],
  [/industrial|commercial|retail|parking|railway|military|airport|aerodrome/, 0x161c22],
  [/./, 0x111820]
];
// [width m, colour, draw order]
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
/** Scene radius buckets: viewers share cached scenes, and each maps to one ladder rung server-side. */
const RADIUS_BUCKETS = [700, 1500, 4000, 10_000, 20_000];

function areaColor(kind: string): number {
  for (const [re, c] of AREA_COLOR) if (re.test(kind)) return c;
  return 0x111820;
}

function roadStyle(cls: string): { w: number; color: number; order: number } {
  for (const [re, w, color, order] of ROAD_STYLE) if (re.test(cls)) return { w, color, order };
  return { w: 6, color: 0x5d6b78, order: 1 };
}

export class Scene3D {
  private renderer: THREE.WebGLRenderer;
  private scene = new THREE.Scene();
  private camera: THREE.PerspectiveCamera;
  private controls: MapControls;
  private base: THREE.Group | null = null;
  private hazards = new THREE.Group();
  private route = new THREE.Group();
  private loaded: { lat: number; lon: number; radiusM: number } | null = null;
  private pending = false;
  private raf = 0;
  private resize: ResizeObserver;
  private readonly kLat = 110_540;
  private readonly kLon: number;
  private settleTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private container: HTMLElement,
    private origin: { lat: number; lon: number },
    startZoom: number,
    private requestScene: (v: ViewRequest) => Promise<SceneData>,
    private onStatus: (s: string) => void
  ) {
    this.kLon = 111_320 * Math.cos((origin.lat * Math.PI) / 180);
    this.renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: "high-performance" });
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    this.renderer.setClearColor(BG);
    container.appendChild(this.renderer.domElement);

    this.scene.fog = new THREE.Fog(BG, 8_000, 60_000);
    this.scene.add(new THREE.HemisphereLight(0xcfe8ff, 0x0b1217, 0.9));
    const sun = new THREE.DirectionalLight(0xffffff, 1.1);
    sun.position.set(-3_000, 6_000, 2_000);
    this.scene.add(sun);
    const ground = new THREE.Mesh(new THREE.PlaneGeometry(400_000, 400_000), new THREE.MeshBasicMaterial({ color: GROUND, depthWrite: false }));
    ground.renderOrder = -2;
    ground.rotation.x = -Math.PI / 2;
    ground.position.y = -0.5;
    this.scene.add(ground, this.hazards, this.route);

    this.camera = new THREE.PerspectiveCamera(50, 1, 5, 250_000);
    const dist = distanceForZoom(startZoom);
    this.camera.position.set(0, dist * 0.75, dist * 0.65); // ~49° tilt, looking north
    this.controls = new MapControls(this.camera, this.renderer.domElement);
    this.controls.enableDamping = true;
    this.controls.dampingFactor = 0.08;
    this.controls.minDistance = 150;
    this.controls.maxDistance = 60_000;
    this.controls.maxPolarAngle = 1.3; // keep the horizon below ~75°
    this.controls.screenSpacePanning = false;
    this.controls.target.set(0, 0, 0);
    this.controls.addEventListener("end", () => this.scheduleLoad());

    this.resize = new ResizeObserver(() => this.fit());
    this.resize.observe(container);
    this.fit();
    const loop = () => {
      this.raf = requestAnimationFrame(loop);
      this.controls.update();
      // keep depth precision at every zoom: planes follow the camera distance
      const d = this.camera.position.distanceTo(this.controls.target);
      const near = Math.max(1, d / 200);
      if (Math.abs(near - this.camera.near) > near * 0.2) {
        this.camera.near = near;
        this.camera.far = d * 60 + 20_000;
        this.camera.updateProjectionMatrix();
      }
      this.renderer.render(this.scene, this.camera);
    };
    loop();
    void this.load();
  }

  /** lat/lon -> local metres (x east, y up, z south) around the fixed session origin. */
  private xz(lat: number, lon: number): [number, number] {
    return [(lon - this.origin.lon) * this.kLon, -(lat - this.origin.lat) * this.kLat];
  }

  private latLonAt(x: number, z: number): { lat: number; lon: number } {
    return { lat: this.origin.lat - z / this.kLat, lon: this.origin.lon + x / this.kLon };
  }

  private fit() {
    const w = this.container.clientWidth || 1;
    const h = this.container.clientHeight || 1;
    this.renderer.setSize(w, h, false);
    this.camera.aspect = w / h;
    this.camera.updateProjectionMatrix();
  }

  /** What the camera is looking at, as a scene request (radius bucketed). */
  private view(): ViewRequest {
    const t = this.controls.target;
    const d = this.camera.position.distanceTo(t);
    const want = d * 1.3;
    const radiusM = RADIUS_BUCKETS.find((b) => b >= want) ?? RADIUS_BUCKETS[RADIUS_BUCKETS.length - 1];
    return { ...this.latLonAt(t.x, t.z), radiusM };
  }

  private scheduleLoad() {
    if (this.settleTimer) clearTimeout(this.settleTimer);
    this.settleTimer = setTimeout(() => void this.load(), 350);
  }

  private async load() {
    if (this.pending) return;
    const v = this.view();
    if (this.loaded) {
      const [ax, az] = this.xz(this.loaded.lat, this.loaded.lon);
      const [bx, bz] = this.xz(v.lat, v.lon);
      const moved = Math.hypot(ax - bx, az - bz);
      if (v.radiusM === this.loaded.radiusM && moved < this.loaded.radiusM * 0.45) return;
    }
    this.pending = true;
    this.onStatus("Loading 3D scene…");
    try {
      const data = await this.requestScene(v);
      this.setBase(data);
      this.loaded = { lat: data.center.lat, lon: data.center.lon, radiusM: v.radiusM };
      this.onStatus("");
    } catch (err) {
      // The map component's requestScene decides what a failure means (e.g. fall back to 2D).
      this.onStatus(err instanceof Error ? err.message : "Scene failed to load.");
    } finally {
      this.pending = false;
    }
  }

  private setBase(data: SceneData) {
    const group = new THREE.Group();

    // Areas: flat, merged per colour.
    const areaGeos = new Map<number, THREE.BufferGeometry[]>();
    for (const a of data.areas ?? []) {
      const shape = this.shape(a.p);
      if (!shape) continue;
      const g = new THREE.ShapeGeometry(shape);
      g.rotateX(-Math.PI / 2);
      const c = areaColor(a.k);
      (areaGeos.get(c) ?? areaGeos.set(c, []).get(c)!).push(g);
    }
    for (const [color, geos] of areaGeos) {
      const merged = mergeGeometries(geos.map((g) => g.toNonIndexed()));
      geos.forEach((g) => g.dispose());
      if (!merged) continue;
      // flat layers draw in a fixed order without depth writes (no z-fighting between overlaps)
      const m = new THREE.Mesh(merged, new THREE.MeshBasicMaterial({ color, side: THREE.DoubleSide, depthWrite: false }));
      m.position.y = 0.1;
      m.renderOrder = 0;
      group.add(m);
    }

    // Roads: ribbons, merged per class style; higher classes drawn above lower ones.
    // Real-world widths vanish below a pixel on wide scenes; scale with the scene radius.
    const widthScale = Math.max(1, (data.radius_m || 1500) / 1500);
    const roadBuf = new Map<string, { style: ReturnType<typeof roadStyle>; pos: number[] }>();
    for (const r of data.roads ?? []) {
      const style = roadStyle(r.c);
      const key = `${style.color}:${style.w}`;
      const buf = roadBuf.get(key) ?? roadBuf.set(key, { style, pos: [] }).get(key)!;
      this.ribbon(r.p, style.w * widthScale, buf.pos);
    }
    for (const { style, pos } of roadBuf.values()) {
      if (pos.length === 0) continue;
      const g = new THREE.BufferGeometry();
      g.setAttribute("position", new THREE.Float32BufferAttribute(pos, 3));
      const m = new THREE.Mesh(g, new THREE.MeshBasicMaterial({ color: style.color, side: THREE.DoubleSide, depthWrite: false }));
      m.position.y = 0.4 + style.order * 0.08;
      m.renderOrder = 1 + style.order;
      group.add(m);
    }

    // Buildings: extruded to their height, one merged lit mesh.
    const bGeos: THREE.BufferGeometry[] = [];
    for (const b of (data.buildings ?? []).slice(0, 8000)) {
      const shape = this.shape(b.p);
      if (!shape) continue;
      const g = new THREE.ExtrudeGeometry(shape, { depth: Math.max(3, Math.min(b.h || 6, 400)), bevelEnabled: false });
      g.rotateX(-Math.PI / 2);
      g.deleteAttribute("uv");
      bGeos.push(g);
    }
    if (bGeos.length) {
      const merged = mergeGeometries(bGeos);
      bGeos.forEach((g) => g.dispose());
      if (merged) {
        merged.computeVertexNormals();
        const bm = new THREE.Mesh(merged, new THREE.MeshLambertMaterial({ color: 0x3a4d5c, side: THREE.DoubleSide }));
        bm.renderOrder = 10;
        group.add(bm);
      }
    }

    if (this.base) {
      this.scene.remove(this.base);
      disposeGroup(this.base);
    }
    this.base = group;
    this.scene.add(group);
  }

  /** Flat [lat, lon, lat, lon, ...] -> a Shape in (east, north) metres. */
  private shape(p: number[]): THREE.Shape | null {
    if (!p || p.length < 6) return null;
    const pts: THREE.Vector2[] = [];
    for (let i = 0; i + 1 < p.length; i += 2) {
      const [x, z] = this.xz(p[i], p[i + 1]);
      pts.push(new THREE.Vector2(x, -z));
    }
    return pts.length >= 3 ? new THREE.Shape(pts) : null;
  }

  /** Append triangles for a polyline ribbon of width w (metres) to pos. */
  private ribbon(p: number[], w: number, pos: number[], y = 0) {
    const half = w / 2;
    for (let i = 0; i + 3 < p.length; i += 2) {
      const [x1, z1] = this.xz(p[i], p[i + 1]);
      const [x2, z2] = this.xz(p[i + 2], p[i + 3]);
      const len = Math.hypot(x2 - x1, z2 - z1);
      if (len < 0.01) continue;
      const nx = (-(z2 - z1) / len) * half;
      const nz = ((x2 - x1) / len) * half;
      pos.push(x1 + nx, y, z1 + nz, x1 - nx, y, z1 - nz, x2 + nx, y, z2 + nz);
      pos.push(x2 + nx, y, z2 + nz, x1 - nx, y, z1 - nz, x2 - nx, y, z2 - nz);
    }
  }

  setHazards(beacons: Beacon[]) {
    disposeGroup(this.hazards);
    this.hazards.clear();
    for (const b of beacons) {
      const [x, z] = this.xz(b.lat, b.lon);
      const h = Math.min(2_500, 250 + Math.sqrt(b.count) * 45);
      const color = new THREE.Color(b.color);
      const beam = new THREE.Mesh(
        new THREE.CylinderGeometry(60, 60, h, 24, 1, true),
        new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.45, side: THREE.DoubleSide, depthWrite: false })
      );
      beam.position.set(x, h / 2, z);
      const ring = new THREE.Mesh(
        new THREE.RingGeometry(260, 330, 48),
        new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.7, side: THREE.DoubleSide, depthWrite: false })
      );
      ring.rotation.x = -Math.PI / 2;
      ring.position.set(x, 2, z);
      this.hazards.add(beam, ring);
    }
  }

  setRoute(points: [number, number][] | null) {
    disposeGroup(this.route);
    this.route.clear();
    if (!points || points.length < 2) return;
    const flat = points.flatMap(([lat, lon]) => [lat, lon]);
    const pos: number[] = [];
    this.ribbon(flat, 14, pos, 0);
    const g = new THREE.BufferGeometry();
    g.setAttribute("position", new THREE.Float32BufferAttribute(pos, 3));
    const m = new THREE.Mesh(g, new THREE.MeshBasicMaterial({ color: 0x00e5e2, side: THREE.DoubleSide, depthTest: false }));
    m.position.y = 3;
    m.renderOrder = 20;
    this.route.add(m);
    // frame the whole route
    let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;
    for (const [lat, lon] of points) {
      const [x, z] = this.xz(lat, lon);
      minX = Math.min(minX, x); maxX = Math.max(maxX, x); minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
    }
    const span = Math.max(maxX - minX, maxZ - minZ, 800);
    this.lookAt((minX + maxX) / 2, (minZ + maxZ) / 2, span * 1.1);
  }

  /** Move the view to a place (e.g. a picked location) at a ladder zoom. */
  focus(lat: number, lon: number, zoom: number) {
    const [x, z] = this.xz(lat, lon);
    this.lookAt(x, z, distanceForZoom(zoom));
  }

  private lookAt(x: number, z: number, dist: number) {
    const dir = this.camera.position.clone().sub(this.controls.target).normalize();
    this.controls.target.set(x, 0, z);
    this.camera.position.set(x + dir.x * dist, Math.max(dir.y, 0.5) * dist, z + dir.z * dist);
    this.controls.update();
    this.scheduleLoad();
  }

  dispose() {
    cancelAnimationFrame(this.raf);
    if (this.settleTimer) clearTimeout(this.settleTimer);
    this.resize.disconnect();
    this.controls.dispose();
    if (this.base) disposeGroup(this.base);
    disposeGroup(this.hazards);
    disposeGroup(this.route);
    this.renderer.dispose();
    this.renderer.domElement.remove();
  }
}

/** Camera distance for a slippy-map style zoom level (15 = streets, 11 = metro area). */
export function distanceForZoom(zoom: number): number {
  return Math.min(60_000, Math.max(300, 700 * 2 ** (15 - zoom)));
}

function disposeGroup(g: THREE.Object3D) {
  g.traverse((o) => {
    const m = o as THREE.Mesh;
    m.geometry?.dispose();
    const mat = m.material as THREE.Material | THREE.Material[] | undefined;
    if (Array.isArray(mat)) mat.forEach((x) => x.dispose());
    else mat?.dispose();
  });
}
