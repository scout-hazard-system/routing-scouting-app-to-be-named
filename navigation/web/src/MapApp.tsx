import { useEffect, useRef, useState } from "react";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import {
  ApiError,
  Shards,
  alertClusters,
  geocode,
  health,
  routeOptions,
  scene,
  type Cluster,
  type Place,
  type RouteOption,
  type Severity
} from "./api";
import { Scene3D } from "./scene3d";

const SEV_COLOR: Record<Severity, string> = {
  severe: "#ff3b3b",
  major: "#ff7a1a",
  moderate: "#f5c400",
  minor: "#00c9c7",
  unknown: "#7d8a99"
};
const KIND_LABEL: Record<string, string> = {
  incident: "incidents",
  closure: "closures",
  construction: "construction",
  work_zone: "work zones",
  weather: "weather",
  other: "other"
};
const DEFAULT_VIEW: [number, number, number] = [39.5, -98.35, 5]; // continental US
const AREA_ZOOM = 11; // ~40 km area around the user's cell centre
const PLACE_ZOOM = 13;
const TILE_TIMEOUT_MS = 12_000; // below the gateway's 15 s origin timeout
const NATIVE_MAX_ZOOM = 15; // deeper zooms upscale z15 renders instead of asking for slower ones
const OSM_TILE = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";

interface StartView {
  lat: number;
  lon: number;
  zoom: number;
}

/**
 * Scout tiles first, always. A tile that errors or takes longer than TILE_TIMEOUT_MS is shown
 * from OpenStreetMap instead; per tile, never the whole map. The slow Scout render still
 * completes and lands in the edge cache, so the next view of that tile comes from Scout.
 */
function scoutTileLayer(onFallback: () => void, onScout: () => void): L.TileLayer {
  const Layer = L.TileLayer.extend({
    createTile(this: L.TileLayer, coords: L.Coords, done: L.DoneCallback) {
      const img = document.createElement("img");
      img.alt = "";
      img.setAttribute("role", "presentation");
      let settled = false;
      const fallback = () => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        onFallback();
        img.onload = () => done(undefined, img);
        img.onerror = () => done(new Error("tile unavailable"), img);
        img.src = L.Util.template(OSM_TILE, { ...coords, z: coords.z });
      };
      const timer = setTimeout(fallback, TILE_TIMEOUT_MS);
      img.onload = () => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        onScout();
        done(undefined, img);
      };
      img.onerror = fallback;
      img.src = this.getTileUrl(coords);
      return img;
    }
  });
  // ?v= busts edge-cached tiles when the renderer changes (the gateway ignores the query).
  return new (Layer as unknown as new (url: string, o: L.TileLayerOptions) => L.TileLayer)("/api/tiles/{z}/{x}/{y}.png?v=2", {
    minZoom: 4,
    maxZoom: 18,
    maxNativeZoom: NATIVE_MAX_ZOOM,
    keepBuffer: 1,
    updateWhenIdle: true,
    updateWhenZooming: false,
    attribution: "Tiles: Scout map engine · Data © OpenStreetMap contributors"
  });
}

