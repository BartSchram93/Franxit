// Cloudflare Worker between the Franxit app and a Google Shopping search service.
//
// - The search key lives here only; the app never has it. Which service answers depends on the secrets: with
//   SERPAPI_KEY set it is SerpApi, else with LITESCRAPE_KEY set it is LiteScrape (whose answers are turned into the
//   format the app reads, see below). To switch, set one secret and remove the other.
// - The app can only ask for two things (a search and a product's shops), with fixed settings.
// - Answers are kept for a day and shared by everyone: the same product costs one search a day.
// - A limit on fresh searches (the ones that cost a request to the service) per IP address per day.
// - Optional: the secret APP_TOKEN. When it is set, only requests that carry it (the app does) pass.

const DAY = 86400;
const MAX_FRESH_PER_IP = 30;      // fresh searches per IP address per day

const bad = () => new Response("Bad request", { status: 400 });

// ---- LiteScrape: its answers are turned into the format the app reads ("shopping_results", "product_results.stores") ----
const LITE = "https://api.litescrape.com/api/google/shopping";
// Belgium's shops: without a place the search can come from anywhere (Singapore shops were listed).
const PLACE = "Brussels, Brussels Region, Belgium";

// A token is the ids of a product, as url-safe base64 of JSON (UTF-8, as the search words can have any letter).
const b64 = (o) => {
  const bytes = new TextEncoder().encode(JSON.stringify(o));
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_");
};
const unb64 = (t) => {
  const bin = atob(t.replace(/-/g, "+").replace(/_/g, "/"));
  return JSON.parse(new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0))));
};

/** The request to LiteScrape for a search ("/search") or the shops of one product ("/stores"). */
function liteRequest(path, params) {
  if (path === "/search") {
    const q = new URLSearchParams({ q: params.q, gl: "be", hl: "nl", google_domain: "google.be", location: PLACE, num: "40" });
    return `${LITE}?${q}`;
  }
  // The token is what the search put in "immersive_product_page_token".
  let t;
  try { t = unb64(params.token); } catch { return null; }
  const q = new URLSearchParams({ q: String(t.q || ""), gl: "be", hl: "nl", google_domain: "google.be", location: PLACE });
  for (const [k, key] of [["g", "gpcid"], ["c", "catalog_id"], ["h", "headline_offer_docid"], ["i", "image_docid"]]) {
    if (t[k] && /^[0-9]{1,20}$/.test(String(t[k]))) q.set(key, String(t[k]));
  }
  if (!q.has("gpcid") && !q.has("headline_offer_docid") && !q.has("catalog_id")) return null;
  return `${LITE}/product?${q}`;
}

// The model codes in the search words ("WH-1000XM5", "GM-2100YM-8AER"): letters and digits together, ignoring dashes and
// case. A result for another model (XM4, XM6, the WF earbuds) lacks them.
const modelCodes = (q) => String(q || "").split(/\s+/)
  .map((w) => w.toLowerCase().replace(/[^a-z0-9]/g, ""))
  .filter((w) => w.length >= 4 && /[a-z]/.test(w) && /[0-9]/.test(w));
const hasCodes = (title, codes) => {
  const t = String(title || "").toLowerCase().replace(/[^a-z0-9]/g, "");
  return codes.every((c) => t.includes(c));
};
// Shops that sell used or refurbished products: LiteScrape doesn't mark those, the app leaves out what is marked.
const SECOND_HAND_SHOP = /back ?market|used products|sellpy|refurbed|swappie|rebuy|2dehands|marktplaats|vinted|cash converters/i;

