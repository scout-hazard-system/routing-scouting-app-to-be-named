// Streaming web port of the Android Map3dView: the map is drawn as grid-aligned
// scene chunks (via the public gateway's /api/map/scene) instead of one scene that
// is thrown away whenever the view moves. A coarse base layer covers the fog line
// at every distance, a street-detail layer stacks over it while the camera is close,
// chunks stream in at the edges as you pan (never blanking the middle), fade in,
// and are evicted once they leave the view. Geometry is built in a worker so panning
// stays smooth; roads are mitred ribbons with dark casings, buildings carry shaded
// vertex colours, and place/road labels ride an HTML overlay.
import * as THREE from "three";
import { MapControls } from "three/examples/jsm/controls/MapControls.js";
import { appendRibbon, buildChunk, type BuildContext, type BuildOptions, type BuiltChunk, type BuiltLabel } from "./chunkBuild";
import {
  BASE_RADIUS_M,
  baseRangeForDistance,
  chunkKey,
  detailRadiusForDistance,
  detailRangeForDistance,
  desiredChunks,
  fogRangeForDistance,
  type DesiredChunk
} from "./chunkGrid";
import type { Beacon, SceneData, ViewRequest } from "./sceneData";

export type { Beacon, SceneData, ViewRequest } from "./sceneData";

const BG = 0x05090c;
const GROUND = 0x0b1217;
/** renderOrder offset for the detail layer, so street data draws over the base district. */
const DETAIL_OFFSET = 30;
const HAZARD_ORDER = 60;
const ROUTE_ORDER = 65;
/** Simultaneous scene requests: enough to fill the view, few enough to stay polite. */
const CONCURRENCY = 5;
const MAX_CHUNKS = 72;
/** Desired-set recompute cadence while the camera is moving. */
const EVAL_MS = 250;
/** A chunk must stay unwanted this long before it fades out (anti-thrash hysteresis). */
const EVICT_AFTER_MS = 900;
const FADE_IN_MS = 300;
const FADE_OUT_MS = 220;
const MAX_LABELS = 48;

interface ChunkEntry {
  key: string;
  radiusM: number;
  level: "base" | "detail";
  cx: number;
  cz: number;
  group: THREE.Group;
  materials: THREE.Material[];
  labels: BuiltLabel[];
  fadingOut: number | null;
  fadeInAt: number;
  settled: boolean;
  wanted: boolean;
  lastWanted: number;
}

export class Scene3D {
  private renderer: THREE.WebGLRenderer;
  private scene = new THREE.Scene();
  private camera: THREE.PerspectiveCamera;
  private controls: MapControls;
  private hazards = new THREE.Group();
  private route = new THREE.Group();
  private chunks = new Map<string, ChunkEntry>();
  private inflight = new Set<string>();
  private failures = new Map<string, { attempts: number; next: number }>();
  private queue: DesiredChunk[] = [];
  private worker: Worker | null | undefined; // undefined = not tried yet
  private buildWaiters = new Map<number, (err: string | null, chunk: BuiltChunk | null) => void>();
  private buildSeq = 0;
  private frustum = new THREE.Frustum();
  private projScreen = new THREE.Matrix4();
  private sphere = new THREE.Sphere(new THREE.Vector3(), 1);
  private tmpVec = new THREE.Vector3();
  private dirty = true;
  private lastEval = 0;
  private status = "";
  private disposed = false;
  private frame = 0;
  private labelLayer: HTMLDivElement;
  private labelEls = new Map<string, HTMLElement>();
  private raf = 0;
  private resize: ResizeObserver;
  private readonly kLat = 110_540;
  private readonly kLon: number;

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

    this.labelLayer = document.createElement("div");
    this.labelLayer.className = "sc-3d-labels";
    container.appendChild(this.labelLayer);

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
    this.controls.maxDistance = 15_000; // base chunks + fog cover the ground out to here
    this.controls.maxPolarAngle = 1.3; // keep the horizon below ~75°
    this.controls.screenSpacePanning = false;
    this.controls.target.set(0, 0, 0);
    this.controls.update();
    const markDirty = () => {
      this.dirty = true;
    };
    this.controls.addEventListener("change", markDirty);