function clusterHtml(c: Cluster): string {
  const esc = (s: string) => s.replace(/[&<>"']/g, (ch) => `&#${ch.charCodeAt(0)};`);
  const kinds = Object.entries(c.kinds)
    .sort((a, b) => b[1] - a[1])
    .map(([k, v]) => `${v} ${esc(KIND_LABEL[k] ?? k)}`)
    .join(" · ");
  const roads = c.roads.length ? `<div class="sc-pop-roads">${c.roads.map(esc).join(", ")}</div>` : "";
  return (
    `<div class="sc-pop"><strong>${c.count} active hazard${c.count === 1 ? "" : "s"}</strong>` +
    `<div>worst: <span style="color:${SEV_COLOR[c.worst]}">${c.worst}</span></div>` +
    `<div>${kinds}</div>${roads}<div class="sc-pop-src">Sources: ${c.sources.map(esc).join(", ")}</div>` +
    `<div class="sc-pop-src">Area summary (~40 km cell), not exact event locations.</div></div>`
  );
}

function PlaceSearch({
  label,
  value,
  onPick,
  bias,
  disabled
}: {
  label: string;
  value: Place | null;
  onPick: (p: Place | null) => void;
  bias?: { lat: number; lon: number };
  disabled: boolean;
}) {
  const [text, setText] = useState("");
  const [results, setResults] = useState<Place[]>([]);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");

  async function search(e: React.FormEvent) {
    e.preventDefault();
    if (!text.trim()) return;
    setBusy(true);
    setErr("");
    try {
      const r = await geocode(text.trim(), bias);
      setResults(r);
      if (r.length === 0) setErr("No matches.");
    } catch (ex) {
      setErr(ex instanceof ApiError && ex.status === 503 ? "Search is offline." : "Search failed.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="sc-field">
      <label>{label}</label>
      {value ? (
        <div className="sc-picked">
          <span>{value.name}</span>
          <button type="button" className="sc-link" onClick={() => onPick(null)}>
            change
          </button>
        </div>
      ) : (
        <form onSubmit={search} className="sc-search">
          <input value={text} onChange={(e) => setText(e.target.value)} placeholder="Address or place" disabled={disabled} maxLength={200} />
          <button type="submit" disabled={disabled || busy}>
            {busy ? "…" : "Search"}
          </button>
        </form>
      )}
      {!value && results.length > 0 ? (
        <ul className="sc-results">
          {results.map((p, i) => (
            <li key={i}>
              <button
                type="button"
                onClick={() => {
                  onPick(p);
                  setResults([]);
                }}
              >
                {p.name}
              </button>
            </li>
          ))}
        </ul>
      ) : null}
      {err ? <p className="sc-err">{err}</p> : null}
    </div>
  );
}

export default function MapApp() {
  const mapEl = useRef<HTMLDivElement>(null);
  const map = useRef<L.Map | null>(null);
  const clusterLayer = useRef<L.LayerGroup | null>(null);
  const routeLayer = useRef<L.LayerGroup | null>(null);
  const view3dEl = useRef<HTMLDivElement>(null);
  const s3d = useRef<Scene3D | null>(null);
  const [mode, setMode] = useState<"3d" | "2d">("3d");
  const [online, setOnline] = useState<boolean | null>(null);
  const [start, setStart] = useState<StartView | null>(null);
  const [fallbackTiles, setFallbackTiles] = useState(0);
  const [scoutTiles, setScoutTiles] = useState(0);
  const [cell, setCell] = useState<string | null>(null);
  const [clusters, setClusters] = useState<Cluster[]>([]);
  const [status, setStatus] = useState("");
  const [from, setFrom] = useState<Place | null>(null);
  const [to, setTo] = useState<Place | null>(null);
  const [routes, setRoutes] = useState<RouteOption[]>([]);
  const [routing, setRouting] = useState(false);

  // Service status for the banner and the search/route controls. Re-checked, so one slow
  // answer never decides the session (it no longer picks the basemap at all).
  useEffect(() => {
    let alive = true;
    const check = () => void health().then((up) => alive && setOnline(up));
    check();
    const t = setInterval(check, 30_000);
    return () => {
      alive = false;
      clearInterval(t);
    };
  }, []);

  // The map is created only after the user picks where to start, so the first tiles
  // rendered are the ones they will actually look at (not a continent's worth).
  useEffect(() => {
    if (!start || mode !== "2d" || !mapEl.current || map.current) return;
    const m = L.map(mapEl.current, { zoomControl: true, worldCopyJump: true }).setView([start.lat, start.lon], start.zoom);
    map.current = m;
    scoutTileLayer(
      () => setFallbackTiles((n) => n + 1),
      () => setScoutTiles((n) => n + 1)
    ).addTo(m);
    clusterLayer.current = L.layerGroup().addTo(m);
    routeLayer.current = L.layerGroup().addTo(m);
    return () => {
      m.remove();
      map.current = null;
      clusterLayer.current = null;
      routeLayer.current = null;
    };
  }, [start, mode]);

  // 3D: the map engine's vector scenes, rendered in WebGL (web port of the Android Map3dView).
  // If scenes aren't available, fall back to the 2D tile map rather than showing nothing.
  useEffect(() => {
    if (!start || mode !== "3d" || !view3dEl.current || s3d.current) return;
    const v = new Scene3D(
      view3dEl.current,
      { lat: start.lat, lon: start.lon },
      Math.max(start.zoom, 13), // 3D opens at district distance: the z13/z15 rungs carry buildings
      async (req) => {
        try {
          return await scene(req.lat, req.lon, req.radiusM);
        } catch (ex) {
          if (ex instanceof ApiError && [404, 502, 503, 504].includes(ex.status)) {
            setMode("2d");
            setStatus("3D scenes aren't available right now, so this is the 2D tile map.");
          }
          throw ex;
        }
      },
      setStatus
    );
    s3d.current = v;
    return () => {
      v.dispose();
      s3d.current = null;
    };
  }, [start, mode]);

  // Alert clusters for the device's 3x3 cell neighbourhood (cells computed here, on-device).
  useEffect(() => {
    if (!cell) return;
    const center = Shards.cellCenter(cell);
    alertClusters(Shards.cellsAround(center.lat, center.lon))
      .then((cs) => {
        setClusters(cs);
        if (cs.length === 0) setStatus("No active hazards reported around you.");
      })
      .catch((ex) => setStatus(ex instanceof ApiError && ex.status === 503 ? "Hazard service offline." : "Could not load hazards."));
  }, [cell]);

  // Draw hazards and routes into whichever view is active.
  useEffect(() => {
    if (mode === "3d") {
      s3d.current?.setHazards(clusters.map((c) => ({ lat: c.lat, lon: c.lon, count: c.count, color: SEV_COLOR[c.worst] })));
      return;
    }
    const layer = clusterLayer.current;
    if (!layer) return;
    layer.clearLayers();
    for (const c of clusters) {
      const r = Math.min(26, 10 + Math.sqrt(c.count) * 1.6);
      L.circleMarker([c.lat, c.lon], { radius: r, color: SEV_COLOR[c.worst], weight: 2, fillOpacity: 0.35 })
        .bindPopup(clusterHtml(c))
        .addTo(layer);
    }
  }, [clusters, mode, start]);

  useEffect(() => {
    if (mode === "3d") {
      s3d.current?.setRoute(routes[0]?.points ?? null);
      return;
    }
    const layer = routeLayer.current;
    if (!layer) return;
    layer.clearLayers();
    routes.forEach((r, i) => {
      L.polyline(r.points, { color: i === 0 ? "#00c9c7" : "#5d6b78", weight: i === 0 ? 6 : 4, opacity: i === 0 ? 0.95 : 0.7 }).addTo(layer);
    });
    if (routes[0]) map.current?.fitBounds(L.latLngBounds(routes[0].points), { padding: [40, 40] });
  }, [routes, mode, start]);

  function useMyArea() {
    if (!("geolocation" in navigator)) {
      setStatus("Location is not available in this browser.");
      return;
    }
    setStatus("Locating…");
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        // The fix never leaves this function: only the coarse cell is kept, and the map
        // opens on the cell centre (~40 km area), not on the device.
        const c = Shards.cellOf(pos.coords.latitude, pos.coords.longitude);
        setCell(c);
        const centre = Shards.cellCenter(c);
        setStatus("");
        if (map.current) map.current.setView([centre.lat, centre.lon], AREA_ZOOM);
        else if (s3d.current) s3d.current.focus(centre.lat, centre.lon, AREA_ZOOM);
        else setStart({ lat: centre.lat, lon: centre.lon, zoom: AREA_ZOOM });
      },
      () => setStatus("Location permission denied. Search a place or browse the map instead."),
      { enableHighAccuracy: false, maximumAge: 300_000, timeout: 15_000 }
    );
  }

  function startAtPlace(p: Place | null) {
    if (!p) return;
    setCell(Shards.cellOf(p.lat, p.lon));
    setStart({ lat: p.lat, lon: p.lon, zoom: PLACE_ZOOM });
  }

  // Picking a destination also loads hazards around it (by its cell, not by coordinates).
  useEffect(() => {
    const p = to ?? from;
    if (!p) return;
    setCell(Shards.cellOf(p.lat, p.lon));
    if (!start) setStart({ lat: p.lat, lon: p.lon, zoom: PLACE_ZOOM });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [from, to]);

  async function findRoutes() {
    if (!from || !to) return;
    setRouting(true);
    setRoutes([]);
    try {
      const rs = await routeOptions(from, to);
      setRoutes(rs);
      if (rs.length === 0) setStatus("No route found.");
    } catch (ex) {
      setStatus(ex instanceof ApiError && ex.status === 503 ? "Routing is offline." : "Route search failed.");
    } finally {
      setRouting(false);
    }
  }

  const bias = cell ? Shards.cellCenter(cell) : undefined;
  const offline = online === false;

  return (
    <div className="sc-app">
      <aside className="sc-panel">
        <h1 className="sc-title">Scout map</h1>
        {offline ? (
          <p className="sc-banner">The Scout map service isn't reachable right now. Map tiles fall back to OpenStreetMap; search, routing and hazards return when it is.</p>
        ) : null}
        {start ? (
          <div className="sc-mode" role="group" aria-label="Map view">
            <button type="button" className={mode === "3d" ? "sc-mode-on" : ""} onClick={() => setMode("3d")}>
              3D view
            </button>
            <button type="button" className={mode === "2d" ? "sc-mode-on" : ""} onClick={() => setMode("2d")}>
              2D tiles
            </button>
          </div>
        ) : null}
        {mode === "3d" && start ? (
          <p className="sc-fine">Drag to pan · right-drag or two fingers to rotate and tilt · scroll to zoom.</p>
        ) : null}
        {mode === "2d" && scoutTiles + fallbackTiles > 0 ? (
          <p className="sc-fine" data-testid="tile-sources">
            Map tiles: {scoutTiles} from Scout's engine, {fallbackTiles} from OpenStreetMap
            {fallbackTiles > 0 ? " (shown while Scout rendered them; they'll come from Scout next time)" : ""}.
          </p>
        ) : null}

        <section>
          <h2>Hazards near you</h2>
          <button type="button" className="sc-btn" onClick={useMyArea}>
            Use my area
          </button>
          <p className="sc-fine">Your browser turns your location into a ~40 km area code; only that code is sent.</p>
          {clusters.length > 0 ? (
            <ul className="sc-clusters">
              {clusters
                .slice()
                .sort((a, b) => b.count - a.count)
                .map((c) => (
                  <li key={c.cell}>
                    <span className="sc-dot" style={{ background: SEV_COLOR[c.worst] }} />
                    {c.count} hazards · worst {c.worst}
                    {c.roads[0] ? ` · ${c.roads.slice(0, 2).join(", ")}` : ""}
                  </li>
                ))}
            </ul>
          ) : null}
        </section>

        <section>
          <h2>Route</h2>
          <PlaceSearch label="From" value={from} onPick={setFrom} bias={bias} disabled={offline} />
          <PlaceSearch label="To" value={to} onPick={setTo} bias={bias} disabled={offline} />
          <button type="button" className="sc-btn" disabled={!from || !to || routing || offline} onClick={() => void findRoutes()}>
            {routing ? "Routing…" : "Find routes"}
          </button>
          {routes.length > 0 ? (
            <ol className="sc-routes">
              {routes.map((r, i) => (
                <li key={i}>
                  <strong>{r.label}</strong>
                  {r.durationMin !== undefined ? ` · ${Math.round(r.durationMin)} min` : ""}
                  {r.distanceKm !== undefined ? ` · ${r.distanceKm.toFixed(1)} km` : ""}
                </li>
              ))}
            </ol>
          ) : null}
          <p className="sc-fine">Route search sends the two points you chose for that one request.</p>
        </section>

        {status ? <p className="sc-status">{status}</p> : null}
      </aside>
      <div className="sc-map-wrap">
        <div ref={mapEl} className="sc-map" role="application" aria-label="Map" hidden={mode !== "2d"} />
        <div ref={view3dEl} className="sc-map sc-3d" role="application" aria-label="3D map" hidden={mode !== "3d"} />
        {start ? null : (
          <div className="sc-start" role="dialog" aria-label="Where should the map start?">
            <h2>Where are you headed?</h2>
            <p className="sc-fine">The map renders around the area you pick, so it loads faster.</p>
            <button type="button" className="sc-btn sc-btn-lg" onClick={useMyArea}>
              Use my location
            </button>
            <p className="sc-fine">Your browser turns it into a ~40 km area code; your exact position isn't sent.</p>
            <PlaceSearch label="Or search a place" value={null} onPick={startAtPlace} disabled={offline} />
            <button
              type="button"
              className="sc-link"
              onClick={() => {
                // a continent isn't a 3D scene: browse it on the 2D tiles
                setMode("2d");
                setStart({ lat: DEFAULT_VIEW[0], lon: DEFAULT_VIEW[1], zoom: DEFAULT_VIEW[2] });
              }}
            >
              Browse the whole US
            </button>
            {status ? <p className="sc-status">{status}</p> : null}
          </div>
        )}
      </div>
    </div>
  );
}
