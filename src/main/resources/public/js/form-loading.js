/*
 * Shows a small inline spinner + "please wait" label on any submit button
 * marked .btn-loading-on-submit the moment its form is submitted, then
 * holds it up for at least MIN_LOADING_MS before swapping in the
 * response - see the SDK/TQK landing pages' "Get your card now" button,
 * whose form POST (IssuerUi.kt's
 * handleGenerateSdkEhicOffer()/handleGenerateTqkEhicOffer()) does real
 * work server-side (pre-authorized-code JWT signing, QR image generation)
 * before the next page renders.
 *
 * A plain disable-and-let-the-browser-navigate version of this turned out
 * to be unreliable at actually being seen: on this demo's local network
 * the request often completes in well under a second, so the button would
 * flip to the next (already-navigated) page before anyone could register
 * the spinner. This intercepts the submit and runs the same request via
 * fetch instead, so the minimum-duration wait is guaranteed regardless of
 * how fast the real response was, then swaps the whole document for the
 * response HTML via document.open()/write()/close() - which behaves like
 * a real navigation (reruns every script fresh) and keeps the same URL,
 * since both handlers render their result directly (ServerResponse.ok(),
 * no redirect) at the URL the form POSTs to.
 *
 * Shared verbatim between both subsites, same reasoning as
 * identity-verify.js: the class/data attribute below are brand-neutral,
 * each subsite's own stylesheet (sdk-theme.css/tqk-theme.css) styles the
 * spinner in its own colors.
 */
(function () {
  "use strict";

  var MIN_LOADING_MS = 500;

  document.addEventListener("submit", function (e) {
    var form = e.target;
    var btn = form.querySelector(".btn-loading-on-submit");
    if (!btn || btn.disabled) return;

    e.preventDefault();
    var loadingText = btn.dataset.loadingText || btn.textContent;
    btn.disabled = true;
    btn.innerHTML = '<span class="btn-spinner" aria-hidden="true"></span>' + loadingText;

    var startedAt = performance.now();
    var formData = new FormData(form);

    fetch(form.action, { method: form.method || "POST", body: formData })
      .then(function (res) { return res.text(); })
      .then(function (html) {
        // Deliberately the *second* argument of this .then(), not a
        // trailing .catch() on the whole chain - see eudi_web_wallet's
        // own identical fix for why: a .catch() here would also swallow
        // any error the document-swap callback itself throws and
        // misreport it as a network failure, triggering the fallback
        // resubmission below for a request that had already succeeded
        // server-side (a duplicate offer generated for one click).
        var elapsed = performance.now() - startedAt;
        setTimeout(function () {
          document.open();
          document.write(html);
          document.close();
        }, Math.max(0, MIN_LOADING_MS - elapsed));
      }, function () {
        // A real failure to even get a response - fall back to letting
        // the browser submit the form for real.
        form.submit();
      });
  });
})();
