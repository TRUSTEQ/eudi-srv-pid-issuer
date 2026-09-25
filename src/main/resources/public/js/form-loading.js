/*
 * Shows a small inline spinner + "please wait" label on any submit button
 * marked .btn-loading-on-submit the moment its form is submitted - see the
 * SDK/TQK landing pages' "Get your card now" button, whose form POST
 * (IssuerUi.kt's handleGenerateSdkEhicOffer()/handleGenerateTqkEhicOffer())
 * does real work server-side (pre-authorized-code JWT signing, QR image
 * generation) before the next page renders. A plain full-page form submit
 * otherwise leaves the button looking exactly as clickable as before, with
 * no sign anything is happening, for that whole wait.
 *
 * Shared verbatim between both subsites, same reasoning as
 * identity-verify.js: the class/data attribute below are brand-neutral,
 * each subsite's own stylesheet (sdk-theme.css/tqk-theme.css) styles the
 * spinner in its own colors.
 *
 * No fetch/AJAX here - this is still a normal full-page form POST; the
 * button is just disabled and re-labeled right before the browser's own
 * navigation happens, and that visual state naturally stays frozen on
 * screen until the response actually arrives and the next page replaces
 * this one. Disabling the button also stops it being double-clicked into
 * submitting the same request twice.
 */
(function () {
  "use strict";

  document.addEventListener("submit", function (e) {
    var btn = e.target.querySelector(".btn-loading-on-submit");
    if (!btn || btn.disabled) return;
    var loadingText = btn.dataset.loadingText || btn.textContent;
    btn.disabled = true;
    btn.innerHTML = '<span class="btn-spinner" aria-hidden="true"></span>' + loadingText;
  });
})();