    this.resize = new ResizeObserver(() => this.fit());
    this.resize.observe(container);
    this.fit();
    this.setStatus("Loading 3D area…");
    const loop = () => {
      this.raf = requestAnimationFrame(loop);
      const now = performance.now();
      this.controls.update();
      // keep depth precision at every zoom: planes follow the camera distance
      const d = this.camera.position.distanceTo(this.controls.target);
      const near = Math.max(1, d / 200);
      if (Math.abs(near - this.camera.near) > near * 0.2) {
        this.camera.near = near;
        this.camera.far = d * 60 + 20_000;
        this.camera.updateProjectionMatrix();
      }
      // the fog line IS the load boundary: ground fades out where chunks stop
      const fogRange = fogRangeForDistance(d);
      const fog = this.scene.fog as THREE.Fog;
      fog.near = fogRange * 0.55;
      fog.far = fogRange * 1.02;
      this.updateFades(now);
      if ((this.dirty && now - this.lastEval >= EVAL_MS) || now - this.lastEval >= 2_000) {
        this.evaluate(now);
      }
      if ((this.frame++ & 1) === 0) this.updateLabels();
      this.renderer.render(this.scene, this.camera);
    };
    loop();
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

  private buildContext(): BuildContext {
    return { origin: this.origin, kLat: this.kLat, kLon: this.kLon };
  }

  private setStatus(s: string) {
    if (s !== this.status) {
      this.status = s;
      this.onStatus(s);
    }
  }

  /**
   * Recompute the wanted chunk set for the current camera: base layer across the
   * fog line, detail layer near the target, both frustum-culled. Wanted chunks
   * that aren't loaded yet are queued nearest-first; unwanted ones age out.
   */
  private evaluate(now: number) {
    this.dirty = false;
    this.lastEval = now;
    const t = this.controls.target;
    const d = this.camera.position.distanceTo(t);
    const centre = this.latLonAt(t.x, t.z);

    this.camera.updateMatrixWorld();
    this.projScreen.multiplyMatrices(this.camera.projectionMatrix, this.camera.matrixWorldInverse);
    this.frustum.setFromProjectionMatrix(this.projScreen);

    const wanted = new Set<string>();
    const missing: DesiredChunk[] = [];
    const consider = (spec: DesiredChunk) => {
      const key = chunkKey(spec.radiusM, spec.i, spec.j);
      wanted.add(key);
      if (this.chunks.has(key) || this.inflight.has(key)) return;
      const fail = this.failures.get(key);
      if (fail && now < fail.next) return;
      missing.push(spec);
    };
    const addLevel = (radiusM: number, rangeM: number) => {
      for (const spec of desiredChunks({
        radiusM,
        centre,
        rangeM,
        visible: (lat, lon, halfM) => {
          const [x, z] = this.xz(lat, lon);
          this.sphere.center.set(x, 0, z);
          this.sphere.radius = halfM;
          return this.frustum.intersectsSphere(this.sphere);
        }
      })) {
        consider(spec);
      }
    };

    const baseRange = baseRangeForDistance(d);
    if (baseRange !== null) addLevel(BASE_RADIUS_M, baseRange);
    const detailR = detailRadiusForDistance(d);
    if (detailR !== null) addLevel(detailR, detailRangeForDistance(d, detailR));

    for (const e of this.chunks.values()) {
      if (wanted.has(e.key)) {
        e.wanted = true;
        e.lastWanted = now;
      } else {
        e.wanted = false;
        if (e.fadingOut === null && now - e.lastWanted > EVICT_AFTER_MS) this.fadeOut(e, now);
      }
    }
    // Hard cap: over budget, drop the farthest unwanted chunks straight away.
    if (this.chunks.size > MAX_CHUNKS) {
      const over = [...this.chunks.values()]
        .filter((e) => !e.wanted && e.fadingOut === null)
        .sort((a, b) => Math.hypot(b.cx - t.x, b.cz - t.z) - Math.hypot(a.cx - t.x, a.cz - t.z));
      for (const e of over.slice(0, this.chunks.size - MAX_CHUNKS)) this.fadeOut(e, now);
    }

    missing.sort((a, b) => a.distM - b.distM);
    this.queue = missing;
    this.pump(now);
    this.reportStatus(now);
  }

