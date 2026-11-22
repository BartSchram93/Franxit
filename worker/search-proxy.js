// Cloudflare Worker between the Franxit app and LiteScrape (Google Shopping).
//
// - The LiteScrape key lives here only, as the secret LITESCRAPE_KEY; the app never has it. LiteScrape's answers are
//   turned into the format the app reads (see below).
// - The app can only ask for two things (a search and a product's shops), with fixed settings.
// - Answers are kept for a day and shared by everyone: the same product costs one search a day.
// - A limit on fresh searches (the ones that cost a LiteScrape request) per IP address per day.
// - Optional: the secret APP_TOKEN. When it is set, only requests that carry it (the app does) pass.
// - POST /recognize: a photo of a product (JPEG, sent only when you choose so in the app) goes to Google Lens through
//   SerpApi (secret SERPAPI_KEY): uploaded to SerpApi's Image API, then searched; the titles of the pages with the
//   same product come back ("Theelichthouder - Molly - geglazuurd"). Without that key, Google Cloud Vision's web
//   detection (secret VISION_KEY) is used. The photo is not kept here.
// - Limits per day are counted in Workers KV (binding LIMITS) when it is bound; the cache otherwise (which does
//   nothing on a workers.dev address, so bind LIMITS there).

const DAY = 86400;
const MAX_FRESH_PER_IP = 30;      // fresh searches per IP address per day

const MAX_RECOGNIZE_PER_IP = 5;   // photos recognised per IP address per day
const MAX_RECOGNIZE_PER_DAY = 8;  // photos recognised per day in all: about 240 a month, SerpApi's free 250
const MAX_PHOTO_BYTES = 500_000;  // SerpApi's Image API takes at most 500 KB

const bad = () => new Response("Bad request", { status: 400 });

/**
 * One more of today's uses under [key]; false when [max] were used already. In Workers KV (binding LIMITS) when
 * bound, else in the cache. KV can be a little behind between places, so a limit may be passed by one or two.
 */
async function countToday(env, ctx, key, max) {
  const day = new Date().toISOString().slice(0, 10);
  if (env.LIMITS) {
    const name = `count/${key}/${day}`;
    const used = parseInt((await env.LIMITS.get(name)) || "0", 10) || 0;
    if (used >= max) return false;
    ctx.waitUntil(env.LIMITS.put(name, String(used + 1), { expirationTtl: 2 * DAY }));
    return true;
  }
  const cache = caches.default;
  const counterKey = new Request(`https://cache.invalid/count/${key}/${day}`);
  const counted = await cache.match(counterKey);
  const used = counted ? parseInt(await counted.text(), 10) || 0 : 0;
  if (used >= max) return false;
  ctx.waitUntil(cache.put(counterKey, new Response(String(used + 1), { headers: { "Cache-Control": `max-age=${DAY}` } })));
  return true;
}

/** What is on a photo: through Google Lens (SerpApi) when its key is set, else Google Cloud Vision. */
async function recognize(request, env, ctx) {
  if (!env.SERPAPI_KEY && !env.VISION_KEY) return new Response("Not configured", { status: 500 });
  const photo = await request.arrayBuffer();
  if (photo.byteLength < 1000 || photo.byteLength > MAX_PHOTO_BYTES) return bad();
  const ip = request.headers.get("CF-Connecting-IP") || "unknown";
  if (!(await countToday(env, ctx, `recognize/${ip}`, MAX_RECOGNIZE_PER_IP))) return new Response("Too many photos today", { status: 429 });
  if (!(await countToday(env, ctx, "recognize/all", MAX_RECOGNIZE_PER_DAY))) return new Response("Too many photos today", { status: 429 });
  return env.SERPAPI_KEY ? lens(photo, env) : vision(photo, env);
}

const json = (body) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } });

