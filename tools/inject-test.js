/*
 * Runs app/src/main/assets/antiscrol.js in headless Chromium against a stand-in page.
 *
 *   node tools/inject-test.js        (needs the `playwright` package)
 *
 * The stand-in imitates only the structure the script relies on (a stories row, <article>
 * posts, history.pushState navigation). It proves the script's own logic, not that the
 * selectors in docs/rules.json match the real instagram.com.
 */
const fs = require('fs');
const path = require('path');
const { chromium, devices } = require('playwright');

const root = path.join(__dirname, '..');
const script = fs.readFileSync(path.join(root, 'app/src/main/assets/antiscrol.js'), 'utf8');
const rules = JSON.parse(fs.readFileSync(path.join(root, 'docs/rules.json'), 'utf8'));
const ORIGIN = 'https://www.instagram.com';

/** Stand-in for the Kotlin bridge: a compact re-implementation of UrlPolicy + NavGuard. */
function installBridge({ policy, ui, loggedIn }) {
  const compiled = policy.routes.map((r) => ({ category: r.category, patterns: r.patterns.map((p) => new RegExp(p)) }));
  const classify = (url) => {
    let u;
    try { u = new URL(url); } catch (e) { return null; }
    if (!policy.appHosts.includes(u.hostname)) return { external: true };
    for (const route of compiled) {
      for (const re of route.patterns) {
        const m = re.exec(u.pathname);
        if (m) return { category: route.category, id: m[1] || null };
      }
    }
    return null;
  };
  const state = { current: null, log: [], loggedIn };
  const decide = (url) => {
    const hit = classify(url);
    if (!hit) return 'block';
    if (hit.external) return 'external';
    switch (hit.category) {
      case 'BLOCKED': case 'PROFILE': return 'block';
      case 'HOME': return state.loggedIn ? 'allow:home' : 'allow:plain';
      case 'STORIES': return 'allow:stories';
      case 'DIRECT': return 'allow:direct';
      case 'CONTENT': case 'REELS_VIEWER': {
        const cur = state.current ? classify(state.current) : null;
        const ok = cur && (cur.category === 'DIRECT' ||
          ((cur.category === 'CONTENT' || cur.category === 'REELS_VIEWER') && cur.id === hit.id));
        if (!ok) return 'block';
        return (hit.category === 'CONTENT' ? 'allow:content:' : 'allow:reelsViewer:') + hit.id;
      }
      default: return 'allow:plain';
    }
  };
  window.__bridgeState = state;
  window.AntiScrolBridge = {
    getConfig: () => JSON.stringify(ui),
    check: (url) => decide(url),
    commit: (url) => {
      const v = decide(url);
      state.log.push('commit ' + new URL(url).pathname + ' -> ' + v);
      if (v.startsWith('allow')) state.current = url;
      return v;
    },
    blocked: (url) => { state.log.push('blocked ' + new URL(url).pathname); },
    report: (event, detail) => { state.log.push('report ' + event + ' ' + detail); },
  };
}

function pageHtml({ tray = 'menu', feedTag = 'article', dialog = false } = {}) {
  const ring = '<span style="display:inline-block;margin:8px"><canvas width="66" height="66" style="width:66px;height:66px"></canvas></span>';
  const trayHtml = {
    menu: `<div role="menu" style="height:100px;white-space:nowrap;overflow-x:auto"><div role="button" aria-label="Your story" id="yourstory">${ring}</div>${ring.repeat(6)}</div>`,
    canvasOnly: `<div style="height:100px;white-space:nowrap">${ring.repeat(5)}</div>`,
    none: '',
  }[tray];
  const post = (i) => `<${feedTag} class="post"><img alt="" style="display:block;width:100%;height:420px;background:#c33" src="data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw=="><a href="/p/POST${i}/">post ${i}</a></${feedTag}>`;
  return `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{margin:0;background:#fff}</style></head><body>
<header style="height:44px">logo</header>
<main>${trayHtml}<div id="feed">${[1, 2, 3, 4, 5, 6].map(post).join('')}</div></main>
<nav style="position:fixed;bottom:0;left:0;right:0;height:48px;background:#eee">
  <a href="/">home</a> <a id="nav-explore" href="/explore/">explore</a> <a id="nav-reels" href="/reels/">reels</a> <a href="/direct/inbox/">dm</a>
</nav>
${dialog ? '<div role="dialog" style="position:fixed;left:20px;right:20px;top:200px;height:200px;background:#ff0;z-index:10">dialog</div>' : ''}
</body></html>`;
}