  private pump(_now: number) {
    while (this.inflight.size < CONCURRENCY && this.queue.length > 0) {
      const spec = this.queue.shift()!;
      void this.fetchChunk(spec);
    }
  }

  private reportStatus(_now: number) {
    let visible = false;
    for (const e of this.chunks.values()) {
      if (e.fadingOut === null) {
        visible = true;
        break;
      }
    }
    if (visible) this.setStatus("");
    else if (this.inflight.size > 0) this.setStatus("Loading 3D area…");
    else if (this.failures.size > 0) this.setStatus("3D map detail couldn't load — it will keep retrying.");
  }

  private async fetchChunk(spec: DesiredChunk) {
    const key = chunkKey(spec.radiusM, spec.i, spec.j);
    this.inflight.add(key);
    try {
      const data = await this.requestScene({ lat: spec.lat, lon: spec.lon, radiusM: spec.radiusM });
      const built = await this.build(data, spec.radiusM);
      if (this.disposed) return;
      this.failures.delete(key);
      this.addChunk(spec, built, performance.now());
    } catch (err) {
      if (this.disposed) return;
      const f = this.failures.get(key) ?? { attempts: 0, next: 0 };
      f.attempts += 1;
      f.next = performance.now() + Math.min(30_000, 2_000 * 2 ** f.attempts);
      this.failures.set(key, f);
      void err;
    } finally {
      this.inflight.delete(key);
      if (!this.disposed) this.dirty = true;
    }
  }

  /** Build geometry for one scene: in the worker when available, inline otherwise. */
  private build(data: SceneData, radiusM: number): Promise<BuiltChunk> {
    const ctx = this.buildContext();
    const opts: BuildOptions = { widthScale: Math.max(1, radiusM / 1500) };
    const w = this.ensureWorker();
    if (!w) return Promise.resolve(buildChunk(data, ctx, opts));
    const id = ++this.buildSeq;
    return new Promise<BuiltChunk>((resolve, reject) => {
      const timer = window.setTimeout(() => {
        this.buildWaiters.delete(id);
        reject(new Error("chunk build timed out"));
      }, 8_000);
      this.buildWaiters.set(id, (err, chunk) => {
        clearTimeout(timer);
        if (err || !chunk) reject(new Error(err ?? "chunk build failed"));
        else resolve(chunk);
      });
      try {
        w.postMessage({ id, data, ctx, opts });
      } catch (e) {
        clearTimeout(timer);
        this.buildWaiters.delete(id);
        reject(e instanceof Error ? e : new Error(String(e)));
      }
    }).catch(() => buildChunk(data, ctx, opts)); // fall back to the main thread
  }

  private ensureWorker(): Worker | null {
    if (this.worker !== undefined) return this.worker;
    try {
      const w = new Worker(new URL("./chunkBuild.worker.ts", import.meta.url), { type: "module" });
      w.onmessage = (e: MessageEvent) => {
        const msg = e.data as { id: number; chunk?: BuiltChunk; error?: string };
        const settle = this.buildWaiters.get(msg.id);
        if (!settle) return;
        this.buildWaiters.delete(msg.id);
        if (msg.chunk) settle(null, msg.chunk);
        else settle(msg.error ?? "worker error", null);
      };
      w.onerror = () => {
        for (const settle of this.buildWaiters.values()) settle("worker failed", null);
        this.buildWaiters.clear();
        this.worker = null; // a worker that can't even load won't start working later
      };
      this.worker = w;
      return w;
    } catch {
      this.worker = null;
      return null;
    }
  }

