import { StrictMode, Suspense, lazy } from "react";
import { createRoot } from "react-dom/client";
import "./app.css";
import ASCIIText from "./effects/ASCIIText.jsx";
import MicroSlats from "./effects/MicroSlats.jsx";
import { Link, useRoute } from "./router";

const MapApp = lazy(() => import("./MapApp"));

const ACCENT = "#00c9c7";

function Landing() {
  return (
    <>
      <div aria-hidden="true" className="sc-sea">
        <MicroSlats
          backgroundColor="#000000"
          color={ACCENT}
          cursorSize={20}
          cursorStrength={2}
          fog={0}
          gap={1}
          glint={0.25}
          glintColor="#ba3737"
          interactive={true}
          lean={1}
          perspective={1}
          preset="swell"
          roundness={1}
          slatHeight={38}
          slatWidth={2}
          speed={2}
          stretch={0.55}
          swirl={0}
          trail={4}
        />
      </div>
      <article className="sc-page">
        <header className="sc-hero">
          <h1>Know the road before you're on it.</h1>
          <p className="sc-sub">
            Scout pulls live hazards from public traffic and weather feeds (crashes, closures, work zones, storms) and
            routes you around them. Your location stays on your device.
          </p>
          <div className="sc-cta">
            <Link className="sc-btn sc-btn-lg" to="/app">
              Open the map
            </Link>
            <Link className="sc-link" to="/privacy">
              How location works
            </Link>
          </div>
        </header>

        <ul className="sc-cards">
          <li className="sc-card">
            <h3>Live hazards, real sources</h3>
            <p>Incidents, closures and work zones from the National Weather Service, state 511 systems, USDOT work-zone feeds and TomTom, refreshed every few minutes.</p>
          </li>
          <li className="sc-card">
            <h3>Private by design</h3>
            <p>Your browser turns your position into a coarse ~40 km area code and asks only for that area's hazards. Scout's servers never receive your GPS.</p>
          </li>
          <li className="sc-card">
            <h3>Routes with the hazards in view</h3>
            <p>Search a destination and compare routes against the hazard picture along the way, on Scout's own map engine.</p>
          </li>
        </ul>
      </article>
    </>
  );
}

function Privacy() {
  return (
    <article className="sc-page sc-prose">
      <h1>How Scout handles location</h1>
      <ul className="sc-points">
        <li>"Use my area" asks your browser for your position, converts it on your device to a geohash cell (about 39 by 20 km), and then forgets it.</li>
        <li>Only that cell and its eight neighbours are sent to ask for hazards. The answer is an area summary: counts, the worst severity, kinds of hazards and the roads mentioned.</li>
        <li>Route search is the one place exact points are sent: the start and destination you picked, for that single request. Nothing is stored against you.</li>
        <li>Map tiles are cached at Cloudflare's edge, so most tile requests never reach Scout's servers at all.</li>
        <li>No accounts, no tracking scripts, no advertising.</li>
      </ul>
      <p>
        <Link className="sc-btn" to="/app">
          Open the map
        </Link>
      </p>
    </article>
  );
}

function NotFound() {
  return (
    <article className="sc-page">
      <h1>Not found</h1>
      <p>
        <Link className="sc-link" to="/">
          Back to Scout
        </Link>
      </p>
    </article>
  );
}

function Shell() {
  const route = useRoute();
  const isApp = route === "/app";
  return (
    <div className={`sc-shell${isApp ? " sc-shell-app" : ""}`}>
      <header className="sc-header">
        <Link className="sc-brand" to="/">
          <span aria-hidden="true" className="sc-brand-mark">
            <ASCIIText asciiFontSize={6} text="Scout" textColor={ACCENT} enableHueShift={false} />
          </span>
          <span className="sc-sr-only">Scout</span>
        </Link>
        <nav className="sc-nav">
          <Link to="/app">Map</Link>
          <Link to="/privacy">Privacy</Link>
        </nav>
      </header>
      <main className="sc-main">
        {route === "/" ? <Landing /> : null}
        {isApp ? (
          <Suspense fallback={<p className="sc-page">Loading map…</p>}>
            <MapApp />
          </Suspense>
        ) : null}
        {route === "/privacy" ? <Privacy /> : null}
        {!["/", "/app", "/privacy"].includes(route) ? <NotFound /> : null}
      </main>
      {isApp ? null : (
        <footer className="sc-footer">
          <span>Scout · hazard-aware routing</span>
          <span>Map data © OpenStreetMap contributors</span>
        </footer>
      )}
    </div>
  );
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <Shell />
  </StrictMode>
);
