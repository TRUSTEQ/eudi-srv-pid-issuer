// Click-to-enlarge for any QR code on the page (.qr-zoom-target - see
// public/css/main.css/sdk-theme.css's own comment) - one shared full-screen
// backdrop, reused by whichever QR code was last clicked. Same recipe used
// by every other app in this demo (eudi_web_login/eudi-dashboard).
(function () {
    "use strict";
    var backdrop = document.createElement("div");
    backdrop.className = "qr-zoom-backdrop";
    var bigImg = document.createElement("img");
    backdrop.appendChild(bigImg);
    document.body.appendChild(backdrop);
    function close() {
        backdrop.classList.remove("open");
    }
    backdrop.addEventListener("click", close);
    document.addEventListener("keydown", function (e) {
        if (e.key === "Escape") close();
    });
    document.querySelectorAll(".qr-zoom-target").forEach(function (img) {
        img.addEventListener("click", function () {
            bigImg.src = img.src;
            bigImg.alt = img.alt;
            backdrop.classList.add("open");
        });
    });
})();
