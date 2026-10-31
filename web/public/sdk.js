/*
 * Quokra embed script. Plain JavaScript, no dependencies.
 *
 * Bubble (chat button in the corner):
 *   <script src="https://chat.example.com/sdk.js" data-quokra-chat="slug" data-quokra-pk="pk_..." async></script>
 *
 * Inline (inside a container):
 *   <div data-quokra-chat-inline="slug" data-quokra-pk="pk_..."></div>
 *
 * Popup (from your own button):
 *   <button data-quokra-chat-trigger="slug" data-quokra-pk="pk_...">Chat with us</button>
 *
 * JavaScript: Quokra.chat.init({ slug, pk, mode, container, position, teaserDelay }), open(), close(),
 *             identify(token), logout(), setTheme(theme), on(event, fn), destroy()
 * Theme: { theme: "host" | "light" | "dark" | "auto", accent: "#rrggbb" } (or data-quokra-theme / data-quokra-accent).
 *        "host" (default) follows the page's own light or dark mode, also when it changes.
 *
 * Agent console (in your dashboard; getToken returns the agent's access token or a Promise of it):
 *   Quokra.console.mount("#console", { tenant: "slug", getToken })
 *   Quokra.console.init({ mode: "bubble", tenant: "slug", getToken, position }), open(), close(),
 *   on(event, fn), unmount()
 *
 * Icons: Lucide (ISC). Robot: "Hello Chat Bot" by Chandalal Kumawat, LottieFiles (Lottie Simple License), recoloured.
 * See THIRD_PARTY_NOTICES.md.
 */
