/**
 * scuole.js
 * Versione DIFENSIVA per portale scuole
 * Non rompe se mancano section / fragment / elementi
 */

(function () {
    "use strict";

    // =========================
    // UTILS
    // =========================

    function exists(selector) {
        return document.querySelector(selector) !== null;
    }

    function getOffsetTop(el) {
        if (!el) return null;
        const rect = el.getBoundingClientRect();
        return rect.top + window.pageYOffset;
    }

    // =========================
    // ON DOM READY
    // =========================
    document.addEventListener("DOMContentLoaded", function () {

        // =========================
        // HEADER STICKY / SCROLL
        // =========================
        const header = document.getElementById("main-header");
        if (header) {
            window.addEventListener("scroll", function () {
                if (window.scrollY > 50) {
                    header.classList.add("is-scrolled");
                } else {
                    header.classList.remove("is-scrolled");
                }
            });
        }

        // =========================
        // SCROLL TO TOP BUTTON
        // =========================
        const scrollTopBtn = document.querySelector(".scroll-top");
        if (scrollTopBtn) {
            scrollTopBtn.addEventListener("click", function (e) {
                e.preventDefault();
                window.scrollTo({
                    top: 0,
                    behavior: "smooth"
                });
            });
        }

        // =========================
        // SIDEBAR OFFSET SAFE
        // =========================
        const sidebar = document.querySelector(".sidebar");
        if (sidebar) {
            const sidebarTop = getOffsetTop(sidebar);
            if (sidebarTop !== null) {
                window.addEventListener("scroll", function () {
                    if (window.pageYOffset > sidebarTop - 100) {
                        sidebar.classList.add("is-fixed");
                    } else {
                        sidebar.classList.remove("is-fixed");
                    }
                });
            }
        }

        // =========================
        // ANCHOR SCROLL SAFE
        // =========================
        const anchorLinks = document.querySelectorAll('a[href^="#"]');
        anchorLinks.forEach(function (link) {
            link.addEventListener("click", function (e) {
                const targetId = this.getAttribute("href").substring(1);
                const targetEl = document.getElementById(targetId);
                if (!targetEl) return; // ← fondamentale

                e.preventDefault();
                const top = getOffsetTop(targetEl);
                if (top !== null) {
                    window.scrollTo({
                        top: top - 80,
                        behavior: "smooth"
                    });
                }
            });
        });

        // =========================
        // IMAGE FALLBACK (NO PLACEHOLDER)
        // =========================
        const images = document.querySelectorAll("img");
        images.forEach(function (img) {
            img.addEventListener("error", function () {
                // se l'immagine non esiste → la nascondiamo
                this.style.display = "none";
            });
        });

        // =========================
        // COOKIE POPUP (SAFE)
        // =========================
        if (window.jQuery && typeof jQuery.fn.euCookieLawPopup === "function") {
            try {
                jQuery(document).euCookieLawPopup().init({
                    cookiePolicyUrl: "/front/35/Privacy_Policy",
                    popupPosition: "bottom",
                    colorStyle: "default",
                    compactStyle: false,
                    popupTitle: "Questo sito utilizza i cookie",
                    popupText: "Utilizziamo cookie tecnici necessari al funzionamento del sito e, previo consenso, cookie statistici e di profilazione. Continuando la navigazione o cliccando \"Accetta\" acconsenti al loro utilizzo.",
                    buttonContinueTitle: "Accetta",
                    buttonLearnmoreTitle: "Leggi l'informativa",
                    buttonLearnmoreOpenInNewWindow: true,
                    agreementExpiresInDays: 30,
                    autoAcceptCookiePolicy: false
                });
            } catch (e) {
                console.warn("Cookie popup non inizializzato:", e);
            }
        }

        // =========================
        // DEBUG
        // =========================
        console.log("scuole.js caricato correttamente (safe mode)");
    });

})();
