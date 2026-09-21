/*
 * Re-applies a stored light/dark override before first paint - loaded
 * blocking (no defer/async), ahead of <body>, same as embed-detect.js
 * right next to it. An external file rather than inline for one reason:
 * this app's CSP is `script-src 'self'` with no 'unsafe-inline', so an
 * inline <script> in the template would just be silently blocked.
 *
 * Shared verbatim between the SDK and TQK subsites - one light/dark
 * preference for "the issuer," not a separate one per brand.
 *
 * The toggle button itself and the rest of the light/dark logic live in
 * fragments/sdk-chrome.html/fragments/tqk-chrome.html (each one's own
 * footer) and public/js/theme-toggle.js.
 */
if (localStorage.getItem("eudi-issuer-theme") === "dark") {
  document.documentElement.dataset.theme = "dark";
}
