// The product search of search-proxy.js, with Exa stood in for: node worker/search-proxy.test.mjs
import assert from "node:assert/strict";
const store = new Map();
globalThis.caches = { default: { match: async (r) => store.get(r.url)?.clone(), put: async (r, res) => { store.set(r.url, res); } } };
const calls = [];
globalThis.fetch = async (url, init) => {
  calls.push({ url: String(url), init });
  if (String(url).startsWith("https://api.exa.ai/search")) {
    const body = JSON.parse(init.body);
    assert.equal(init.headers["x-api-key"], "KEY");
    // The big Belgian shops on their own, when the search found few Belgian shops' pages.
    if (body.includeDomains) {
      assert.ok(body.includeDomains.includes("mediamarkt.be"));
      return Response.json({ results: body.query.startsWith("TCL")
        ? [{ url: "https://www.mediamarkt.be/nl/product/_tcl-nxtpaper-14-2155555.html", title: "TCL NXTPAPER 14 | MediaMarkt" }] : [] });
    }
    assert.ok(body.excludeDomains.includes("youtube.com"));
    if (body.query.startsWith("fail")) return new Response("no credits", { status: 402 });
    // Asked as a purchase in Belgium, unless the words say so already.
    if (body.query.startsWith("PlayStation 5")) assert.equal(body.query, "PlayStation 5 kopen België");
    return Response.json({ results: [
      { url: "https://www.coolblue.be/nl/product/123/ps5.html?utm_source=x#top", title: " PlayStation 5  Slim ", score: 0.8, image: "https://img/1.jpg", highlights: ["De PS5 Slim met disc."] },
      { url: "https://www.coolblue.be/nl/product/123/ps5.html", title: "Duplicate", score: 0.7 },
      { url: "https://www.bol.com/be/nl/p/ps5-pro/1/", title: "PS5 Pro", image: "http://insecure/img.jpg" },
      { url: "javascript:alert(1)", title: "bad" },
      { url: "https://www.fnac.be/a1", title: "" },
    ] });
  }
  if (String(url).startsWith("https://suggestqueries.google.com/")) return new Response(JSON.stringify(["play", ["playstation 5", "playstation 5 pro", 3, ""]]));
  if (String(url).startsWith("https://serpapi.com/search.json")) {
    const u = new URL(url);
    if (u.searchParams.get("api_key") === "SPENT") return Response.json({ error: "Your account has run out of searches." }, { status: 429 });
    if (u.searchParams.get("engine") === "google_immersive_product") {
      assert.equal(u.searchParams.get("more_stores"), "true");
      assert.equal(u.searchParams.get("page_token"), "SERP-TOKEN-1234567890");
      return Response.json({ product_results: { stores: [
        { name: "Coolblue", title: "Sony WH-1000XM5 Zwart", extracted_price: 299, link: "https://www.coolblue.be/x", logo: "https://l/c.png", rating: 4.6 },
        { name: "MediaMarkt", title: "Sony WH-1000XM4 Zwart", extracted_price: 199, link: "https://www.mediamarkt.be/y" },
        { name: "Back Market", title: "Sony WH-1000XM5", extracted_price: 150, link: "https://www.backmarket.be/z" },
      ] } });
    }
    if (u.searchParams.get("q") === "nothing here") return Response.json({ error: "Google hasn't returned any results for this query." });
    if (u.searchParams.get("q") === "serp down") return new Response("oops", { status: 500 });
    return Response.json({ shopping_results: [
      { title: "Sony WH-1000XM5 Zwart", source: "Coolblue.be", extracted_price: 299, immersive_product_page_token: "SERP-TOKEN-1234567890" },
      { title: "Sony WH-1000XM4", source: "bol.com", extracted_price: 199, immersive_product_page_token: "OTHER" },
    ] });
  }
  if (String(url).startsWith("https://api.litescrape.com/")) {
    return Response.json({ shopping_results: [{ title: "Sony WH-1000XM5", source: "Krëfel", extracted_price: 289, gpcid: "123" }] });
  }
  throw new Error("unexpected " + url);
};
const { default: worker } = await import("./search-proxy.js");
const env = { EXA_API_KEY: "KEY", APP_TOKEN: "T" };
const waits = []; const ctx = { waitUntil: (p) => waits.push(p) };
const post = (body, token = "T") => worker.fetch(new Request("https://w/api/product-search", { method: "POST", headers: { "X-Franxit-Token": token, "CF-Connecting-IP": "1.2.3.4" }, body: JSON.stringify(body) }), env, ctx);