/** LiteScrape's search answer in the app's format. */
function liteSearch(data, q) {
  const codes = modelCodes(q);
  const results = (data.shopping_results || []).filter((r) => hasCodes(r.title, codes)).map((r) => {
    const o = {
      title: r.title, source: r.source, price: r.price, extracted_price: r.extracted_price,
      thumbnail: r.thumbnail, rating: r.rating, reviews: r.reviews,
    };
    if (r.old_price) o.old_price = r.old_price;
    if (r.extensions) o.extensions = r.extensions;
    if (r.second_hand_condition || SECOND_HAND_SHOP.test(r.source || "")) o.second_hand_condition = r.second_hand_condition || "Refurbished";
    if (r.alternative_price) o.alternative_price = r.alternative_price;
    if (r.gpcid || r.catalog_id || r.headline_offer_docid) {
      o.immersive_product_page_token = b64({
        q, g: r.gpcid, c: r.catalog_id, h: r.headline_offer_docid, i: r.image_docid,
      });
    }
    return o;
  });
  return { shopping_results: results };
}

/** LiteScrape's product answer ("offers") as the app's "product_results.stores". */
function liteStores(data, q) {
  const codes = modelCodes(q);
  const stores = (data.offers || []).filter((o) => !o.title || hasCodes(o.title, codes)).map((o) => ({
    name: o.merchant, title: o.title, extracted_price: o.extracted_price, price: o.price, link: o.link,
    details_and_offers: [o.availability, ...(o.details || [])].filter(Boolean),
  })).filter((o) => !SECOND_HAND_SHOP.test(o.name || ""));
  return { product_results: { stores } };
}

/** An error from the provider as the status the app understands (401 key, 429 no searches left). */
function liteStatus(status) {
  if (status === 401 || status === 403) return 401;
  if (status === 402 || status === 429) return 429;
  return 502;
}

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
    const cacheKey = new Request("https://cache.invalid/" + (env.SERPAPI_KEY ? "serpapi" : "litescrape") + url.pathname + "?" + upstream.toString().toLowerCase());
    const hit = await cache.match(cacheKey);
    if (hit) return hit;

    const day = new Date().toISOString().slice(0, 10);

    // A fresh search is counted per IP address per day.
    const ip = request.headers.get("CF-Connecting-IP") || "unknown";
    const counterKey = new Request(`https://cache.invalid/count/${ip}/${day}`);
    const counted = await cache.match(counterKey);
    const used = counted ? parseInt(await counted.text(), 10) || 0 : 0;
    if (used >= MAX_FRESH_PER_IP) return new Response("Too many searches today", { status: 429 });

    ctx.waitUntil(cache.put(counterKey, new Response(String(used + 1), { headers: { "Cache-Control": `max-age=${DAY}` } })));

    let body;
    const status = 200;
    if (env.SERPAPI_KEY) {
      const answer = await fetch(`https://serpapi.com/search.json?${upstream}&api_key=${encodeURIComponent(env.SERPAPI_KEY)}`);
      if (!answer.ok) return new Response(await answer.text(), { status: answer.status });
      body = await answer.text();
    } else {
      if (!env.LITESCRAPE_KEY) return new Response("Not configured", { status: 500 });
      const target = liteRequest(url.pathname, { q: upstream.get("q"), token: url.searchParams.get("page_token") });
      if (!target) return bad();
      const answer = await fetch(target, { headers: { Authorization: `Bearer ${env.LITESCRAPE_KEY}` } });
      if (!answer.ok) return new Response("Search service error", { status: liteStatus(answer.status) });
      let data;
      try { data = await answer.json(); } catch { return new Response("Search service error", { status: 502 }); }
      // The search words of a shops request are in its token.
      let storesQuery = "";
      if (url.pathname === "/stores") { try { storesQuery = unb64(url.searchParams.get("page_token")).q; } catch { /* no words: no filter */ } }
      body = JSON.stringify(url.pathname === "/search" ? liteSearch(data, upstream.get("q")) : liteStores(data, storesQuery));
    }
    const response = new Response(body, {
      status,
      headers: { "Content-Type": "application/json", "Cache-Control": `public, max-age=${DAY}` },
    });
    // Only good answers are kept; an error (no searches left, a wrong key) is asked again next time.
    if (status === 200) ctx.waitUntil(cache.put(cacheKey, response.clone()));
    return response;
  },
};