/** Google Lens through SerpApi: the photo uploaded (an id for 10 minutes), then the titles of its visual matches. */
async function lens(photo, env) {
  const form = new FormData();
  form.append("image", new Blob([photo], { type: "image/jpeg" }), "photo.jpg");
  form.append("api_key", env.SERPAPI_KEY);
  const uploaded = await fetch("https://serpapi.com/image", { method: "POST", body: form });
  let upload;
  try { upload = await uploaded.json(); } catch { return new Response("Recognition service error", { status: 502 }); }
  if (!uploaded.ok || upload.error || !upload.image_id) return new Response("Recognition service error", { status: liteStatus(uploaded.status) });
  const params = new URLSearchParams({ engine: "google_lens", image_id: upload.image_id, type: "visual_matches", hl: "nl", country: "be", api_key: env.SERPAPI_KEY });
  const answer = await fetch(`https://serpapi.com/search?${params}`);
  let data;
  try { data = await answer.json(); } catch { return new Response("Recognition service error", { status: 502 }); }
  if (!answer.ok || data.error) {
    // No matches is an answer, not an error.
    if (/hasn't returned any results|no results/i.test(data.error || "")) return json({ guess: "", entities: [], titles: [] });
    return new Response("Recognition service error", { status: liteStatus(answer.status) });
  }
  const matches = (data.visual_matches || []).filter((m) => (m.title || "").trim()).slice(0, 15);
  // The shop of each match ("Amazon.com.be", "Delhaize"): the app leaves its name out of the product's.
  return json({ guess: "", entities: [], titles: matches.map((m) => m.title.trim()), sources: matches.map((m) => (m.source || "").trim()) });
}

/** Google Cloud Vision's web detection: its best guess, the things it names and the titles of pages with the photo. */
async function vision(photo, env) {
  const bytes = new Uint8Array(photo);
  let binary = "";
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  const answer = await fetch(`https://vision.googleapis.com/v1/images:annotate?key=${encodeURIComponent(env.VISION_KEY)}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      requests: [{
        image: { content: btoa(binary) },
        features: [{ type: "WEB_DETECTION", maxResults: 10 }],
        imageContext: { webDetectionParams: { includeGeoResults: true } },
      }],
    }),
  });
  if (!answer.ok) return new Response("Recognition service error", { status: liteStatus(answer.status) });
  let data;
  try { data = await answer.json(); } catch { return new Response("Recognition service error", { status: 502 }); }
  const web = (data.responses && data.responses[0] && data.responses[0].webDetection) || {};
  const body = {
    guess: ((web.bestGuessLabels || [])[0] || {}).label || "",
    entities: (web.webEntities || []).filter((e) => e.description).map((e) => ({ name: e.description, score: e.score || 0 })),
    titles: (web.pagesWithMatchingImages || []).map((p) => (p.pageTitle || "").replace(/<[^>]*>/g, "")).filter(Boolean).slice(0, 10),
  };
  return json(body);
}

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
// Colours (Dutch, English, French) with the marketing names shops use ("frost", "midnight"), each to one colour. When the
// search words name a colour, a title that names only other colours is another variant.
const COLOURS = {
  zwart: "black", black: "black", noir: "black", onyx: "black", obsidian: "black",
  wit: "white", white: "white", blanc: "white",
  zilver: "silver", zilverkleurig: "silver", silver: "silver", argent: "silver",
  grijs: "grey", grey: "grey", gray: "grey", gris: "grey", graphite: "grey", grafiet: "grey", charcoal: "grey",
  blauw: "blue", blue: "blue", bleu: "blue", navy: "blue", marine: "blue",
  rood: "red", red: "red", rouge: "red", burgundy: "red", bordeaux: "red",
  groen: "green", green: "green", vert: "green", olive: "green", olijf: "green",
  goud: "gold", goudkleurig: "gold", gold: "gold", or: "gold", champagne: "gold",
  roze: "pink", pink: "pink", rose: "pink",
  paars: "purple", purple: "purple", violet: "purple", lavender: "purple", lavendel: "purple", lilac: "purple",
  bruin: "brown", brown: "brown", brun: "brown", bronze: "brown", koper: "brown", copper: "brown",
  oranje: "orange", orange: "orange",
  geel: "yellow", yellow: "yellow", jaune: "yellow",
  beige: "beige", creme: "beige", cream: "beige", ivoor: "beige", ivory: "beige", sand: "beige", zand: "beige",
  frost: "frost", midnight: "midnight", starlight: "starlight", titanium: "titanium", titan: "titanium",
  mint: "mint", teal: "teal", turquoise: "teal",
};
const colourSet = (text) => new Set(String(text || "").toLowerCase().split(/[^\p{L}]+/u).map((w) => COLOURS[w]).filter(Boolean));
// True when the search words name a colour and the title names colours but none of them: the same model in another colour.
const otherColour = (title, q) => {
  const wanted = colourSet(q), has = colourSet(title);
  return wanted.size > 0 && has.size > 0 && ![...has].some((c) => wanted.has(c));
};

// Shops that sell used or refurbished products: LiteScrape doesn't mark those, the app leaves out what is marked.
const SECOND_HAND_SHOP = /back ?market|used products|sellpy|refurbed|swappie|rebuy|2dehands|marktplaats|vinted|cash converters/i;

/** LiteScrape's search answer in the app's format. */
function liteSearch(data, q) {
  const codes = modelCodes(q);
  const results = (data.shopping_results || []).filter((r) => hasCodes(r.title, codes) && !otherColour(r.title, q)).map((r) => {
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
  const stores = (data.offers || []).filter((o) => !o.title || (hasCodes(o.title, codes) && !otherColour(o.title, q))).map((o) => ({
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
    if (env.APP_TOKEN && request.headers.get("X-Franxit-Token") !== env.APP_TOKEN) {
      return new Response("Forbidden", { status: 403 });
    }
    const url = new URL(request.url);
    if (url.pathname === "/recognize") {
      if (request.method !== "POST") return new Response("Method not allowed", { status: 405 });
      return recognize(request, env, ctx);
    }
    if (request.method !== "GET") return new Response("Method not allowed", { status: 405 });

    let upstream;
    if (url.pathname === "/search") {
      const q = (url.searchParams.get("q") || "").trim();
      if (q.length < 2 || q.length > 150) return bad();
      upstream = new URLSearchParams({ q, gl: "be", hl: "nl", google_domain: "google.be" });
    } else if (url.pathname === "/stores") {
      const token = url.searchParams.get("page_token") || "";
      if (token.length < 10 || token.length > 4000 || !/^[A-Za-z0-9_\-=.%]+$/.test(token)) return bad();
      upstream = new URLSearchParams({ page_token: token });
    } else {
      return new Response("Not found", { status: 404 });
    }

    // The same words in another case are the same search.
    const cache = caches.default;
    const cacheKey = new Request("https://cache.invalid" + url.pathname + "?" + upstream.toString().toLowerCase());
    const hit = await cache.match(cacheKey);
    if (hit) return hit;

    // A fresh search is counted per IP address per day.
    const ip = request.headers.get("CF-Connecting-IP") || "unknown";
    if (!(await countToday(env, ctx, ip, MAX_FRESH_PER_IP))) return new Response("Too many searches today", { status: 429 });

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
    const body = JSON.stringify(url.pathname === "/search" ? liteSearch(data, upstream.get("q")) : liteStores(data, storesQuery));
    const status = 200;
    const response = new Response(body, {
      status,
      headers: { "Content-Type": "application/json", "Cache-Control": `public, max-age=${DAY}` },
    });
    // Only good answers are kept; an error (no searches left, a wrong key) is asked again next time.
    if (status === 200) ctx.waitUntil(cache.put(cacheKey, response.clone()));
    return response;
  },
};
