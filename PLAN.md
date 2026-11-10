# AntiScrol – plán aplikace proti scrollování na Instagramu (Android)

> Stav: **MVP (fáze 0 + 1, varianta A) je naprogramované** – viz [Stav implementace](#8-stav-implementace) na konci.
> Chování přímo na Instagramu zatím nikdo neověřil s přihlášeným účtem. Žádné poplatky, žádný backend.

## 1. Cíl

Android aplikace, která funguje jako „okleštěný Instagram“ (podobně jako Konvo na iOS):

| Povoleno ✅ | Zakázáno ⛔ |
|---|---|
| Prohlížení stories přátel | Hlavní feed (příspěvky) |
| Přidávání vlastních stories | Záložka Reels |
| Zprávy (DM) | Explore / doporučené |
| Reel nebo příspěvek, který mi někdo pošle v DM | Swipe na „další reel“ po otevření reelu z DM |
| (volitelně) profil přítele | Jakýkoli nekonečný scroll |

Princip: **obsah, který mi poslal člověk, je OK. Obsah, který mi vybírá algoritmus, je blokovaný.**

## 2. Jak se dá dostat k Instagramu – rozbor možností

| Přístup | Jde to? | Proč |
|---|---|---|
| **Oficiální API (Instagram Graph API)** | ❌ Ne | Basic Display API Meta v prosinci 2024 vypnula. Graph API je jen pro business/creator účty, neumí stories přátel a DM jen pro firemní účty. |
| **Neoficiální „private API“** (reverse-engineered, např. knihovny typu instagrapi) | ❌ Nedoporučuji | Porušuje podmínky Instagramu, hrozí **ban účtu** uživatele, aplikace by musela pracovat s heslem a každá změna API ji rozbije. |
| **A) WebView obal nad instagram.com** + skrytí částí UI | ✅ **Ano – doporučeno pro MVP** | Uživatel se přihlásí přímo na stránkách Instagramu, my jen skryjeme a zablokujeme nežádoucí části. Nikdy nevidíme heslo, nic neposíláme na vlastní server. Pravděpodobně to podobně dělá i Konvo (nedá se ověřit). |
| **B) Accessibility Service nad oficiální aplikací** | ✅ Ano, jako doplněk | Android umí sledovat, co je na obrazovce jiné aplikace. Když uživatel otevře Reels/feed v oficiální appce, vrátíme ho zpět nebo překryjeme obrazovku. Funguje jen na Androidu (iOS to nedovoluje, proto Konvo musí být samostatná appka). |

### Doporučení
**MVP = varianta A (WebView).** Odpovídá to přesně Konvo: samostatná appka s vlastním omezeným UI.
Varianta B se může přidat později jako volitelný „strážce“, který zablokuje Reels i v oficiální aplikaci (pro lidi, kteří ji nechtějí mazat).

## 3. Jak by fungovala varianta A (WebView)

