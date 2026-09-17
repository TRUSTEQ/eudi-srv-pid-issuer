(
    function () {
        const generateButton = document.getElementById("generateButton");
        const grid = document.getElementById("credential-card-grid");
        const preAuthorizedCode = document.getElementById("preAuthorizedCode");

        // DEMO_BRAND=sdk (see DemoBrandProperties/generate-credentials-offer-form.html):
        // the Health Insurance Card (EHIC) becomes the demo's lead credential -
        // shown first, visually featured, and pre-selected so the default path
        // through this page is "load, EHIC is already picked, click Generate."
        // Unset (data-demo-brand-sdk="false") in neutral mode, where every
        // credential stays an equal, unselected peer exactly as before.
        const demoBrandSdk = grid.dataset.demoBrandSdk === "true";
        const featuredBadgeText = grid.dataset.featuredBadgeText || "";
        const EHIC_CONFIG_ID = "urn:eudi:ehic:1:dc+sd-jwt-compact";
        let ehicCardController = null;
        const customDataFields = Array.from(document.querySelectorAll(".custom-data-fields"));
        const customDataInputs = customDataFields.flatMap(fieldset => Array.from(fieldset.querySelectorAll("input")));

        // Icon shown per card. Matched by the same "base label" cards are grouped
        // under (see baseLabel() below) - anything not listed here just gets a
        // generic document icon, so a future new credential type never ends up
        // with a blank/broken card.
        const ICONS = {
            "PID": "🪪",
            "Mobile Driving Licence": "🚗",
            "Diploma": "🎓",
            "Health Insurance Card": "🩺",
            "Residence Permit": "🏠",
            "Schufa Credit Report": "💳",
            "Employment Certificate (TRUSTEQ)": "💼",
        };
        const DEFAULT_ICON = "📄";

        // Typed-in demo data only takes effect in "quick demo mode" - check it automatically so people
        // filling in the optional fields don't need to separately discover and tick that box themselves.
        function onCustomDataInput(event) {
            if (event.target.value.trim() !== "" && !preAuthorizedCode.checked) {
                preAuthorizedCode.checked = true;
            }
        }
        customDataInputs.forEach(input => input.addEventListener("input", onCustomDataInput));

        // One card can represent several underlying checkboxes (this app's format/
        // deferred-delivery variants for the same real-world credential); "cards"
        // tracks, per card, which checkboxes to check on selection (just the
        // sensible default variant) and which to clear on deselection (every
        // variant in its group, so switching cards never leaves a stray box checked).
        const cards = [];

        function registerCard(cardEl, category, checkboxesToSelect, allCheckboxesInGroup) {
            cardEl.dataset.category = category;
            cardEl.addEventListener("click", () => selectCard(cardController));
            const cardController = { el: cardEl, checkboxesToSelect, allCheckboxesInGroup };
            cards.push(cardController);
            grid.appendChild(cardEl);
            return cardController;
        }

        function selectCard(cardController) {
            const wasSelected = cardController.el.classList.contains("selected");
            cards.forEach(c => {
                c.el.classList.remove("selected");
                c.allCheckboxesInGroup.forEach(cb => { if (cb) cb.checked = false; });
            });
            if (!wasSelected) {
                cardController.el.classList.add("selected");
                cardController.checkboxesToSelect.forEach(cb => { if (cb) cb.checked = true; });
            }
            onSelectionChanged();
        }

        function makeCardElement(label, category) {
            const card = document.createElement("button");
            card.type = "button";
            card.className = "credential-card";
            const icon = ICONS[label] || DEFAULT_ICON;
            card.innerHTML =
                '<span class="credential-card-icon" aria-hidden="true">' + icon + "</span>" +
                '<span class="credential-card-label"></span>';
            card.querySelector(".credential-card-label").textContent = label;
            return card;
        }

        // --- Flat checkboxes (name="credentialIds"): group format/deferred variants of
        // the same credential into one card, defaulting to SD-JWT VC, non-deferred. ---
        const FORMAT_SUFFIX_PATTERN = /\s*\((?:MSO MDoc|SD-JWT VC Compact)\)\s*$/;
        const DEFERRED_SUFFIX_PATTERN = /\s*\(deferred\)\s*$/;

        function baseLabel(rawLabel) {
            return rawLabel.replace(DEFERRED_SUFFIX_PATTERN, "").replace(FORMAT_SUFFIX_PATTERN, "").trim();
        }

        const flatCheckboxes = Array.from(document.querySelectorAll('.credential-configuration-id[name="credentialIds"]'));
        const flatGroups = new Map(); // baseLabel -> [{ cb, rawLabel }]
        flatCheckboxes.forEach(cb => {
            const row = cb.closest(".form-check");
            const rawLabel = row.querySelector("label").textContent.trim();
            const key = baseLabel(rawLabel);
            if (!flatGroups.has(key)) flatGroups.set(key, []);
            flatGroups.get(key).push({ cb, rawLabel });
        });

        flatGroups.forEach((items, key) => {
            const nonDeferred = items.filter(i => !DEFERRED_SUFFIX_PATTERN.test(i.rawLabel) && !/\(deferred\)$/.test(i.rawLabel));
            const preferred = nonDeferred.find(i => /SD-JWT/i.test(i.rawLabel)) || nonDeferred[0] || items[0];
            const cardController = registerCard(
                makeCardElement(key, preferred.cb.dataset.category),
                preferred.cb.dataset.category,
                [preferred.cb],
                items.map(i => i.cb),
            );
            if (items.some(i => i.cb.dataset.configId === EHIC_CONFIG_ID)) {
                ehicCardController = cardController;
            }
        });

        // --- Family rows (name="credentialFamilies", e.g. Schufa/Employment Certificate):
        // already one concept each, with their own format radio (SD-JWT VC checked by
        // default in the markup) and a deferred checkbox. ---
        const familyRows = Array.from(document.querySelectorAll(".credential-family"));
        familyRows.forEach(row => {
            const familyCb = row.querySelector('input[name="credentialFamilies"]');
            const label = row.querySelector("label[for='" + familyCb.id + "']").textContent.trim();
            const sdJwtRadio = row.querySelector('input[type="radio"][value="sdjwt"]');
            const mdocRadio = row.querySelector('input[type="radio"][value="mdoc"]');
            const deferredCb = row.querySelector('input[name$="_deferred"]');

            const toSelect = [familyCb];
            if (sdJwtRadio) toSelect.push(sdJwtRadio);
            registerCard(
                makeCardElement(label, familyCb.dataset.category),
                familyCb.dataset.category,
                toSelect,
                [familyCb, sdJwtRadio, mdocRadio, deferredCb],
            );
        });

        function onSelectionChanged() {
            generateButton.disabled = !cards.some(c => c.el.classList.contains("selected"));

            const checkedConfigIds = new Set(
                flatCheckboxes.filter(cb => cb.checked).map(cb => cb.dataset.configId),
            );
            const checkedFamilyKeys = new Set(
                Array.from(document.querySelectorAll('input[name="credentialFamilies"]'))
                    .filter(cb => cb.checked)
                    .map(cb => cb.value),
            );
            customDataFields.forEach(fieldset => {
                const matches =
                    (Boolean(fieldset.dataset.configId) && checkedConfigIds.has(fieldset.dataset.configId)) ||
                    (Boolean(fieldset.dataset.family) && checkedFamilyKeys.has(fieldset.dataset.family));
                fieldset.style.display = matches ? "" : "none";
            });
        }

        // Lead with EHIC, featured and pre-selected - see the demoBrandSdk comment above.
        if (demoBrandSdk && ehicCardController) {
            grid.insertBefore(ehicCardController.el, grid.firstChild);
            ehicCardController.el.classList.add("featured");
            if (featuredBadgeText) {
                const badge = document.createElement("span");
                badge.className = "credential-card-badge";
                badge.textContent = featuredBadgeText;
                ehicCardController.el.appendChild(badge);
            }
            selectCard(ehicCardController);
        }

        onSelectionChanged();
    }
)();