let failures = 0;
function check(name, condition, detail) {
  if (condition) {
    console.log('  ok   ' + name);
  } else {
    failures++;
    console.log('  FAIL ' + name + (detail !== undefined ? '  -> ' + JSON.stringify(detail) : ''));
  }
}

async function open(browser, { url = ORIGIN + '/', html = pageHtml(), loggedIn = true, csp = null } = {}) {
  const context = await browser.newContext({ ...devices['Pixel 7'] });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  await page.route('**/*', (route) => route.fulfill({
    status: 200,
    contentType: 'text/html',
    headers: csp ? { 'Content-Security-Policy': csp } : {},
    body: html,
  }));
  await page.addInitScript(installBridge, { policy: rules.policy, ui: rules.ui, loggedIn });
  await page.addInitScript(script);
  await page.goto(url);
  await page.waitForTimeout(700);
  return { page, context, errors };
}

const coverInfo = (page) => page.evaluate(() => {
  const c = document.getElementById('antiscrol-cover');
  if (!c) return null;
  const cs = getComputedStyle(c);
  return { display: cs.display, top: Math.round(c.getBoundingClientRect().top), position: cs.position, text: c.textContent };
});
const state = (page) => page.evaluate(() => window.__antiscrol.state());
const log = (page) => page.evaluate(() => window.__bridgeState.log);

