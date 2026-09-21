/*
 * Light/dark toggle for the SDK/EHIC subsite's footer button (see
 * fragments/sdk-chrome.html's sdkFooter) - same mechanism as
 * eudi_web_login's own toggleTheme()/syncToggleIcon(): sets
 * document.documentElement.dataset.theme, which sdk-theme.css's
 * :root[data-theme="dark"] block reads, and remembers the choice in
 * localStorage (a pure display preference with no reason to ever reach
 * the server - same key scheme as the client site's own
 * 'client-site-theme', just named for this app instead).
 *
 * SDK only - the TQK subsite doesn't load this file at all (it's fixed
 * dark, no toggle button exists there - see tqk-theme.css's header
 * comment). Reads the neutral #theme-toggle id (SDK's own
 * .sdk-theme-toggle class is what actually colors it; this file only
 * ever touches the id) - kept neutral rather than sdk-prefixed on the
 * off chance a future brand wants this exact toggle again.
 *
 * The button's own click handler is wired here via addEventListener
 * rather than an onclick="" attribute in the template - this app's CSP
 * is `script-src 'self'` with no 'unsafe-inline', which blocks inline
 * event-handler attributes exactly like it blocks inline <script> tags
 * (see theme-restore.js for the other half of that same reasoning).
 *
 * The early restore (applying a stored "dark" choice before first paint,
 * so there's no flash of the wrong theme) is that separate file, loaded
 * in <head>; this one loads at the bottom of the page and only needs to
 * wire the click and keep the button's own icon in sync from then on.
 */
(function () {
  "use strict";

  var STORAGE_KEY = "eudi-issuer-theme";
  var SUN_ICON =
    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="4.5"/><path d="M12 2v3M12 19v3M4.2 4.2l2.1 2.1M17.7 17.7l2.1 2.1M2 12h3M19 12h3M4.2 19.8l2.1-2.1M17.7 6.3l2.1-2.1"/></svg>';
  var MOON_ICON =
    '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M20 14.5A8.5 8.5 0 0 1 9.5 4 8.5 8.5 0 1 0 20 14.5Z"/></svg>';

  function effectiveTheme() {
    return document.documentElement.dataset.theme === "dark" ? "dark" : "light";
  }

  function syncIcon() {
    var btn = document.getElementById("theme-toggle");
    if (btn) btn.innerHTML = effectiveTheme() === "dark" ? MOON_ICON : SUN_ICON;
  }

  function toggle() {
    var next = effectiveTheme() === "dark" ? "light" : "dark";
    document.documentElement.dataset.theme = next;
    localStorage.setItem(STORAGE_KEY, next);
    syncIcon();
  }

  document.addEventListener("DOMContentLoaded", function () {
    syncIcon();
    var btn = document.getElementById("theme-toggle");
    if (btn) btn.addEventListener("click", toggle);
  });
})();
