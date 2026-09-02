/* Kryon — website behaviour.
   Three small features, no framework, no build. If this file needs a bundler, something
   has gone wrong with the site rather than with the bundler. */

(function () {
  "use strict";

  /* ---------- theme toggle ---------- */

  var root = document.documentElement;
  var toggle = document.getElementById("theme-toggle");

  function stored(key) {
    try {
      return localStorage.getItem(key);
    } catch (e) {
      return null; // private mode or blocked site data — the media query still applies
    }
  }

  function remember(key, value) {
    try {
      localStorage.setItem(key, value);
    } catch (e) {
      /* nothing to do; the theme still applies for this page view */
    }
  }

  function currentTheme() {
    var explicit = root.getAttribute("data-theme");
    if (explicit) return explicit;
    return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  }

  if (toggle) {
    toggle.addEventListener("click", function () {
      var next = currentTheme() === "dark" ? "light" : "dark";
      root.setAttribute("data-theme", next);
      remember("kryon-theme", next);
      toggle.setAttribute("aria-label", "Switch to " + (next === "dark" ? "light" : "dark") + " theme");
    });
  }

  /* ---------- copy buttons ---------- */

  document.querySelectorAll("[data-copy]").forEach(function (button) {
    button.addEventListener("click", function () {
      var block = button.closest(".code");
      var code = block && block.querySelector("pre");
      if (!code) return;

      var done = function (ok) {
        button.textContent = ok ? "Copied" : "Press Ctrl+C";
        setTimeout(function () {
          button.textContent = "Copy";
        }, 1600);
      };

      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(code.innerText).then(
          function () { done(true); },
          function () { done(false); }
        );
      } else {
        // Older browsers, and any context where the clipboard API is unavailable:
        // select the text so the keyboard shortcut works.
        var range = document.createRange();
        range.selectNodeContents(code);
        var selection = window.getSelection();
        selection.removeAllRanges();
        selection.addRange(range);
        done(false);
      }
    });
  });

  /* ---------- example tabs ---------- */

  var tabs = Array.prototype.slice.call(document.querySelectorAll('[role="tab"]'));

  function select(tab) {
    tabs.forEach(function (other) {
      var selected = other === tab;
      other.setAttribute("aria-selected", selected ? "true" : "false");
      other.tabIndex = selected ? 0 : -1;
      var panel = document.getElementById(other.getAttribute("aria-controls"));
      if (panel) panel.hidden = !selected;
    });
  }

  tabs.forEach(function (tab, index) {
    tab.tabIndex = tab.getAttribute("aria-selected") === "true" ? 0 : -1;

    tab.addEventListener("click", function () {
      select(tab);
    });

    // Arrow-key navigation, as the tab pattern requires. Without it the tablist is a
    // keyboard trap for anyone not using a mouse.
    tab.addEventListener("keydown", function (event) {
      var step = event.key === "ArrowRight" ? 1 : event.key === "ArrowLeft" ? -1 : 0;
      if (event.key === "Home") {
        event.preventDefault();
        tabs[0].focus();
        select(tabs[0]);
        return;
      }
      if (event.key === "End") {
        event.preventDefault();
        tabs[tabs.length - 1].focus();
        select(tabs[tabs.length - 1]);
        return;
      }
      if (!step) return;
      event.preventDefault();
      var next = tabs[(index + step + tabs.length) % tabs.length];
      next.focus();
      select(next);
    });
  });

  /* Guard against a stale stored theme value from an earlier version. */
  var saved = stored("kryon-theme");
  if (saved && saved !== "dark" && saved !== "light") {
    root.removeAttribute("data-theme");
    remember("kryon-theme", "");
  }
})();
