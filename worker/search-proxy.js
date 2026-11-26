// Cloudflare Worker between the Franxit app and Google Shopping.
//
// - Google Shopping: for a product just added (the app sends X-Franxit-Speed: fast) through SerpApi (secrets
//   SERPAPI_KEY, and SERPAPI2_KEY when the first's searches are used up), a search in a second or two, a product's shops
//   in about one; LiteScrape (secret LITESCRAPE_KEY), slower but cheaper, answers the nightly checks, and also when
//   SerpApi can't (both keys used up, an error). The keys live here only; the app never has them. Both answers are
//   given to the app in the same format and as strictly filtered (see below).
// - The app can only ask for two things (a search and a product's shops), with fixed settings.
// - Answers are kept for a day and shared by everyone: the same product costs one search a day.
// - A limit on fresh searches (the ones that cost a LiteScrape request) per IP address per day.
// - Optional: the secret APP_TOKEN. When it is set, only requests that carry it (the app does) pass.
// - POST /recognize: a photo of a product (JPEG, sent only when you choose so in the app) goes to Google Lens through
//   SerpApi (secret SERPAPI_KEY, and SERPAPI2_KEY when the first's searches are used up): uploaded to SerpApi's Image API, then searched; the titles of the pages with the
//   same product come back ("Theelichthouder - Molly - geglazuurd"). Without that key, Google Cloud Vision's web
//   detection (secret VISION_KEY) is used. The photo is not kept here.
// - POST /api/product-search {"query": "…"}: products found on the web (Exa, secret EXA_API_KEY), as {"products": [{title, description, url, imageUrl, price, currency, retailer,
//   availability, score}]}, from the whole web but news, video and social sites. Only what Exa returns: no price, stock or shop is ever made up (the app reads prices from
//   the pages themselves). Kept a day per query and shared (a search someone made today costs nothing); 20 fresh
//   searches per user (the app's anonymous id) per day.
// - GET /api/suggest?q=…: search words while typing ("play" → "playstation 5"), from Google's suggestions. Words
//   only, never products.
// - Limits per day are counted in Workers KV (binding LIMITS) when it is bound; the cache otherwise (which does
//   nothing on a workers.dev address, so bind LIMITS there).

const DAY = 86400;
const MAX_FRESH_PER_IP = 50;      // fresh Google Shopping searches per IP address per day (SerpApi or LiteScrape)

const MAX_RECOGNIZE_PER_IP = 5;   // photos recognised per IP address per day
const MAX_PHOTO_BYTES = 500_000;  // SerpApi's Image API takes at most 500 KB

const MAX_DISCOVER_PER_USER = 20;  // product searches (Exa) per app (its anonymous X-Franxit-Install id) per day
const MAX_DISCOVER_PER_IP = 200;   // and per IP address: an app that makes up new ids is stopped there
const DISCOVER_RESULTS = 25;       // asked of Exa; the app shows the product pages among them
const MAX_QUERY = 150;
const MAX_SUGGEST_PER_IP = 600;    // suggestions while typing per IP address per day (free, but not unlimited)

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
  if (!serpKeys(env).length && !env.VISION_KEY) return new Response("Not configured", { status: 500 });
  const photo = await request.arrayBuffer();
  if (photo.byteLength < 1000 || photo.byteLength > MAX_PHOTO_BYTES) return bad();
  const ip = request.headers.get("CF-Connecting-IP") || "unknown";
  if (!(await countToday(env, ctx, `recognize/${ip}`, MAX_RECOGNIZE_PER_IP))) return new Response("Too many photos today", { status: 429 });
  return serpKeys(env).length ? lens(photo, env) : vision(photo, env);
}

const json = (body) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } });

/** SerpApi's keys in order: the second (SERPAPI2_KEY) when the first's searches are used up or it is refused. */
const serpKeys = (env) => [env.SERPAPI_KEY, env.SERPAPI2_KEY].filter(Boolean);

/** Whether SerpApi's answer says the key can't search (searches used up, a wrong key): then the next key is tried. */
const keySpent = (status, error) => status === 401 || status === 429 || /run out|searches|invalid api key|account/i.test(error || "");

