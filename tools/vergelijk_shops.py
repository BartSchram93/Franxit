#!/usr/bin/env python3
"""
Refreshes core/.../data/VergelijkShops.kt: every shop vergelijk.be and vergelijk.nl list, so Google Shopping's offers from those shops
are trusted on every phone (not only on phones that happened to see the shop on vergelijk.be).

It reads each site's categories (from its home page) and the first three products of each, slowly (0.6 s between
pages, some 750 pages a site), and adds the shops it finds to the ones already in the file: a shop seen once stays.

    python3 tools/vergelijk_shops.py                            # both sites
    python3 tools/vergelijk_shops.py https://www.vergelijk.nl   # one
    python3 tools/vergelijk_shops.py --domains                  # only the Worker's shop sites, from the file
"""
import json, re, subprocess, sys, time, pathlib

SITES = ["https://www.vergelijk.be", "https://www.vergelijk.nl"]

UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
OUT = pathlib.Path(__file__).resolve().parent.parent / "core/src/main/java/com/bartschram/franxit/data/VergelijkShops.kt"
WORKER = pathlib.Path(__file__).resolve().parent.parent / "worker/search-proxy.js"
WRAPPERS = {"ShallowReactive", "Reactive", "ShallowRef", "Ref"}
DOMAIN = re.compile(r"^[a-z0-9-]+(\.[a-z0-9-]+)*\.(be|nl|com|eu|de|fr|net|shop)$")
# Shops the lists name without their site ("Bol.", "MediaMarkt", "Kamera Express"), and the big ones' other sites.
EXTRA_DOMAINS = [
    "bol.com", "amazon.com.be", "amazon.nl", "coolblue.be", "mediamarkt.be", "mediamarkt.nl", "fnac.be", "nl.fnac.be",
    "krefel.be", "vandenborre.be", "kamera-express.be", "cameranu.be", "bax-shop.be", "hema.be", "lego.com",
    "samsung.com", "lg.com", "philips.be", "sony.be", "dreamland.be", "x2o.be", "galaxus.be", "decathlon.be",
    "ikea.com", "zalando.be", "gamemania.be", "store.nintendo.com", "thomann.de", "gear4music.be", "conrad.be",
    "gooise-gitaren.nl", "music-vanderheyden.be",
]


def write_domains(names):
    """The shops' sites, for the product search (Exa) in the Worker: between its SHOP_DOMAINS markers."""
    domains = sorted({n.lower().removeprefix("www.") for n in names if DOMAIN.match(n.lower().removeprefix("www."))} | set(EXTRA_DOMAINS))
    text = WORKER.read_text()
    start, end = text.index("// SHOP_DOMAINS-START"), text.index("// SHOP_DOMAINS-END")
    head = text[start:].split("\n", 1)[0]
    body = "const SHOP_DOMAINS = [\n" + "".join(f'  "{d}",\n' for d in domains) + "];\n"
    WORKER.write_text(text[:start] + head + "\n" + body + text[end:])
    return len(domains)


def get(site, path):
    r = subprocess.run(["curl", "-sS", "--compressed", "-L", "--max-redirs", "2", "-A", UA, site + path],
                       capture_output=True, timeout=40)
    return r.stdout.decode("utf-8", "replace")


def shops(page):
    """The shops on a page: the objects in its app state that have a shop logo."""
    m = re.search(r'<script[^>]+id="__NUXT_DATA__"[^>]*>(.*?)</script>', page, re.S)
    if not m:
        return set()
    table = json.loads(m.group(1))

    def deref(i):
        if not isinstance(i, int) or i >= len(table):
            return None
        v = table[i]
        while isinstance(v, list) and len(v) == 2 and v[0] in WRAPPERS:
            v = table[v[1]]
        return v

    found = set()
    for entry in table:
        if not isinstance(entry, dict):
            continue
        values = {k: deref(v) for k, v in entry.items() if isinstance(v, int)}
        if any(isinstance(v, str) and "shoplogos" in v for v in values.values()) and isinstance(values.get("name"), str):
            found.add(values["name"].strip())
    return found


def main():
    if sys.argv[1:] == ["--domains"]:
        names = re.findall(r'^\s*"((?:[^"\\]|\\.)*)",$', OUT.read_text(), re.M)
        print(f"{write_domains(names)} sites for the product search")
        return
    known = set(re.findall(r'^\s*"((?:[^"\\]|\\.)*)",$', OUT.read_text(), re.M)) if OUT.exists() else set()
    found = set()
    for site in sys.argv[1:] or SITES:
        home = get(site, "/")
        for category in sorted(set(re.findall(r'href="(/[a-z0-9_]+/)"', home))):
            page = get(site, category)
            time.sleep(0.6)
            found |= shops(page)
            products = list(dict.fromkeys(re.findall(r'href="(/[a-z0-9_]+/[a-z0-9_.,-]+/[a-z0-9_.,-]*_[0-9]{6,}/)"', page)))[:3]
            for product in products:
                found |= shops(get(site, product))
                time.sleep(0.6)
    names = sorted({n for n in known | found if n}, key=str.lower)
    lines = "\n".join("        " + json.dumps(n, ensure_ascii=False) + "," for n in names)
    OUT.write_text(f'''package com.bartschram.franxit.data

/**
 * Every shop vergelijk.be and vergelijk.nl list (they screen their shops), as they name them. Made by tools/vergelijk_shops.py; run it again
 * before a release to add shops vergelijk.be has taken on since. Google Shopping's offers from these shops are trusted
 * on every phone, also one that never saw the shop on vergelijk.be itself.
 */
object VergelijkShops {{
    val NAMES = setOf(
{lines}
    )
}}
''')
    print(f"{len(names)} shops ({len(names) - len(known)} new), {write_domains(names)} sites for the product search")


if __name__ == "__main__":
    main()
