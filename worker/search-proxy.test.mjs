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
    assert.ok(body.excludeDomains.includes("youtube.com"));
    if (body.query === "fail") return new Response("no credits", { status: 402 });
    return Response.json({ results: [
      { url: "https://www.coolblue.be/nl/product/123/ps5.html?utm_source=x#top", title: " PlayStation 5  Slim ", score: 0.8, image: "https://img/1.jpg", highlights: ["De PS5 Slim met disc."] },
      { url: "https://www.coolblue.be/nl/product/123/ps5.html", title: "Duplicate", score: 0.7 },
      { url: "https://www.bol.com/be/nl/p/ps5-pro/1/", title: "PS5 Pro", image: "http://insecure/img.jpg" },
      { url: "javascript:alert(1)", title: "bad" },
      { url: "https://www.fnac.be/a1", title: "" },
    ] });
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
console.log("worker tests ok");
