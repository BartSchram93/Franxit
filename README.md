# Franxit

The logo files (all four variants) are in `branding/`; the launcher icon, themed icon and notification
icon in `app/src/main/res` are made from them.

A minimal Android app that checks product prices once a day and keeps the history.

- Every day at a chosen time (default 08:00) it reads each tracked product page and stores the
  cheapest price and seller.
- The list shows the current price, the seller and the Buy or wait advice in a word (green "Buy now",
  otherwise grey with a coloured dot; the all-time low "ATL" when there's too little history).
- The detail screen has a step chart (1W / 1M / 3M / All; drag to inspect a day) and the
  lowest and typical (median) price of the past year, the same numbers the Buy or wait advice uses. The chart is one line: vergelijk.be's history in grey until your
  first check, then your own daily checks in blue. **Buy at …** opens the cheapest shop directly
  (a fresh vergelijk.be shop link is fetched on tap).
- Notifications only for your **price alert** (set per product): once, when the cheapest price
  drops to or below it.
- **⋮ → My discounts**: a personal discount per shop (e.g. 5% at bol.com through work). It also counts
  for the shop's partners (bol.com partners, Amazon Marketplace). It decides where it's cheapest for you: the Buy button, the order of Shops today (your price, with the shop's
  price and "−5%" under it) and a line like "€ 375,25 at Bol. (−5%)" on the Overview. Price history, Lowest and price
  alerts keep using the shops' own prices; when only your discount gets a product to or below your
  alert, the cart line turns green and you get one notification about it.
- Said no to notifications at first? With price alerts set, the Overview says "Notifications are off",
  with **Turn on** (also in ⋮). It asks again, or opens Franxit's notification settings.
- New users see four tips on the Overview (+ or share a link, long-press for an alert, swipe to remove, the filter icon) until **Got it**; ⋮ → Tips shows them again.
- Long-press a product on the Overview to set its price alert without opening it.
- The price alert dialog suggests amounts from the past year: a "good deal" (cheaper than on 4 of
  5 days), the year low, and 10% off.
- Products get a short name and a photo; tap the pencil to rename. The short name keeps the
  title's brand and model and drops the specs, e.g. "Lenovo Laptop Lenovo Chromebook Duet 10,95"
  Kompanio 838 8 GB RAM …" becomes "Lenovo Chromebook Duet 10,95"". The full title shows under it.
  The filter icon next to ⋮ sorts the list (order added, lowest or highest price, name) and can show
  only products with a price alert; swipe a product left to stop tracking it
  (Undo brings it back).
- "Out of stock" shows when even the cheapest offer can't be bought.
- **Google Shopping** (⋮ → Google Shopping, with your own SerpApi key) for products vergelijk.be
  doesn't list: search, then **Track via Google Shopping**. Matching results (no accessories),
  cheapest per shop, join Shops today. Each such product is checked once a day: a search, then
  Google's page for the best match, which lists more shops with their own links (two searches of
  the plan; SerpApi stops at the plan's limit). The key stays on the phone.
  On a vergelijk.be product, **Also search Google Shopping** adds the shops vergelijk.be lacks.
  Only trusted shops count, since Google also lists fake shops (Garantie2ans.com): shops vergelijk.be
  lists (learned with each check) and a fixed list of well-known shops selling in Belgium
  (`TrustedShops.kt`), by name or by the site a result links to ("Bax Music" is bax-shop.be). Any
  other shop is left out, however cheap. A trusted shop's .nl, .fr or .de site counts too (thomann.de/be):
  Google Shopping Belgium only lists shops that ship to Belgium.
- **Buy or wait** at the bottom of a product: Buy now, Consider buying, Wait or Insufficient data, with
  a 0–100 score, one sentence and 2–4 reasons, all from the price history (median of the past year,
  the reliable low, the last 30 and 90 days). When waiting, a price it really had, with a button to
  set an alert at it. Rules and weights are constants in `BuyAdvice.kt`. Not a forecast.
- **Shops today** on a product lists every shop's price from the latest check; tap a shop to open
  the product at that shop. The same price under two names of one shop (Amazon and Amazon
  Marketplace) shows once.
- If the daily check hasn't run for over 30 hours, the app and widget say so in orange.
- Add products with **+** (search vergelijk.be or paste a link), or share a link from your browser.
  A link from another shop (e.g. Coolblue) is looked up on vergelijk.be: when one result clearly is
  the same product and version ("WF-1000XM6 Zwart"), it's tracked right away; otherwise you pick
  from the best matches. **Track this link only** follows just that shop. Search results put
  accessories (cases, covers…) last.
- A home-screen widget, **Price list**, shows every tracked product with photo, price and the Buy or
  wait advice. Tap a product to open it. It uses the app's sorting, and with **Only with price
  alert** on it shows only those products too.

Everything runs on the phone: no server or account. History lives in a local SQLite database.

## How prices are read

Comparison sites like vergelijk.be embed schema.org `Product` / `AggregateOffer` data (JSON-LD)
in their pages. `Scraper` parses that and picks the cheapest in-stock offer, so it also works on
other shops that publish the same markup.

| Site | Works | Notes |
| --- | --- | --- |
| vergelijk.be | yes | All shops' offers; cheapest one wins. Reads prices after discounts. Also imports vergelijk.be's own ~1 year price history (fills the chart before your first check), which excludes Amazon |
| coolblue.be | yes | Coolblue's own price; seller shown as coolblue.be |
| bol.com | no | Blocks automated requests (bot protection). Track the product on vergelijk.be, which lists bol.com offers |
| zalando.be | no | Blocks automated requests (Akamai) |

## Build

Open in Android Studio, or:

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testWebDebugUnitTest    # parser tests
FRANXIT_LIVE_URL="https://www.vergelijk.be/..." ./gradlew testWebDebugUnitTest   # also hit the live page
```

## Daily site check

`.github/workflows/site-check.yml` runs every morning (04:30 UTC) and reads the pages in
`canary-urls.txt` with the app's own scraper: price, discounted offers and shop links,
vergelijk.be history and search. If anything breaks, it opens (or comments on) a GitHub issue
"Site check failed". Add a url to `canary-urls.txt` to cover another site.

## Releases and auto-update

`whats-new.txt` holds one short sentence about the next release, in plain words for the people who
use the app: it is the text of the public release, shown in the app's update banner and notification.
Change it before a release people should notice.

Every push to `main` runs `.github/workflows/release.yml`: it runs the tests, builds the Play bundle signed
with the release key and sends it to Google Play's closed test (track `alpha`, or the repository variable
`PLAY_TRACK`) with the notes in `play-notes/<language>.txt`. **No APKs are built or published any more**: the
app is only distributed through Google Play (no GitHub Releases, no download page updates).

The release key is Google Play's upload key. It lives outside the
repo, in the repo's Actions secrets `FRANXIT_KEYSTORE_BASE64` (the keystore, base64),
`FRANXIT_PLAY_KEYSTORE_PASSWORD` and `FRANXIT_PLAY_KEY_ALIAS`. Without the keystore the workflow still runs
the tests and builds, but publishes nothing. **Keep a copy of the keystore file and its password somewhere
safe.**

Google Play is reached without a key: the workflow signs in as the service account `franxit-upload` through
GitHub's own proof (Workload Identity Federation in the Google Cloud project), and that account may release to
testing tracks in Play Console.

## Notes

- The daily check uses WorkManager. Android may delay it a little to save battery; it runs as
  soon as the phone has a network connection.
- **⋮ → Daily check at …** changes the time.
