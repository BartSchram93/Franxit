#!/usr/bin/env python3
"""
Refreshes core/.../data/VergelijkShops.kt: every shop vergelijk.be lists, so Google Shopping's offers from those shops
are trusted on every phone (not only on phones that happened to see the shop on vergelijk.be).

It reads vergelijk.be's categories (from its home page) and the first three products of each, slowly (0.6 s between
pages, some 750 pages in all), and adds the shops it finds to the ones already in the file: a shop seen once stays.

    python3 tools/vergelijk_shops.py
"""
import json, re, subprocess, time, pathlib

UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
OUT = pathlib.Path(__file__).resolve().parent.parent / "core/src/main/java/com/bartschram/franxit/data/VergelijkShops.kt"
WRAPPERS = {"ShallowReactive", "Reactive", "ShallowRef", "Ref"}


def get(path):
    r = subprocess.run(["curl", "-sS", "--compressed", "-L", "--max-redirs", "2", "-A", UA, "https://www.vergelijk.be" + path],
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
    known = set(re.findall(r'^\s*"((?:[^"\\]|\\.)*)",$', OUT.read_text(), re.M)) if OUT.exists() else set()
    found = set()
    home = get("/")
    for category in sorted(set(re.findall(r'href="(/[a-z0-9_]+/)"', home))):
        page = get(category)
        time.sleep(0.6)
        found |= shops(page)
        products = list(dict.fromkeys(re.findall(r'href="(/[a-z0-9_]+/[a-z0-9_.,-]+/[a-z0-9_.,-]*_[0-9]{6,}/)"', page)))[:3]
        for product in products:
            found |= shops(get(product))
            time.sleep(0.6)
    names = sorted({n for n in known | found if n}, key=str.lower)
    lines = "\n".join("        " + json.dumps(n, ensure_ascii=False) + "," for n in names)
    OUT.write_text(f'''package com.bartschram.franxit.data

/**
 * Every shop vergelijk.be lists (it screens its shops), as it names them. Made by tools/vergelijk_shops.py; run it again
 * before a release to add shops vergelijk.be has taken on since. Google Shopping's offers from these shops are trusted
 * on every phone, also one that never saw the shop on vergelijk.be itself.
 */
object VergelijkShops {{
    val NAMES = setOf(
{lines}
    )
}}
''')
    print(f"{len(names)} shops ({len(names) - len(known)} new)")


if __name__ == "__main__":
    main()
