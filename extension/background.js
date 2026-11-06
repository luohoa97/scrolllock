// Fetches the relay status for the content script. The service worker is the one with host
// permission for the relay, so the page's own CORS rules never come into it.
chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg && msg.type === "status") {
    fetchStatus(msg.config).then(sendResponse);
    return true; // async response
  }
  return false;
});

async function fetchStatus(cfg) {
  try {
    const url = new URL("/api/status", cfg.relayUrl);
    url.searchParams.set("mode", cfg.mode || "claude_busy");
    url.searchParams.set("minutes", String(cfg.minutes || 15));
    url.searchParams.set("grace", String(cfg.grace || 10));
    const res = await fetch(url, {
      headers: { authorization: "Bearer " + cfg.readToken },
      signal: AbortSignal.timeout(5000),
    });
    if (!res.ok) return { ok: false, error: "Relay answered " + res.status };
    return { ok: true, status: await res.json() };
  } catch (e) {
    return { ok: false, error: String(e && e.message ? e.message : e) };
  }
}