```
┌────────────────────────── AntiScrol (Kotlin) ──────────────────────────┐
│  Spodní lišta:  [ Stories ]   [ Zprávy ]   [ + Přidat story ]          │
│                                                                         │
│  ┌──────────────── Android WebView (instagram.com) ──────────────────┐ │
│  │  1. Filtr URL  (shouldOverrideUrlLoading + hook na history API)    │ │
│  │  2. Injektované CSS – skryje feed, Reels, Explore, „návrhy“        │ │
│  │  3. Injektovaný JS  – MutationObserver hlídá DOM, blokuje scroll   │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│  Pravidla (selektory, URL) = JSON, stahovaný zdarma z GitHub Pages      │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.1 Filtr URL (hlavní pojistka)
Instagram web je SPA – mění URL přes `history.pushState`, takže nestačí jen `shouldOverrideUrlLoading`.
Do stránky se vloží JS, který obalí `pushState`/`replaceState` a hlásí každou změnu do Kotlinu (`addJavascriptInterface`). Kotlin rozhodne:

| URL | Akce |
|---|---|
| `/accounts/login`, `/accounts/*`, `/challenge/*` | povolit (přihlášení, 2FA) |
| `/` (domů) | povolit, ale CSS ukáže **jen lištu stories**, feed pod ní skrytý |
| `/stories/*` | povolit |
| `/direct/*` | povolit |
| `/reel/{id}`, `/p/{id}` | povolit **jen pokud předchozí stránka byla `/direct/*`** (= poslal mi to kamarád) |
| `/{uživatel}/` | volitelně povolit (profil má konečnou mřížku) |
| `/reels/`, `/explore/*` a cokoliv jiného | **zablokovat** → návrat na Stories |

### 3.2 Skrytí UI (CSS + JS)
- Na `/` skrýt všechno kromě stories lišty.
- Na `/reel/{id}` z DM skrýt doporučené reely pod videem a zablokovat swipe/scroll na další (`overscroll-behavior`, odchycení `touchmove`/`wheel`).
- Skrýt ikony Reels/Explore ve spodní navigaci Instagramu (vlastní navigaci dělá appka).
- `MutationObserver` aplikuje pravidla znovu, kdykoli Instagram překreslí stránku.

### 3.3 Přidávání stories
- Mobilní web Instagramu umožňuje nahrát story z galerie / fotoaparátu.
- WebView potřebuje: `WebChromeClient.onShowFileChooser` (výběr souboru), oprávnění `CAMERA`, případně `READ_MEDIA_IMAGES/VIDEO`.
- ⚠️ **Nutno ověřit ve fázi 0** – web nemá všechny nálepky/efekty jako oficiální appka.

### 3.4 Přihlášení a soukromí
- Přihlášení probíhá na stránce Instagramu, appka heslo nevidí ani neukládá.
- Session drží `CookieManager` WebView lokálně v telefonu.
- **Žádný vlastní server, žádná analytika** → nulové náklady a jednoduché zásady ochrany osobních údajů.
- User-Agent: běžný mobilní Chrome bez značky `wv` (Meta někdy omezuje přihlášení z WebView).

### 3.5 Odolnost proti změnám Instagramu
Instagram často mění HTML (náhodné CSS třídy). Proto:
- selektory primárně podle stabilních věcí (`href`, `aria-label`, `role`, struktura), ne podle tříd;
- pravidla v souboru `rules.json` hostovaném zdarma na **GitHub Pages** → oprava bez nové verze appky;
- „bezpečný režim“: pokud se pravidla nepodaří aplikovat, appka feed raději **zakryje celý**, než aby ho ukázala.

## 4. Technologie (vše zdarma)

| Oblast | Volba | Poznámka |
|---|---|---|
| Jazyk / UI | **Kotlin + Jetpack Compose** | Nativní WebView API, nejlepší kontrola. (Flutter / React Native jsou možné, ale práce s WebView injekcemi je nativně jednodušší.) |
| Prohlížeč | Android System WebView (`androidx.webkit`) | `addDocumentStartJavaScript` pro vložení JS ještě před načtením stránky |
| Nastavení | DataStore | např. povolit profily, časový limit |
| Vzdálená pravidla | JSON na GitHub Pages | zdarma |
| Build / CI | Android Studio + GitHub Actions | zdarma pro veřejné repo |
| Distribuce | APK na GitHub Releases, F-Droid; Google Play později | Play vyžaduje jednorázový poplatek 25 USD – zatím ne |
| Varianta B | `AccessibilityService` | jen čte názvy obrazovek / ID view oficiální appky |

## 5. Postup (fáze)

### Fáze 0 – Ověření proveditelnosti (1–2 týdny) ⭐ nejdůležitější
Minimální appka = jedno WebView s instagram.com. Ověřit a zapsat výsledky:
1. Funguje přihlášení (včetně 2FA) ve WebView?
2. Jde zobrazit **jen** stories lištu na `/`?
3. Fungují DM (čtení, psaní, posílání fotek, hlasovky)?
4. Jde otevřít reel z DM a zablokovat swipe na další?
5. Jde **nahrát story** z WebView (fotka i video)?
6. Mapa všech URL, které Instagram web používá.
7. Blokuje / varuje Meta po pár dnech používání?

**Rozhodnutí po fázi 0:** pokud body 1–4 fungují → pokračovat s variantou A. Pokud ne → přejít na variantu B.

### Fáze 1 – MVP (2–4 týdny)
- Vlastní spodní navigace: Stories / Zprávy / + Story
- Filtr URL + CSS/JS pravidla
- Reel z DM bez dalšího scrollu
- Obrazovka „Tohle je zablokované 🙂“ místo feedu

### Fáze 2 – Stabilita
- `rules.json` z GitHub Pages + bezpečný režim
- Automatický test (Playwright / Espresso), který denně zkontroluje, že pravidla stále sedí
- Volitelně: denní časový limit, statistika „ušetřeného času“ (lokálně)

### Fáze 3 – Rozšíření
- Varianta B: Accessibility „strážce“ pro oficiální Instagram
- Notifikace na nové zprávy (WebView push neumí – nejlepší je nechat notifikace oficiální appky, nebo nouzově periodická kontrola)
- Distribuce (F-Droid / Play)

## 6. Rizika

| Riziko | Dopad | Zmírnění |
|---|---|---|
| Instagram změní web | Feed na chvíli prosákne / něco přestane fungovat | Vzdálená pravidla, bezpečný režim, denní test |
| Meta omezí přihlášení z WebView | Appka nepůjde použít | Ověřit ve fázi 0; záložní varianta B |
| Podmínky Instagramu | Šedá zóna (jen skrýváme UI, nepoužíváme API) | Žádný scraping, žádná automatizace akcí, nic se neodesílá mimo telefon |
| Ochranná známka | Odmítnutí v obchodě | Nepoužívat „Instagram“ v názvu ani logo |
| Google Play a Accessibility API | Varianta B může být odmítnuta | Jasné vysvětlení a souhlas uživatele; distribuce mimo Play |
| Chybějící funkce webu (nálepky, efekty u stories) | Horší UX při tvorbě stories | Tlačítko „otevřít oficiální appku jen pro vytvoření story“ |
| Žádné push notifikace | Uživatel přehlédne zprávy | Nechat notifikace z oficiální appky |

## 7. Shrnutí

- **Jde to.** Nejrealističtější cesta je **WebView nad instagram.com s filtrem URL a skrytím feedu/Reels**. Žádné API klíče, žádný server, nulové náklady.
- Oficiální API to neumí a neoficiální API ohrožuje účty uživatelů → nepoužívat.
- Android má navíc výhodu oproti iOS: **Accessibility Service** dokáže hlídat i oficiální aplikaci Instagram.
- Prvním krokem by měl být **dvoutýdenní prototyp (fáze 0)**, který odpoví na 7 otázek výše. Teprve pak má smysl stavět celé MVP.

## 8. Stav implementace

Ke stažení: <https://github.com/vitduchecek-afk/AntiScrol/releases/latest/download/AntiScrol.apk>

### Co je hotové

| Část plánu | Stav | Kde |
|---|---|---|
| Spodní navigace Stories / Zprávy / + Story (+ Nastavení) | hotovo | `app/…/ui/Screens.kt` |
| WebView s instagram.com, přihlášení na stránce, session v cookies | hotovo | `app/…/web/WebController.kt` |
| Filtr URL (tabulka 3.1) jako čistá Kotlin třída | hotovo, 27 unit testů | `core/…/UrlPolicy.kt`, `NavGuard.kt` |
| Hlídání navigace uvnitř stránky (`pushState`/`replaceState`) | hotovo | `app/src/main/assets/antiscrol.js` |
| Skrytí feedu, zámek scrollu, bezpečný režim | hotovo | `antiscrol.js` + `docs/rules.json` |
| Reel/příspěvek jen ze zpráv, bez přechodu na další | hotovo | `UrlPolicy.kt`, `antiscrol.js` |
| Obrazovka „Tohle je zablokované 🙂“ | hotovo | `Screens.kt` |
| Výběr souboru + fotoaparát pro story | hotovo | `MainActivity.kt` |
| Pravidla v `rules.json`, přibalená + stahovaná z GitHub Pages | hotovo (Pages je potřeba zapnout) | `docs/rules.json`, `RulesRepository.kt` |
| Přepínač profilů v nastavení (DataStore) | hotovo | `SettingsStore.kt` |
| Sestavení a vydání APK přes GitHub Actions | hotovo | `.github/workflows/android.yml` |

### Jak to drží pohromadě

Blokované stránky hlídají tři na sobě nezávislé vrstvy:

1. `shouldOverrideUrlLoading` – klasické načtení stránky a přesměrování.
2. Vložený skript – navigace uvnitř stránky; aplikace je dotázána **dřív**, než se URL změní,
   takže blokovaná stránka se vůbec nedostane do historie (tlačítko zpět na ní nemůže skončit).
3. `doUpdateVisitedHistory` – cokoliv, co by přesto prošlo, aplikace zjistí a vrátí se zpět.

O každé URL rozhoduje jen Kotlin (`UrlPolicy`); skript rozhodnutí pouze provádí.
`rules.json` obsahuje jen data (vzory URL, CSS, selektory), nikdy spustitelný kód.

Feed na úvodní stránce je schovaný dvakrát: pravidlem CSS a neprůhledným překrytím od spodního
okraje lišty stories dolů. Když se lišta stories nenajde nebo CSS přestane fungovat, překrytí zůstává
(bezpečný režim).

### Odchylky od zadání

- **Oprávnění k fotkám (`READ_MEDIA_IMAGES/VIDEO`) aplikace nežádá.** Používá systémový výběr
  souborů, který předá jen vybrané soubory a žádné oprávnění nepotřebuje. Žádá jen o fotoaparát
  (a o mikrofon, když si ho stránka vyžádá kvůli hlasové zprávě).
- **Navigace má čtvrtou položku Nastavení**, aby byl přepínač profilů dohledatelný.
- **„+ Story“** přejde na lištu stories a pokusí se klepnout na vlastní kroužek „Váš příběh“.
  Prohlížeč ale výběr souboru bez skutečného dotyku na stránku otevřít nemusí, proto aplikace
  zároveň ukáže nápovědu, kam klepnout.
- **Se zapnutými profily** jde otevřít i příspěvek z mřížky profilu (ne jen ze zpráv).

### Co je ověřené a co ne

Ověřeno:
- unit testy `core` (všechny řádky tabulky 3.1, triky s URL, neplatná pravidla),
- `tools/inject-test.js` – vložený skript v Chromiu na napodobenině stránky (45 kontrol),
- sestavení a podpis APK v GitHub Actions.

**Neověřeno – fáze 0 z bodu 5 stále platí a musí ji projít člověk s účtem:**
1. přihlášení a 2FA ve WebView,
2. že se na skutečném webu najde lišta stories a feed zmizí (selektory v `rules.json` jsou odhad),
3. zprávy včetně posílání fotek a hlasovek,
4. otevření reelu ze zpráv a zákaz přechodu na další,
5. nahrání story,
6. jestli Instagram nepoužívá další adresy, které teď končí na obrazovce „zablokováno“,
7. jestli Meta přihlášení z WebView po čase neomezí.

### Zapnutí vzdálených pravidel

V nastavení repozitáře: *Settings → Pages → Deploy from a branch* → větev
`claude/pensive-mccarthy-f2i4hv`, složka `/docs`. Aplikace pak při startu zkusí
`https://vitduchecek-afk.github.io/AntiScrol/rules.json` a použije ho, pokud má vyšší `version`
než přibalená pravidla. Bez toho aplikace funguje s přibalenými.
