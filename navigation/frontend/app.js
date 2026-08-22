const state = {
  metrics: {
    captured: 0,
    skipped_silence: 0,
    skipped_clipped: 0,
    llm_alert: 0,
    soft_alert_fallback: 0,
  },
  jurisdictionCount: 0,
  lastAlertCoords: null,
  lastJurisdictionNoticeTs: 0,
  visualizerFrame: null,
  modalHideTimer: null,
  modalQueue: [],
  modalActive: false,
  visualizer: {
    targetEnergy: 0.35,
    currentEnergy: 0.25,
    rms: 0,
    clipRatio: 0,
    seed: Math.random() * Math.PI * 2,
  },
  lastSnapshotTs: null,
  seenEventKeys: new Set(),
  currentGps: null,
  gpsWatchId: null,
  map: {
    instance: null,
    isometricOnly: true,
    isometricImg: null,
    loadingOverlay: null,
    centerLat: null,
    centerLon: null,
    centerZoom: 6,
    currentMarker: null,
    alertMarker: null,
    trackLine: null,
    triangulationMarker: null,
    followMode: true,
    hasAutoCentered: false,
    lastBackendRenderAt: 0,
    lastBackendStatusAt: 0,
    lastBackendCenterKey: "",
    loadingToken: 0,
    chunkReadyToken: 0,
    imageReadyToken: 0,
  },
  gpsIngest: {
    inFlight: false,
    pendingSample: null,
    seq: 0,
    lastAckAt: 0,
  },
  clientId: null,
  notificationWorkflow: {
    total: 0,
    normalCalls: 0,
    alerts: 0,
    browserSent: 0,
    parseErrors: 0,
    quietMode: false,
    lastBrowserNotifyAt: 0,
    lastEventType: null,
  },
  catalog: {
    regions: [],
    channels: [],
    selectedRegion: "",
    selectedChannelId: "",
  },
  routeUi: {
    options: null,
    activeAltIndex: 0,
    selectedClusterIndex: -1,
    stopRows: [],
  },
  routeSearch: {
    suggestions: [],
    activeIndex: -1,
    debounceTimer: null,
    requestSeq: 0,
    appliedLabel: "",
    menuEl: null,
  },
  google: {
    scriptPromise: null,
    mapsReady: false,
    placesReady: false,
    geocoder: null,
    directionsService: null,
    autocompleteService: null,
  },
};
const BROWSER_NOTIFY_COOLDOWN_MS = 6000;
const JURISDICTION_COOLDOWN_MS = 4 * 60 * 1000;
const BACKEND_MAP_RENDER_MIN_INTERVAL_MS = 3200;
const DEST_SUGGEST_DEBOUNCE_MS = 220;
const GOOGLE_ROUTE_SEARCH_ENABLED = window.SCANNER_GOOGLE_ROUTE_SEARCH_ENABLED !== false;
const GOOGLE_MAPS_API_KEY = String(window.SCANNER_GOOGLE_MAPS_API_KEY || "").trim();
const STARTUP_MAP_ZOOM_OUT_FACTOR = 50;

const API_BASE = (window.SCANNER_API_BASE_URL || "").replace(/\/+$/, "");
const ui = {
  connStatus: document.getElementById("connStatus"),
  runStatus: document.getElementById("runStatus"),
  gpsStatus: document.getElementById("gpsStatus"),
  captured: document.getElementById("captured"),
  skippedSilence: document.getElementById("skippedSilence"),
  skippedClipped: document.getElementById("skippedClipped"),
  llmAlert: document.getElementById("llmAlert"),
  softFallback: document.getElementById("softFallback"),
  jurisdictionCount: document.getElementById("jurisdictionCount"),
  alerts: document.getElementById("alerts"),
  transcripts: document.getElementById("transcripts"),
  weatherList: document.getElementById("weatherList"),
  jurisdictionNotices: document.getElementById("jurisdictionNotices"),
  eventPreview: document.getElementById("eventPreview"),
  latInput: document.getElementById("latInput"),
  lonInput: document.getElementById("lonInput"),
  startInput: document.getElementById("startInput"),
  endInput: document.getElementById("endInput"),
  addStopBtn: document.getElementById("addStopBtn"),
  multiStopList: document.getElementById("multiStopList"),
  integratedMap: document.getElementById("integratedMap"),
  openWazeBtn: document.getElementById("openWazeBtn"),
  planRouteBtn: document.getElementById("planRouteBtn"),
  useAlertCoordsBtn: document.getElementById("useAlertCoordsBtn"),
  useCurrentGpsBtn: document.getElementById("useCurrentGpsBtn"),
  refreshSelectorBtn: document.getElementById("refreshSelectorBtn"),
  selectorStatus: document.getElementById("selectorStatus"),
  enableNotifyBtn: document.getElementById("enableNotifyBtn"),
  alertModal: document.getElementById("alertModal"),
  alertModalText: document.getElementById("alertModalText"),
  closeAlertModalBtn: document.getElementById("closeAlertModalBtn"),
  visualizerCanvas: document.getElementById("visualizerCanvas"),
  notifyTotal: document.getElementById("notifyTotal"),
  notifyNormal: document.getElementById("notifyNormal"),
  notifyAlerts: document.getElementById("notifyAlerts"),
  notifyQueueDepth: document.getElementById("notifyQueueDepth"),
  notifyModalState: document.getElementById("notifyModalState"),
  notifyLastEvent: document.getElementById("notifyLastEvent"),
  notifyBrowserSent: document.getElementById("notifyBrowserSent"),
  notifyParseErrors: document.getElementById("notifyParseErrors"),
  notifyQuietMode: document.getElementById("notifyQuietMode"),
  toggleQuietModeBtn: document.getElementById("toggleQuietModeBtn"),
  resetNotifyAuditBtn: document.getElementById("resetNotifyAuditBtn"),
  clearNotifyListsBtn: document.getElementById("clearNotifyListsBtn"),
  autoSelectorCheckbox: document.getElementById("autoSelectorCheckbox"),
  regionSelect: document.getElementById("regionSelect"),
  channelSelect: document.getElementById("channelSelect"),
  mapCenterHud: document.getElementById("mapCenterHud"),
  mapAccuracyHud: document.getElementById("mapAccuracyHud"),
  mapUsersHud: document.getElementById("mapUsersHud"),
  mapTriangulationHud: document.getElementById("mapTriangulationHud"),
  backendMapStatus: document.getElementById("backendMapStatus"),
  backendMapPreview: document.getElementById("backendMapPreview"),
  refreshBackendMapBtn: document.getElementById("refreshBackendMapBtn"),
  recenterMapBtn: document.getElementById("recenterMapBtn"),
  toggleFollowBtn: document.getElementById("toggleFollowBtn"),
  quickRouteBtn: document.getElementById("quickRouteBtn"),
  quickAlertsBtn: document.getElementById("quickAlertsBtn"),
  routeSketchCanvas: document.getElementById("routeSketchCanvas"),
  routeAltList: document.getElementById("routeAltList"),
  routeEtaSummary: document.getElementById("routeEtaSummary"),
  routeVisualStatus: document.getElementById("routeVisualStatus"),
  clusterList: document.getElementById("clusterList"),
  clusterDetailPanel: document.getElementById("clusterDetailPanel"),
  clusterDetailTitle: document.getElementById("clusterDetailTitle"),
  clusterDetailItems: document.getElementById("clusterDetailItems"),
  closeClusterDetailBtn: document.getElementById("closeClusterDetailBtn"),
};

