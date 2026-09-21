/*
 * Re-applies a stored light/dark override before first paint - loaded
 * blocking (no defer/async), ahead of <body>, same as embed-detect.js
 * right next to it. An external file rather than inline for one reason:
 * this app's CSP is `script-src 'self'` with no 'unsafe-inline', so an
 * inline <script> in the template would just be silently blocked.
 *
 * SDK subsite only - the TQK subsite doesn't load this file at all (it's
 * fixed dark, no light/dark choice exists there - see tqk-theme.css's
 * header comment). The toggle button itself and the rest of the
 * light/dark logic live in fragments/sdk-chrome.html's own footer and
 * public/js/theme-toggle.js.
 */
if (localStorage.getItem("eudi-issuer-theme") === "dark") {
  document.documentElement.dataset.theme = "dark";
}
