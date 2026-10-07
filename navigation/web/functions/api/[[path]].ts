// /api/* for the public Scout site. Everything not in gateway/lib.ts's allowlist is a 404
// at the edge; allowed calls go to ORIGIN (the Dell map server, reached through a
// Cloudflare Tunnel) and come back rebuilt/scrubbed. With no ORIGIN configured the
// site still works and reports the map service as offline.
//
// Pages env:
//   ORIGIN     https://api.scoutnavigation.stream (Cloudflare Tunnel to the Dell; unset = offline)
//   EDGE_KEY   secret: X-Scout-Edge-Key. The Dell's nginx gate (scout-public-gate.conf) 404s
//              anything without it, so the tunnel hostname is useless to direct callers. The
//              map server's subscription token is injected there and never leaves the Dell.
import { RateLimiter, isRejection, parseRoute, scrub, summarizeHazards, upstreamFor, type Route } from "../../gateway/lib";

interface Env {
  ORIGIN?: string;
  EDGE_KEY?: string;
}

const general = new RateLimiter(120, 60_000);
const tiles = new RateLimiter(900, 60_000);

const SECURITY_HEADERS = {
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "no-referrer",
  "Cache-Control": "no-store"
};

function json(status: number, body: unknown, extra: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", ...SECURITY_HEADERS, ...extra }
  });
}

async function fromOrigin(env: Env, route: Route, timeoutMs: number): Promise<Response> {
  const origin = (env.ORIGIN ?? "").replace(/\/+$/, "");
  const headers: Record<string, string> = { Accept: route.kind === "tile" ? "image/png" : "application/json" };
  if (env.EDGE_KEY) headers["X-Scout-Edge-Key"] = env.EDGE_KEY;
  return fetch(`${origin}${upstreamFor(route)}`, { headers, signal: AbortSignal.timeout(timeoutMs) });
}

export const onRequest: PagesFunction<Env> = async (ctx) => {
  const { request, env } = ctx;
  if (request.method !== "GET" && request.method !== "HEAD") return json(405, { error: "method_not_allowed" });

  const url = new URL(request.url);
  const route = parseRoute(url.pathname.replace(/^\/api/, ""), url.searchParams);
  if (isRejection(route)) return json(route.status, { error: route.error });

  const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
  const limiter = route.kind === "tile" ? tiles : general;
  if (!limiter.allow(`${route.kind === "tile" ? "t" : "g"}:${ip}`)) {
    return json(429, { error: "rate_limited" }, { "Retry-After": "60" });
  }

  if (!env.ORIGIN || !env.EDGE_KEY) {
    return route.kind === "health"
      ? json(200, { online: false, reason: "map service not connected yet" })
      : json(503, { error: "service_offline" });
  }

  try {
    if (route.kind === "tile") {
      // Shared edge cache: popular tiles are served without reaching the Dell,
      // and the origin never learns which viewer asked.
      // Workers runtime cache; the DOM lib shared with the browser code doesn't know `default`.
      const cache = (caches as unknown as { default: Cache }).default;
      const key = new Request(url.toString(), { method: "GET" });
      const hit = await cache.match(key);
      if (hit) return hit;
      // The render + cache fill is registered with waitUntil, so it completes even when the
      // browser gives up first (it swaps a slow tile to OSM): the next request is a cache hit.
      const filled = (async (): Promise<Response | null> => {
        const up = await fromOrigin(env, route, 25_000);
        if (!up.ok || !(up.headers.get("content-type") ?? "").startsWith("image/")) return null;
        const res = new Response(await up.arrayBuffer(), {
          status: 200,
          headers: { "Content-Type": "image/png", "Cache-Control": "public, max-age=3600, s-maxage=86400", "X-Content-Type-Options": "nosniff" }
        });
        await cache.put(key, res.clone());
        return res;
      })();
      ctx.waitUntil(filled.catch(() => null));
      return (await filled) ?? json(502, { error: "tile_unavailable" });
    }

    if (route.kind === "scene") {
      // Keyed by the snapped upstream request, so every viewer in the same grid square at the
      // same ladder rung shares one cached scene. scrub() drops the alert_clusters the map server
      // appends to scenes (they carry raw radio transcripts).
      const cache = (caches as unknown as { default: Cache }).default;
      const key = new Request(`https://scene-cache.scout.internal${upstreamFor(route)}`, { method: "GET" });
      const hit = await cache.match(key);
      if (hit) return hit;
      const filled = (async (): Promise<Response | null> => {
        const up = await fromOrigin(env, route, 30_000);
        if (!up.ok) return null;
        const body = scrub(await up.json());
        const res = json(200, body, { "Cache-Control": "public, max-age=300, s-maxage=900" });
        await cache.put(key, res.clone());
        return res;
      })();
      ctx.waitUntil(filled.catch(() => null));
      return (await filled.catch(() => null)) ?? json(502, { error: "scene_unavailable" });
    }

    const up = await fromOrigin(env, route, route.kind === "health" ? 4_000 : 20_000);
    if (route.kind === "health") return json(200, { online: up.ok });
    let body: unknown;
    try {
      body = await up.json();
    } catch {
      return json(502, { error: "bad_upstream" });
    }
    if (!up.ok) return json(up.status === 400 ? 400 : 502, { error: up.status === 400 ? "rejected" : "upstream_error" });

    if (route.kind === "alerts") {
      return json(200, { clusters: summarizeHazards(body, route.cells) }, { "Cache-Control": "public, max-age=60" });
    }
    if (route.kind === "route") {
      const b = body as Record<string, unknown>;
      return json(200, scrub({ status: b.status, origin: b.origin, destination: b.destination, routes: b.routes, alternatives: b.alternatives }));
    }
    return json(200, scrub(body)); // geocode
  } catch {
    return json(route.kind === "health" ? 200 : 504, route.kind === "health" ? { online: false } : { error: "upstream_timeout" });
  }
};
