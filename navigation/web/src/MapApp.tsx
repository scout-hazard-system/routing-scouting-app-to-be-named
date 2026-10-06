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
  type Cluster,
  type Place,
  type RouteOption,
  type Severity
} from "./api";

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
  const [online, setOnline] = useState<boolean | null>(null);
  const [cell, setCell] = useState<string | null>(null);
  const [clusters, setClusters] = useState<Cluster[]>([]);
  const [status, setStatus] = useState("");
  const [from, setFrom] = useState<Place | null>(null);
  const [to, setTo] = useState<Place | null>(null);
  const [routes, setRoutes] = useState<RouteOption[]>([]);
  const [routing, setRouting] = useState(false);

  // Map + basemap: Scout's own tiles when the service is up, OpenStreetMap otherwise.
  useEffect(() => {
    if (!mapEl.current || map.current) return;
    const m = L.map(mapEl.current, { zoomControl: true, worldCopyJump: true }).setView([DEFAULT_VIEW[0], DEFAULT_VIEW[1]], DEFAULT_VIEW[2]);
    map.current = m;
    clusterLayer.current = L.layerGroup().addTo(m);
    routeLayer.current = L.layerGroup().addTo(m);
    let cancelled = false;
    void health().then((up) => {
      if (cancelled) return;
      setOnline(up);
      if (up) {
        // ?v= busts edge-cached tiles when the renderer changes (the gateway ignores the query).
        L.tileLayer("/api/tiles/{z}/{x}/{y}.png?v=2", { minZoom: 3, maxZoom: 19, attribution: "Scout map engine · © OpenStreetMap contributors" }).addTo(m);
      } else {
        L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", { maxZoom: 19, attribution: "© OpenStreetMap contributors" }).addTo(m);
      }
    });
    return () => {
      cancelled = true;
      m.remove();
      map.current = null;
    };
  }, []);

  // Alert clusters for the device's 3x3 cell neighbourhood (cells computed here, on-device).
  useEffect(() => {
    if (!cell || !clusterLayer.current) return;
    const layer = clusterLayer.current;
    const center = Shards.cellCenter(cell);
    setStatus("Loading hazards…");
    alertClusters(Shards.cellsAround(center.lat, center.lon))
      .then((cs) => {
        setClusters(cs);
        layer.clearLayers();
        for (const c of cs) {
          const r = Math.min(26, 10 + Math.sqrt(c.count) * 1.6);
          L.circleMarker([c.lat, c.lon], { radius: r, color: SEV_COLOR[c.worst], weight: 2, fillOpacity: 0.35 })
            .bindPopup(clusterHtml(c))
            .addTo(layer);
        }
        setStatus(cs.length ? "" : "No active hazards reported around you.");
      })
      .catch((ex) => setStatus(ex instanceof ApiError && ex.status === 503 ? "Hazard service offline." : "Could not load hazards."));
  }, [cell]);

  function useMyArea() {
    if (!("geolocation" in navigator)) {
      setStatus("Location is not available in this browser.");
      return;
    }
    setStatus("Locating…");
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        // The fix never leaves this function: only the coarse cell is kept.
        const c = Shards.cellOf(pos.coords.latitude, pos.coords.longitude);
        setCell(c);
        const centre = Shards.cellCenter(c);
        map.current?.setView([centre.lat, centre.lon], 10);
      },
      () => setStatus("Location permission denied. Search a place instead."),
      { enableHighAccuracy: false, maximumAge: 300_000, timeout: 15_000 }
    );
  }

  // Picking a destination also loads hazards around it (by its cell, not by coordinates).
  useEffect(() => {
    const p = to ?? from;
    if (p) setCell(Shards.cellOf(p.lat, p.lon));
  }, [from, to]);

  async function findRoutes() {
    if (!from || !to || !routeLayer.current) return;
    setRouting(true);
    setRoutes([]);
    try {
      const rs = await routeOptions(from, to);
      setRoutes(rs);
      const layer = routeLayer.current;
      layer.clearLayers();
      rs.forEach((r, i) => {
        L.polyline(r.points, { color: i === 0 ? "#00c9c7" : "#5d6b78", weight: i === 0 ? 6 : 4, opacity: i === 0 ? 0.95 : 0.7 }).addTo(layer);
      });
      if (rs[0]) map.current?.fitBounds(L.latLngBounds(rs[0].points), { padding: [40, 40] });
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
          <p className="sc-banner">The Scout map service isn't connected yet. You're seeing an OpenStreetMap basemap; search, routing and hazards will light up once it is.</p>
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
      <div ref={mapEl} className="sc-map" role="application" aria-label="Map" />
    </div>
  );
}
