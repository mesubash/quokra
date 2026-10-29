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
 * JavaScript: Quokra.chat.init({ slug, pk, mode, container, position }), open(), close(), on(event, fn), destroy()
 */
(function () {
  "use strict";

  window.Quokra = window.Quokra || {};
  if (window.Quokra.chat) return;

  var script = document.currentScript;
  var ORIGIN = script && script.src ? new URL(script.src).origin : window.location.origin;
  var SOURCE = "quokra";
  var STYLE_ID = "quokra-chat-style";

  var state = null; // { frame, button, mode, open, color }
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

  function addStyles() {
    if (document.getElementById(STYLE_ID)) return;
    var css =
      ".quokra-button{position:fixed;bottom:20px;width:56px;height:56px;border:0;border-radius:50%;" +
      "background:#333;color:#fff;cursor:pointer;box-shadow:0 4px 14px rgba(0,0,0,.25);z-index:2147483646;" +
      "font:600 22px/56px system-ui,sans-serif;padding:0}" +
      ".quokra-button.right{right:20px}.quokra-button.left{left:20px}" +
      ".quokra-badge{position:absolute;top:-4px;right:-4px;min-width:20px;height:20px;padding:0 5px;border-radius:10px;" +
      "background:#cf222e;color:#fff;font:600 12px/20px system-ui,sans-serif}" +
      ".quokra-panel{position:fixed;bottom:88px;width:380px;height:560px;max-height:calc(100vh - 108px);border:0;" +
      "border-radius:12px;box-shadow:0 8px 30px rgba(0,0,0,.2);background:#fff;z-index:2147483647;display:none}" +
      ".quokra-panel.right{right:20px}.quokra-panel.left{left:20px}.quokra-panel.open{display:block}" +
      ".quokra-panel.popup{left:50%;right:auto;top:50%;bottom:auto;transform:translate(-50%,-50%)}" +
      "@media (max-width:480px){.quokra-panel{inset:0;width:100%;height:100%;max-height:none;border-radius:0;transform:none}}" +
      ".quokra-inline{display:block;width:100%;height:520px;border:1px solid #d0d7de;border-radius:12px}";
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
    } else if (badge) {
      badge.remove();
    }
  }

  function onMessage(event) {
    if (!state) return;
    // Only trust our own frame, from our own origin, speaking our protocol.
    if (event.origin !== ORIGIN || event.source !== state.frame.contentWindow) return;
    var data = event.data;
    if (!data || data.source !== SOURCE) return;

    if (data.type === "chat.ready") {
      if (typeof data.color === "string" && /^#[0-9a-fA-F]{6}$/.test(data.color) && state.button) {
        state.button.style.background = data.color;
      }
      if (state.open) post("chat.open");
      emit("ready");
    } else if (data.type === "chat.close") {
      chat.close();
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
      addStyles();

      var frame = document.createElement("iframe");
      frame.title = "Chat";
      frame.src = frameUrl(options.slug, options.pk, mode);
      state = { frame: frame, button: null, mode: mode, open: mode === "inline" };

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
          button.textContent = "?";
          button.addEventListener("click", function () {
            state.open ? chat.close() : chat.open();
          });
          document.body.appendChild(button);
          state.button = button;
        }
      }
      window.addEventListener("message", onMessage);
    },

    open: function () {
      if (!state || state.mode === "inline") return;
      state.open = true;
      state.frame.classList.add("open");
      if (state.button) state.button.setAttribute("aria-label", "Close chat");
      setBadge(0);
      post("chat.open");
      emit("open");
    },

    close: function () {
      if (!state || state.mode === "inline") return;
      state.open = false;
      state.frame.classList.remove("open");
      if (state.button) state.button.setAttribute("aria-label", "Open chat");
      post("chat.close");
      emit("close");
    },

    on: function (event, fn) {
      (listeners[event] = listeners[event] || []).push(fn);
    },

    destroy: function () {
      if (!state) return;
      window.removeEventListener("message", onMessage);
      state.frame.remove();
      if (state.button) state.button.remove();
      state = null;
    },
  };

  window.Quokra.chat = chat;

  function autoInit() {
    if (script && script.dataset.quokraChat) {
      chat.init({ slug: script.dataset.quokraChat, pk: script.dataset.quokraPk, position: script.dataset.quokraPosition });
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