  /** Turn a built chunk into scene objects and fade it in. */
  private addChunk(spec: DesiredChunk, built: BuiltChunk, now: number) {
    const key = chunkKey(spec.radiusM, spec.i, spec.j);
    if (this.chunks.has(key) || this.disposed) return;
    const level = spec.radiusM === BASE_RADIUS_M ? "base" : "detail";
    const off = level === "detail" ? DETAIL_OFFSET : 0;
    const group = new THREE.Group();
    const materials: THREE.Material[] = [];

    for (const a of built.areas) {
      const g = new THREE.BufferGeometry();
      g.setAttribute("position", new THREE.BufferAttribute(a.pos, 3));
      const m = new THREE.MeshBasicMaterial({ color: a.color, side: THREE.DoubleSide, depthWrite: false });
      const mesh = new THREE.Mesh(g, m);
      mesh.position.y = 0.1;
      mesh.renderOrder = off;
      materials.push(m);
      group.add(mesh);
    }
    for (const r of built.roads) {
      const g = new THREE.BufferGeometry();
      g.setAttribute("position", new THREE.BufferAttribute(r.pos, 3));
      const m = new THREE.MeshBasicMaterial({ color: r.color, side: THREE.DoubleSide, depthWrite: false });
      const mesh = new THREE.Mesh(g, m);
      mesh.position.y = 0.3 + r.order * 0.05;
      mesh.renderOrder = off + 1 + r.order;
      materials.push(m);
      group.add(mesh);
    }
    if (built.buildings) {
      const g = new THREE.BufferGeometry();
      g.setAttribute("position", new THREE.BufferAttribute(built.buildings.pos, 3));
      g.setAttribute("normal", new THREE.BufferAttribute(built.buildings.normals, 3));
      g.setAttribute("color", new THREE.BufferAttribute(built.buildings.colors, 3));
      const m = new THREE.MeshLambertMaterial({ vertexColors: true, side: THREE.DoubleSide });
      const mesh = new THREE.Mesh(g, m);
      mesh.renderOrder = off + 20;
      materials.push(m);
      group.add(mesh);
    }

    const [cx, cz] = this.xz(spec.lat, spec.lon);
    const entry: ChunkEntry = {
      key,
      radiusM: spec.radiusM,
      level,
      cx,
      cz,
      group,
      materials,
      labels: built.labels,
      fadingOut: null,
      fadeInAt: now,
      settled: false,
      wanted: true,
      lastWanted: now
    };
    this.chunks.set(key, entry);
    this.scene.add(group);
    for (const m of materials) {
      m.transparent = true;
      m.opacity = 0;
      m.needsUpdate = true;
    }
  }

  private fadeOut(e: ChunkEntry, now: number) {
    e.fadingOut = now;
    for (const m of e.materials) {
      m.transparent = true;
      m.needsUpdate = true;
    }
  }

  private updateFades(now: number) {
    for (const e of this.chunks.values()) {
      if (e.fadingOut !== null) {
        const k = (now - e.fadingOut) / FADE_OUT_MS;
        if (k >= 1) {
          this.removeChunk(e);
          continue;
        }
        this.setOpacity(e, 1 - k);
      } else if (!e.settled) {
        const k = (now - e.fadeInAt) / FADE_IN_MS;
        if (k >= 1) {
          e.settled = true;
          for (const m of e.materials) {
            m.transparent = false;
            m.opacity = 1;
            m.needsUpdate = true;
          }
        } else {
          this.setOpacity(e, k);
        }
      }
    }
  }

  private setOpacity(e: ChunkEntry, o: number) {
    for (const m of e.materials) m.opacity = o;
  }

