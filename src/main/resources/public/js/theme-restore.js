/*
 * Re-applies a stored light/dark override before first paint - loaded
 * blocking (no defer/async), ahead of <body>, same as embed-detect.js
 * right next to it. An external file rather than inline for one reason:
 * this app's CSP is `script-src 'self'` with no 'unsafe-inline', so an
 * inline <script> in the template would just be silently blocked.
 *
 * Shared verbatim between the SDK and TQK subsites - one light/dark
 * preference for "the issuer," not a separate one per brand. This
 * doesn't create any visible inconsistency even though the two subsites'
 * own defaults differ (see below): SDK defaults light, TQK defaults
 * dark, and since those are exact opposites, "remember whichever
 * absolute light/dark state was last explicitly chosen" always agrees
 * with whatever page you're actually on - either it matches that page's
 * own default, or it's that page's deliberate opt-in alternate.
 *
 * The toggle button itself and the rest of the light/dark logic live in
 * fragments/sdk-chrome.html/fragments/tqk-chrome.html (each one's own
 * footer) and public/js/theme-toggle.js.
 *
 * document.documentElement.dataset.defaultTheme reads a `data-default-
 * theme` attribute on <html> - absent on the SDK templates (falls back
 * to "light", identical to this file's behavior before TQK existed) and
 * "dark" on the TQK ones - see tqk-theme.css's header comment for why
 * TQK defaults dark. <html>'s own attributes are available immediately,
 * before <body> is even parsed, so reading it here (ahead of first
 * paint) is safe.
 */
var storedTheme = localStorage.getItem("eudi-issuer-theme");
var defaultTheme = document.documentElement.dataset.defaultTheme || "light";
if ((storedTheme || defaultTheme) === "dark") {
  document.documentElement.dataset.theme = "dark";
}