(async () => {
  const browser = await chromium.launch();

  console.log('home, logged in: only the stories row stays');
  {
    const { page, context, errors } = await open(browser);
    const s = await state(page);
    check('mode is home', s.mode === 'home', s);
    const cover = await coverInfo(page);
    const trayBottom = await page.evaluate(() => Math.round(document.querySelector('[role=menu]').getBoundingClientRect().bottom));
    check('cover is shown and fixed', cover && cover.display === 'flex' && cover.position === 'fixed', cover);
    check('cover starts right below the stories row', cover && cover.top === trayBottom, { cover, trayBottom });
    check('cover carries the normal text', cover && cover.text === rules.ui.texts.cover, cover);
    check('posts are hidden by CSS', await page.evaluate(() => [...document.querySelectorAll('article')].every((a) => getComputedStyle(a).visibility === 'hidden')));
    check('page scroll is locked', await page.evaluate(() => getComputedStyle(document.documentElement).overflowY === 'hidden'));
    check('explore and reels links are hidden', await page.evaluate(() => ['nav-explore', 'nav-reels'].every((id) => getComputedStyle(document.getElementById(id)).display === 'none')));
    check('what is under the feed area is the cover', await page.evaluate(() => document.elementFromPoint(200, 400).closest('#antiscrol-cover') !== null));
    check('the stories row is not covered', await page.evaluate(() => document.elementFromPoint(40, 90).closest('[role=menu]') !== null));

    const moved = await page.evaluate(() => {
      const fire = (el, y) => {
        const t = new Touch({ identifier: 1, target: el, clientX: 100, clientY: y });
        const e = new TouchEvent('touchmove', { touches: [t], cancelable: true, bubbles: true });
        el.dispatchEvent(e);
        return e.defaultPrevented;
      };
      return { feed: fire(document.getElementById('feed'), 400), tray: fire(document.querySelector('[role=menu]'), 90) };
    });
    check('swiping the feed area is cancelled', moved.feed === true, moved);
    check('swiping the stories row still works', moved.tray === false, moved);

    console.log('in-page navigation');
    await page.evaluate(() => history.pushState({}, '', '/explore/'));
    check('pushState to /explore/ does not change the URL', page.url() === ORIGIN + '/', page.url());
    await page.evaluate(() => history.pushState({}, '', '/reels/'));
    await page.evaluate(() => history.pushState({}, '', '/reel/AAA/'));
    check('reel from the home page is refused', page.url() === ORIGIN + '/', page.url());
    check('history did not grow', await page.evaluate(() => history.length) === 2, await page.evaluate(() => history.length));
    const l1 = await log(page);
    check('the app was told about each blocked attempt', ['/explore/', '/reels/', '/reel/AAA/'].every((p) => l1.includes('blocked ' + p)), l1);

    await page.evaluate(() => history.pushState({}, '', '/direct/t/123/'));
    await page.waitForTimeout(100);
    check('messages are allowed', page.url() === ORIGIN + '/direct/t/123/' && (await state(page)).mode === 'direct', await state(page));
    check('cover is gone in messages', (await coverInfo(page)).display === 'none', await coverInfo(page));
    check('posts are visible again outside home (CSS is per mode)', await page.evaluate(() => getComputedStyle(document.querySelector('article')).visibility === 'visible'));
    check('scroll is free in messages', await page.evaluate(() => {
      const e = new TouchEvent('touchmove', { touches: [new Touch({ identifier: 1, target: document.body, clientX: 1, clientY: 400 })], cancelable: true, bubbles: true });
      document.body.dispatchEvent(e);
      return !e.defaultPrevented;
    }));

    console.log('reel opened from messages');
    await page.evaluate(() => {
      document.getElementById('feed').innerHTML =
        '<a id="same" href="/reel/AAA/liked_by/">likes</a><a id="other" href="/reel/BBB/">more</a><a id="other2" href="/p/CCC/">more</a><a id="profile" href="/someone/">someone</a>';
      history.pushState({}, '', '/reel/AAA/');
    });
    await page.waitForTimeout(150);
    const s2 = await state(page);
    check('reel from messages is allowed', page.url() === ORIGIN + '/reel/AAA/' && s2.mode === 'content' && s2.contentId === 'AAA', s2);
    const vis = await page.evaluate(() => Object.fromEntries(['same', 'other', 'other2', 'profile'].map((id) => [id, getComputedStyle(document.getElementById(id)).visibility])));
    check('links to other reels/posts are hidden, the rest stays', vis.same === 'visible' && vis.other === 'hidden' && vis.other2 === 'hidden' && vis.profile === 'visible', vis);
    await page.evaluate(() => history.pushState({}, '', '/reel/BBB/'));
    await page.evaluate(() => history.replaceState({}, '', '/reels/BBB/'));
    check('moving on to another reel is refused', page.url() === ORIGIN + '/reel/AAA/', page.url());

    await page.evaluate(() => history.replaceState({}, '', '/reels/AAA/'));
    await page.waitForTimeout(100);
    const s3 = await state(page);
    check('same reel in the swipe player is allowed', s3.mode === 'reelsViewer' && s3.contentId === 'AAA', s3);
    const swipe = await page.evaluate(() => {
      const e = new TouchEvent('touchmove', { touches: [new Touch({ identifier: 1, target: document.body, clientX: 1, clientY: 400 })], cancelable: true, bubbles: true });
      document.body.dispatchEvent(e);
      const w = new WheelEvent('wheel', { deltaY: 100, cancelable: true, bubbles: true });
      document.body.dispatchEvent(w);
      const k = new KeyboardEvent('keydown', { key: 'ArrowDown', cancelable: true, bubbles: true });
      document.body.dispatchEvent(k);
      return { touch: e.defaultPrevented, wheel: w.defaultPrevented, key: k.defaultPrevented };
    });
    check('swipe, wheel and arrow keys are cancelled in the player', swipe.touch && swipe.wheel && swipe.key, swipe);

    console.log('back button');
    await page.goBack();
    await page.waitForTimeout(600);
    check('back returns to the thread', page.url() === ORIGIN + '/direct/t/123/' && (await state(page)).mode === 'direct', page.url());
    await page.goBack();
    await page.waitForTimeout(600);
    check('back again returns home with the cover', page.url() === ORIGIN + '/' && (await coverInfo(page)).display === 'flex', page.url());

    check('"+ Story" finds the "Your story" control', await page.evaluate(() => {
      let clicked = false;
      document.getElementById('yourstory').addEventListener('click', () => { clicked = true; });
      return window.__antiscrol.addStory() && clicked;
    }));
    check('no script errors', errors.length === 0, errors);
    await context.close();
  }

  console.log('stories row found only by its rings (markup changed)');
  {
    const { page, context } = await open(browser, { html: pageHtml({ tray: 'canvasOnly' }) });
    const cover = await coverInfo(page);
    const ringBottom = await page.evaluate(() => Math.round(Math.max(...[...document.querySelectorAll('canvas')].map((c) => c.getBoundingClientRect().bottom))));
    check('cover sits below the rings plus padding', cover && cover.display === 'flex' && cover.top === ringBottom + rules.ui.trayPadPx, { cover, ringBottom });
    await context.close();
  }

  console.log('safe mode: no stories row at all');
  {
    const { page, context } = await open(browser, { html: pageHtml({ tray: 'none' }) });
    const cover = await coverInfo(page);
    check('cover starts below the header', cover && cover.display === 'flex' && cover.top === rules.ui.safeTopPx, cover);
    check('cover says it is in safe mode', cover && cover.text === rules.ui.texts.coverSafe, cover);
    check('no post can be reached', await page.evaluate(() => document.elementFromPoint(200, 300).closest('#antiscrol-cover') !== null));
    await context.close();
  }

  console.log('safe mode: feed markup changed so the CSS no longer hides it');
  {
    const { page, context } = await open(browser, { html: pageHtml({ feedTag: 'section' }) });
    check('posts are not hidden by CSS (as set up)', await page.evaluate(() => getComputedStyle(document.querySelector('.post')).visibility === 'visible'));
    check('cover still hides them', await page.evaluate(() => document.elementFromPoint(200, 400).closest('#antiscrol-cover') !== null));
    await context.close();
  }

  console.log('dialogs');
  {
    const a = await open(browser, { html: pageHtml({ dialog: true }) });
    check('cover is lifted for a dialog while CSS hides the feed', (await coverInfo(a.page)).display === 'none', await coverInfo(a.page));
    check('the dialog can be tapped', await a.page.evaluate(() => document.elementFromPoint(200, 300).closest('[role=dialog]') !== null));
    await a.context.close();
    const b = await open(browser, { html: pageHtml({ dialog: true, feedTag: 'section' }) });
    check('cover stays when the feed would show behind the dialog', (await coverInfo(b.page)).display === 'flex', await coverInfo(b.page));
    await b.context.close();
  }

  console.log('logged out');
  {
    const { page, context } = await open(browser, { loggedIn: false });
    const s = await state(page);
    const cover = await coverInfo(page);
    check('login page is left alone', s.mode === 'plain' && (!cover || cover.display === 'none'), { s, cover });
    check('login page can scroll', await page.evaluate(() => getComputedStyle(document.documentElement).overflowY !== 'hidden'));
    await page.evaluate(() => { window.__bridgeState.loggedIn = true; });
    await page.waitForTimeout(1500);
    check('logging in without a URL change turns the cover on', (await state(page)).mode === 'home' && (await coverInfo(page)).display === 'flex', await state(page));
    await context.close();
  }

  console.log('blocked page loaded directly');
  {
    const { page, context } = await open(browser, { url: ORIGIN + '/explore/' });
    check('page is hidden until the app navigates away', await page.evaluate(() => getComputedStyle(document.documentElement).visibility === 'hidden'));
    check('the app was told', (await log(page)).includes('commit /explore/ -> block'), await log(page));
    await context.close();
  }

  console.log('strict Content-Security-Policy');
  {
    const { page, context, errors } = await open(browser, { csp: "default-src 'self'; style-src 'self'; script-src 'self'" });
    const cover = await coverInfo(page);
    check('styles still apply', cover && cover.display === 'flex' && cover.position === 'fixed', cover);
    check('posts are hidden', await page.evaluate(() => getComputedStyle(document.querySelector('article')).visibility === 'hidden'));
    check('no script errors', errors.length === 0, errors);
    await context.close();
  }

  await browser.close();
  console.log(failures === 0 ? '\nAll checks passed.' : `\n${failures} check(s) FAILED.`);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(2); });
