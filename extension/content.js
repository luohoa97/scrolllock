// Blocks Reels and Explore on instagram.com while Claude is idle. Messages are never touched.
// Reads only location.pathname, never page content.
(() => {
  const Gate = globalThis.ScrollLockGate;
  const Route = globalThis.ScrollLockRoute;

  const DEFAULTS = { relayUrl: "", readToken: "", mode: "claude_busy", minutes: 15, grace: 10, blockReels: true, blockExplore: true, bypass: null };
  let cfg = { ...DEFAULTS };
  let snap = null;
  let fetchFailed = false;
  let lastFetch = 0;
  let inFlight = false;
  let host = null;
  let els = null;
  let pauseTimer = null;

  const configured = () => !!(cfg.relayUrl && cfg.readToken);

  function loadConfig() {
    chrome.storage.local.get(Object.keys(DEFAULTS), (v) => {
      cfg = { ...DEFAULTS, ...v };
      snap = null;
      lastFetch = 0;
      tick();
    });
  }
  chrome.storage.onChanged.addListener(loadConfig);
  loadConfig();

  function refresh() {
    if (inFlight || !configured()) return;
    inFlight = true;
    lastFetch = performance.now();
    chrome.runtime.sendMessage(
      { type: "status", config: { relayUrl: cfg.relayUrl, readToken: cfg.readToken, mode: cfg.mode, minutes: cfg.minutes, grace: cfg.grace } },
      (res) => {
        inFlight = false;
        if (chrome.runtime.lastError || !res || !res.ok) {
          fetchFailed = true;
        } else {
          fetchFailed = false;
          snap = Gate.snapshotFromStatus(res.status, performance.now());
        }
        tick();
      },
    );
  }

  function tick() {
    if (document.visibilityState !== "visible") return;
    if (!Route.isBlocked(location.pathname, cfg)) {
      hideOverlay();
      return;
    }
    const now = performance.now();
    const gate = Gate.computeGate({
      configured: configured(),
      snap,
      fetchFailed,
      now,
      bypassRemainingMs: Gate.bypassRemaining(cfg.bypass, Date.now()),
    });
    // Ask the relay often while locked so an unlock lands fast, rarely while open.
    const every = gate.kind === "closed" ? 3000 : 20000;
    if (configured() && now - lastFetch >= every) refresh();

    if (gate.kind === "closed") showOverlay(gate);
    else hideOverlay();
  }

  setInterval(tick, 1000);
  document.addEventListener("visibilitychange", tick);
  if (globalThis.navigation) globalThis.navigation.addEventListener("navigatesuccess", () => setTimeout(tick, 50));

  // Overlay

  const SUBTITLE = {
    no_setup: "Setup isn't finished",
    relay_unreachable: "Can't reach Claude's status",
    checking: "Checking Claude…",
  };

  function showOverlay(gate) {
    if (!host) buildOverlay();
    els.sub.textContent = SUBTITLE[gate.reason] || "Claude isn't working";
    els.time.textContent = gate.elapsedMs != null ? Gate.formatElapsed(gate.elapsedMs) : "";
    els.time.style.display = gate.elapsedMs != null ? "" : "none";
    els.bypass.style.display = Gate.canBypass(cfg.bypass, Gate.dayKey()) ? "" : "none";
    if (!host.isConnected) (document.documentElement || document).appendChild(host);
    if (!pauseTimer) {
      pauseMedia();
      pauseTimer = setInterval(pauseMedia, 400);
    }
  }

  function hideOverlay() {
    if (host && host.isConnected) host.remove();
    if (pauseTimer) { clearInterval(pauseTimer); pauseTimer = null; }
  }

  // Reels autoplay with sound; the blur would hide the picture but not stop the audio.
  function pauseMedia() {
    document.querySelectorAll("video, audio").forEach((m) => {
      try { m.muted = true; m.pause(); } catch (_) {}
    });
  }

  function buildOverlay() {
    host = document.createElement("div");
    host.id = "scrolllock-overlay";
    const root = host.attachShadow({ mode: "open" });
    root.innerHTML = `
      <style>
        :host { all: initial; }
        .wrap { position: fixed; inset: 0; z-index: 2147483647; display: flex; align-items: center; justify-content: center;
          background: rgba(11,11,11,.6); -webkit-backdrop-filter: blur(24px); backdrop-filter: blur(24px);
          font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; color: #fff;
          animation: in .14s ease-out; }
        @media (prefers-color-scheme: light) { .wrap { background: rgba(255,255,255,.6); color: #111; } }
        @media (prefers-reduced-motion: reduce) { .wrap { animation: none; } }
        @keyframes in { from { opacity: 0 } to { opacity: 1 } }
        .col { display: flex; flex-direction: column; align-items: center; text-align: center; gap: 6px; padding: 24px; }
        h1 { margin: 0; font-size: 24px; font-weight: 600; }
        .sub { font-size: 16px; opacity: .72; }
        .time { font-size: 14px; opacity: .72; font-variant-numeric: tabular-nums; }
        .row { display: flex; gap: 8px; margin-top: 24px; }
        a, button { font: inherit; cursor: pointer; border-radius: 24px; min-height: 44px; padding: 0 20px; display: inline-flex;
          align-items: center; text-decoration: none; border: 0; }
        a.primary { background: currentColor; }
        a.primary span { color: #000; mix-blend-mode: difference; }
        button, a.quiet { background: transparent; color: inherit; font-size: 14px; }
        button:hover, a.quiet:hover { background: rgba(127,127,127,.2); }
      </style>
      <div class="wrap" role="dialog" aria-label="Instagram locked">
        <div class="col">
          <h1>Instagram locked</h1>
          <div class="sub"></div>
          <div class="time"></div>
          <div class="row">
            <a class="quiet" href="/direct/inbox/">Messages</a>
            <button class="bypass" type="button">Bypass 5 min</button>
          </div>
        </div>
      </div>`;
    els = { sub: root.querySelector(".sub"), time: root.querySelector(".time"), bypass: root.querySelector(".bypass") };
    els.bypass.addEventListener("click", () => {
      const today = Gate.dayKey();
      if (!Gate.canBypass(cfg.bypass, today)) return;
      const state = Gate.startBypass(Date.now(), today);
      cfg.bypass = state;
      chrome.storage.local.set({ bypass: state });
      tick();
    });
  }
})();
