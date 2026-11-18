// Cloudflare Worker between the Franxit app and SerpApi (Google Shopping).
//
// - The SerpApi key lives here only, as the secret SERPAPI_KEY; the app never has it.
// - The app can only ask for two things (a search and a product's shops), with fixed settings.
// - Answers are kept for a day and shared by everyone: the same product costs one search a day.
// - Limits on fresh searches (the ones that cost a SerpApi search): per IP address per day, and for
//   everyone together per day, so a day of misuse can never use up the month.
// - Optional: the secret APP_TOKEN. When it is set, only requests that carry it (the app does) pass.

const DAY = 86400;
const MAX_FRESH_PER_IP = 30;      // fresh searches per IP address per day
const DEFAULT_FRESH_PER_DAY = 20; // for everyone together; change with the variable GLOBAL_DAILY

const bad = () => new Response("Bad request", { status: 400 });

export default {
  async fetch(request, env, ctx) {
    if (request.method !== "GET") return new Response("Method not allowed", { status: 405 });
    if (env.APP_TOKEN && request.headers.get("X-Franxit-Token") !== env.APP_TOKEN) {
      return new Response("Forbidden", { status: 403 });
    }
    const url = new URL(request.url);

    let upstream;
    if (url.pathname === "/search") {
      const q = (url.searchParams.get("q") || "").trim();
      if (q.length < 2 || q.length > 150) return bad();
      upstream = new URLSearchParams({ engine: "google_shopping", q, gl: "be", hl: "nl", google_domain: "google.be" });
    } else if (url.pathname === "/stores") {
      const token = url.searchParams.get("page_token") || "";
      if (token.length < 10 || token.length > 4000 || !/^[A-Za-z0-9_\-=.%]+$/.test(token)) return bad();
      upstream = new URLSearchParams({ engine: "google_immersive_product", page_token: token, more_stores: "true" });
    } else {
      return new Response("Not found", { status: 404 });
    }

    // The same words in another case are the same search.
    const cache = caches.default;
    const cacheKey = new Request("https://cache.invalid" + url.pathname + "?" + upstream.toString().toLowerCase());
    const hit = await cache.match(cacheKey);
    if (hit) return hit;

    const day = new Date().toISOString().slice(0, 10);

    // A fresh search is counted per IP address per day ...
    const ip = request.headers.get("CF-Connecting-IP") || "unknown";
    const counterKey = new Request(`https://cache.invalid/count/${ip}/${day}`);
    const counted = await cache.match(counterKey);
    const used = counted ? parseInt(await counted.text(), 10) || 0 : 0;
    if (used >= MAX_FRESH_PER_IP) return new Response("Too many searches today", { status: 429 });

    // ... and for everyone together (needs the KV storage QUOTA; without it only the limit above applies).
    if (env.QUOTA) {
      const limit = parseInt(env.GLOBAL_DAILY || "", 10) || DEFAULT_FRESH_PER_DAY;
      const key = `fresh:${day}`;
      const total = parseInt((await env.QUOTA.get(key)) || "0", 10) || 0;
      if (total >= limit) return new Response("Searches are used up for today", { status: 429 });
      ctx.waitUntil(env.QUOTA.put(key, String(total + 1), { expirationTtl: 2 * DAY }));
    }
    ctx.waitUntil(cache.put(counterKey, new Response(String(used + 1), { headers: { "Cache-Control": `max-age=${DAY}` } })));

    if (!env.SERPAPI_KEY) return new Response("Not configured", { status: 500 });
    const answer = await fetch(`https://serpapi.com/search.json?${upstream}&api_key=${encodeURIComponent(env.SERPAPI_KEY)}`);
    const body = await answer.text();
    const response = new Response(body, {
      status: answer.status,
      headers: { "Content-Type": "application/json", "Cache-Control": `public, max-age=${DAY}` },
    });
    // Only good answers are kept; an error (no searches left, a wrong key) is asked again next time.
    if (answer.ok) ctx.waitUntil(cache.put(cacheKey, response.clone()));
    return response;
  },
};
