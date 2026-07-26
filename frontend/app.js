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
  lastSnapshotTs: null,
  seenEventKeys: new Set(),
};
const JURISDICTION_COOLDOWN_MS = 4 * 60 * 1000;

const API_BASE = (window.SCANNER_API_BASE_URL || "").replace(/\/+$/, "");
const ui = {
  connStatus: document.getElementById("connStatus"),
  runStatus: document.getElementById("runStatus"),
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
  wazeMapFrame: document.getElementById("wazeMapFrame"),
  openWazeBtn: document.getElementById("openWazeBtn"),
  planRouteBtn: document.getElementById("planRouteBtn"),
  useAlertCoordsBtn: document.getElementById("useAlertCoordsBtn"),
  enableNotifyBtn: document.getElementById("enableNotifyBtn"),
  alertModal: document.getElementById("alertModal"),
  alertModalText: document.getElementById("alertModalText"),
  closeAlertModalBtn: document.getElementById("closeAlertModalBtn"),
  visualizerCanvas: document.getElementById("visualizerCanvas"),
};

function apiUrl(path) {
  return API_BASE ? `${API_BASE}${path}` : path;
}

function setConn(text, cls) {
  ui.connStatus.className = `pill ${cls}`;
  ui.connStatus.textContent = text;
}

function setRun(text, cls = "mute") {
  ui.runStatus.className = `pill ${cls}`;
  ui.runStatus.textContent = text;
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

function openWazeUrl(url) {
  window.open(url, "_blank", "noopener,noreferrer");
}

function openWazeFromCoords(lat, lon) {
  const url = `https://waze.com/ul?ll=${encodeURIComponent(lat)},${encodeURIComponent(lon)}&navigate=yes`;
  openWazeUrl(url);
}

function updateEmbeddedMap(lat, lon, zoom = 11) {
  const src = `https://embed.waze.com/iframe?zoom=${encodeURIComponent(zoom)}&lat=${encodeURIComponent(lat)}&lon=${encodeURIComponent(lon)}`;
  ui.wazeMapFrame.src = src;
}

function maybeBrowserNotify(title, body) {
  if (!("Notification" in window)) return;
  if (Notification.permission === "granted") {
    new Notification(title, { body });
  }
}

function showAlertModal(text) {
  ui.alertModalText.textContent = text;
  ui.alertModal.classList.remove("hidden");
  ui.alertModal.setAttribute("aria-hidden", "false");
  startVisualizerAnimation();
  setTimeout(hideAlertModal, 12000);
}

function hideAlertModal() {
  ui.alertModal.classList.add("hidden");
  ui.alertModal.setAttribute("aria-hidden", "true");
  if (state.visualizerFrame) cancelAnimationFrame(state.visualizerFrame);
  state.visualizerFrame = null;
}

function startVisualizerAnimation() {
  const canvas = ui.visualizerCanvas;
  if (!canvas) return;
  const ctx = canvas.getContext("2d");
  const bars = 42;

  const draw = () => {
    const { width, height } = canvas;
    ctx.clearRect(0, 0, width, height);
    const gap = 4;
    const barWidth = (width - gap * (bars + 1)) / bars;
    for (let i = 0; i < bars; i += 1) {
      const amp = 0.2 + Math.random() * 0.8;
      const h = amp * (height - 22);
      const x = gap + i * (barWidth + gap);
      const y = height - h - 10;
      const hue = 30 + Math.floor((i / bars) * 90);
      ctx.fillStyle = `hsl(${hue}, 92%, 61%)`;
      ctx.fillRect(x, y, barWidth, h);
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
    addListItem(ui.transcripts, event.transcript || "(no transcript)");
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
    addListItem(ui.alerts, alertText);
    if (!fromSnapshot) {
      showAlertModal(alertText);
      maybeBrowserNotify("Scanner Alert", alertText);
    }

    const coords = extractLatLon(event.alert || "") || extractLatLon(event.transcript || "");
    if (coords) {
      state.lastAlertCoords = coords;
      updateEmbeddedMap(coords.lat, coords.lon);
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

function planRoute() {
  const endRaw = ui.endInput.value.trim();
  const startRaw = ui.startInput.value.trim();
  const parsedEnd = parseLatLonInput(endRaw);
  const parsedStart = parseLatLonInput(startRaw);
  const lat = parsedEnd?.lat ?? parsedStart?.lat;
  const lon = parsedEnd?.lon ?? parsedStart?.lon;

  fetchWazeRouteFromBackend({ start: startRaw, end: endRaw, lat, lon })
    .then((route) => {
      if (route?.embed_url && ui.wazeMapFrame) ui.wazeMapFrame.src = route.embed_url;
      if (route?.app_url) openWazeUrl(route.app_url);
    })
    .catch(() => {
      if (parsedEnd) {
        updateEmbeddedMap(parsedEnd.lat, parsedEnd.lon, 12);
        openWazeFromCoords(parsedEnd.lat, parsedEnd.lon);
      } else if (endRaw) {
        const url = `https://waze.com/ul?q=${encodeURIComponent(endRaw)}&navigate=yes`;
        openWazeUrl(url);
      }
    });

  if (parsedStart) {
    ui.latInput.value = parsedStart.lat;
    ui.lonInput.value = parsedStart.lon;
  }
  fetchRouteWeather();
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

ui.planRouteBtn.addEventListener("click", planRoute);

ui.useAlertCoordsBtn.addEventListener("click", () => {
  if (!state.lastAlertCoords) return;
  ui.latInput.value = state.lastAlertCoords.lat;
  ui.lonInput.value = state.lastAlertCoords.lon;
  updateEmbeddedMap(state.lastAlertCoords.lat, state.lastAlertCoords.lon, 12);
});

ui.closeAlertModalBtn.addEventListener("click", hideAlertModal);

renderMetrics();
setRun("waiting", "mute");
connectSSE();
fetchSnapshotFallback();