(function () {
  "use strict";

  window.Quokra = window.Quokra || {};
  if (window.Quokra.chat) return;

  var script = document.currentScript;
  var ORIGIN = script && script.src ? new URL(script.src).origin : window.location.origin;
  var SOURCE = "quokra";
  var STYLE_ID = "quokra-chat-style";
  var FRAME_ID = "quokra-chat-frame";
  var DEFAULT_COLOR = "#0051fb";
  var WAVE_EVERY_MS = 15000;
  // Nothing is shown until the Quokra page inside says it's ready; if that doesn't happen in time,
  // the "unavailable" event fires and the page keeps nothing broken on screen.
  var READY_TIMEOUT_MS = 15000;
  var DEFAULT_TEASER_DELAY = 5000;
  var ROBOT_URL = ORIGIN + "/robot.svg";
  // The waving robot is an upgrade loaded after the page; until then (or without it) the still image shows.
  var LOTTIE_URL = ORIGIN + "/vendor/lottie_light-5.13.0.min.js";
  var ANIMATION_URL = ORIGIN + "/animations/robot.js";
  var ROBOT_VIEWBOX = "156 30 196 290"; // the robot and its waving arms, without the empty canvas around them
  var reducedMotion = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  var SVG_ATTRS =
    'xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
    'stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"';
  var ICON_CHAT =
    "<svg " + SVG_ATTRS + '><path d="M12 6V2H8"/><path d="M15 11v2"/><path d="M2 12h2"/><path d="M20 12h2"/>' +
    '<path d="M20 16a2 2 0 0 1-2 2H8.828a2 2 0 0 0-1.414.586l-2.202 2.202A.71.71 0 0 1 4 20.286V8a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2z"/>' +
    '<path d="M9 11v2"/></svg>';
  var ICON_CLOSE = "<svg " + SVG_ATTRS + '><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>';


  var state = null;
  var listeners = {};

  function emit(event, value) {
    (listeners[event] || []).forEach(function (fn) {
      try {
        fn(value);
      } catch (e) {
        // A broken listener on the host page shouldn't stop the chat.
      }
    });
  }

  function storage(key, value) {
    try {
      if (value === undefined) return window.sessionStorage.getItem(key);
      window.sessionStorage.setItem(key, value);
    } catch (e) {
      return null;
    }
  }

  function addStyles() {
    if (document.getElementById(STYLE_ID)) return;
    var css =
      // The robot itself is the button. It floats gently and waves on hover or when the greeting shows.
      ".quokra-button{position:fixed;bottom:16px;width:64px;height:94px;padding:0;border:0;background:none;" +
      "cursor:pointer;z-index:2147483646;filter:drop-shadow(0 6px 10px rgba(0,0,0,.18))}" +
      ".quokra-button.right{right:18px}.quokra-button.left{left:18px}" +
      ".quokra-button img{display:block;width:100%;height:100%;transform-origin:50% 85%;" +
      "animation:quokra-float 3.2s ease-in-out infinite}" +
      ".quokra-button:hover img,.quokra-button.wave img{animation:quokra-wave .9s ease-in-out}" +
      ".quokra-button:focus-visible{outline:3px solid var(--quokra-color," + DEFAULT_COLOR + ");outline-offset:2px;border-radius:16px}" +
      // If the image can't load: a plain round chat button.
      ".quokra-button.fallback{width:60px;height:60px;border:2px solid var(--quokra-color," + DEFAULT_COLOR + ");" +
      "border-radius:50%;background:#fff;color:var(--quokra-color," + DEFAULT_COLOR + ");display:flex;" +
      "align-items:center;justify-content:center;filter:none;box-shadow:0 6px 20px rgba(0,0,0,.18)}" +
      ".quokra-button.fallback svg{width:26px;height:26px}.quokra-button.fallback img{display:none}" +
      ".quokra-anim{display:none;position:absolute;inset:0}.quokra-button.animated .quokra-anim{display:block}" +
      ".quokra-button.animated img{visibility:hidden;animation:none}" +
      ".quokra-icon-chat{display:none}.quokra-button.fallback .quokra-icon-chat{display:block}" +
      // While the chat is open the panel's own close button is used, so the launcher steps aside.
      ".quokra-button.open{display:none}" +
      ".quokra-badge{position:absolute;top:-4px;right:-4px;min-width:20px;height:20px;padding:0 5px;border-radius:10px;" +
      "background:#cf222e;color:#fff;font:600 12px/20px system-ui,sans-serif;text-align:center;animation:quokra-pop .3s ease}" +
      ".quokra-teaser{position:fixed;bottom:118px;max-width:260px;display:flex;align-items:flex-start;gap:4px;" +
      "background:#fff;color:#1f2328;border-radius:14px;box-shadow:0 8px 28px rgba(0,0,0,.18);z-index:2147483646;" +
      "font:14px/1.4 system-ui,sans-serif;animation:quokra-rise .35s ease}" +
      ".quokra-teaser.dark{background:#18181b;color:#ececf0}.quokra-teaser.dark .quokra-teaser-close{color:#9b9ba4}" +
      ".quokra-teaser.right{right:20px}.quokra-teaser.left{left:20px}.quokra-button.fallback~.quokra-teaser{bottom:92px}" +
      ".quokra-teaser-text{flex:1;border:0;background:none;padding:12px 4px 12px 14px;text-align:left;font:inherit;" +
      "color:inherit;cursor:pointer}" +
      ".quokra-teaser-close{border:0;background:none;padding:8px;color:#6e7781;cursor:pointer;line-height:0}" +
      ".quokra-teaser-close svg{width:16px;height:16px}" +
      ".quokra-teaser-text:focus-visible,.quokra-teaser-close:focus-visible{outline:2px solid var(--quokra-color," + DEFAULT_COLOR + ");border-radius:8px}" +
      ".quokra-panel{position:fixed;bottom:20px;width:380px;height:600px;max-height:calc(100vh - 40px);border:0;" +
      "border-radius:16px;box-shadow:0 12px 40px rgba(0,0,0,.22);background:#fff;z-index:2147483647;" +
      "opacity:0;visibility:hidden;transform:translateY(12px) scale(.98);transform-origin:bottom right;" +
      "transition:opacity .2s ease,transform .2s ease,visibility 0s linear .2s}" +
      ".quokra-panel.right{right:20px}.quokra-panel.left{left:20px;transform-origin:bottom left}" +
      ".quokra-panel.open{opacity:1;visibility:visible;transform:none;transition:opacity .2s ease,transform .2s ease}" +
      ".quokra-panel.popup{left:50%;right:auto;top:50%;bottom:auto;transform:translate(-50%,-48%) scale(.98)}" +
      ".quokra-panel.popup.open{transform:translate(-50%,-50%)}" +
      "@media (max-width:480px){.quokra-panel,.quokra-panel.popup,.quokra-panel.popup.open{inset:0;width:100%;height:100%;" +
      "max-height:none;border-radius:0;transform:none}.quokra-teaser{max-width:calc(100vw - 40px)}}" +
      ".quokra-pending{display:none!important}" +
      ".quokra-inline{display:block;width:100%;height:520px;border:1px solid #d0d7de;border-radius:12px}" +
      "@keyframes quokra-pop{0%{transform:scale(.4)}70%{transform:scale(1.15)}100%{transform:scale(1)}}" +
      "@keyframes quokra-rise{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:none}}" +
      "@keyframes quokra-float{0%,100%{transform:translateY(0)}50%{transform:translateY(-4px)}}" +
      "@keyframes quokra-wave{0%,100%{transform:rotate(0)}20%{transform:rotate(-10deg)}40%{transform:rotate(8deg)}" +
      "60%{transform:rotate(-6deg)}80%{transform:rotate(3deg)}}" +
      "@media (prefers-reduced-motion:reduce){.quokra-panel,.quokra-panel.open{transition:none}" +
      ".quokra-button img,.quokra-badge,.quokra-teaser{animation:none!important}}";
    var style = document.createElement("style");
    style.id = STYLE_ID;
    style.textContent = css;
    document.head.appendChild(style);
  }

  function frameUrl(slug, pk, mode, theme, accent) {
    return ORIGIN + "/w/" + encodeURIComponent(slug) + "?pk=" + encodeURIComponent(pk) + "&mode=" + mode +
      "&theme=" + theme + accentParam(accent) + hostParam();
  }

  // The page's own origin, so the frame sends its messages only here (frame-ancestors decides who may embed).
  function hostParam() {
    return /^https?:\/\//.test(location.origin) ? "&host=" + encodeURIComponent(location.origin) : "";
  }

  // ---- Theme ----
  // "host" (default): follow the page this is embedded in, and keep following it when it changes.
  // "light" / "dark": fixed. "auto": the visitor's system setting.
  function themeOf(value) {
    return value === "light" || value === "dark" || value === "auto" ? value : "host";
  }

  // An optional brand colour (#rrggbb) that replaces the tenant's.
  function accentParam(accent) {
    return typeof accent === "string" && /^#[0-9a-fA-F]{6}$/.test(accent) ? "&accent=" + encodeURIComponent(accent) : "";
  }

  function luminance(rgb) {
    var c = rgb.map(function (v) {
      v /= 255;
      return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    });
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
  }

  function backgroundOf(el) {
    if (!el || !window.getComputedStyle) return null;
    var m = /rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)(?:,\s*([\d.]+))?\)/.exec(getComputedStyle(el).backgroundColor || "");
    if (!m || (m[4] !== undefined && Number(m[4]) === 0)) return null;
    return [Number(m[1]), Number(m[2]), Number(m[3])];
  }

  // Reads the host page's theme the way most sites mark it: a "dark" class, a data-theme style attribute,
  // the color-scheme property, and as a last resort how dark the page background is.
  function hostTheme() {
    var nodes = [document.documentElement, document.body];
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      if (!n) continue;
      if (n.classList.contains("dark")) return "dark";
      if (n.classList.contains("light")) return "light";
      var attr = n.getAttribute("data-theme") || n.getAttribute("data-mode") || n.getAttribute("data-color-scheme") ||
        n.getAttribute("data-bs-theme") || "";
      if (/dark/i.test(attr)) return "dark";
      if (/light/i.test(attr)) return "light";
    }
    var scheme = (window.getComputedStyle && getComputedStyle(document.documentElement).colorScheme) || "";
    if (/^\s*dark\s*$/.test(scheme)) return "dark";
    var bg = backgroundOf(document.body) || backgroundOf(document.documentElement);
    if (bg) return luminance(bg) < 0.4 ? "dark" : "light";
    return /dark/.test(scheme) ? "auto" : "light";
  }

  // What the frame is told: "host" becomes the host's current light or dark.
  function resolveTheme(mode) {
    return mode === "host" ? hostTheme() : mode;
  }

  function isDark(theme) {
    return theme === "dark" || (theme === "auto" && !!window.matchMedia &&
      window.matchMedia("(prefers-color-scheme: dark)").matches);
  }

  // The frame's own background shows while it loads, so it matches the theme too.
  function paintFrame(frame, theme) {
    frame.style.background = isDark(theme) ? "#0c0c0e" : "#fff";
  }

  // One watcher for the whole page: when the host switches theme, every "host" widget follows.
  var watchingHost = false;
  function watchHost() {
    if (watchingHost) return;
    watchingHost = true;
    var sync = function () {
      if (state) applyChatTheme();
      if (consoleState) applyConsoleTheme();
    };
    if (window.MutationObserver) {
      var observer = new MutationObserver(sync);
      var options = { attributes: true, attributeFilter: ["class", "style", "data-theme", "data-mode", "data-color-scheme", "data-bs-theme"] };
      observer.observe(document.documentElement, options);
      if (document.body) observer.observe(document.body, options);
    }
    if (window.matchMedia) {
      var media = window.matchMedia("(prefers-color-scheme: dark)");
      if (media.addEventListener) media.addEventListener("change", sync);
    }
  }

  function applyChatTheme() {
    var theme = resolveTheme(state.theme);
    if (theme === state.appliedTheme) return;
    state.appliedTheme = theme;
    paintFrame(state.frame, theme);
    if (state.teaser) state.teaser.classList.toggle("dark", isDark(theme));
    post("theme.set", { theme: theme });
  }

  function post(type, fields) {
    if (state && state.frame.contentWindow) {
      var message = { source: SOURCE, type: type };
      for (var key in fields) message[key] = fields[key];
      state.frame.contentWindow.postMessage(message, ORIGIN);
    }
  }

  // An identity token waits here until the panel is ready, so identify() can be called any time.
  var pendingIdentity = null;
  // A logout waits the same way; it's sent before any identity that came after it.
  var pendingLogout = false;

  function sendIdentity() {
    if (!state || !state.ready) return;
    if (pendingLogout) {
      post("chat.logout");
      pendingLogout = false;
    }
    if (pendingIdentity) {
      post("chat.identify", { token: pendingIdentity });
      pendingIdentity = null;
    }
  }

  function setBadge(count) {
    if (!state || !state.button) return;
    var badge = state.button.querySelector(".quokra-badge");
    if (count > 0) {
      if (!badge) {
        badge = document.createElement("span");
        badge.className = "quokra-badge";
        state.button.appendChild(badge);
      }
      badge.textContent = count > 99 ? "99+" : String(count);
      state.button.setAttribute("aria-label", "Open chat, " + count + " unread");
    } else if (badge) {
      badge.remove();
      state.button.setAttribute("aria-label", state.open ? "Close chat" : "Open chat");
    }
  }

  function wave() {
    if (!state || !state.button || reducedMotion) return;
    if (state.animation) {
      state.animation.goToAndPlay(0, true);
      return;
    }
    state.button.classList.remove("wave");
    void state.button.offsetWidth; // restart the CSS animation if it's already running
    state.button.classList.add("wave");
  }

  function loadScript(src, done) {
    var el = document.createElement("script");
    el.src = src;
    el.async = true;
    el.onload = done;
    document.head.appendChild(el);
  }

  // Swap the still robot for the waving one. Reuses a Lottie player the page already has.
  function startAnimation() {
    if (!state || !state.button || state.animation || reducedMotion) return;
    var play = function () {
      if (!state || !state.button || !window.lottie || !window.QuokraAnimations || !window.QuokraAnimations.robot) return;
      state.animation = window.lottie.loadAnimation({
        container: state.button.querySelector(".quokra-anim"),
        renderer: "svg",
        loop: false,
        autoplay: true,
        animationData: window.QuokraAnimations.robot,
        rendererSettings: { viewBoxSize: ROBOT_VIEWBOX },
      });
      state.button.classList.add("animated");
    };
    var withData = function () {
      if (window.lottie) play();
      else loadScript(LOTTIE_URL, play);
    };
    if (window.QuokraAnimations && window.QuokraAnimations.robot) withData();
    else loadScript(ANIMATION_URL, withData);
  }

  // ---- Greeting card next to the button ----

  function teaserKey() {
    return "quokra.teaser.dismissed." + state.pk;
  }

  function scheduleTeaser() {
    if (!state || state.mode !== "bubble" || !state.teaserText || state.teaserDelay < 0) return;
    if (state.open || storage(teaserKey())) return;
    clearTimeout(state.teaserTimer);
    state.teaserTimer = setTimeout(showTeaser, state.teaserDelay);
  }

  function showTeaser() {
    if (!state || state.open || state.teaser || storage(teaserKey())) return;
    var teaser = document.createElement("div");
    teaser.className = "quokra-teaser " + state.position + (isDark(state.appliedTheme) ? " dark" : "");
    var text = document.createElement("button");
    text.type = "button";
    text.className = "quokra-teaser-text";
    text.textContent = state.teaserText;
    text.addEventListener("click", function () {
      chat.open();
    });
    var close = document.createElement("button");
    close.type = "button";
    close.className = "quokra-teaser-close";
    close.setAttribute("aria-label", "Dismiss");
    close.innerHTML = ICON_CLOSE;
    close.addEventListener("click", function () {
      hideTeaser(true);
    });
    teaser.appendChild(text);
    teaser.appendChild(close);
    document.body.appendChild(teaser);
    state.teaser = teaser;
    wave();
    emit("teaser");
  }

  function hideTeaser(remember) {
    if (!state) return;
    clearTimeout(state.teaserTimer);
    if (remember) storage(teaserKey(), "1");
    if (state.teaser) {
      state.teaser.remove();
      state.teaser = null;
    }
  }

  // ---- Messages from our frame ----

  var escapeListening = false;
  function listenForEscape() {
    if (escapeListening) return;
    escapeListening = true;
    document.addEventListener("keydown", onKeydown);
  }

  // Escape on the host page closes whatever Quokra has open, even if the frame itself can't respond.
  function onKeydown(event) {
    if (event.key !== "Escape") return;
    if (state && state.open && state.mode !== "inline") chat.close();
    if (consoleState && consoleState.open && consoleState.mode === "bubble") agentConsole.close();
  }

  function onMessage(event) {
    if (!state) return;
    // Only trust our own frame, from our own origin, speaking our protocol.
    if (event.origin !== ORIGIN || event.source !== state.frame.contentWindow) return;
    var data = event.data;
    if (!data || data.source !== SOURCE) return;

    if (data.type === "chat.ready") {
      if (typeof data.color === "string" && /^#[0-9a-fA-F]{6}$/.test(data.color)) {
        document.documentElement.style.setProperty("--quokra-color", data.color);
      }
      if (typeof data.teaser === "string" && data.teaser.trim()) {
        state.teaserText = data.teaser.trim().slice(0, 140);
        scheduleTeaser();
      }
      var wantOpen = state.open && state.mode !== "inline";
      state.ready = true;
      clearTimeout(state.readyTimer);
      state.frame.classList.remove("quokra-pending");
      if (state.button) state.button.classList.remove("quokra-pending");
      if (state.mode === "inline") post("chat.open");
      // Opened before it was ready (e.g. from your own button): open it now.
      if (wantOpen) {
        state.open = false;
        chat.open();
      }
      sendIdentity();
      emit("ready");
    } else if (data.type === "chat.close") {
      chat.close();
      if (state && state.button) state.button.focus();
    } else if (data.type === "chat.unread" && typeof data.count === "number") {
      setBadge(data.count);
      emit("unread", data.count);
    } else if (data.type === "chat.identified") {
      emit("identified");
    } else if (data.type === "chat.identify.failed") {
      emit("identifyfailed", typeof data.code === "string" ? data.code : "UNKNOWN");
    }
  }

  var chat = {
    init: function (options) {
      if (state) return;
      options = options || {};
      if (!options.slug || !options.pk) throw new Error("Quokra: slug and pk are required");
      var mode = options.mode || "bubble";
      var position = options.position === "left" ? "left" : "right";
      var delay = Number(options.teaserDelay);
      addStyles();

      var frame = document.createElement("iframe");
      frame.id = FRAME_ID;
      frame.title = "Chat";
      var theme = themeOf(options.theme);
      var applied = resolveTheme(theme);
      frame.src = frameUrl(options.slug, options.pk, mode, applied, options.accent);
      paintFrame(frame, applied);
      if (theme === "host") watchHost();
      state = {
        frame: frame,
        button: null,
        teaser: null,
        mode: mode,
        position: position,
        pk: options.pk,
        open: mode === "inline",
        teaserText: null,
        ready: false,
        theme: theme,
        appliedTheme: applied,
        animation: null,
        waveTimer: null,
        teaserDelay: options.teaserDelay === "off" ? -1 : isFinite(delay) && options.teaserDelay !== undefined ? delay : DEFAULT_TEASER_DELAY,
      };

      if (mode === "inline") {
        var container =
          typeof options.container === "string" ? document.querySelector(options.container) : options.container;
        if (!container) throw new Error("Quokra: container not found");
        frame.className = "quokra-inline quokra-pending";
        container.appendChild(frame);
      } else {
        frame.className = "quokra-panel " + (mode === "popup" ? "popup" : position);
        document.body.appendChild(frame);
        if (mode === "bubble") {
          var button = document.createElement("button");
          button.type = "button";
          button.className = "quokra-button quokra-pending " + position;
          button.setAttribute("aria-label", "Open chat");
          button.setAttribute("aria-expanded", "false");
          button.setAttribute("aria-controls", FRAME_ID);
          var robot = document.createElement("img");
          robot.src = ROBOT_URL;
          robot.alt = "";
          robot.addEventListener("error", function () {
            button.classList.add("fallback");
          });
          button.appendChild(robot);
          button.insertAdjacentHTML("beforeend", '<span class="quokra-anim"></span>');
          button.insertAdjacentHTML("beforeend", ICON_CHAT.replace("<svg ", '<svg class="quokra-icon-chat" '));
          button.addEventListener("mouseenter", wave);
          button.addEventListener("animationend", function () {
            button.classList.remove("wave");
          });
          button.addEventListener("click", function () {
            state.open ? chat.close() : chat.open();
          });
          document.body.appendChild(button);
          state.button = button;

          // A friendly wave now and then while the chat is closed, but not while the tab is hidden.
          state.waveTimer = setInterval(function () {
            if (!state.open && !document.hidden) wave();
          }, WAVE_EVERY_MS);

          if (document.readyState === "complete") setTimeout(startAnimation, 300);
          else window.addEventListener("load", function () { setTimeout(startAnimation, 300); }, { once: true });
        }
      }
      window.addEventListener("message", onMessage);
      listenForEscape();
      state.readyTimer = setTimeout(function () {
        if (state && !state.ready) emit("unavailable");
      }, READY_TIMEOUT_MS);
    },

    open: function () {
      if (!state || state.mode === "inline") return;
      state.open = true;
      // Never show a frame that hasn't loaded: it may be a browser error page with no way to close it.
      if (!state.ready) return;
      hideTeaser(true);
      state.frame.classList.add("open");
      if (state.button) {
        state.button.classList.add("open");
        state.button.setAttribute("aria-expanded", "true");
      }
      setBadge(0);
      if (state.button) state.button.setAttribute("aria-label", "Close chat");
      state.frame.focus();
      post("chat.open");
      emit("open");
    },

    close: function () {
      if (!state || state.mode === "inline") return;
      state.open = false;
      state.frame.classList.remove("open");
      if (state.button) {
        state.button.classList.remove("open");
        state.button.setAttribute("aria-expanded", "false");
        state.button.setAttribute("aria-label", "Open chat");
      }
      post("chat.close");
      emit("close");
    },

    setTheme: function (value) {
      if (!state) return;
      state.theme = themeOf(value);
      if (state.theme === "host") watchHost();
      applyChatTheme();
    },

    // token: a short-lived identity token from your backend, signed with your secret key.
    identify: function (token) {
      if (typeof token !== "string" || !token) throw new Error("Quokra: identify needs a token");
      pendingIdentity = token;
      sendIdentity();
    },

    // Call when the customer logs out of your site. The chat forgets them and starts over as a guest.
    logout: function () {
      pendingIdentity = null;
      pendingLogout = true;
      sendIdentity();
    },

    on: function (event, fn) {
      (listeners[event] = listeners[event] || []).push(fn);
    },

    destroy: function () {
      if (!state) return;
      window.removeEventListener("message", onMessage);
      clearTimeout(state.readyTimer);
      hideTeaser(false);
      clearInterval(state.waveTimer);
      if (state.animation) state.animation.destroy();
      state.frame.remove();
      if (state.button) state.button.remove();
      state = null;
    },
  };

  window.Quokra.chat = chat;

  // ---- Agent console, embedded in a dashboard ----
  //
  // Quokra.console.mount(target, { tenant, getToken })            full page inside `target`
  // Quokra.console.init({ mode: "bubble", tenant, getToken })      floating button + side drawer
  // getToken() returns the agent's current access token (or a Promise of it). It's called whenever
  // the console asks, and the token is only ever posted to the Quokra origin.

  var CONSOLE_STYLE_ID = "quokra-console-style";
  var ICON_HEADSET =
    "<svg " + SVG_ATTRS + '><path d="M3 11h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-5Zm0 0a9 9 0 1 1 18 0m0 0v5a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3Z"/>' +
    '<path d="M21 16v2a4 4 0 0 1-4 4h-5"/></svg>';

  var consoleState = null;
  var consoleListeners = {};

  function consoleEmit(event, value) {
    (consoleListeners[event] || []).forEach(function (fn) {
      try {
        fn(value);
      } catch (e) {
        // A broken listener on the host page shouldn't stop the console.
      }
    });
  }

  function addConsoleStyles() {
    if (document.getElementById(CONSOLE_STYLE_ID)) return;
    var css =
      ".quokra-console{display:block;width:100%;height:100%;min-height:480px;border:0}" +
      ".quokra-console-button{position:fixed;bottom:20px;width:56px;height:56px;padding:0;border:0;border-radius:50%;" +
      "background:" + DEFAULT_COLOR + ";color:#fff;cursor:pointer;z-index:2147483646;display:flex;align-items:center;" +
      "justify-content:center;box-shadow:0 6px 20px rgba(0,0,0,.2);transition:transform .2s ease}" +
      ".quokra-console-button:hover{transform:translateY(-2px)}" +
      ".quokra-console-button:focus-visible{outline:3px solid " + DEFAULT_COLOR + ";outline-offset:3px}" +
      ".quokra-console-button.right{right:20px}.quokra-console-button.left{left:20px}" +
      ".quokra-console-button svg{width:26px;height:26px}" +
      ".quokra-console-drawer{position:fixed;top:0;bottom:0;width:440px;max-width:100vw;height:100%;border:0;" +
      "background:#fff;box-shadow:0 0 40px rgba(0,0,0,.2);z-index:2147483647;visibility:hidden;" +
      "transition:transform .25s ease,visibility 0s linear .25s}" +
      ".quokra-console-drawer.right{right:0;transform:translateX(100%)}.quokra-console-drawer.left{left:0;transform:translateX(-100%)}" +
      ".quokra-console-drawer.open{visibility:visible;transform:none;transition:transform .25s ease}" +
      "@media (prefers-reduced-motion:reduce){.quokra-console-drawer,.quokra-console-drawer.open,.quokra-console-button{transition:none}}";
    var style = document.createElement("style");
    style.id = CONSOLE_STYLE_ID;
    style.textContent = css;
    document.head.appendChild(style);
  }

  function applyConsoleTheme() {
    var theme = resolveTheme(consoleState.theme);
    if (theme === consoleState.appliedTheme) return;
    consoleState.appliedTheme = theme;
    paintFrame(consoleState.frame, theme);
    postToConsole({ type: "theme.set", theme: theme });
  }

  function consoleUrl(tenant, mode, theme, extra) {
    return ORIGIN + "/console?tenant=" + encodeURIComponent(tenant || "") + "&mode=" + mode + "&theme=" + theme +
      hostParam() + (extra || "");
  }

  // fullView: the host's full-page console, as a URL or a function(conversationId). Single-page apps pass a
  // function so they can navigate with their own router.
  function expand(conversationId) {
    var fullView = consoleState && consoleState.fullView;
    var id = typeof conversationId === "string" && /^[0-9a-f-]{36}$/i.test(conversationId) ? conversationId : null;
    if (!fullView) return;
    agentConsole.close();
    consoleEmit("expand", id);
    if (typeof fullView === "function") {
      fullView(id);
    } else {
      var url = new URL(String(fullView), window.location.href);
      if (id) url.searchParams.set("conversation", id);
      window.location.assign(url.toString());
    }
  }

  function postToConsole(message) {
    if (consoleState && consoleState.frame.contentWindow) {
      message.source = SOURCE;
      consoleState.frame.contentWindow.postMessage(message, ORIGIN);
    }
  }

  function sendToken() {
    if (!consoleState) return;
    Promise.resolve()
      .then(consoleState.getToken)
      .then(function (token) {
        if (typeof token === "string" && token) postToConsole({ type: "auth.token", token: token });
      })
      .catch(function () {
        // The host couldn't provide a token; the console keeps showing "waiting for sign-in".
      });
  }

  function setConsoleBadge(count) {
    if (!consoleState || !consoleState.button) return;
    var badge = consoleState.button.querySelector(".quokra-badge");
    if (count > 0) {
      if (!badge) {
        badge = document.createElement("span");
        badge.className = "quokra-badge";
        consoleState.button.appendChild(badge);
      }
      badge.textContent = count > 99 ? "99+" : String(count);
      consoleState.button.setAttribute("aria-label", "Support console, " + count + " waiting");
    } else {
      if (badge) badge.remove();
      consoleState.button.setAttribute("aria-label", "Support console");
    }
  }

  // ---- Browser notifications for agents (opt-in with notifications: true) ----
  // The console decides what's worth telling the agent; this page shows it as a system notification,
  // since an embedded frame isn't allowed to ask for that permission itself.

  function notificationsAvailable() {
    return !!(consoleState && consoleState.notifications && "Notification" in window);
  }

  function tellConsoleAboutNotifications() {
    postToConsole({
      type: "console.notifications",
      available: notificationsAvailable(),
      permission: "Notification" in window ? Notification.permission : "denied",
    });
  }

  function text(value, max) {
    return typeof value === "string" ? value.slice(0, max) : "";
  }

  function showNotification(data) {
    var notice = {
      kind: text(data.kind, 20),
      title: text(data.title, 120) || "Support",
      body: text(data.body, 240),
      conversationId: /^[0-9a-f-]{36}$/i.test(data.conversationId || "") ? data.conversationId : null,
    };
    consoleEmit("notification", notice);
    if (!notificationsAvailable() || Notification.permission !== "granted") return;
    // In front of the agent already? The console's own toast and sound are enough.
    var consoleVisible = !document.hidden && document.hasFocus() &&
      !(consoleState.mode === "bubble" && !consoleState.open);
    if (consoleVisible) return;
    var shown = new Notification(notice.title, {
      body: notice.body,
      icon: ORIGIN + "/logo.png",
      tag: notice.conversationId || undefined,
    });
    shown.onclick = function () {
      window.focus();
      shown.close();
      if (!consoleState) return;
      if (consoleState.mode === "bubble") agentConsole.open();
      if (notice.conversationId) postToConsole({ type: "console.open", conversationId: notice.conversationId });
    };
  }

  function onConsoleMessage(event) {
    if (!consoleState) return;
    // Only trust our own frame, from our own origin, speaking our protocol.
    if (event.origin !== ORIGIN || event.source !== consoleState.frame.contentWindow) return;
    var data = event.data;
    if (!data || data.source !== SOURCE) return;

    if (data.type === "console.ready") {
      consoleState.ready = true;
      clearTimeout(consoleState.readyTimer);
      consoleState.frame.classList.remove("quokra-pending");
      if (consoleState.button) consoleState.button.classList.remove("quokra-pending");
      tellConsoleAboutNotifications();
      consoleEmit("ready");
    }
    else if (data.type === "auth.request") sendToken();
    else if (data.type === "console.color" && typeof data.color === "string" && /^#[0-9a-fA-F]{6}$/.test(data.color)) {
      // The company's colour, unless the dashboard chose its own accent.
      if (consoleState.button && !consoleState.accent) consoleState.button.style.background = data.color;
    }
    else if (data.type === "console.unread" && typeof data.count === "number") {
      setConsoleBadge(data.count);
      consoleEmit("unread", data.count);
    } else if (data.type === "console.notify") {
      showNotification(data);
    } else if (data.type === "console.notifications.check") {
      tellConsoleAboutNotifications();
    } else if (data.type === "console.notifications.request") {
      if (notificationsAvailable() && Notification.permission === "default") {
        Notification.requestPermission().then(tellConsoleAboutNotifications, tellConsoleAboutNotifications);
      } else {
        tellConsoleAboutNotifications();
      }
    } else if (data.type === "console.expand") {
      expand(data.conversationId);
    } else if (data.type === "console.close") {
      agentConsole.close();
      if (consoleState && consoleState.button) consoleState.button.focus();
    }
  }

  function createConsole(options, mode) {
    if (consoleState) return null;
    options = options || {};
    if (typeof options.getToken !== "function") throw new Error("Quokra: getToken is required");
    addStyles(); // badge styles
    addConsoleStyles();
    var frame = document.createElement("iframe");
    frame.id = "quokra-console-frame";
    frame.title = "Support console";
    var theme = themeOf(options.theme);
    var applied = resolveTheme(theme);
    var extra = accentParam(options.accent);
    if (mode === "bubble" && options.fullView) extra += "&expand=1";
    if (mode === "full" && typeof options.conversation === "string" && /^[0-9a-f-]{36}$/i.test(options.conversation)) {
      extra += "&conversation=" + options.conversation;
    }
    frame.src = consoleUrl(options.tenant, mode, applied, extra);
    paintFrame(frame, applied);
    if (theme === "host") watchHost();
    consoleState = { frame: frame, button: null, mode: mode, open: mode === "full", getToken: options.getToken,
      theme: theme, appliedTheme: applied, fullView: options.fullView || null, ready: false,
      notifications: options.notifications === true, accent: accentParam(options.accent) !== "" };
    window.addEventListener("message", onConsoleMessage);
    listenForEscape();
    consoleState.readyTimer = setTimeout(function () {
      if (consoleState && !consoleState.ready) consoleEmit("unavailable");
    }, READY_TIMEOUT_MS);
    return frame;
  }

  var agentConsole = {
    mount: function (target, options) {
      var container = typeof target === "string" ? document.querySelector(target) : target;
      if (!container) throw new Error("Quokra: console container not found");
      var frame = createConsole(options, "full");
      if (!frame) return;
      frame.className = "quokra-console quokra-pending";
      if (options && options.height) frame.style.height = options.height;
      container.appendChild(frame);
    },

    init: function (options) {
      options = options || {};
      if (options.mode && options.mode !== "bubble") throw new Error("Quokra: use mount() for a full-page console");
      var frame = createConsole(options, "bubble");
      if (!frame) return;
      var position = options.position === "left" ? "left" : "right";
      frame.className = "quokra-console-drawer quokra-pending " + position;
      document.body.appendChild(frame);

      var button = document.createElement("button");
      button.type = "button";
      button.className = "quokra-console-button quokra-pending " + position;
      button.setAttribute("aria-label", "Support console");
      button.setAttribute("aria-expanded", "false");
      button.setAttribute("aria-controls", frame.id);
      button.innerHTML = ICON_HEADSET;
      button.addEventListener("click", function () {
        consoleState.open ? agentConsole.close() : agentConsole.open();
      });
      document.body.appendChild(button);
      consoleState.button = button;
    },

    open: function () {
      if (!consoleState || consoleState.mode !== "bubble" || !consoleState.ready) return;
      consoleState.open = true;
      consoleState.frame.classList.add("open");
      consoleState.button.setAttribute("aria-expanded", "true");
      consoleState.frame.focus();
      consoleEmit("open");
    },

    close: function () {
      if (!consoleState || consoleState.mode !== "bubble") return;
      consoleState.open = false;
      consoleState.frame.classList.remove("open");
      consoleState.button.setAttribute("aria-expanded", "false");
      consoleEmit("close");
    },

    on: function (event, fn) {
      (consoleListeners[event] = consoleListeners[event] || []).push(fn);
    },

    setTheme: function (value) {
      if (!consoleState) return;
      consoleState.theme = themeOf(value);
      if (consoleState.theme === "host") watchHost();
      applyConsoleTheme();
    },

    unmount: function () {
      if (!consoleState) return;
      window.removeEventListener("message", onConsoleMessage);
      clearTimeout(consoleState.readyTimer);
      consoleState.frame.remove();
      if (consoleState.button) consoleState.button.remove();
      consoleState = null;
    },
  };

  window.Quokra.console = agentConsole;

  function autoInit() {
    if (script && script.dataset.quokraChat) {
      chat.init({
        slug: script.dataset.quokraChat,
        pk: script.dataset.quokraPk,
        position: script.dataset.quokraPosition,
        theme: script.dataset.quokraTheme,
        accent: script.dataset.quokraAccent,
        teaserDelay: script.dataset.quokraTeaserDelay,
      });
      return;
    }
    var inline = document.querySelector("[data-quokra-chat-inline]");
    if (inline) {
      chat.init({ slug: inline.dataset.quokraChatInline, pk: inline.dataset.quokraPk, mode: "inline", container: inline });
      return;
    }
    var trigger = document.querySelector("[data-quokra-chat-trigger]");
    if (trigger) {
      chat.init({ slug: trigger.dataset.quokraChatTrigger, pk: trigger.dataset.quokraPk, mode: "popup" });
      trigger.addEventListener("click", function () {
        chat.open();
      });
    }
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", autoInit);
  else autoInit();
})();