/** Google Lens through SerpApi: the photo uploaded (an id for 10 minutes), then the titles of its visual matches. */
async function lens(photo, env) {
  let last = new Response("Recognition service error", { status: 502 });
  for (const key of serpKeys(env)) {
    const form = new FormData();
    form.append("image", new Blob([photo], { type: "image/jpeg" }), "photo.jpg");
    form.append("api_key", key);
    const uploaded = await fetch("https://serpapi.com/image", { method: "POST", body: form });
    let upload;
    try { upload = await uploaded.json(); } catch { return new Response("Recognition service error", { status: 502 }); }
    if (!uploaded.ok || upload.error || !upload.image_id) {
      last = new Response("Recognition service error", { status: liteStatus(uploaded.status) });
      if (keySpent(uploaded.status, upload.error)) continue;
      return last;
    }
    const params = new URLSearchParams({ engine: "google_lens", image_id: upload.image_id, type: "visual_matches", hl: "nl", country: "be", api_key: key });
    const answer = await fetch(`https://serpapi.com/search?${params}`);
    let data;
    try { data = await answer.json(); } catch { return new Response("Recognition service error", { status: 502 }); }
    if (!answer.ok || data.error) {
      // No matches is an answer, not an error.
      if (/hasn't returned any results|no results/i.test(data.error || "")) return json({ guess: "", entities: [], titles: [] });
      last = new Response("Recognition service error", { status: liteStatus(answer.status) });
      if (keySpent(answer.status, data.error)) continue;
      return last;
    }
    const matches = (data.visual_matches || []).filter((m) => (m.title || "").trim()).slice(0, 15);
    // The shop of each match ("Amazon.com.be", "Delhaize"): the app leaves its name out of the product's.
    return json({ guess: "", entities: [], titles: matches.map((m) => m.title.trim()), sources: matches.map((m) => (m.source || "").trim()) });
  }
  return last;
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

/** The results of a search for [q] the app can use: [q]'s model codes, not another colour. */
const sameProduct = (q) => {
  const codes = modelCodes(q);
  return (title) => hasCodes(title, codes) && !otherColour(title, q);
};

/** A search result as the app reads it, with [token] for its product's shops (none: no shops asked). */
function shoppingResult(r, token) {
  const o = {
    title: r.title, source: r.source, price: r.price, extracted_price: r.extracted_price,
    thumbnail: r.thumbnail, rating: r.rating, reviews: r.reviews,
  };
  if (r.old_price) o.old_price = r.old_price;
  if (r.extensions) o.extensions = r.extensions;
  if (r.second_hand_condition || SECOND_HAND_SHOP.test(r.source || "")) o.second_hand_condition = r.second_hand_condition || "Refurbished";
  if (r.alternative_price) o.alternative_price = r.alternative_price;
  if (token) o.immersive_product_page_token = token;
  return o;
}

/** LiteScrape's search answer in the app's format. */
function liteSearch(data, q) {
  const same = sameProduct(q);
  const results = (data.shopping_results || []).filter((r) => same(r.title)).map((r) => shoppingResult(r,
    r.gpcid || r.catalog_id || r.headline_offer_docid ? b64({ q, g: r.gpcid, c: r.catalog_id, h: r.headline_offer_docid, i: r.image_docid }) : null));
  return { shopping_results: results };
}

/** LiteScrape's product answer ("offers") as the app's "product_results.stores". */
function liteStores(data, q) {
  const same = sameProduct(q);
  const stores = (data.offers || []).filter((o) => !o.title || same(o.title)).map((o) => ({
    name: o.merchant, title: o.title, extracted_price: o.extracted_price, price: o.price, link: o.link,
    details_and_offers: [o.availability, ...(o.details || [])].filter(Boolean),
  })).filter((o) => !SECOND_HAND_SHOP.test(o.name || ""));
  return { product_results: { stores } };
}

// ---- SerpApi: already the app's format; filtered as strictly as LiteScrape's ----

// SerpApi's product tokens start with this here, so a shops request knows whose token it has (LiteScrape's are ours).
const SERP_TOKEN = "sa.";

/** SerpApi's search answer, filtered; its product tokens marked as SerpApi's, with the search words to filter shops by. */
function serpSearch(data, q) {
  const same = sameProduct(q);
  const results = (data.shopping_results || []).filter((r) => same(r.title)).map((r) => shoppingResult(r,
    r.immersive_product_page_token ? SERP_TOKEN + b64({ q, t: r.immersive_product_page_token }) : null));
  return { shopping_results: results };
}

/** SerpApi's product page, its shops filtered. */
function serpStores(data, q) {
  const same = sameProduct(q);
  const stores = ((data.product_results && data.product_results.stores) || [])
    .filter((o) => !o.title || same(o.title))
    .filter((o) => !SECOND_HAND_SHOP.test(o.name || ""))
    .map((o) => ({
      name: o.name, title: o.title, extracted_price: o.extracted_price, price: o.price, link: o.link, logo: o.logo,
      rating: o.rating, reviews: o.reviews, details_and_offers: o.details_and_offers,
    }));
  return { product_results: { stores } };
}

// SerpApi saying Google found nothing: an answer (no results), not a failure.
const NOTHING_FOUND = /hasn't returned any results|no results/i;

/**
 * Google Shopping through SerpApi: the answer, or null when it can't answer (no keys, all used up, an error), for
 * LiteScrape to answer instead.
 */
async function serpApi(params, env) {
  for (const key of serpKeys(env)) {
    params.set("api_key", key);
    let answer, data;
    try { answer = await fetch(`https://serpapi.com/search.json?${params}`); data = await answer.json(); } catch { return null; }
    if (keySpent(answer.status, data.error)) continue;
    if (data.error && NOTHING_FOUND.test(data.error)) return {};
    return answer.ok && !data.error ? data : null;
  }
  return null;
}

/** An error from the provider as the status the app understands (401 key, 429 no searches left). */
function liteStatus(status) {
  if (status === 401 || status === 403) return 401;
  if (status === 402 || status === 429) return 429;
  return 502;
}

// ---- Product search (Exa) ----

// Sites that sell nothing: left out of the search, so its results are shops' pages. The app keeps only product pages
// of Belgian and Dutch shops and of the shops it trusts elsewhere.
const NOT_SHOPS = [
  "wikipedia.org", "youtube.com", "reddit.com", "facebook.com", "instagram.com", "x.com", "twitter.com", "tiktok.com",
  "pinterest.com", "linkedin.com", "tweakers.net", "hln.be", "nieuwsblad.be", "standaard.be", "vrt.be", "nu.nl",
  "nos.nl", "consumentenbond.nl", "test-aankoop.be", "testaankoop.be", "trustpilot.com", "kieskeurig.nl",
  "vergelijk.be", "vergelijk.nl", "beslist.nl", "beslist.be", "google.com", "google.be",
];

/** A query as the app may send it: words, not too few or too many. Null when it isn't one. */
function cleanQuery(raw) {
  const q = String(raw ?? "").replace(/\s+/g, " ").trim();
  if (q.length < 2 || q.length > MAX_QUERY) return null;
  return q;
}

/** The same page under another tracking code is the same page: those parameters and the "#…" go. */
function canonicalUrl(raw) {
  let u;
  try { u = new URL(raw); } catch { return null; }
  if (u.protocol !== "https:" && u.protocol !== "http:") return null;
  u.hash = "";
  for (const key of [...u.searchParams.keys()]) {
    if (/^(utm_|gclid|fbclid|ref|referrer|tag|campaign|cmp|affiliate|aff_|mc_)/i.test(key)) u.searchParams.delete(key);
  }
  return u.toString();
}

/** The shop of a page, from its address only ("www.coolblue.be" → "coolblue.be"): never guessed otherwise. */
function retailerOf(url) {
  try { return new URL(url).hostname.replace(/^www\./, ""); } catch { return null; }
}

/** Exa's results as products: one per page, with only what Exa gave (no price: Exa doesn't read one). */
function exaProducts(data) {
  const seen = new Set();
  const products = [];
  for (const r of (data && data.results) || []) {
    const url = canonicalUrl(r.url);
    const title = String(r.title || "").replace(/\s+/g, " ").trim();
    if (!url || !title || seen.has(url)) continue;
    seen.add(url);
    const highlight = Array.isArray(r.highlights) ? r.highlights.find((h) => typeof h === "string" && h.trim()) : null;
    products.push({
      title,
      description: highlight ? highlight.replace(/\s+/g, " ").trim().slice(0, 300) : null,
      url,
      imageUrl: typeof r.image === "string" && /^https:\/\//.test(r.image) ? r.image : null,
      price: null,
      currency: null,
      retailer: retailerOf(url),
      availability: null,
      score: typeof r.score === "number" ? r.score : null,
    });
  }
  return products;
}

/** POST /api/product-search: Exa's search of the trusted shops' sites, for words or a question in plain language. */
async function productSearch(request, env, ctx) {
  let body;
  try { body = await request.json(); } catch { return bad(); }
  const query = cleanQuery(body && body.query);
  if (!query) return bad();
  const limit = Math.min(Math.max(parseInt(body.limit, 10) || DISCOVER_RESULTS, 1), DISCOVER_RESULTS);

  const cache = caches.default;
  const cacheKey = new Request(`https://cache.invalid/api/product-search?q=${encodeURIComponent(query.toLowerCase())}&n=${limit}`);
  const hit = await cache.match(cacheKey);
  if (hit) return hit;

  const ip = request.headers.get("CF-Connecting-IP") || "unknown";
  // Per user: the app's anonymous id (a random UUID it made itself), else its IP address.
  const install = request.headers.get("X-Franxit-Install") || "";
  const user = /^[0-9a-f-]{36}$/i.test(install) ? `install/${install.toLowerCase()}` : `ip/${ip}`;
  if (!(await countToday(env, ctx, `discover/${user}`, MAX_DISCOVER_PER_USER))) return new Response("Daily limit reached", { status: 429 });
  if (!(await countToday(env, ctx, `discover/ip-all/${ip}`, MAX_DISCOVER_PER_IP))) return new Response("Daily limit reached", { status: 429 });
  if (!env.EXA_API_KEY) return new Response("Not configured", { status: 500 });

  // Asked as a purchase in Belgium: Exa then finds shops' pages in Belgium and the Netherlands rather than reviews
  // and shops abroad ("hjc f100 bios helm" alone gave British and Australian shops; with these words, Biker Outfit).
  const search = {
    query: /\b(kopen|acheter|buy)\b/i.test(query) ? query : `${query} kopen België`,
    type: "auto",
    numResults: limit,
    userLocation: "BE",
    contents: { highlights: { numSentences: 2, highlightsPerUrl: 1 } },
  };
  search.excludeDomains = NOT_SHOPS;
  let answer;
  try {
    answer = await fetch("https://api.exa.ai/search", {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-api-key": env.EXA_API_KEY },
      body: JSON.stringify(search),
    });
  } catch {
    return new Response("Search service error", { status: 502 });
  }
  // Exa's own trouble (credits, its limits) is no user's daily limit: a plain service error.
  if (!answer.ok) return new Response("Search service error", { status: 502 });
  let data;
  try { data = await answer.json(); } catch { return new Response("Search service error", { status: 502 }); }
  const response = new Response(JSON.stringify({ products: exaProducts(data) }), {
    status: 200,
    headers: { "Content-Type": "application/json", "Cache-Control": `public, max-age=${DAY}` },
  });
  ctx.waitUntil(cache.put(cacheKey, response.clone()));
  return response;
}

/** GET /api/suggest?q=…: Google's suggested search words for what you typed, in Dutch for Belgium. */
async function suggest(url, request, env, ctx) {
  const q = cleanQuery(url.searchParams.get("q"));
  if (!q) return bad();
  const cache = caches.default;
  const cacheKey = new Request(`https://cache.invalid/api/suggest?q=${encodeURIComponent(q.toLowerCase())}`);
  const hit = await cache.match(cacheKey);
  if (hit) return hit;
  const ip = request.headers.get("CF-Connecting-IP") || "unknown";
  if (!(await countToday(env, ctx, `suggest/${ip}`, MAX_SUGGEST_PER_IP))) return new Response("Too many searches today", { status: 429 });
  const params = new URLSearchParams({ client: "firefox", hl: "nl", gl: "be", q });
  let words = [];
  try {
    const answer = await fetch(`https://suggestqueries.google.com/complete/search?${params}`);
    if (!answer.ok) return new Response("Suggestion service error", { status: 502 });
    const data = JSON.parse(new TextDecoder("utf-8").decode(await answer.arrayBuffer()));
    words = (Array.isArray(data) && Array.isArray(data[1]) ? data[1] : [])
      .filter((w) => typeof w === "string" && w.trim()).map((w) => w.trim()).slice(0, 8);
  } catch {
    return new Response("Suggestion service error", { status: 502 });
  }
  const response = new Response(JSON.stringify({ suggestions: words }), {
    status: 200,
    headers: { "Content-Type": "application/json", "Cache-Control": `public, max-age=${DAY}` },
  });
  ctx.waitUntil(cache.put(cacheKey, response.clone()));
  return response;
}

export default {
  async fetch(request, env, ctx) {
    if (env.APP_TOKEN && request.headers.get("X-Franxit-Token") !== env.APP_TOKEN) {
      return new Response("Forbidden", { status: 403 });
    }
    const url = new URL(request.url);
    if (url.pathname === "/api/suggest") {
      if (request.method !== "GET") return new Response("Method not allowed", { status: 405 });
      return suggest(url, request, env, ctx);
    }
    if (url.pathname === "/api/product-search") {
      if (request.method !== "POST") return new Response("Method not allowed", { status: 405 });
      return productSearch(request, env, ctx);
    }
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
      if (token.length < 10 || token.length > 8000 || !/^[A-Za-z0-9_\-=.%]+$/.test(token)) return bad();
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

    const token = url.searchParams.get("page_token") || "";
    let body;
    // SerpApi for a product just added (the app asks for speed); LiteScrape for the nightly checks, where the wait
    // bothers no one and searches cost less. A SerpApi product's shops only SerpApi has.
    const fast = request.headers.get("X-Franxit-Speed") === "fast";
    if ((url.pathname === "/search" && fast) || token.startsWith(SERP_TOKEN)) {
      let t = null;
      if (url.pathname === "/stores") { try { t = unb64(token.slice(SERP_TOKEN.length)); } catch { return bad(); } }
      if (t && (typeof t.t !== "string" || t.t.length < 10)) return bad();
      const params = t
        ? new URLSearchParams({ engine: "google_immersive_product", page_token: t.t, gl: "be", hl: "nl", more_stores: "true" })
        : new URLSearchParams({ engine: "google_shopping", q: upstream.get("q"), gl: "be", hl: "nl", google_domain: "google.be" });
      const data = await serpApi(params, env);
      if (data) body = JSON.stringify(t ? serpStores(data, String(t.q || "")) : serpSearch(data, upstream.get("q")));
      // A SerpApi product's shops: only SerpApi has them.
      else if (t) return new Response("Search service error", { status: serpKeys(env).length ? 502 : 500 });
    }
    if (!body) {
      if (!env.LITESCRAPE_KEY) return new Response(serpKeys(env).length ? "Search service error" : "Not configured", { status: serpKeys(env).length ? 502 : 500 });
      const target = liteRequest(url.pathname, { q: upstream.get("q"), token });
      if (!target) return bad();
      const answer = await fetch(target, { headers: { Authorization: `Bearer ${env.LITESCRAPE_KEY}` } });
      if (!answer.ok) return new Response("Search service error", { status: liteStatus(answer.status) });
      let data;
      try { data = await answer.json(); } catch { return new Response("Search service error", { status: 502 }); }
      // The search words of a shops request are in its token.
      let storesQuery = "";
      if (url.pathname === "/stores") { try { storesQuery = unb64(token).q; } catch { /* no words: no filter */ } }
      body = JSON.stringify(url.pathname === "/search" ? liteSearch(data, upstream.get("q")) : liteStores(data, storesQuery));
    }
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