assert.equal((await post({ query: "ps5" }, "wrong")).status, 403);
assert.equal((await post({ query: " " })).status, 400);
assert.equal((await post({ query: "x".repeat(151) })).status, 400);
let r = await post({ query: "PlayStation 5" });
assert.equal(r.status, 200);
let j = await r.json();
assert.equal(j.products.length, 2);
assert.deepEqual(j.products[0], { title: "PlayStation 5 Slim", description: "De PS5 Slim met disc.", url: "https://www.coolblue.be/nl/product/123/ps5.html", imageUrl: "https://img/1.jpg", price: null, currency: null, retailer: "coolblue.be", availability: null, score: 0.8 });
assert.equal(j.products[1].imageUrl, null);
await Promise.all(waits);
const before = calls.length;
r = await post({ query: "playstation 5" });  // cached (case)
assert.equal(r.status, 200); assert.equal(calls.length, before);
r = await post({ query: "fail" });
assert.equal(r.status, 502);
// 20 fresh searches a day per user (its install id), counted apart from another user on the same address.
const user = (id, q) => worker.fetch(new Request("https://w/api/product-search", { method: "POST", headers: { "X-Franxit-Token": "T", "CF-Connecting-IP": "5.6.7.8", "X-Franxit-Install": id }, body: JSON.stringify({ query: q }) }), env, ctx);
const a = "11111111-1111-1111-1111-111111111111", b = "22222222-2222-2222-2222-222222222222";
for (let i = 0; i < 20; i++) { assert.equal((await user(a, "q" + i + "x")).status, 200); await Promise.all(waits); }
assert.equal((await user(a, "another one")).status, 429);
assert.equal((await user(b, "another one")).status, 200);
await Promise.all(waits);
// A search made today already costs nothing, also over the limit.
assert.equal((await user(a, "q3x")).status, 200);
r = await worker.fetch(new Request("https://w/api/product-search", { headers: { "X-Franxit-Token": "T" } }), env, ctx);
assert.equal(r.status, 405);
r = await worker.fetch(new Request("https://w/api/product-search", { method: "POST", headers: { "X-Franxit-Token": "T" }, body: JSON.stringify({ query: "ps5" }) }), { APP_TOKEN: "T" }, ctx);
assert.equal(r.status, 500);
r = await worker.fetch(new Request("https://w/api/suggest?q=play", { headers: { "X-Franxit-Token": "T" } }), env, ctx);
assert.deepEqual(await r.json(), { suggestions: ["playstation 5", "playstation 5 pro"] });

// Google Shopping: SerpApi when the app asks for speed (a product just added), LiteScrape otherwise (the nightly checks).
const gs = (path, fast, e = { APP_TOKEN: "T", SERPAPI_KEY: "SPENT", SERPAPI2_KEY: "K2", LITESCRAPE_KEY: "L" }) =>
  worker.fetch(new Request("https://w" + path, { headers: { "X-Franxit-Token": "T", "CF-Connecting-IP": "9.9.9.9", ...(fast ? { "X-Franxit-Speed": "fast" } : {}) } }), e, ctx);
calls.length = 0;
r = await gs("/search?q=sony wh-1000xm5", true);
j = await r.json();
assert.equal(j.shopping_results.length, 1);  // the XM4 is another model
assert.equal(j.shopping_results[0].source, "Coolblue.be");
assert.ok(j.shopping_results[0].immersive_product_page_token.startsWith("sa."));
assert.equal(calls.filter((c) => c.url.includes("serpapi")).length, 2);  // the first key used up: the second
assert.ok(!calls.some((c) => c.url.includes("litescrape")));
// Its shops: SerpApi's, filtered (another model, a second-hand shop).
r = await gs("/stores?page_token=" + encodeURIComponent(j.shopping_results[0].immersive_product_page_token), false);
j = await r.json();
assert.deepEqual(j.product_results.stores.map((s) => s.name), ["Coolblue"]);
assert.equal(j.product_results.stores[0].logo, "https://l/c.png");
// The nightly check: LiteScrape.
await Promise.all(waits);
calls.length = 0;
r = await gs("/search?q=sony wh-1000xm5 nacht", false);
j = await r.json();
assert.equal(j.shopping_results[0].source, "Krëfel");
assert.ok(!calls.some((c) => c.url.includes("serpapi")));
// SerpApi failing: LiteScrape answers. Google finding nothing: an empty answer, not LiteScrape.
calls.length = 0;
r = await gs("/search?q=serp down", true);
assert.equal((await r.json()).shopping_results[0].source, "Krëfel");
assert.ok(calls.some((c) => c.url.includes("litescrape")));
calls.length = 0;
r = await gs("/search?q=nothing here", true);
assert.deepEqual(await r.json(), { shopping_results: [] });
assert.ok(!calls.some((c) => c.url.includes("litescrape")));
assert.equal((await gs("/stores?page_token=sa.notbase64!!", false)).status, 400);

// Few Belgian shops found: the big Belgian shops searched too, their pages first.
r = await post({ query: "TCL nxtpaper 14" });
j = await r.json();
assert.equal(j.products[0].retailer, "mediamarkt.be");
assert.equal(j.products.length, 3);
console.log("worker tests ok");