function apiUrl(path) {
  return API_BASE ? `${API_BASE}${path}` : path;
}
function isGoogleRouteSearchEnabled() {
  return GOOGLE_ROUTE_SEARCH_ENABLED && !!GOOGLE_MAPS_API_KEY;
}
function googleMapsScriptUrl() {
  const params = new URLSearchParams({
    key: GOOGLE_MAPS_API_KEY,
    libraries: "places",
  });
  return `https://maps.googleapis.com/maps/api/js?${params.toString()}`;
}
function loadGoogleMapsApi() {
  if (!isGoogleRouteSearchEnabled()) return Promise.resolve(false);
  if (state.google.scriptPromise) return state.google.scriptPromise;
  state.google.scriptPromise = new Promise((resolve) => {
    if (window.google?.maps) {
      state.google.mapsReady = true;
      state.google.placesReady = !!window.google?.maps?.places;
      resolve(true);
      return;
    }
    const script = document.createElement("script");
    script.src = googleMapsScriptUrl();
    script.async = true;
    script.defer = true;
    script.onload = () => {
      state.google.mapsReady = !!window.google?.maps;
      state.google.placesReady = !!window.google?.maps?.places;
      resolve(state.google.mapsReady);
    };
    script.onerror = () => resolve(false);
    document.head.appendChild(script);
  });
  return state.google.scriptPromise;
}
async function ensureGoogleServices() {
  const loaded = await loadGoogleMapsApi();
  if (!loaded || !window.google?.maps) return false;
  if (!state.google.geocoder) {
    state.google.geocoder = new window.google.maps.Geocoder();
  }
  if (!state.google.directionsService) {
    state.google.directionsService = new window.google.maps.DirectionsService();
  }
  if (state.google.placesReady && !state.google.autocompleteService) {
    state.google.autocompleteService = new window.google.maps.places.AutocompleteService();
  }
  return true;
}
function googleLocationBias(bias) {
  if (!window.google?.maps || !bias || !Number.isFinite(bias.lat) || !Number.isFinite(bias.lon)) return undefined;
  return {
    center: new window.google.maps.LatLng(bias.lat, bias.lon),
    radius: 50000,
  };
}
function geocodeResultToCandidate(result, fallbackName) {
  const loc = result?.geometry?.location;
  const lat = typeof loc?.lat === "function" ? loc.lat() : asFiniteNumber(loc?.lat);
  const lon = typeof loc?.lng === "function" ? loc.lng() : asFiniteNumber(loc?.lng);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
  return {
    lat,
    lon,
    displayName: String(result?.formatted_address || fallbackName || "").trim() || fallbackName,
  };
}
function googleDirectionsRouteToAlternative(route) {
  const legs = Array.isArray(route?.legs) ? route.legs : [];
  let distanceM = 0;
  let durationS = 0;
  let durationTrafficS = 0;
  legs.forEach((leg) => {
    distanceM += Number(leg?.distance?.value || 0);
    durationS += Number(leg?.duration?.value || 0);
    durationTrafficS += Number(leg?.duration_in_traffic?.value || leg?.duration?.value || 0);
  });
  const overview = Array.isArray(route?.overview_path) ? route.overview_path : [];
  const routePoints = overview
    .map((point) => {
      const lat = typeof point?.lat === "function" ? point.lat() : asFiniteNumber(point?.lat);
      const lon = typeof point?.lng === "function" ? point.lng() : asFiniteNumber(point?.lng);
      if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
      return { lat, lon };
    })
    .filter(Boolean);
  return {
    index: 0,
    distance_m: distanceM,
    duration_s: durationS,
    eta_speed_limit_s: durationTrafficS || durationS,
    stop_dwell_s: 0,
    eta_with_stops_s: durationTrafficS || durationS,
    has_toll_hint: false,
    has_ferry_hint: false,
    maxspeed_coverage: 1,
    route_points: routePoints,
  };
}
function setRouteVisualStatus(text) {
  if (!ui.routeVisualStatus) return;
  ui.routeVisualStatus.textContent = text;
}
function clearRouteDrawing() {
  const canvas = ui.routeSketchCanvas;
  if (!canvas) return;
  const ctx = canvas.getContext("2d");
  if (!ctx) return;
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  ctx.fillStyle = "#9fb5d8";
  ctx.font = "14px Inter, system-ui, sans-serif";
  ctx.fillText("No route drawn yet.", 16, 28);
}
function getRouteExtents(points) {
  let minLat = Infinity;
  let maxLat = -Infinity;
  let minLon = Infinity;
  let maxLon = -Infinity;
  points.forEach((p) => {
    if (!Number.isFinite(p.lat) || !Number.isFinite(p.lon)) return;
    minLat = Math.min(minLat, p.lat);
    maxLat = Math.max(maxLat, p.lat);
    minLon = Math.min(minLon, p.lon);
    maxLon = Math.max(maxLon, p.lon);
  });
  if (!Number.isFinite(minLat) || !Number.isFinite(maxLat) || !Number.isFinite(minLon) || !Number.isFinite(maxLon)) {
    return null;
  }
  if (Math.abs(maxLat - minLat) < 1e-6) {
    minLat -= 0.005;
    maxLat += 0.005;
  }
  if (Math.abs(maxLon - minLon) < 1e-6) {
    minLon -= 0.005;
    maxLon += 0.005;
  }
  return { minLat, maxLat, minLon, maxLon };
}
function projectRoutePoint(p, extents, width, height, pad) {
  const x = pad + ((p.lon - extents.minLon) / (extents.maxLon - extents.minLon)) * (width - pad * 2);
  const y = height - pad - ((p.lat - extents.minLat) / (extents.maxLat - extents.minLat)) * (height - pad * 2);
  return { x, y };
}
function drawRouteSketch(alternative, clusters) {
  const canvas = ui.routeSketchCanvas;
  if (!canvas) return;
  const ctx = canvas.getContext("2d");
  if (!ctx) return;
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  const nodes = Array.isArray(alternative?.route_points)
    ? alternative.route_points.filter((n) => Number.isFinite(n.lat) && Number.isFinite(n.lon))
    : [];
  if (nodes.length < 2) {
    clearRouteDrawing();
    return;
  }
  const allPoints = [...nodes];
  (Array.isArray(clusters) ? clusters : []).forEach((c) => {
    if (Number.isFinite(c?.lat) && Number.isFinite(c?.lon)) allPoints.push({ lat: c.lat, lon: c.lon });
  });
  const extents = getRouteExtents(allPoints);
  if (!extents) {
    clearRouteDrawing();
    return;
  }
  const pad = 16;
  ctx.strokeStyle = "rgba(75, 97, 128, 0.65)";
  ctx.lineWidth = 1;
  ctx.strokeRect(0.5, 0.5, canvas.width - 1, canvas.height - 1);
  ctx.beginPath();
  nodes.forEach((node, idx) => {
    const p = projectRoutePoint(node, extents, canvas.width, canvas.height, pad);
    if (idx === 0) ctx.moveTo(p.x, p.y);
    else ctx.lineTo(p.x, p.y);
  });
  ctx.lineWidth = 4;
  ctx.strokeStyle = "#6fc2ff";
  ctx.shadowColor = "rgba(62, 145, 240, 0.35)";
  ctx.shadowBlur = 8;
  ctx.stroke();
  ctx.shadowBlur = 0;
  const startP = projectRoutePoint(nodes[0], extents, canvas.width, canvas.height, pad);
  const endP = projectRoutePoint(nodes[nodes.length - 1], extents, canvas.width, canvas.height, pad);
  ctx.fillStyle = "#57e1a4";
  ctx.beginPath();
  ctx.arc(startP.x, startP.y, 5, 0, Math.PI * 2);
  ctx.fill();
  ctx.fillStyle = "#ff8fa0";
  ctx.beginPath();
  ctx.arc(endP.x, endP.y, 5, 0, Math.PI * 2);
  ctx.fill();
  (Array.isArray(clusters) ? clusters : []).forEach((cluster) => {
    if (!Number.isFinite(cluster?.lat) || !Number.isFinite(cluster?.lon)) return;
    const p = projectRoutePoint(cluster, extents, canvas.width, canvas.height, pad);
    const count = Math.max(1, Number(cluster?.count || 1));
    ctx.fillStyle = "rgba(255, 187, 87, 0.9)";
    ctx.beginPath();
    ctx.arc(p.x, p.y, Math.min(12, 3 + count), 0, Math.PI * 2);
    ctx.fill();
  });
}
function closeClusterDetailPanel() {
  if (ui.clusterDetailPanel) ui.clusterDetailPanel.classList.add("hidden");
  if (ui.clusterDetailItems) ui.clusterDetailItems.innerHTML = "";
  state.routeUi.selectedClusterIndex = -1;
}
function renderClusterDetail(cluster, clusterIndex) {
  if (!ui.clusterDetailPanel || !ui.clusterDetailItems || !ui.clusterDetailTitle) return;
  ui.clusterDetailPanel.classList.remove("hidden");
  const count = Math.max(0, Number(cluster?.count || 0));
  ui.clusterDetailTitle.textContent = `Cluster ${clusterIndex + 1} • ${count} alert${count === 1 ? "" : "s"}`;
  const alerts = Array.isArray(cluster?.alerts) ? cluster.alerts : [];
  ui.clusterDetailItems.innerHTML = "";
  if (!alerts.length) {
    const li = document.createElement("li");
    li.textContent = "No alert specifics available for this cluster.";
    ui.clusterDetailItems.appendChild(li);
    return;
  }
  alerts.forEach((item) => {
    const li = document.createElement("li");
    const ts = String(item?.ts || "").trim();
    const headline = String(item?.alert || "").trim();
    const transcript = String(item?.transcript || "").trim();
    li.textContent = `${ts || "unknown time"} • ${headline || transcript || "alert detail unavailable"}`;
    ui.clusterDetailItems.appendChild(li);
  });
}
function renderClusterList() {
  if (!ui.clusterList) return;
  const clusters = Array.isArray(state.routeUi.options?.alert_clusters?.clusters)
    ? [...state.routeUi.options.alert_clusters.clusters]
    : [];
  clusters.sort((a, b) => Number(b?.count || 0) - Number(a?.count || 0));
  ui.clusterList.innerHTML = "";
  if (!clusters.length) {
    const fallback = document.createElement("div");
    fallback.className = "sub tiny";
    fallback.textContent = "No alert clusters available for this route.";
    ui.clusterList.appendChild(fallback);
    closeClusterDetailPanel();
    return;
  }
  clusters.slice(0, 10).forEach((cluster, idx) => {
    const count = Math.max(0, Number(cluster?.count || 0));
    const lat = Number(cluster?.lat || 0).toFixed(3);
    const lon = Number(cluster?.lon || 0).toFixed(3);
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "cluster-item-btn";
    if (state.routeUi.selectedClusterIndex === idx) {
      btn.classList.add("active");
    }
    btn.innerHTML = `Cluster ${idx + 1} · ${count} alert${count === 1 ? "" : "s"}<span class="cluster-meta">center: ${lat}, ${lon}</span>`;
    btn.addEventListener("click", () => {
      state.routeUi.selectedClusterIndex = idx;
      renderClusterList();
      renderClusterDetail(cluster, idx);
    });
    ui.clusterList.appendChild(btn);
  });
}
function formatDurationLabel(seconds) {
  const total = Math.max(0, Math.round(Number(seconds) || 0));
  const days = Math.floor(total / 86400);
  const hours = Math.floor((total % 86400) / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  if (days > 0) {
    return `${days}d ${hours}h ${minutes}m`;
  }
  if (hours > 0) {
    return `${hours}h ${minutes}m`;
  }
  return `${minutes}m`;
}
function formatCoverageLabel(value) {
  const num = Number(value);
  if (!Number.isFinite(num) || num <= 0) return "fallback";
  const pct = Math.max(0, Math.min(100, Math.round(num * 100)));
  return `${pct}%`;
}
function renderActiveRouteEta(alternative) {
  if (!ui.routeEtaSummary) return;
  if (!alternative || typeof alternative !== "object") {
    ui.routeEtaSummary.innerHTML = "";
    return;
  }
  const distanceM = Number(alternative.distance_m || 0);
  const durationS = Number(alternative.duration_s || 0);
  const etaSpeedS = Number(alternative.eta_speed_limit_s ?? durationS);
  const stopDwellS = Number(alternative.stop_dwell_s || 0);
  const etaWithStopsS = Number(alternative.eta_with_stops_s ?? (etaSpeedS + stopDwellS));
  const coverage = formatCoverageLabel(alternative.maxspeed_coverage);
  const toll = alternative.has_toll_hint ? "yes" : "no";
  const ferry = alternative.has_ferry_hint ? "yes" : "no";
  ui.routeEtaSummary.innerHTML = `
    <div class="eta-chip"><span>Distance</span><strong>${(distanceM / 1000).toFixed(1)} km</strong></div>
    <div class="eta-chip"><span>ETA (speed/fallback)</span><strong>${formatDurationLabel(etaSpeedS)}</strong></div>
    <div class="eta-chip"><span>Stop dwell</span><strong>${formatDurationLabel(stopDwellS)}</strong></div>
    <div class="eta-chip"><span>Total ETA</span><strong>${formatDurationLabel(etaWithStopsS)}</strong></div>
    <div class="eta-chip"><span>Maxspeed coverage</span><strong>${coverage}</strong></div>
    <div class="eta-chip"><span>Toll / Ferry hints</span><strong>${toll} / ${ferry}</strong></div>
  `;
}
function renderRouteAlternatives() {
  if (!ui.routeAltList) return;
  const alternatives = Array.isArray(state.routeUi.options?.alternatives) ? state.routeUi.options.alternatives : [];
  ui.routeAltList.innerHTML = "";
  if (!alternatives.length) return;
  alternatives.forEach((alt, idx) => {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = `route-alt-chip${idx === state.routeUi.activeAltIndex ? " active" : ""}`;
    const meters = Number(alt?.distance_m || 0);
    const etaSeconds = Number(alt?.eta_with_stops_s ?? alt?.eta_speed_limit_s ?? alt?.duration_s ?? 0);
    btn.textContent = `Route ${idx + 1} · ${(meters / 1000).toFixed(1)} km · ETA ${formatDurationLabel(etaSeconds)}`;
    btn.addEventListener("click", () => {
      state.routeUi.activeAltIndex = idx;
      renderRouteVisuals();
    });
    ui.routeAltList.appendChild(btn);
  });
}
function renderRouteVisuals() {
  const alternatives = Array.isArray(state.routeUi.options?.alternatives) ? state.routeUi.options.alternatives : [];
  if (!alternatives.length) {
    clearRouteDrawing();
    setRouteVisualStatus("No route geometry returned");
    if (ui.routeAltList) ui.routeAltList.innerHTML = "";
    if (ui.routeEtaSummary) ui.routeEtaSummary.innerHTML = "";
    if (ui.clusterList) ui.clusterList.innerHTML = "";
    closeClusterDetailPanel();
    return;
  }
  const altIndex = Math.min(Math.max(0, state.routeUi.activeAltIndex), alternatives.length - 1);
  state.routeUi.activeAltIndex = altIndex;
  const activeAlternative = alternatives[altIndex];
  const clusters = Array.isArray(state.routeUi.options?.alert_clusters?.clusters)
    ? state.routeUi.options.alert_clusters.clusters
    : [];
  drawRouteSketch(activeAlternative, clusters);
  renderRouteAlternatives();
  renderActiveRouteEta(activeAlternative);
  renderClusterList();
  const count = alternatives.length;
  const clusterCount = clusters.length;
  setRouteVisualStatus(`Showing route ${altIndex + 1}/${count} • ${clusterCount} cluster${clusterCount === 1 ? "" : "s"}`);
}
async function fetchRouteOptionsFromBackend({ originLat, originLon, destLat, destLon, start, end }) {
  const params = new URLSearchParams();
  if (Number.isFinite(originLat)) params.set("origin_lat", String(originLat));
  if (Number.isFinite(originLon)) params.set("origin_lon", String(originLon));
  if (Number.isFinite(destLat)) params.set("dest_lat", String(destLat));
  if (Number.isFinite(destLon)) params.set("dest_lon", String(destLon));
  if (start) params.set("start", start);
  if (end) params.set("end", end);
  const r = await fetch(apiUrl(`/api/platform/route/options?${params.toString()}`));
  if (!r.ok) throw new Error("route options unavailable");
  return r.json();
}
async function loadRouteVisuals(payload) {
  setRouteVisualStatus("Loading route drawing…");
  try {
    const options = await fetchRouteOptionsFromBackend(payload);
    state.routeUi.options = options?.status === "ok" ? options : null;
    state.routeUi.activeAltIndex = 0;
    state.routeUi.selectedClusterIndex = -1;
    renderRouteVisuals();
  } catch {
    state.routeUi.options = null;
    clearRouteDrawing();
    if (ui.routeAltList) ui.routeAltList.innerHTML = "";
    if (ui.routeEtaSummary) ui.routeEtaSummary.innerHTML = "";
    if (ui.clusterList) ui.clusterList.innerHTML = "";
    closeClusterDetailPanel();
    setRouteVisualStatus("Route drawing unavailable");
  }
}

function setConn(text, cls) {
  ui.connStatus.className = `pill ${cls}`;
  ui.connStatus.textContent = text;
}

function setRun(text, cls = "mute") {
  ui.runStatus.className = `pill ${cls}`;
  ui.runStatus.textContent = text;
}
function setGpsStatus(text, cls = "mute") {
  ui.gpsStatus.className = `pill ${cls}`;
  ui.gpsStatus.textContent = text;
}
function setMapHud({ lat, lon, accuracy, users, triangulation }) {
  if (Number.isFinite(lat) && Number.isFinite(lon)) {
    ui.mapCenterHud.textContent = `center: ${lat.toFixed(5)}, ${lon.toFixed(5)}`;
  }
  if (Number.isFinite(accuracy)) {
    ui.mapAccuracyHud.textContent = `accuracy: ±${Math.max(0, accuracy).toFixed(1)}m`;
  }
  if (Number.isFinite(users)) {
    ui.mapUsersHud.textContent = `users: ${users}`;
  }
  if (triangulation) {
    ui.mapTriangulationHud.textContent = `triangulation: ${triangulation}`;
  }
}

function addListItem(list, text) {
  const li = document.createElement("li");
  li.textContent = text;
  list.prepend(li);
  while (list.children.length > 15) list.removeChild(list.lastChild);
}

function renderMetrics() {
  ui.captured.textContent = state.metrics.captured;
  ui.skippedSilence.textContent = state.metrics.skipped_silence;
  ui.skippedClipped.textContent = state.metrics.skipped_clipped;
  ui.llmAlert.textContent = state.metrics.llm_alert;
  ui.softFallback.textContent = state.metrics.soft_alert_fallback;
  ui.jurisdictionCount.textContent = state.jurisdictionCount;
}
function renderNotificationWorkflow() {
  ui.notifyTotal.textContent = state.notificationWorkflow.total;
  ui.notifyNormal.textContent = state.notificationWorkflow.normalCalls;
  ui.notifyAlerts.textContent = state.notificationWorkflow.alerts;
  ui.notifyBrowserSent.textContent = state.notificationWorkflow.browserSent;
  ui.notifyParseErrors.textContent = state.notificationWorkflow.parseErrors;
  ui.notifyQueueDepth.textContent = state.modalQueue.length;
  ui.notifyModalState.textContent = state.modalActive ? "active" : "idle";
  ui.notifyLastEvent.textContent = state.notificationWorkflow.lastEventType || "none";
  ui.notifyQuietMode.textContent = state.notificationWorkflow.quietMode ? "on" : "off";
  ui.toggleQuietModeBtn.textContent = `Quiet Mode: ${state.notificationWorkflow.quietMode ? "On" : "Off"}`;
}

function updatePreview(event) {
  ui.eventPreview.textContent = JSON.stringify(event, null, 2);
}

function updateSnapshotPreview(snapshot) {
  const compact = {
    snapshot_ts: snapshot.ts || null,
    metrics: snapshot.metrics || {},
    event_type_counts: snapshot.event_type_counts || {},
    recent_events: Array.isArray(snapshot.recentEvents) ? snapshot.recentEvents.length : 0,
  };
  ui.eventPreview.textContent = JSON.stringify(compact, null, 2);
}

function eventKey(event) {
  const ts = event.ts || "na";
  const type = event.event_type || "na";
  const transcript = event.transcript || "";
  return `${ts}|${type}|${transcript.slice(0, 64)}`;
}

function shouldProcessEvent(event) {
  const key = eventKey(event);
  if (state.seenEventKeys.has(key)) return false;
  state.seenEventKeys.add(key);
  if (state.seenEventKeys.size > 3000) {
    state.seenEventKeys = new Set(Array.from(state.seenEventKeys).slice(-1500));
  }
  return true;
}

function extractLatLon(text) {
  if (!text) return null;
  const m = text.match(/\b(-?\d{1,2}\.\d+)[,\s]+(-?\d{1,3}\.\d+)\b/);
  if (!m) return null;
  return { lat: Number(m[1]), lon: Number(m[2]) };
}

function parseLatLonInput(text) {
  const m = text?.match(/^\s*(-?\d{1,2}\.\d+)\s*,\s*(-?\d{1,3}\.\d+)\s*$/);
  if (!m) return null;
  return { lat: Number(m[1]), lon: Number(m[2]) };
}
function buildStopDayOptions(selected = 0) {
  const opts = [];
  for (let d = 0; d <= 14; d += 1) {
    const label = `${d} day${d === 1 ? "" : "s"}`;
    opts.push(`<option value="${d}"${d === selected ? " selected" : ""}>${label}</option>`);
  }
  return opts.join("");
}
function buildStopHourOptions(selected = 0) {
  const opts = [];
  for (let h = 0; h <= 23; h += 1) {
    const label = `${h} hr`;
    opts.push(`<option value="${h}"${h === selected ? " selected" : ""}>${label}</option>`);
  }
  return opts.join("");
}
function removeStopRow(rowId) {
  const idx = state.routeUi.stopRows.findIndex((row) => row.id === rowId);
  if (idx < 0) return;
  const [row] = state.routeUi.stopRows.splice(idx, 1);
  if (row?.el?.parentNode) {
    row.el.parentNode.removeChild(row.el);
  }
}
function addStopRow(seed = {}) {
  if (!ui.multiStopList) return;
  const rowId = `stop-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`;
  const row = document.createElement("div");
  row.className = "multi-stop-row";
  row.dataset.stopId = rowId;
  row.innerHTML = `
    <input type="text" class="stop-input" placeholder="Stop address or lat,lon" value="${String(seed.label || "").replace(/"/g, "&quot;")}" />
    <select class="stop-days">${buildStopDayOptions(Number(seed.days || 0))}</select>
    <select class="stop-hours">${buildStopHourOptions(Number(seed.hours || 0))}</select>
    <button type="button" class="stop-remove-btn">Remove</button>
  `;
  const removeBtn = row.querySelector(".stop-remove-btn");
  removeBtn?.addEventListener("click", () => removeStopRow(rowId));
  ui.multiStopList.appendChild(row);
  state.routeUi.stopRows.push({
    id: rowId,
    el: row,
    inputEl: row.querySelector(".stop-input"),
    daysEl: row.querySelector(".stop-days"),
    hoursEl: row.querySelector(".stop-hours"),
  });
}
function collectStopRows() {
  return state.routeUi.stopRows
    .map((row) => ({
      label: row.inputEl?.value?.trim() || "",
      days: Number(row.daysEl?.value || 0),
      hours: Number(row.hoursEl?.value || 0),
    }))
    .filter((row) => row.label);
}
function stopDwellSeconds(stop) {
  const days = Number(stop?.days || 0);
  const hours = Number(stop?.hours || 0);
  return Math.max(0, days) * 86400 + Math.max(0, hours) * 3600;
}

function openWazeUrl(url) {
  window.open(url, "_blank", "noopener,noreferrer");
}

function openWazeFromCoords(lat, lon) {
  const url = `https://waze.com/ul?ll=${encodeURIComponent(lat)},${encodeURIComponent(lon)}&navigate=yes`;
  openWazeUrl(url);
}
function updateFollowButtonUi() {
  if (!ui.toggleFollowBtn) return;
  ui.toggleFollowBtn.textContent = `Follow: ${state.map.followMode ? "On" : "Off"}`;
  ui.toggleFollowBtn.classList.toggle("active", state.map.followMode);
}
function setFollowMode(enabled) {
  state.map.followMode = !!enabled;
  updateFollowButtonUi();
}
function mapRadiusForZoom(zoom) {
  const z = Number.isFinite(zoom) ? zoom : 9;
  if (z >= 15) return 35000;
  if (z >= 14) return 55000;
  if (z >= 13) return 85000;
  if (z >= 12) return 130000;
  if (z >= 11) return 210000;
  if (z >= 10) return 500000;
  if (z >= 9) return 900000;
  if (z >= 7) return 1400000;
  return 1800000;
}
function setBackendMapStatus(text) {
  if (!ui.backendMapStatus) return;
  ui.backendMapStatus.textContent = text;
}
function ensureMapLoadingOverlay() {
  if (!ui.integratedMap) return null;
  if (state.map.loadingOverlay) return state.map.loadingOverlay;
  const overlay = document.createElement("div");
  overlay.id = "integratedMapLoading";
  overlay.className = "map-loading-overlay";
  overlay.textContent = "Loading map chunks…";
  ui.integratedMap.appendChild(overlay);
  state.map.loadingOverlay = overlay;
  return overlay;
}
function setMapLoading(loading, text = "Loading map chunks…") {
  const overlay = ensureMapLoadingOverlay();
  if (!overlay) return;
  overlay.textContent = text;
  overlay.classList.toggle("hidden", !loading);
}
function maybeResolveMapLoading(token) {
  if (token !== state.map.loadingToken) return;
  const chunksReady = state.map.chunkReadyToken === token;
  const imageReady = state.map.imageReadyToken === token;
  if (chunksReady && imageReady) {
    setMapLoading(false);
  }
}
function ensureIsometricSurface() {
  if (!ui.integratedMap) return null;
  if (state.map.isometricImg) return state.map.isometricImg;
  const img = document.createElement("img");
  img.id = "integratedMapIsometricImage";
  img.alt = "Backend isometric map render";
  img.decoding = "async";
  img.loading = "eager";
  ui.integratedMap.replaceChildren(img);
  ensureMapLoadingOverlay();
  state.map.isometricImg = img;
  return img;
}
async function refreshBackendMapStatus() {
  const now = Date.now();
  if (now - state.map.lastBackendStatusAt < 12000) return;
  state.map.lastBackendStatusAt = now;
  try {
    const r = await fetch(apiUrl("/api/map/status"));
    if (!r.ok) return;
    const data = await r.json();
    const ladder = Array.isArray(data.zoom_ladder) ? data.zoom_ladder.join("/") : "n/a";
    const ready = data?.planet?.ready ? "ready" : "warming";
    setBackendMapStatus(`backend-map: ${data.status || "ok"} • planet:${ready} • ladder:${ladder}`);
  } catch {
    // keep last status line
  }
}
function refreshBackendMapPreview(lat, lon, opts = {}) {
  if (!ui.backendMapPreview) return;
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return;
  state.map.centerLat = lat;
  state.map.centerLon = lon;
  const zoom = Number.isFinite(opts.zoom) ? opts.zoom : state.map.instance?.getZoom?.() ?? 9;
  state.map.centerZoom = zoom;
  const radiusM = Number.isFinite(opts.radiusM) ? opts.radiusM : mapRadiusForZoom(zoom);
  const now = Date.now();
  if (!opts.force && now - state.map.lastBackendRenderAt < BACKEND_MAP_RENDER_MIN_INTERVAL_MS) {
    return;
  }
  const centerKey = `${lat.toFixed(5)},${lon.toFixed(5)}@${Math.round(radiusM)}`;
  if (!opts.force && centerKey === state.map.lastBackendCenterKey && now - state.map.lastBackendRenderAt < BACKEND_MAP_RENDER_MIN_INTERVAL_MS) {
    return;
  }
  state.map.lastBackendCenterKey = centerKey;
  state.map.lastBackendRenderAt = now;
  const token = ++state.map.loadingToken;
  state.map.chunkReadyToken = 0;
  state.map.imageReadyToken = 0;
  setMapLoading(true, "Loading map chunks…");
  const sceneUrl = apiUrl(
    `/api/map/scene?lat=${encodeURIComponent(lat)}&lon=${encodeURIComponent(lon)}&radius_m=${Math.round(radiusM)}&zoom=${Math.round(zoom)}`
  );
  fetch(sceneUrl)
    .then((response) => (response.ok ? response.json() : null))
    .then((scene) => {
      if (!scene || token !== state.map.loadingToken) {
        return;
      }
      const loadedCells = Array.isArray(scene.cells) ? scene.cells.length : 0;
      if (loadedCells > 0) {
        state.map.chunkReadyToken = token;
        maybeResolveMapLoading(token);
      } else {
        setMapLoading(true, "Waiting for map chunks…");
      }
    })
    .catch(() => {
      if (token === state.map.loadingToken) {
        setMapLoading(true, "Loading map chunks…");
      }
    });
  const src =
    apiUrl(
      `/api/map/render?lat=${encodeURIComponent(lat)}&lon=${encodeURIComponent(lon)}&radius_m=${Math.round(radiusM)}&w=880&h=390`
    ) + `&ts=${now}`;
  const isometricImg = ensureIsometricSurface();
  if (isometricImg) {
    isometricImg.onload = () => {
      if (token !== state.map.loadingToken) return;
      state.map.imageReadyToken = token;
      maybeResolveMapLoading(token);
    };
    isometricImg.onerror = () => {
      if (token !== state.map.loadingToken) return;
      setMapLoading(true, "Map render retrying…");
    };
    isometricImg.src = src;
  }
  ui.backendMapPreview.src = src;
  setBackendMapStatus(`backend-map: render @ ${lat.toFixed(4)}, ${lon.toFixed(4)} r=${Math.round(radiusM)}m`);
  refreshBackendMapStatus();
}
function initIntegratedMap() {
  if (!ui.integratedMap) return;
  document.querySelector(".map-card")?.classList.add("isometric-mode");
  ensureIsometricSurface();
  ensureMapLoadingOverlay();
  updateFollowButtonUi();
  refreshBackendMapPreview(34.0522, -118.2437, {
    force: true,
    zoom: 2,
    radiusM: 1800000 * STARTUP_MAP_ZOOM_OUT_FACTOR,
  });
}

function updateMapTrack(points) {
  if (state.map.isometricOnly || !state.map.instance || !window.L) return;
  if (!Array.isArray(points) || !points.length) return;
  const latLngs = points
    .filter((p) => Number.isFinite(p.lat) && Number.isFinite(p.lon))
    .map((p) => [p.lat, p.lon]);
  if (!latLngs.length) return;
  if (state.map.trackLine) {
    state.map.trackLine.setLatLngs(latLngs);
  } else {
    state.map.trackLine = window.L.polyline(latLngs, {
      color: "#4f8cff",
      weight: 4,
      opacity: 0.8,
    }).addTo(state.map.instance);
  }
}

function queueGpsSampleForIngestion(sample) {
  state.gpsIngest.pendingSample = sample;
  flushGpsIngestionQueue();
}

function flushGpsIngestionQueue() {
  if (state.gpsIngest.inFlight || !state.gpsIngest.pendingSample) return;
  const sample = state.gpsIngest.pendingSample;
  state.gpsIngest.pendingSample = null;
  state.gpsIngest.inFlight = true;

  fetch(apiUrl("/api/gps/update"), {
    method: "POST",
    keepalive: true,
    body: JSON.stringify(sample),
  })
    .then((res) => {
      if (!res.ok) throw new Error("gps ingest failed");
      return res.json();
    })
    .then((data) => {
      state.gpsIngest.lastAckAt = Date.now();
      updateMapTrack(Array.isArray(data?.track) ? data.track : []);
      setMapHud({ users: Number(data?.active_users || 0) });
    })
    .catch(() => {
      // Keep UI responsive; newer samples supersede older failed samples.
    })
    .finally(() => {
      state.gpsIngest.inFlight = false;
      if (state.gpsIngest.pendingSample) {
        flushGpsIngestionQueue();
      }
    });
}

async function fetchGpsTrackSnapshot() {
  try {
    const r = await fetch(apiUrl("/api/gps/track?limit=120"));
    if (!r.ok) return;
    const data = await r.json();
    if (!Array.isArray(data.points) || !data.points.length) return;
    updateMapTrack(data.points);
    const latest = data.points[data.points.length - 1];
    if (!state.currentGps && Number.isFinite(latest?.lat) && Number.isFinite(latest?.lon)) {
      updateIntegratedMap(latest.lat, latest.lon, 6, "gps");
    }
    setMapHud({ users: Number(data?.active_users || 0) });
  } catch {
    // optional bootstrap
  }
}

async function refreshTriangulationView() {
  try {
    const r = await fetch(apiUrl("/api/gps/triangulation"));
    if (!r.ok) return;
    const data = await r.json();
    if (data.status !== "ok") {
      setMapHud({
        users: Number(data?.active_users || 0),
        triangulation: data.status || "idle",
      });
      return;
    }
    const tLat = Number(data.estimated_lat);
    const tLon = Number(data.estimated_lon);
    if (!state.map.isometricOnly && Number.isFinite(tLat) && Number.isFinite(tLon) && state.map.instance && window.L) {
      const latLng = [tLat, tLon];
      if (!state.map.triangulationMarker) {
        state.map.triangulationMarker = window.L.circleMarker(latLng, {
          radius: 7,
          color: "#b58cff",
          fillColor: "#d0b7ff",
          fillOpacity: 0.75,
          weight: 2,
        }).addTo(state.map.instance);
      } else {
        state.map.triangulationMarker.setLatLng(latLng);
      }
      state.map.triangulationMarker.bindPopup("Triangulation seed (multi-user)");
    }
    setMapHud({
      users: Number(data.active_users || 0),
      triangulation: "active",
      lat: Number.isFinite(tLat) ? tLat : undefined,
      lon: Number.isFinite(tLon) ? tLon : undefined,
      accuracy: Number(data.average_accuracy_m || 0),
    });
  } catch {
    setMapHud({ triangulation: "unavailable" });
  }
}

function updateIntegratedMap(lat, lon, zoom = 11, markerKind = "gps", opts = {}) {
  const { forceCenter = false } = opts;
  initIntegratedMap();
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return;
  const shouldCenter =
    forceCenter ||
    markerKind === "alert" ||
    (markerKind === "gps" && (state.map.followMode || !state.map.hasAutoCentered));
  if (!shouldCenter) return;
  state.map.hasAutoCentered = true;
  refreshBackendMapPreview(lat, lon, { zoom, force: forceCenter || markerKind === "alert" });
}
function setSelectorStatus(text) {
  ui.selectorStatus.textContent = text;
}
function setSelectorModeUi() {
  const auto = ui.autoSelectorCheckbox?.checked !== false;
  ui.regionSelect.disabled = auto;
  ui.channelSelect.disabled = auto || !ui.regionSelect.value;
}
function isAutoSelectorMode() {
  return ui.autoSelectorCheckbox?.checked !== false;
}
function renderRegionOptions(regions) {
  ui.regionSelect.innerHTML = "";
  if (!regions.length) {
    ui.regionSelect.innerHTML = `<option value="">No regions found</option>`;
    return;
  }
  const defaultOpt = document.createElement("option");
  defaultOpt.value = "";
  defaultOpt.textContent = "Select region";
  ui.regionSelect.appendChild(defaultOpt);
  regions.forEach((region) => {
    const opt = document.createElement("option");
    opt.value = region;
    opt.textContent = region.toUpperCase();
    ui.regionSelect.appendChild(opt);
  });
}
function renderChannelOptions(channels) {
  ui.channelSelect.innerHTML = "";
  if (!channels.length) {
    ui.channelSelect.innerHTML = `<option value="">No channels in region</option>`;
    return;
  }
  const defaultOpt = document.createElement("option");
  defaultOpt.value = "";
  defaultOpt.textContent = "Select channel";
  ui.channelSelect.appendChild(defaultOpt);
  channels.forEach((ch) => {
    const opt = document.createElement("option");
    opt.value = ch.id || "";
    const services = Array.isArray(ch.service_types) ? ch.service_types.join("/") : "";
    opt.textContent = `${ch.name || ch.id} (${ch.state || ""}${services ? ` • ${services}` : ""})`;
    opt.dataset.channel = JSON.stringify(ch);
    ui.channelSelect.appendChild(opt);
  });
}
async function loadCatalogRegions() {
  try {
    const r = await fetch(apiUrl("/api/platform/broadcastify/catalog"));
    if (!r.ok) throw new Error("catalog unavailable");
    const data = await r.json();
    state.catalog.regions = Array.isArray(data.regions) ? data.regions : [];
    renderRegionOptions(state.catalog.regions);
    setSelectorStatus("selector: catalog loaded");
  } catch {
    setSelectorStatus("selector: catalog unavailable");
    ui.regionSelect.innerHTML = `<option value="">Catalog unavailable</option>`;
    ui.channelSelect.innerHTML = `<option value="">Catalog unavailable</option>`;
  } finally {
    setSelectorModeUi();
  }
}
async function loadChannelsForRegion(region) {
  if (!region) {
    state.catalog.channels = [];
    renderChannelOptions([]);
    setSelectorModeUi();
    return;
  }
  try {
    const r = await fetch(apiUrl(`/api/platform/broadcastify/catalog?region=${encodeURIComponent(region)}`));
    if (!r.ok) throw new Error("region catalog unavailable");
    const data = await r.json();
    state.catalog.channels = Array.isArray(data.channels) ? data.channels : [];
    renderChannelOptions(state.catalog.channels);
    setSelectorStatus(`selector: ${state.catalog.channels.length} channels in ${region.toUpperCase()}`);
  } catch {
    state.catalog.channels = [];
    renderChannelOptions([]);
    setSelectorStatus("selector: region load failed");
  }
  setSelectorModeUi();
}
function selectedManualChannel() {
  const selectedOption = ui.channelSelect.options[ui.channelSelect.selectedIndex];
  if (!selectedOption || !selectedOption.value || !selectedOption.dataset.channel) return null;
  try {
    return JSON.parse(selectedOption.dataset.channel);
  } catch {
    return null;
  }
}
function applyManualSelectionToStatus() {
  const selected = selectedManualChannel();
  if (!selected) {
    setSelectorStatus("selector: pick a channel for manual mode");
    return false;
  }
  const channelName = selected.name || selected.id || "manual channel";
  setSelectorStatus(`selector: manual ${channelName}`);
  addListItem(
    ui.jurisdictionNotices,
    `Selector manual channel: ${channelName} (${selected.city || ""}/${selected.county || ""}/${selected.state || ""})`
  );
  return true;
}
const STATE_NAME_TO_ABBR = {
  alabama: "AL", alaska: "AK", arizona: "AZ", arkansas: "AR", california: "CA", colorado: "CO", connecticut: "CT",
  delaware: "DE", "district of columbia": "DC", florida: "FL", georgia: "GA", hawaii: "HI", idaho: "ID", illinois: "IL",
  indiana: "IN", iowa: "IA", kansas: "KS", kentucky: "KY", louisiana: "LA", maine: "ME", maryland: "MD",
  massachusetts: "MA", michigan: "MI", minnesota: "MN", mississippi: "MS", missouri: "MO", montana: "MT", nebraska: "NE",
  nevada: "NV", "new hampshire": "NH", "new jersey": "NJ", "new mexico": "NM", "new york": "NY", "north carolina": "NC",
  "north dakota": "ND", ohio: "OH", oklahoma: "OK", oregon: "OR", pennsylvania: "PA", "rhode island": "RI",
  "south carolina": "SC", "south dakota": "SD", tennessee: "TN", texas: "TX", utah: "UT", vermont: "VT", virginia: "VA",
  washington: "WA", "west virginia": "WV", wisconsin: "WI", wyoming: "WY", "puerto rico": "PR"
};
function normalizeState(value) {
  const raw = (value || "").trim();
  if (!raw) return "";
  if (/^[a-z]{2}$/i.test(raw)) return raw.toUpperCase();
  return STATE_NAME_TO_ABBR[raw.toLowerCase()] || raw.toUpperCase();
}
function parseJurisdictionFromText(text) {
  if (!text) return null;
  const trimmed = text.trim();
  if (!trimmed) return null;
  const parts = trimmed.split(",").map((s) => s.trim()).filter(Boolean);
  if (parts.length < 2) return null;
  const state = normalizeState(parts[parts.length - 1]);
  if (!state || state.length !== 2) return null;
  return { city: parts[0] || "", county: "", state };
}
function inferJurisdictionFromRouteInputs() {
  return (
    parseJurisdictionFromText(ui.endInput?.value || "") ||
    parseJurisdictionFromText(ui.startInput?.value || "") ||
    null
  );
}
async function reverseGeocodeJurisdiction(lat, lon) {
  try {
    const url = `https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=${encodeURIComponent(lat)}&lon=${encodeURIComponent(lon)}`;
    const r = await fetch(url, {
      headers: { Accept: "application/json" }
    });
    if (!r.ok) return null;
    const data = await r.json();
    const addr = data?.address || {};
    const city = addr.city || addr.town || addr.village || addr.municipality || "";
    const county = addr.county || "";
    const state = normalizeState(addr.state || addr.state_code || "");
    if (!state) return null;
    return { city, county, state };
  } catch {
    return null;
  }
}
async function refreshBroadcastifySelector(lat, lon) {
  if (!isAutoSelectorMode()) {
    applyManualSelectionToStatus();
    return;
  }
  try {
    setSelectorStatus("selector: updating");
    const inferred = inferJurisdictionFromRouteInputs();
    const reverse = inferred ? null : await reverseGeocodeJurisdiction(lat, lon);
    const jurisdiction = inferred || reverse || {};
    const params = new URLSearchParams({
      lat: String(lat),
      lon: String(lon),
    });
    if (jurisdiction.city) params.set("city", jurisdiction.city);
    if (jurisdiction.county) params.set("county", jurisdiction.county);
    if (jurisdiction.state) params.set("state", jurisdiction.state);
    const url = apiUrl(`/api/platform/broadcastify/select?${params.toString()}`);
    const r = await fetch(url);
    if (!r.ok) throw new Error("selector endpoint unavailable");
    const data = await r.json();
    const selected = data.selected || {};
    const channelName = selected.name || selected.id || "unknown";
    setSelectorStatus(`selector: ${channelName}`);
    if (selected.city || selected.county || selected.state) {
      addListItem(
        ui.jurisdictionNotices,
        `Selector chose channel: ${channelName} (${selected.city || ""}/${selected.county || ""}/${selected.state || ""})`
      );
    }
  } catch {
    setSelectorStatus("selector: unavailable");
  }
}
function ensureClientId() {
  if (state.clientId) return state.clientId;
  const existing = window.localStorage.getItem("scanner_client_id");
  if (existing) {
    state.clientId = existing;
    return existing;
  }
  const created = `client-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
  window.localStorage.setItem("scanner_client_id", created);
  state.clientId = created;
  return created;
}

function applyCurrentGpsToUi(lat, lon, meta = {}) {
  state.currentGps = { lat, lon };
  ui.latInput.value = lat.toFixed(6);
  ui.lonInput.value = lon.toFixed(6);
  updateIntegratedMap(lat, lon, 13, "gps");
  setGpsStatus("gps: locked", "ok");
  setMapHud({
    lat,
    lon,
    accuracy: Number.isFinite(meta?.accuracy) ? meta.accuracy : 0,
  });
  state.gpsIngest.seq += 1;
  queueGpsSampleForIngestion({
    seq: state.gpsIngest.seq,
    ts: new Date().toISOString(),
    user_id: ensureClientId(),
    source: "frontend_browser",
    lat,
    lon,
    accuracy: Number.isFinite(meta?.accuracy) ? meta.accuracy : 0,
    speed: Number.isFinite(meta?.speed) ? meta.speed : 0,
    heading: Number.isFinite(meta?.heading) ? meta.heading : 0,
  });
  if (isAutoSelectorMode()) {
    refreshBroadcastifySelector(lat, lon);
  } else {
    applyManualSelectionToStatus();
  }
}
function acquireCurrentGpsAndApply() {
  if (!("geolocation" in navigator)) {
    setGpsStatus("gps: unsupported", "bad");
    return;
  }
  setGpsStatus("gps: locating", "warn");
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      applyCurrentGpsToUi(pos.coords.latitude, pos.coords.longitude, pos.coords);
    },
    (err) => {
      if (err?.code === 1) {
        setGpsStatus("gps: denied", "bad");
      } else if (err?.code === 2) {
        setGpsStatus("gps: unavailable", "bad");
      } else if (err?.code === 3) {
        setGpsStatus("gps: timeout", "warn");
      } else {
        setGpsStatus("gps: error", "bad");
      }
      if (err?.message) {
        console.warn("Geolocation error:", err.message);
      }
    },
    { enableHighAccuracy: true, timeout: 15000, maximumAge: 0 }
  );
}
function startGpsWatchMode() {
  if (!("geolocation" in navigator)) return;
  if (state.gpsWatchId !== null) return;
  state.gpsWatchId = navigator.geolocation.watchPosition(
    (pos) => {
      applyCurrentGpsToUi(pos.coords.latitude, pos.coords.longitude, pos.coords);
    },
    () => {
      // keep single-shot mode available via button even if watch fails
    },
    { enableHighAccuracy: true, timeout: 10000, maximumAge: 700 }
  );
}
async function initGeolocationFlow() {
  if (!("geolocation" in navigator)) {
    setGpsStatus("gps: unsupported", "bad");
    return;
  }
  if (navigator.permissions?.query) {
    try {
      const status = await navigator.permissions.query({ name: "geolocation" });
      if (status.state === "granted") {
        setGpsStatus("gps: granted", "ok");
        acquireCurrentGpsAndApply();
        startGpsWatchMode();
      } else if (status.state === "prompt") {
        setGpsStatus("gps: prompt needed", "warn");
      } else {
        setGpsStatus("gps: denied", "bad");
      }
      status.onchange = () => {
        if (status.state === "granted") {
          setGpsStatus("gps: granted", "ok");
          acquireCurrentGpsAndApply();
          startGpsWatchMode();
        } else if (status.state === "prompt") {
          setGpsStatus("gps: prompt needed", "warn");
        } else {
          setGpsStatus("gps: denied", "bad");
        }
      };
      return;
    } catch {
      // fallback below
    }
  }
  acquireCurrentGpsAndApply();
  startGpsWatchMode();
}

function maybeBrowserNotify(title, body) {
  if (!("Notification" in window)) return;
  if (Notification.permission === "granted") {
    new Notification(title, { body });
  }
}
function clamp01(value, fallback = 0) {
  const num = Number(value);
  if (!Number.isFinite(num)) return fallback;
  return Math.max(0, Math.min(1, num));
}
function buildVisualizerProfileFromEvent(event, mode = "normal") {
  const rms = clamp01((Number(event?.rms) || 0) * 5.2, NaN);
  const fallbackEnergy = mode === "alert" ? 0.8 : 0.45;
  const energy = Number.isFinite(rms) ? Math.max(rms, mode === "alert" ? 0.42 : 0.18) : fallbackEnergy;
  return {
    energy: clamp01(energy, fallbackEnergy),
    rms: clamp01(event?.rms, 0),
    clipRatio: clamp01(event?.clip_ratio, 0),
  };
}
function formatNotificationListText(rawText, eventTs) {
  if (!eventTs) return rawText;
  try {
    const d = new Date(eventTs);
    if (Number.isNaN(d.getTime())) return rawText;
    return `[${d.toLocaleTimeString()}] ${rawText}`;
  } catch {
    return rawText;
  }
}
function dispatchNotification(payload, opts = {}) {
  const { fromSnapshot = false } = opts;
  state.notificationWorkflow.total += 1;
  state.notificationWorkflow.lastEventType = payload.eventType || null;
  if (payload.mode === "alert") state.notificationWorkflow.alerts += 1;
  if (payload.mode === "normal") state.notificationWorkflow.normalCalls += 1;

  if (payload.listTarget && payload.listText) {
    addListItem(payload.listTarget, formatNotificationListText(payload.listText, payload.eventTs));
  }
  if (!fromSnapshot && payload.modalText) {
    showCallModal(payload.modalText, payload.mode || "normal", payload.durationMs ?? null);
  }
  if (!fromSnapshot && payload.browserTitle && payload.browserBody && !state.notificationWorkflow.quietMode) {
    const now = Date.now();
    if (now - state.notificationWorkflow.lastBrowserNotifyAt >= BROWSER_NOTIFY_COOLDOWN_MS) {
      maybeBrowserNotify(payload.browserTitle, payload.browserBody);
      state.notificationWorkflow.lastBrowserNotifyAt = now;
      state.notificationWorkflow.browserSent += 1;
    }
  }
  renderNotificationWorkflow();
}

function renderCallModal(text, mode = "normal", durationMs = null) {
  ui.alertModalText.textContent = text;
  ui.alertModal.classList.remove("modal-normal", "modal-alert");
  ui.alertModal.classList.add(mode === "alert" ? "modal-alert" : "modal-normal");
  ui.alertModal.classList.remove("hidden");
  ui.alertModal.setAttribute("aria-hidden", "false");
  startVisualizerAnimation(mode);
  if (state.modalHideTimer) clearTimeout(state.modalHideTimer);
  const hideAfter = Number.isFinite(durationMs) ? durationMs : (mode === "alert" ? 12000 : 4500);
  state.modalHideTimer = setTimeout(hideAlertModal, hideAfter);
}

function flushModalQueue() {
  if (state.modalActive) return;
  const next = state.modalQueue.shift();
  if (!next) return;
  state.modalActive = true;
  renderCallModal(next.text, next.mode, next.durationMs);
  renderNotificationWorkflow();
}

function showCallModal(text, mode = "normal", durationMs = null) {
  state.modalQueue.push({ text, mode, durationMs });
  renderNotificationWorkflow();
  flushModalQueue();
}

function hideAlertModal() {
  ui.alertModal.classList.add("hidden");
  ui.alertModal.classList.remove("modal-normal", "modal-alert");
  ui.alertModal.setAttribute("aria-hidden", "true");
  if (state.modalHideTimer) {
    clearTimeout(state.modalHideTimer);
    state.modalHideTimer = null;
  }
  if (state.visualizerFrame) cancelAnimationFrame(state.visualizerFrame);
  state.visualizerFrame = null;
  state.modalActive = false;
  renderNotificationWorkflow();
  flushModalQueue();
}
function startVisualizerAnimation(mode = "normal") {
  const canvas = ui.visualizerCanvas;
  if (!canvas) return;
  const ctx = canvas.getContext("2d");
  const bars = 42;

  const draw = () => {
    const now = performance.now() / 1000;
    const { width, height } = canvas;
    ctx.clearRect(0, 0, width, height);
    const floor = mode === "alert" ? 0.28 : 0.12;
    const decay = mode === "alert" ? 0.988 : 0.981;
    state.visualizer.targetEnergy = Math.max(floor, state.visualizer.targetEnergy * decay);
    state.visualizer.currentEnergy += (state.visualizer.targetEnergy - state.visualizer.currentEnergy) * 0.14;
    const gap = 4;
    const barWidth = (width - gap * (bars + 1)) / bars;
    for (let i = 0; i < bars; i += 1) {
      const drift = state.visualizer.seed + (i * 0.73);
      const wave = 0.5 + (0.5 * Math.sin((now * 8.5) + drift));
      const pulse = 0.5 + (0.5 * Math.sin((now * 17.3) + drift * 1.9));
      const amp = clamp01(
        floor + (state.visualizer.currentEnergy * (0.35 + (wave * 0.65))) + (pulse * 0.08),
        floor
      );
      const h = amp * (height - 22);
      const x = gap + i * (barWidth + gap);
      const y = height - h - 10;
      const hue = mode === "alert"
        ? Math.floor((i / bars) * 16)
        : 200 + Math.floor((i / bars) * 24);
      ctx.fillStyle = `hsl(${hue}, 92%, 61%)`;
      ctx.fillRect(x, y, barWidth, h);
      if (state.visualizer.clipRatio > 0.1 && mode === "alert") {
        const clipGlow = clamp01(state.visualizer.clipRatio * 2.2, 0);
        const glowHeight = Math.min(7, h * 0.25);
        ctx.fillStyle = `rgba(255, 80, 80, ${0.2 + (clipGlow * 0.5)})`;
        ctx.fillRect(x, y, barWidth, glowHeight);
      }
    }
    state.visualizerFrame = requestAnimationFrame(draw);
  };

  if (state.visualizerFrame) cancelAnimationFrame(state.visualizerFrame);
  state.visualizerFrame = requestAnimationFrame(draw);
}

function maybeJurisdictionNoticeFromText(text, source = "transcript", notify = true) {
  if (!text) return;
  const now = Date.now();
  if (now - state.lastJurisdictionNoticeTs < JURISDICTION_COOLDOWN_MS) return;

  const hints = [
    /county line/i,
    /city limit/i,
    /state line/i,
    /jurisdiction/i,
    /crossing into/i,
    /entering\b/i,
  ];

  if (hints.some((r) => r.test(text))) {
    state.lastJurisdictionNoticeTs = now;
    state.jurisdictionCount += 1;
    renderMetrics();
    const msg = `Approaching jurisdiction edge (${source}): ${text}`;
    addListItem(ui.jurisdictionNotices, msg);
    if (notify) maybeBrowserNotify("Jurisdiction Edge Notice", msg);
  }
}

function handleEvent(event, opts = {}) {
  const { fromSnapshot = false } = opts;
  if (!shouldProcessEvent(event)) return;
  updatePreview(event);
  const t = event.event_type;

  if (t === "pipeline_ready") {
    setRun("running", "ok");
    return;
  }
  if (t === "chunk_skipped_silence") {
    state.metrics.skipped_silence += 1;
    renderMetrics();
    return;
  }
  if (t === "chunk_skipped_clipped") {
    state.metrics.skipped_clipped += 1;
    renderMetrics();
    return;
  }
  if (t === "chunk_captured") {
    state.metrics.captured += 1;
    renderMetrics();
    const callText = event.transcript || "(no transcript)";
    const profile = buildVisualizerProfileFromEvent(event, "normal");
    state.visualizer.targetEnergy = profile.energy;
    state.visualizer.rms = profile.rms;
    state.visualizer.clipRatio = profile.clipRatio;
    state.visualizer.seed = Math.random() * Math.PI * 2;
    dispatchNotification(
      {
        eventType: t,
        eventTs: event.ts,
        mode: "normal",
        listTarget: ui.transcripts,
        listText: callText,
        modalText: `Call: ${callText}`,
        durationMs: 4500,
      },
      { fromSnapshot }
    );
    maybeJurisdictionNoticeFromText(event.transcript, "captured_chatter", !fromSnapshot);
    return;
  }
  if (t === "jurisdiction_proximity") {
    state.lastJurisdictionNoticeTs = Date.now();
    state.jurisdictionCount += 1;
    renderMetrics();
    const edgeText = event.message || "Approaching boundary of a new jurisdiction";
    addListItem(ui.jurisdictionNotices, edgeText);
    if (!fromSnapshot) maybeBrowserNotify("Jurisdiction Edge Notice", edgeText);
    return;
  }
  if (t === "alert_triggered") {
    if (event.kind === "llm_alert") state.metrics.llm_alert += 1;
    if (event.kind === "soft_alert_fallback") state.metrics.soft_alert_fallback += 1;
    renderMetrics();
    const alertText = `[${event.kind}] ${event.alert}`;
    const profile = buildVisualizerProfileFromEvent(event, "alert");
    state.visualizer.targetEnergy = profile.energy;
    state.visualizer.rms = profile.rms;
    state.visualizer.clipRatio = profile.clipRatio;
    state.visualizer.seed = Math.random() * Math.PI * 2;
    dispatchNotification(
      {
        eventType: t,
        eventTs: event.ts,
        mode: "alert",
        listTarget: ui.alerts,
        listText: alertText,
        modalText: alertText,
        durationMs: 12000,
        browserTitle: "Scanner Alert",
        browserBody: alertText,
      },
      { fromSnapshot }
    );

    const coords = extractLatLon(event.alert || "") || extractLatLon(event.transcript || "");
    if (coords) {
      state.lastAlertCoords = coords;
      updateIntegratedMap(coords.lat, coords.lon, 13, "alert");
    }
    maybeJurisdictionNoticeFromText(event.transcript, "alert_context", !fromSnapshot);
    return;
  }
  if (t === "run_summary") {
    state.metrics = {
      captured: Number(event.captured || 0),
      skipped_silence: Number(event.skipped_silence || 0),
      skipped_clipped: Number(event.skipped_clipped || 0),
      llm_alert: Number(event.llm_alert || 0),
      soft_alert_fallback: Number(event.soft_alert_fallback || 0),
    };
    renderMetrics();
    setRun("stopped", "warn");
  }
}

function connectSSE() {
  const source = new EventSource(apiUrl("/api/pipeline/stream"));
  setConn("connecting", "warn");

  source.onopen = () => setConn("live", "ok");
  source.onerror = () => setConn("reconnecting", "warn");

  source.onmessage = (msg) => {
    try {
      const event = JSON.parse(msg.data);
      handleEvent(event, { fromSnapshot: false });
    } catch {
      state.notificationWorkflow.parseErrors += 1;
      renderNotificationWorkflow();
      setConn("parse_error", "bad");
    }
  };
}

async function fetchSnapshotFallback() {
  try {
    const r = await fetch(apiUrl("/api/pipeline/snapshot"));
    if (!r.ok) return;
    const snapshot = await r.json();
    state.lastSnapshotTs = snapshot.ts || null;
    updateSnapshotPreview(snapshot);
    if (snapshot.metrics) {
      state.metrics = {
        ...state.metrics,
        ...snapshot.metrics,
      };
      renderMetrics();
    }
    if (snapshot.event_type_counts?.run_summary > 0) {
      setRun("stopped", "warn");
    }
    if (snapshot.recentEvents && Array.isArray(snapshot.recentEvents)) {
      snapshot.recentEvents.forEach((ev) => handleEvent(ev, { fromSnapshot: true }));
    }
  } catch {
    // SSE is primary source
  }
}

async function fetchRouteWeather() {
  const start = ui.startInput.value.trim();
  const end = ui.endInput.value.trim();
  return fetchRouteWeatherFor(start, end);
}

async function fetchRouteWeatherFor(start, end) {
  if (!start || !end) {
    ui.weatherList.innerHTML = "<li>Enter start and destination to load route weather.</li>";
    return;
  }
  try {
    const url = apiUrl(`/api/platform/weather/forecast?start=${encodeURIComponent(start)}&end=${encodeURIComponent(end)}`);
    const r = await fetch(url);
    if (!r.ok) throw new Error("weather endpoint unavailable");
    const data = await r.json();
    const points = Array.isArray(data.forecast) ? data.forecast : [];
    if (!points.length) {
      ui.weatherList.innerHTML = "<li>No forecast data returned for this route.</li>";
      return;
    }
    ui.weatherList.innerHTML = "";
    points.slice(0, 8).forEach((p) => {
      addListItem(ui.weatherList, `${p.segment || "route"} • ${p.temp ?? "?"}° • ${p.condition || "unknown"} • ${p.time || ""}`);
    });
  } catch {
    ui.weatherList.innerHTML = "<li>Weather API not available yet. Hook your Java backend endpoint at /api/platform/weather/forecast.</li>";
  }
}

async function fetchWazeRouteFromBackend({ start, end, lat, lon }) {
  const params = new URLSearchParams();
  if (start) params.set("start", start);
  if (end) params.set("end", end);
  if (Number.isFinite(lat)) params.set("lat", String(lat));
  if (Number.isFinite(lon)) params.set("lon", String(lon));
  const r = await fetch(apiUrl(`/api/platform/waze/route?${params.toString()}`));
  if (!r.ok) throw new Error("waze route endpoint unavailable");
  return r.json();
}
function asFiniteNumber(value) {
  const num = Number(value);
  return Number.isFinite(num) ? num : null;
}

function readBiasCoordinates() {
  if (state.currentGps?.lat != null && state.currentGps?.lon != null) {
    return { lat: state.currentGps.lat, lon: state.currentGps.lon };
  }
  const lat = asFiniteNumber(ui.latInput.value);
  const lon = asFiniteNumber(ui.lonInput.value);
  if (lat != null && lon != null) {
    return { lat, lon };
  }
  return null;
}
function hideDestinationSuggestions() {
  if (!state.routeSearch.menuEl) return;
  state.routeSearch.menuEl.classList.add("hidden");
  state.routeSearch.menuEl.innerHTML = "";
  state.routeSearch.activeIndex = -1;
}

function renderDestinationSuggestions() {
  const menu = state.routeSearch.menuEl;
  if (!menu) return;
  const items = state.routeSearch.suggestions;
  if (!Array.isArray(items) || !items.length) {
    hideDestinationSuggestions();
    return;
  }
  menu.innerHTML = "";
  items.forEach((item, idx) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "dest-suggest-item";
    if (idx === state.routeSearch.activeIndex) {
      button.classList.add("active");
    }
    button.textContent = item.display_name;
    button.addEventListener("mousedown", (event) => {
      event.preventDefault();
      applyDestinationSuggestion(item);
    });
    menu.appendChild(button);
  });
  menu.classList.remove("hidden");
}

function applyDestinationSuggestion(item) {
  if (!item) return;
  ui.endInput.value = item.display_name || ui.endInput.value;
  state.routeSearch.appliedLabel = ui.endInput.value.trim();
  if (Number.isFinite(item.lat) && Number.isFinite(item.lon)) {
    ui.latInput.value = Number(item.lat).toFixed(6);
    ui.lonInput.value = Number(item.lon).toFixed(6);
  } else if (item.place_id) {
    resolveGooglePlaceIdToCandidate(item.place_id, item.display_name)
      .then((candidate) => {
        if (!candidate) return;
        ui.latInput.value = Number(candidate.lat).toFixed(6);
        ui.lonInput.value = Number(candidate.lon).toFixed(6);
      })
      .catch(() => {
        // keep text-only destination when place lookup fails
      });
  }
  hideDestinationSuggestions();
}

async function fetchDestinationSuggestions(query) {
  const seq = ++state.routeSearch.requestSeq;
  const bias = readBiasCoordinates();
  const useGoogle = await ensureGoogleServices();
  try {
    if (useGoogle && state.google.autocompleteService && window.google?.maps?.places) {
      const request = {
        input: query,
        locationBias: googleLocationBias(bias),
      };
      const response = await state.google.autocompleteService.getPlacePredictions(request);
      if (seq !== state.routeSearch.requestSeq) return;
      const predictions = Array.isArray(response?.predictions) ? response.predictions : [];
      state.routeSearch.suggestions = predictions.slice(0, 8).map((p) => ({
        display_name: String(p?.description || "").trim(),
        lat: null,
        lon: null,
        place_id: String(p?.place_id || "").trim(),
      })).filter((p) => p.display_name);
      state.routeSearch.activeIndex = state.routeSearch.suggestions.length ? 0 : -1;
      renderDestinationSuggestions();
      return;
    }
    const url =
      apiUrl(`/api/platform/address-catalog/suggest?q=${encodeURIComponent(query)}&limit=8`)
      + buildBiasQueryString(bias);
    const response = await fetch(url);
    if (!response.ok || seq !== state.routeSearch.requestSeq) return;
    const payload = await response.json();
    if (seq !== state.routeSearch.requestSeq) return;
    const results = Array.isArray(payload?.results) ? payload.results : [];
    state.routeSearch.suggestions = results
      .map((r) => ({
        display_name: String(r?.display_name || "").trim(),
        lat: asFiniteNumber(r?.lat),
        lon: asFiniteNumber(r?.lon),
        place_id: "",
      }))
      .filter((r) => r.display_name);
    state.routeSearch.activeIndex = state.routeSearch.suggestions.length ? 0 : -1;
    renderDestinationSuggestions();
  } catch {
    if (seq === state.routeSearch.requestSeq) {
      hideDestinationSuggestions();
    }
  }
}

function scheduleDestinationSuggest() {
  if (!ui.endInput) return;
  const query = ui.endInput.value.trim();
  if (!query || parseLatLonInput(query)) {
    hideDestinationSuggestions();
    return;
  }
  if (state.routeSearch.debounceTimer) {
    clearTimeout(state.routeSearch.debounceTimer);
  }
  state.routeSearch.debounceTimer = setTimeout(() => {
    fetchDestinationSuggestions(query);
  }, DEST_SUGGEST_DEBOUNCE_MS);
}

function initDestinationSearchUi() {
  if (!ui.endInput || state.routeSearch.menuEl) return;
  const menu = document.createElement("div");
  menu.className = "dest-suggest-menu hidden";
  ui.endInput.insertAdjacentElement("afterend", menu);
  state.routeSearch.menuEl = menu;

  ui.endInput.addEventListener("input", () => {
    if (state.routeSearch.appliedLabel && ui.endInput.value.trim() !== state.routeSearch.appliedLabel) {
      state.routeSearch.appliedLabel = "";
    }
    scheduleDestinationSuggest();
  });
  ui.endInput.addEventListener("focus", () => {
    scheduleDestinationSuggest();
  });
  ui.endInput.addEventListener("blur", () => {
    setTimeout(() => {
      hideDestinationSuggestions();
    }, 120);
  });
  ui.endInput.addEventListener("keydown", (event) => {
    const items = state.routeSearch.suggestions;
    if (!items.length) {
      return;
    }
    if (event.key === "ArrowDown") {
      event.preventDefault();
      state.routeSearch.activeIndex = (state.routeSearch.activeIndex + 1) % items.length;
      renderDestinationSuggestions();
      return;
    }
    if (event.key === "ArrowUp") {
      event.preventDefault();
      state.routeSearch.activeIndex =
        (state.routeSearch.activeIndex - 1 + items.length) % items.length;
      renderDestinationSuggestions();
      return;
    }
    if (event.key === "Enter" && !event.shiftKey) {
      if (state.routeSearch.activeIndex >= 0 && state.routeSearch.activeIndex < items.length) {
        event.preventDefault();
        applyDestinationSuggestion(items[state.routeSearch.activeIndex]);
      }
      return;
    }
    if (event.key === "Escape") {
      hideDestinationSuggestions();
    }
  });
  document.addEventListener("click", (event) => {
    if (!menu.contains(event.target) && event.target !== ui.endInput) {
      hideDestinationSuggestions();
    }
  });
}

function buildBiasQueryString(bias) {
  if (!bias || !Number.isFinite(bias.lat) || !Number.isFinite(bias.lon)) return "";
  return `&lat=${encodeURIComponent(bias.lat)}&lon=${encodeURIComponent(bias.lon)}`;
}

function parseAddressCandidate(candidate, fallbackName) {
  if (!candidate || typeof candidate !== "object") return null;
  const lat = asFiniteNumber(candidate.lat);
  const lon = asFiniteNumber(candidate.lon);
  if (lat == null || lon == null) return null;
  const displayName = String(candidate.display_name || candidate.name || fallbackName || "").trim() || fallbackName;
  return { lat, lon, displayName };
}

function firstObjectFromArray(value) {
  return Array.isArray(value) && value.length && value[0] && typeof value[0] === "object" ? value[0] : null;
}

function extractCatalogCandidate(payload, fallbackName) {
  if (!payload || typeof payload !== "object") return null;
  return parseAddressCandidate(
    payload.entry ||
      payload.result ||
      payload.address ||
      firstObjectFromArray(payload.results) ||
      firstObjectFromArray(payload.entries),
    fallbackName
  );
}

async function resolveFromAddressCatalog(query, bias) {
  const url =
    apiUrl(`/api/platform/address-catalog/resolve?q=${encodeURIComponent(query)}`) + buildBiasQueryString(bias);
  const response = await fetch(url);
  if (!response.ok) return null;
  const payload = await response.json();
  return extractCatalogCandidate(payload, query);
}

async function resolveFromGeocode(query, bias) {
  const url = apiUrl(`/api/platform/geocode?q=${encodeURIComponent(query)}`) + buildBiasQueryString(bias);
  const response = await fetch(url);
  if (!response.ok) return null;
  const payload = await response.json();
  const first = Array.isArray(payload?.results) ? payload.results[0] : null;
  return parseAddressCandidate(first, query);
}
async function resolveFromGoogleGeocode(query, bias) {
  const ready = await ensureGoogleServices();
  if (!ready || !state.google.geocoder) return null;
  return new Promise((resolve) => {
    const request = { address: query };
    const locationBias = googleLocationBias(bias);
    if (locationBias) request.locationBias = locationBias;
    state.google.geocoder.geocode(request, (results, status) => {
      if (status !== "OK" || !Array.isArray(results) || !results.length) {
        resolve(null);
        return;
      }
      resolve(geocodeResultToCandidate(results[0], query));
    });
  });
}
async function resolveGooglePlaceIdToCandidate(placeId, fallbackName) {
  const ready = await ensureGoogleServices();
  if (!ready || !state.google.geocoder || !placeId) return null;
  return new Promise((resolve) => {
    state.google.geocoder.geocode({ placeId }, (results, status) => {
      if (status !== "OK" || !Array.isArray(results) || !results.length) {
        resolve(null);
        return;
      }
      resolve(geocodeResultToCandidate(results[0], fallbackName));
    });
  });
}
async function buildGoogleDirectionsRoute(origin, destination, stops) {
  const ready = await ensureGoogleServices();
  if (!ready || !state.google.directionsService || !window.google?.maps?.TravelMode) return null;
  return new Promise((resolve) => {
    const waypointItems = (Array.isArray(stops) ? stops : [])
      .filter((s) => Number.isFinite(s?.lat) && Number.isFinite(s?.lon))
      .map((s) => ({
        location: { lat: s.lat, lng: s.lon },
        stopover: true,
      }));
    state.google.directionsService.route(
      {
        origin: { lat: origin.lat, lng: origin.lon },
        destination: { lat: destination.lat, lng: destination.lon },
        waypoints: waypointItems,
        optimizeWaypoints: false,
        provideRouteAlternatives: false,
        travelMode: window.google.maps.TravelMode.DRIVING,
        drivingOptions: {
          departureTime: new Date(),
          trafficModel: window.google.maps.TrafficModel.BEST_GUESS,
        },
      },
      (result, status) => {
        if (status !== "OK" || !result?.routes?.length) {
          resolve(null);
          return;
        }
        resolve(result.routes[0]);
      }
    );
  });
}

async function upsertAddressCatalog(query, candidate, bias) {
  if (!candidate) return;
  const body = {
    query,
    display_name: candidate.displayName,
    lat: candidate.lat,
    lon: candidate.lon,
    source: "frontend_web_client",
  };
  if (bias && Number.isFinite(bias.lat) && Number.isFinite(bias.lon)) {
    body.bias_lat = bias.lat;
    body.bias_lon = bias.lon;
  }
  try {
    await fetch(apiUrl("/api/platform/address-catalog/upsert"), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
  } catch {
    // best effort warm-up
  }
}

async function resolveAddressWithFunnel(query, bias) {
  if (!query) return null;
  const fromGoogle = await resolveFromGoogleGeocode(query, bias).catch(() => null);
  if (fromGoogle) return { candidate: fromGoogle, fromCatalog: false, source: "google" };
  const fromCatalog = await resolveFromAddressCatalog(query, bias).catch(() => null);
  if (fromCatalog) return { candidate: fromCatalog, fromCatalog: true, source: "catalog" };
  const fromGeocode = await resolveFromGeocode(query, bias).catch(() => null);
  if (!fromGeocode) return null;
  upsertAddressCatalog(query, fromGeocode, bias);
  return { candidate: fromGeocode, fromCatalog: false, source: "osm-fallback" };
}
async function resolveWaypoint(raw, bias, labelPrefix = "Stop") {
  const parsed = parseLatLonInput(raw);
  if (parsed) {
    return { lat: parsed.lat, lon: parsed.lon, label: raw };
  }
  const resolved = await resolveAddressWithFunnel(raw, bias);
  if (!resolved?.candidate) {
    throw new Error(`${labelPrefix} unresolved`);
  }
  const sourceLabel = resolved.source || (resolved.fromCatalog ? "catalog" : "osm-fallback");
  addListItem(
    ui.jurisdictionNotices,
    `${labelPrefix}: ${sourceLabel} → ${resolved.candidate.displayName || raw}`
  );
  return {
    lat: resolved.candidate.lat,
    lon: resolved.candidate.lon,
    label: resolved.candidate.displayName || raw,
  };
}

async function buildMultiStopRouteOptions(origin, destination, stops) {
  const googleRoute = await buildGoogleDirectionsRoute(origin, destination, stops).catch(() => null);
  if (googleRoute) {
    const alternative = googleDirectionsRouteToAlternative(googleRoute);
    let stopDwellS = 0;
    (Array.isArray(stops) ? stops : []).forEach((stop) => {
      stopDwellS += stopDwellSeconds(stop);
    });
    alternative.stop_dwell_s = stopDwellS;
    alternative.eta_with_stops_s = Number(alternative.eta_speed_limit_s || 0) + stopDwellS;
    return {
      ts: new Date().toISOString(),
      status: "ok",
      origin: { lat: origin.lat, lon: origin.lon },
      destination: { lat: destination.lat, lon: destination.lon },
      alternatives: [alternative],
      alert_clusters: {
        ts: new Date().toISOString(),
        status: "ok",
        grid_deg: 1.0,
        clusters: [],
      },
      stop_count: Array.isArray(stops) ? stops.length : 0,
      source: "google_traffic",
    };
  }
  let current = origin;
  let totalDistanceM = 0;
  let totalDurationS = 0;
  let totalEtaSpeedS = 0;
  let totalStopDwellS = 0;
  const mergedRoutePoints = [];
  const mergedClusters = [];

  const waypoints = [...stops, destination];
  for (let i = 0; i < waypoints.length; i += 1) {
    const next = waypoints[i];
    const leg = await fetchRouteOptionsFromBackend({
      originLat: current.lat,
      originLon: current.lon,
      destLat: next.lat,
      destLon: next.lon,
      start: current.label || "",
      end: next.label || "",
    });
    const alt = Array.isArray(leg?.alternatives) && leg.alternatives.length ? leg.alternatives[0] : null;
    if (!alt) {
      throw new Error("missing_route_leg");
    }
    const legPoints = Array.isArray(alt.route_points) ? alt.route_points : [];
    if (legPoints.length) {
      if (!mergedRoutePoints.length) {
        mergedRoutePoints.push(...legPoints);
      } else {
        mergedRoutePoints.push(...legPoints.slice(1));
      }
    }
    totalDistanceM += Number(alt.distance_m || 0);
    totalDurationS += Number(alt.duration_s || 0);
    totalEtaSpeedS += Number(alt.eta_speed_limit_s ?? alt.duration_s ?? 0);
    const legClusters = Array.isArray(leg?.alert_clusters?.clusters) ? leg.alert_clusters.clusters : [];
    mergedClusters.push(...legClusters);
    if (i < stops.length) {
      totalStopDwellS += stopDwellSeconds(stops[i]);
    }
    current = next;
  }

  const aggregateAlternative = {
    index: 0,
    distance_m: totalDistanceM,
    duration_s: totalDurationS,
    eta_speed_limit_s: totalEtaSpeedS,
    stop_dwell_s: totalStopDwellS,
    eta_with_stops_s: totalEtaSpeedS + totalStopDwellS,
    has_toll_hint: false,
    has_ferry_hint: false,
    route_points: mergedRoutePoints,
  };

  return {
    ts: new Date().toISOString(),
    status: "ok",
    origin: { lat: origin.lat, lon: origin.lon },
    destination: { lat: destination.lat, lon: destination.lon },
    alternatives: [aggregateAlternative],
    alert_clusters: {
      ts: new Date().toISOString(),
      status: "ok",
      grid_deg: 1.0,
      clusters: mergedClusters,
    },
    stop_count: stops.length,
    source: "backend_route_options",
  };
}

async function planRoute() {
  const endRaw = ui.endInput.value.trim();
  const startRaw = ui.startInput.value.trim();
  if (!endRaw) {
    setRun("destination missing", "bad");
    setRouteVisualStatus("Enter a destination");
    return;
  }
  const parsedStart = parseLatLonInput(startRaw);
  const bias = readBiasCoordinates() || parsedStart || null;
  try {
    setRun("resolving route", "warn");
    const originLat = parsedStart?.lat ?? state.currentGps?.lat ?? asFiniteNumber(ui.latInput.value);
    const originLon = parsedStart?.lon ?? state.currentGps?.lon ?? asFiniteNumber(ui.lonInput.value);
    if (!Number.isFinite(originLat) || !Number.isFinite(originLon)) {
      setRun("origin unresolved", "bad");
      setRouteVisualStatus("Route origin needs valid coordinates");
      return;
    }
    const origin = { lat: originLat, lon: originLon, label: startRaw || "Current position" };
    const destination = await resolveWaypoint(endRaw, bias, "Destination");
    const stopInputs = collectStopRows();
    const resolvedStops = [];
    for (let i = 0; i < stopInputs.length; i += 1) {
      const stop = stopInputs[i];
      const resolved = await resolveWaypoint(stop.label, bias, `Stop ${i + 1}`);
      resolved.days = stop.days;
      resolved.hours = stop.hours;
      resolvedStops.push(resolved);
    }

    const options = await buildMultiStopRouteOptions(origin, destination, resolvedStops);
    state.routeUi.options = options;
    state.routeUi.activeAltIndex = 0;
    state.routeUi.selectedClusterIndex = -1;
    renderRouteVisuals();

    ui.latInput.value = destination.lat;
    ui.lonInput.value = destination.lon;
    const finalAlt = options.alternatives[0] || {};
    const etaLabel = formatDurationLabel(finalAlt.eta_with_stops_s ?? finalAlt.eta_speed_limit_s ?? finalAlt.duration_s);
    const viaLabel = options?.source === "google_traffic" ? "google traffic" : "backend route model";
    setRouteVisualStatus(
      `Stops: ${resolvedStops.length} • ETA: ${etaLabel} via ${viaLabel}`
    );
    setRun("route ready", "ok");
    fetchRouteWeatherFor(startRaw, destination.label || endRaw);

    fetchWazeRouteFromBackend({
      start: startRaw,
      end: destination.label || endRaw,
      lat: destination.lat,
      lon: destination.lon,
    })
      .then((route) => {
        if (Number.isFinite(route?.lat) && Number.isFinite(route?.lon)) {
          updateIntegratedMap(route.lat, route.lon, 12, "gps", { forceCenter: true });
        }
        if (route?.app_url) openWazeUrl(route.app_url);
      })
      .catch(() => {
        updateIntegratedMap(destination.lat, destination.lon, 12, "gps", { forceCenter: true });
        openWazeFromCoords(destination.lat, destination.lon);
      });
  } catch {
    setRun("route destination unresolved", "bad");
    setRouteVisualStatus("Route drawing unavailable");
  }
}

ui.enableNotifyBtn.addEventListener("click", async () => {
  if (!("Notification" in window)) return;
  try {
    const permission = await Notification.requestPermission();
    ui.enableNotifyBtn.textContent = permission === "granted" ? "Notifications Enabled" : "Notifications Blocked";
  } catch {
    ui.enableNotifyBtn.textContent = "Notifications Unavailable";
  }
});

ui.openWazeBtn.addEventListener("click", () => {
  const lat = Number(ui.latInput.value);
  const lon = Number(ui.lonInput.value);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return;
  openWazeFromCoords(lat, lon);
});
if (ui.addStopBtn) {
  ui.addStopBtn.addEventListener("click", () => addStopRow());
}

ui.planRouteBtn.addEventListener("click", planRoute);

ui.useAlertCoordsBtn.addEventListener("click", () => {
  if (!state.lastAlertCoords) return;
  ui.latInput.value = state.lastAlertCoords.lat;
  ui.lonInput.value = state.lastAlertCoords.lon;
  updateIntegratedMap(state.lastAlertCoords.lat, state.lastAlertCoords.lon, 12, "alert", { forceCenter: true });
});
ui.useCurrentGpsBtn.addEventListener("click", acquireCurrentGpsAndApply);
if (ui.recenterMapBtn) {
  ui.recenterMapBtn.addEventListener("click", () => {
    if (state.currentGps) {
      updateIntegratedMap(state.currentGps.lat, state.currentGps.lon, 13, "gps", { forceCenter: true });
      setMapHud({ triangulation: "recentered" });
      return;
    }
    if (state.lastAlertCoords) {
      updateIntegratedMap(state.lastAlertCoords.lat, state.lastAlertCoords.lon, 13, "alert", { forceCenter: true });
      setMapHud({ triangulation: "recentered" });
    }
  });
}
if (ui.toggleFollowBtn) {
  ui.toggleFollowBtn.addEventListener("click", () => {
    const nextFollow = !state.map.followMode;
    setFollowMode(nextFollow);
    if (nextFollow && state.currentGps) {
      updateIntegratedMap(state.currentGps.lat, state.currentGps.lon, 13, "gps", { forceCenter: true });
    }
  });
}
if (ui.quickRouteBtn) {
  ui.quickRouteBtn.addEventListener("click", () => {
    const hasRouteInput = !!(ui.startInput.value.trim() || ui.endInput.value.trim());
    if (hasRouteInput) {
      planRoute();
      return;
    }
    document.querySelector(".route-card")?.scrollIntoView({ behavior: "smooth", block: "start" });
    ui.endInput.focus();
  });
}
if (ui.quickAlertsBtn) {
  ui.quickAlertsBtn.addEventListener("click", () => {
    if (state.lastAlertCoords) {
      updateIntegratedMap(state.lastAlertCoords.lat, state.lastAlertCoords.lon, 14, "alert", { forceCenter: true });
      return;
    }
    ui.alerts?.scrollIntoView({ behavior: "smooth", block: "start" });
  });
}
if (ui.refreshBackendMapBtn) {
  ui.refreshBackendMapBtn.addEventListener("click", () => {
    const lat = Number(ui.latInput.value);
    const lon = Number(ui.lonInput.value);
    if (Number.isFinite(lat) && Number.isFinite(lon)) {
      refreshBackendMapPreview(lat, lon, { force: true });
      return;
    }
    const centerLat = state.map.centerLat;
    const centerLon = state.map.centerLon;
    if (Number.isFinite(centerLat) && Number.isFinite(centerLon)) {
      refreshBackendMapPreview(centerLat, centerLon, { force: true, zoom: state.map.centerZoom || 12 });
    }
  });
}
ui.refreshSelectorBtn.addEventListener("click", () => {
  if (!isAutoSelectorMode()) {
    applyManualSelectionToStatus();
    return;
  }
  const lat = Number(ui.latInput.value);
  const lon = Number(ui.lonInput.value);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
    setSelectorStatus("selector: set GPS first");
    return;
  }
  refreshBroadcastifySelector(lat, lon);
});
ui.autoSelectorCheckbox.addEventListener("change", () => {
  setSelectorModeUi();
  if (isAutoSelectorMode()) {
    setSelectorStatus("selector: automatic mode");
    const lat = Number(ui.latInput.value);
    const lon = Number(ui.lonInput.value);
    if (Number.isFinite(lat) && Number.isFinite(lon)) {
      refreshBroadcastifySelector(lat, lon);
    }
    return;
  }
  applyManualSelectionToStatus();
});
ui.regionSelect.addEventListener("change", () => {
  state.catalog.selectedRegion = ui.regionSelect.value || "";
  state.catalog.selectedChannelId = "";
  loadChannelsForRegion(state.catalog.selectedRegion);
});
ui.channelSelect.addEventListener("change", () => {
  state.catalog.selectedChannelId = ui.channelSelect.value || "";
  if (!isAutoSelectorMode()) {
    applyManualSelectionToStatus();
  }
});
if (ui.closeClusterDetailBtn) {
  ui.closeClusterDetailBtn.addEventListener("click", () => {
    closeClusterDetailPanel();
    renderClusterList();
  });
}

ui.closeAlertModalBtn.addEventListener("click", hideAlertModal);
ui.toggleQuietModeBtn.addEventListener("click", () => {
  state.notificationWorkflow.quietMode = !state.notificationWorkflow.quietMode;
  renderNotificationWorkflow();
});
ui.resetNotifyAuditBtn.addEventListener("click", () => {
  state.notificationWorkflow.total = 0;
  state.notificationWorkflow.normalCalls = 0;
  state.notificationWorkflow.alerts = 0;
  state.notificationWorkflow.browserSent = 0;
  state.notificationWorkflow.parseErrors = 0;
  state.notificationWorkflow.lastEventType = null;
  renderNotificationWorkflow();
});
ui.clearNotifyListsBtn.addEventListener("click", () => {
  ui.alerts.innerHTML = "";
  ui.transcripts.innerHTML = "";
});

renderMetrics();
renderNotificationWorkflow();
clearRouteDrawing();
setRouteVisualStatus("Route visualization idle");
setRun("waiting", "mute");
setGpsStatus("gps: pending", "mute");
initIntegratedMap();
if (ui.multiStopList) {
  ui.multiStopList.innerHTML = "";
}
initDestinationSearchUi();
updateFollowButtonUi();
setSelectorModeUi();
loadCatalogRegions();
connectSSE();
fetchSnapshotFallback();
fetchGpsTrackSnapshot();
refreshTriangulationView();
setInterval(refreshTriangulationView, 3000);
initGeolocationFlow();
window.addEventListener(
  "pointerdown",
  () => {
    if (!state.currentGps) {
      acquireCurrentGpsAndApply();
    }
  },
  { once: true }
);
