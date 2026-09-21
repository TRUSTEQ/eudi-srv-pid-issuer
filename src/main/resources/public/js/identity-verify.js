/*
 * Drives the SDK/EHIC and TQK/EHIC landing pages' simulated identity-check
 * step (see sdk-ehic-landing.html's/tqk-ehic-landing.html's #identity-
 * verify-step) - purely front-end theatre with a fixed timeline, not a
 * real check of anything: no PID is actually requested from a wallet, no
 * network call happens here. It exists so a live audience SEES "verify
 * identity, then get your card" as two distinct beats instead of only
 * hearing the presenter say the first one was skipped - see the top-level
 * docs/governance/trust-and-compliance.md for why that distinction
 * matters and stays explicit (the disclaimer text this reveals alongside
 * is never hidden, on purpose).
 *
 * Shared verbatim between both subsites - the element ids below are
 * deliberately neutral (not sdk-/tqk-prefixed) so this file doesn't need
 * to know or care which brand's page it's running on; each subsite's own
 * stylesheet (sdk-theme.css/tqk-theme.css) styles these same ids/classes
 * in its own colors.
 *
 * No-JS fallback: each landing page's own <noscript> block shows the real
 * form immediately if this script never runs at all.
 */
(function () {
  "use strict";

  document.addEventListener("DOMContentLoaded", function () {
    var btn = document.getElementById("identity-verify-btn");
    var progress = document.getElementById("identity-verify-progress");
    var verifyStep = document.getElementById("identity-verify-step");
    var issueStep = document.getElementById("issue-step");
    if (!btn || !progress || !verifyStep || !issueStep) return;

    var items = progress.querySelectorAll("li");

    // One entry per progress line: how long it stays "in progress" (spinner)
    // before flipping to "done" (checkmark) and starting the next one.
    // Deliberately a few seconds end to end - long enough to read on a
    // shared screen, short enough not to stall a live demo.
    var STEP_DELAYS_MS = [1100, 900, 900, 700];
    var REVEAL_DELAY_MS = 600; // pause after the last checkmark before reveal

    function runStep(index) {
      if (index >= items.length) {
        setTimeout(reveal, REVEAL_DELAY_MS);
        return;
      }
      items[index].classList.add("pending");
      setTimeout(function () {
        items[index].classList.remove("pending");
        items[index].classList.add("done");
        runStep(index + 1);
      }, STEP_DELAYS_MS[index] || 900);
    }

    function reveal() {
      verifyStep.hidden = true;
      issueStep.hidden = false;
      var activeStep = document.querySelector('.step[data-step="1"]');
      var nextStep = document.querySelector('.step[data-step="2"]');
      if (activeStep) activeStep.classList.remove("step--active");
      if (nextStep) nextStep.classList.add("step--active");
    }

    btn.addEventListener("click", function () {
      btn.disabled = true;
      progress.hidden = false;
      runStep(0);
    });
  });
})();
