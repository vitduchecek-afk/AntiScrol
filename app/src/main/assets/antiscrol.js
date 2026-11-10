/*
 * AntiScrol page script. Injected at document start into every page the WebView loads.
 *
 * It decides nothing itself. Every URL change is reported to the app (AntiScrolBridge),
 * the app answers with a verdict, and this script only applies it: it refuses blocked
 * in-page navigations, hides the feed, and stops the scroll to the next reel.
 * Selectors and CSS come from rules.json (bridge.getConfig()); the values below are
 * fallbacks for when a key is missing.
 */
(function () {
  'use strict';

  if (window.top !== window.self) return;
  if (window.__antiscrol) return;
  var bridge = window.AntiScrolBridge;
  if (!bridge || typeof bridge.check !== 'function') return;

  var BASE_CSS =
    '#antiscrol-cover{position:fixed;left:0;right:0;bottom:0;top:0;z-index:2147483000;' +
    'background:#fff;color:#737373;display:none;align-items:flex-start;justify-content:center;' +
    'box-sizing:border-box;padding:48px 32px 0;text-align:center;' +
    'font:15px/1.4 system-ui,-apple-system,Roboto,sans-serif;touch-action:none}' +
    '@media (prefers-color-scheme:dark){#antiscrol-cover{background:#000;color:#a8a8a8}}' +
    'html[data-antiscrol-mode="content"] .antiscrol-hidden,' +
    'html[data-antiscrol-mode="reelsViewer"] .antiscrol-hidden' +
    '{visibility:hidden!important;pointer-events:none!important}';

  var DEFAULTS = {
    css: {
      global: 'a[href="/explore/"],a[href="/reels/"]{display:none!important}',
      home: 'article{visibility:hidden!important}html,body{overflow:hidden!important;overscroll-behavior:none!important}',
      reelsViewer: 'html,body{overflow:hidden!important;overscroll-behavior:none!important}',
      blocked: 'html{visibility:hidden!important}'
    },
    selectors: {
      storyTray: ['[role="menu"]'],
      dialog: ['[role="dialog"]'],
      leak: ['article', 'img', 'video'],
      addStory: []
    },
    contentLinkPattern: '/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)',
    addStoryTextPattern: 'your story|add to (?:your )?story',
    lockScrollModes: ['home', 'reelsViewer'],
    safeTopPx: 56,
    trayPadPx: 30,
    leakMinWidthRatio: 0.45,
    texts: { cover: 'Feed je skrytý.', coverSafe: 'Feed je skrytý (bezpečný režim).' }
  };

  var cfg = DEFAULTS;
  var mode = 'plain';      // plain | home | stories | direct | create | profile | content | reelsViewer | blocked
  var contentId = null;    // id of the one post/reel the user may see in content modes
  var lastHref = null;
  var styleDirty = true;
  var startedAt = Date.now();

  function isObject(v) { return v !== null && typeof v === 'object' && !Array.isArray(v); }

  function merge(base, extra) {
    var out = {}, k;
    for (k in base) out[k] = base[k];
    if (!isObject(extra)) return out;
    for (k in extra) {
      if (!Object.prototype.hasOwnProperty.call(extra, k)) continue;
      out[k] = isObject(base[k]) && isObject(extra[k]) ? merge(base[k], extra[k]) : extra[k];
    }
    return out;
  }

  function loadConfig() {
    try {
      cfg = merge(DEFAULTS, JSON.parse(String(bridge.getConfig() || '{}')));
    } catch (e) {
      cfg = DEFAULTS;
    }
    styleDirty = true;
  }

  function list(v) { return Array.isArray(v) ? v : []; }

  function report(event, detail) {
    try { bridge.report(String(event), String(detail || '')); } catch (e) { /* ignore */ }
  }

  // ---------------------------------------------------------------- verdicts

  function applyVerdict(verdict) {
    var parts = String(verdict || '').split(':');
    var newMode = 'plain', newId = null;
    if (parts[0] === 'allow') {
      newMode = parts[1] || 'plain';
      newId = parts[2] || null;
    } else if (parts[0] === 'block' || parts[0] === 'external') {
      newMode = 'blocked';
    }
    if (newMode !== mode || newId !== contentId) {
      mode = newMode;
      contentId = newId;
      styleDirty = true;
    }
  }

  /** The location changed (or may have): tell the app and apply its answer. */
  function commitLocation(force) {
    var href = location.href;
    if (!force && href === lastHref) return;
    lastHref = href;
    try { applyVerdict(bridge.commit(href)); } catch (e) { /* ignore */ }
    enforce();
  }

  /** Re-asks without side effects; catches e.g. logging in without a URL change. */
  function recheck() {
    try { applyVerdict(bridge.check(location.href)); } catch (e) { /* ignore */ }
  }

  // ---------------------------------------------------------------- navigation

  /** Asks the app before an in-page navigation. Returns false when it must not happen. */
  function navigationAllowed(url) {
    if (url === undefined || url === null) return true;
    var abs;
    try { abs = new URL(String(url), location.href).href; } catch (e) { return true; }
    if (abs === location.href) return true;
    var verdict;
    try { verdict = String(bridge.check(abs)); } catch (e) { return true; }
    if (verdict.indexOf('allow') === 0) return true;
    try { bridge.blocked(abs); } catch (e) { /* ignore */ }
    return false;
  }

  function wrapHistory(name) {
    var proto = window.History && window.History.prototype;
    if (!proto) return;
    var original = proto[name];
    if (typeof original !== 'function' || original.__antiscrol) return;
    var wrapped = function (state, title, url) {
      // A blocked page never enters the history, so the back button cannot land on it.
      if (!navigationAllowed(url)) return undefined;
      var result = original.apply(this, arguments);
      commitLocation(false);
      return result;
    };
    wrapped.__antiscrol = true;
    try { proto[name] = wrapped; } catch (e) { /* ignore */ }
  }

  wrapHistory('pushState');
  wrapHistory('replaceState');
  window.addEventListener('popstate', function () { commitLocation(false); }, true);
  window.addEventListener('hashchange', function () { commitLocation(false); }, true);
  window.addEventListener('pageshow', function () { commitLocation(true); }, true);

  // ---------------------------------------------------------------- CSS

  var sheet = null;
  var styleEl = null;

  function applyCss(css) {
    // A constructed stylesheet is not subject to the page's Content-Security-Policy.
    try {
      if (!sheet) sheet = new CSSStyleSheet();
      var adopted = Array.from(document.adoptedStyleSheets || []);
      if (adopted.indexOf(sheet) < 0) document.adoptedStyleSheets = adopted.concat([sheet]);
      sheet.replaceSync(css);
      return;
    } catch (e) {
      sheet = null;
    }
    var root = document.documentElement;
    if (!root) return;
    if (!styleEl || !styleEl.isConnected) {
      styleEl = document.createElement('style');
      styleEl.id = 'antiscrol-style';
      (document.head || root).appendChild(styleEl);
    }
    if (styleEl.textContent !== css) styleEl.textContent = css;
  }

  function ensureStyle() {
    var root = document.documentElement;
    if (!root) return;
    if (root.getAttribute('data-antiscrol-mode') !== mode) root.setAttribute('data-antiscrol-mode', mode);
    var detached = sheet
      ? Array.from(document.adoptedStyleSheets || []).indexOf(sheet) < 0
      : !styleEl || !styleEl.isConnected;
    if (!styleDirty && !detached) return;
    var css = cfg.css || {};
    applyCss(BASE_CSS + '\n' + (css.global || '') + '\n' + (css[mode] || ''));
    styleDirty = false;
  }

  // ---------------------------------------------------------------- helpers

  function isVisible(el) {
    var r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) return false;
    var cs = getComputedStyle(el);
    return cs.visibility !== 'hidden' && cs.display !== 'none' && cs.opacity !== '0';
  }

  function queryAll(selector) {
    try { return document.querySelectorAll(selector); } catch (e) { return []; }
  }

  function closestAny(el, selectors) {
    if (!el || typeof el.closest !== 'function') return null;
    for (var i = 0; i < selectors.length; i++) {
      try {
        var hit = el.closest(selectors[i]);
        if (hit) return hit;
      } catch (e) { /* invalid selector */ }
    }
    return null;
  }

  function dialogOpen() {
    var selectors = list(cfg.selectors.dialog);
    for (var i = 0; i < selectors.length; i++) {
      var els = queryAll(selectors[i]);
      for (var j = 0; j < els.length; j++) if (isVisible(els[j])) return true;
    }
    return false;
  }

  // ---------------------------------------------------------------- home: stories only

  var cover = null;
  var coverText = null;
  var coverState = '';
  var trayRect = null;
  var safeReported = false;

  /** Finds the stories row. Returns {top, bottom} in viewport pixels, or null. */
  function findTray() {
    var vw = window.innerWidth, i, j, r;
    var selectors = list(cfg.selectors.storyTray);
    for (i = 0; i < selectors.length; i++) {
      var els = queryAll(selectors[i]);
      for (j = 0; j < els.length; j++) {
        r = els[j].getBoundingClientRect();
        if (r.height >= 40 && r.height <= 280 && r.width >= vw * 0.5 && r.top < 420 && r.bottom > 0) {
          return { top: r.top, bottom: r.bottom };
        }
      }
    }
    // Fallback that does not depend on markup: story rings are small square canvases near the top.
    var canvases = document.querySelectorAll('canvas');
    var top = Infinity, bottom = -Infinity, found = 0;
    for (i = 0; i < canvases.length; i++) {
      r = canvases[i].getBoundingClientRect();
      if (r.width >= 36 && r.width <= 130 && Math.abs(r.width - r.height) <= 8 && r.top < 420 && r.bottom > 0) {
        found++;
        top = Math.min(top, r.top);
        bottom = Math.max(bottom, r.bottom);
      }
    }
    if (found > 0) return { top: top - 8, bottom: bottom + (Number(cfg.trayPadPx) || 0) };
    return null;
  }

  /** Is something feed-like visible below `top` although the CSS should have hidden it? */
  function leakBelow(top) {
    var selectors = list(cfg.selectors.leak);
    if (!selectors.length) return false;
    var els = queryAll(selectors.join(','));
    var vw = window.innerWidth, vh = window.innerHeight;
    var minWidth = vw * (Number(cfg.leakMinWidthRatio) || 0.45);
    var dialogs = list(cfg.selectors.dialog);
    for (var i = 0; i < els.length && i < 400; i++) {
      var el = els[i];
      var r = el.getBoundingClientRect();
      if (r.width < minWidth || r.height < 80) continue;
      if (r.bottom <= top + 4 || r.top >= vh) continue;
      if (closestAny(el, dialogs)) continue;
      if (!isVisible(el)) continue;
      return true;
    }
    return false;
  }

  function ensureCover() {
    if (cover && cover.isConnected) return cover;
    cover = document.createElement('div');
    cover.id = 'antiscrol-cover';
    coverText = document.createElement('div');
    cover.appendChild(coverText);
    document.documentElement.appendChild(cover);
    coverState = '';
    return cover;
  }

  function updateCover() {
    if (!document.documentElement || !document.body) return;
    if (mode !== 'home') {
      trayRect = null;
      if (cover && coverState !== 'off') {
        cover.style.setProperty('display', 'none');
        coverState = 'off';
      }
      return;
    }
    var c = ensureCover();
    trayRect = findTray();
    var vh = window.innerHeight;
    // Safe mode: without a stories row to anchor to, cover everything below the header.
    var top = trayRect ? Math.max(0, Math.min(Math.round(trayRect.bottom), vh)) : (Number(cfg.safeTopPx) || 0);
    // A dialog (cookie consent, "save login info") must stay usable. The cover is lifted
    // for it only while the CSS really hides the feed behind the dialog.
    var show = !dialogOpen() || leakBelow(top);
    var text = trayRect ? cfg.texts.cover : cfg.texts.coverSafe;
    var bg = '';
    try {
      var bodyBg = getComputedStyle(document.body).backgroundColor;
      if (bodyBg && bodyBg !== 'transparent' && bodyBg !== 'rgba(0, 0, 0, 0)') bg = bodyBg;
    } catch (e) { /* ignore */ }

    var state = [show ? 'on' : 'hidden', top, text, bg].join('|');
    if (state !== coverState) {
      c.style.setProperty('top', top + 'px');
      c.style.setProperty('display', show ? 'flex' : 'none');
      if (bg) c.style.setProperty('background', bg);
      if (coverText.textContent !== text) coverText.textContent = text || '';
      coverState = state;
    }

    // Autoplaying feed videos must not keep playing behind the cover.
    var videos = document.querySelectorAll('article video');
    for (var i = 0; i < videos.length; i++) {
      try { if (!videos[i].paused) videos[i].pause(); } catch (e) { /* ignore */ }
    }

    if (!trayRect && !safeReported && document.readyState === 'complete' && Date.now() - startedAt > 6000) {
      safeReported = true;
      report('safeMode', 'stories row not found');
    }
  }

  // ---------------------------------------------------------------- reel / post from messages

  /** On a post or reel page, hides links to every other post or reel ("more like this"). */
  function hideOtherContent() {
    if (mode !== 'content' && mode !== 'reelsViewer') return;
    var re;
    try { re = new RegExp(cfg.contentLinkPattern); } catch (e) { return; }
    var links = document.querySelectorAll('a[href]');
    for (var i = 0; i < links.length; i++) {
      var a = links[i];
      var m = re.exec(a.getAttribute('href') || '');
      var hide = !!(m && m[1] && m[1] !== contentId);
      if (a.classList.contains('antiscrol-hidden') !== hide) a.classList.toggle('antiscrol-hidden', hide);
    }
  }

  function silenceBlockedPage() {
    if (mode !== 'blocked') return;
    var media = document.querySelectorAll('video,audio');
    for (var i = 0; i < media.length; i++) {
      try { media[i].pause(); } catch (e) { /* ignore */ }
    }
  }

  // ---------------------------------------------------------------- scroll lock

  function scrollLocked() {
    return list(cfg.lockScrollModes).indexOf(mode) >= 0;
  }

  /** Scrolling stays possible inside dialogs (comments) and sideways in the stories row. */
  function scrollFree(target, clientY) {
    if (closestAny(target, list(cfg.selectors.dialog))) return true;
    if (mode === 'home' && trayRect && clientY >= trayRect.top && clientY <= trayRect.bottom) return true;
    return false;
  }

  document.addEventListener('touchmove', function (e) {
    if (!scrollLocked()) return;
    var t = e.touches && e.touches[0];
    if (scrollFree(e.target, t ? t.clientY : -1)) return;
    if (e.cancelable) e.preventDefault();
  }, { capture: true, passive: false });

  document.addEventListener('wheel', function (e) {
    if (!scrollLocked()) return;
    if (scrollFree(e.target, e.clientY)) return;
    if (e.cancelable) e.preventDefault();
  }, { capture: true, passive: false });

  var SCROLL_KEYS = ['ArrowDown', 'ArrowUp', 'PageDown', 'PageUp', 'Home', 'End', ' '];
  document.addEventListener('keydown', function (e) {
    if (!scrollLocked() || SCROLL_KEYS.indexOf(e.key) < 0) return;
    var t = e.target;
    var tag = t && t.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA' || (t && t.isContentEditable)) return;
    if (closestAny(t, list(cfg.selectors.dialog))) return;
    e.preventDefault();
  }, true);

  // ---------------------------------------------------------------- add story

  /** Best effort: taps the page's own "your story" control. Returns true when one was found. */
  function addStory() {
    var el = null, i;
    var selectors = list(cfg.selectors.addStory);
    for (i = 0; i < selectors.length && !el; i++) {
      var found = queryAll(selectors[i]);
      for (var j = 0; j < found.length && !el; j++) if (isVisible(found[j])) el = found[j];
    }
    if (!el) {
      var re = null;
      try { re = new RegExp(cfg.addStoryTextPattern, 'i'); } catch (e) { re = null; }
      var candidates = re ? document.querySelectorAll('[role="button"],button,[aria-label]') : [];
      for (i = 0; i < candidates.length && !el; i++) {
        var c = candidates[i];
        if (cover && cover.contains(c)) continue;
        var label = (c.getAttribute('aria-label') || '') + ' ' + (c.textContent || '').slice(0, 80);
        if (!re.test(label) || !isVisible(c)) continue;
        if (c.getBoundingClientRect().top < 420) el = c;
      }
    }
    if (el) {
      var clickable = closestAny(el, ['[role="button"]', 'button', 'a']) || el;
      try { clickable.click(); } catch (e) { /* ignore */ }
      report('addStory', 'clicked');
      return true;
    }
    report('addStory', 'notfound');
    return false;
  }

  // ---------------------------------------------------------------- main loop

  function enforce() {
    try {
      ensureStyle();
      updateCover();
      hideOtherContent();
      silenceBlockedPage();
    } catch (e) { /* never break the page */ }
  }

  var scheduled = false;
  function schedule() {
    if (scheduled) return;
    scheduled = true;
    var run = function () { scheduled = false; enforce(); };
    if (window.requestAnimationFrame) window.requestAnimationFrame(run); else setTimeout(run, 16);
  }

  var observer = null;
  function startObserver() {
    if (observer || !document.documentElement || typeof MutationObserver !== 'function') return;
    observer = new MutationObserver(function (mutations) {
      for (var i = 0; i < mutations.length; i++) {
        var target = mutations[i].target;
        if (cover && (target === cover || cover.contains(target))) continue;
        schedule();
        return;
      }
    });
    observer.observe(document.documentElement, { childList: true, subtree: true });
  }

  var ticks = 0;
  setInterval(function () {
    ticks++;
    startObserver();
    commitLocation(false);            // catches navigations that bypass the history wrappers
    if (ticks % 3 === 0) recheck();   // catches a login/logout that does not change the URL
    enforce();
  }, 400);

  document.addEventListener('DOMContentLoaded', function () { startObserver(); enforce(); }, true);
  window.addEventListener('scroll', schedule, true);
  window.addEventListener('resize', schedule, true);

  Object.defineProperty(window, '__antiscrol', {
    value: {
      /** Re-reads rules.json from the app (after a newer version was downloaded). */
      refresh: function () { loadConfig(); recheck(); enforce(); },
      addStory: addStory,
      state: function () { return { mode: mode, contentId: contentId, tray: trayRect, cover: coverState }; }
    },
    enumerable: false
  });

  loadConfig();
  startObserver();
  commitLocation(true);
})();
