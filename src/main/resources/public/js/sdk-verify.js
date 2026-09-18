/*
 * Drives the SDK/EHIC landing page's simulated identity-check step (see
 * sdk-ehic-landing.html's #sdk-verify-step) - purely front-end theatre with
 * a fixed timeline, not a real check of anything: there is no camera, no
 * document scan, no network call here. It exists so a live audience SEES
 * "verify identity, then get your card" as two distinct beats instead of
 * only hearing the presenter say the first one was skipped - see the
 * top-level docs/governance/trust-and-compliance.md for why that
 * distinction matters and stays explicit (the disclaimer text this reveals
 * alongside is never hidden, on purpose).
 *
 * No-JS fallback: sdk-ehic-landing.html's <noscript> block shows the real
 * form immediately if this script never runs at all.
 */
(function () {
  "use strict";

  document.addEventListener("DOMContentLoaded", function () {
    var btn = document.getElementById("sdk-verify-btn");
    var progress = document.getElementById("sdk-verify-progress");
    var verifyStep = document.getElementById("sdk-verify-step");
    var issueStep = document.getElementById("sdk-issue-step");
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
      var activeStep = document.querySelector('.sdk-step[data-step="1"]');
      var nextStep = document.querySelector('.sdk-step[data-step="2"]');
      if (activeStep) activeStep.classList.remove("sdk-step--active");
      if (nextStep) nextStep.classList.add("sdk-step--active");
    }

    btn.addEventListener("click", function () {
      btn.disabled = true;
      progress.hidden = false;
      runStep(0);
    });
  });
})();
