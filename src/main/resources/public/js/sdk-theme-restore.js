/*
 * Re-applies a stored light/dark override before first paint - loaded
 * blocking (no defer/async), ahead of <body>, same as embed-detect.js
 * right next to it. An external file rather than inline for one reason:
 * this app's CSP is `script-src 'self'` with no 'unsafe-inline', so an
 * inline <script> in the template would just be silently blocked.
 *
 * The toggle button itself and the rest of the light/dark logic live in
 * fragments/sdk-chrome.html (footer) and public/js/sdk-theme-toggle.js.
 */
if (localStorage.getItem("sdk-issuer-theme") === "dark") {
  document.documentElement.dataset.theme = "dark";
}
