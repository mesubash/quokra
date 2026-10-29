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
 *             on(event, fn), destroy()
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
  var DEFAULT_COLOR = "#4c4fba";
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

  function frameUrl(slug, pk, mode) {
    return ORIGIN + "/w/" + encodeURIComponent(slug) + "?pk=" + encodeURIComponent(pk) + "&mode=" + mode;
  }

  function post(type) {
    if (state && state.frame.contentWindow) {
      state.frame.contentWindow.postMessage({ source: SOURCE, type: type }, ORIGIN);
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
    teaser.className = "quokra-teaser " + state.position;
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
      if (state.open) post("chat.open");
      emit("ready");
    } else if (data.type === "chat.close") {
      chat.close();
      if (state && state.button) state.button.focus();
    } else if (data.type === "chat.unread" && typeof data.count === "number") {
      setBadge(data.count);
      emit("unread", data.count);
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
      frame.src = frameUrl(options.slug, options.pk, mode);
      state = {
        frame: frame,
        button: null,
        teaser: null,
        mode: mode,
        position: position,
        pk: options.pk,
        open: mode === "inline",
        teaserText: null,
        animation: null,
        teaserDelay: options.teaserDelay === "off" ? -1 : isFinite(delay) && options.teaserDelay !== undefined ? delay : DEFAULT_TEASER_DELAY,
      };

      if (mode === "inline") {
        var container =
          typeof options.container === "string" ? document.querySelector(options.container) : options.container;
        if (!container) throw new Error("Quokra: container not found");
        frame.className = "quokra-inline";
        container.appendChild(frame);
      } else {
        frame.className = "quokra-panel " + (mode === "popup" ? "popup" : position);
        document.body.appendChild(frame);
        if (mode === "bubble") {
          var button = document.createElement("button");
          button.type = "button";
          button.className = "quokra-button " + position;
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

          if (document.readyState === "complete") setTimeout(startAnimation, 300);
          else window.addEventListener("load", function () { setTimeout(startAnimation, 300); }, { once: true });
        }
      }
      window.addEventListener("message", onMessage);
    },

    open: function () {
      if (!state || state.mode === "inline") return;
      state.open = true;
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

    on: function (event, fn) {
      (listeners[event] = listeners[event] || []).push(fn);
    },

    destroy: function () {
      if (!state) return;
      window.removeEventListener("message", onMessage);
      hideTeaser(false);
      if (state.animation) state.animation.destroy();
      state.frame.remove();
      if (state.button) state.button.remove();
      state = null;
    },
  };

  window.Quokra.chat = chat;

  function autoInit() {
    if (script && script.dataset.quokraChat) {
      chat.init({
        slug: script.dataset.quokraChat,
        pk: script.dataset.quokraPk,
        position: script.dataset.quokraPosition,
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