  private removeChunk(e: ChunkEntry) {
    if (!this.chunks.delete(e.key)) return;
    this.scene.remove(e.group);
    disposeGroup(e.group);
    for (let i = 0; i < e.labels.length; i++) {
      const el = this.labelEls.get(`${e.key}#${i}`);
      if (el) {
        el.remove();
        this.labelEls.delete(`${e.key}#${i}`);
      }
    }
  }

  /** Place the nearest, most important chunk labels on the HTML overlay. */
  private updateLabels() {
    const t = this.controls.target;
    const fogFar = (this.scene.fog as THREE.Fog).far;
    const cands: { id: string; label: BuiltLabel; d: number }[] = [];
    for (const e of this.chunks.values()) {
      if (e.fadingOut !== null) continue;
      e.labels.forEach((label, idx) => {
        const d = Math.hypot(label.x - t.x, label.z - t.z);
        if (d > fogFar * 0.95) return;
        cands.push({ id: `${e.key}#${idx}`, label, d });
      });
    }
    cands.sort((a, b) => a.label.rank - b.label.rank || a.d - b.d);
    const w = this.container.clientWidth;
    const h = this.container.clientHeight;
    const shown = new Set<string>();
    for (let i = 0; i < Math.min(MAX_LABELS, cands.length); i++) {
      const { id, label } = cands[i];
      this.tmpVec.set(label.x, 6, label.z);
      this.tmpVec.project(this.camera);
      if (this.tmpVec.z > 1 || Math.abs(this.tmpVec.x) > 1.02 || Math.abs(this.tmpVec.y) > 1.02) continue;
      let el = this.labelEls.get(id);
      if (!el) {
        el = document.createElement("div");
        el.className =
          "sc-3d-label" +
          (label.rank === 0 ? " sc-3d-label-place" : label.rank === 1 ? " sc-3d-label-road" : "");
        el.textContent = label.text;
        this.labelLayer.appendChild(el);
        this.labelEls.set(id, el);
      }
      const x = ((this.tmpVec.x * 0.5 + 0.5) * w).toFixed(1);
      const y = ((-this.tmpVec.y * 0.5 + 0.5) * h).toFixed(1);
      el.style.transform = `translate(${x}px, ${y}px) translate(-50%, -50%)`;
      shown.add(id);
    }
    for (const [id, el] of this.labelEls) {
      if (!shown.has(id)) el.style.transform = "translate(-9999px, -9999px)";
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
      // above every road/building layer (incl. the detail offset), so they never cut the ring
      beam.renderOrder = HAZARD_ORDER;
      ring.renderOrder = HAZARD_ORDER;
      this.hazards.add(beam, ring);
    }
  }

  setRoute(points: [number, number][] | null) {
    disposeGroup(this.route);
    this.route.clear();
    if (!points || points.length < 2) return;
    const flat = points.flatMap(([lat, lon]) => [lat, lon]);
    const pos: number[] = [];
    appendRibbon(flat, 14, pos, 0, this.buildContext());
    const g = new THREE.BufferGeometry();
    g.setAttribute("position", new THREE.Float32BufferAttribute(pos, 3));
    const m = new THREE.Mesh(g, new THREE.MeshBasicMaterial({ color: 0x00e5e2, side: THREE.DoubleSide, depthTest: false }));
    m.position.y = 3;
    m.renderOrder = ROUTE_ORDER;
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
    this.dirty = true;
    this.lastEval = 0; // re-evaluate immediately for the new view
  }

  dispose() {
    this.disposed = true;
    cancelAnimationFrame(this.raf);
    this.resize.disconnect();
    this.controls.dispose();
    if (this.worker) this.worker.terminate();
    for (const settle of this.buildWaiters.values()) settle("disposed", null);
    this.buildWaiters.clear();
    for (const e of [...this.chunks.values()]) this.removeChunk(e);
    disposeGroup(this.hazards);
    disposeGroup(this.route);
    this.labelEls.forEach((el) => el.remove());
    this.labelEls.clear();
    this.labelLayer.remove();
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
