const $ = (id) => document.getElementById(id);
const KEYS = ["relayUrl", "readToken", "mode", "minutes", "grace"];

chrome.storage.local.get(KEYS, (v) => {
  $("relayUrl").value = v.relayUrl || "";
  $("readToken").value = v.readToken || "";
  $("mode").value = v.mode || "claude_busy";
  $("minutes").value = v.minutes || 15;
  $("grace").value = v.grace || 10;
});

$("save").addEventListener("click", async () => {
  const msg = $("msg");
  let origin;
  try {
    origin = new URL($("relayUrl").value.trim()).origin;
  } catch (_) {
    msg.textContent = "That relay URL isn't valid.";
    return;
  }
  // The service worker needs permission for the relay's origin; asked here so it's tied to a click.
  const granted = await chrome.permissions.request({ origins: [origin + "/*"] });
  if (!granted) {
    msg.textContent = "Permission for the relay was declined, so ScrollLock can't check Claude.";
    return;
  }
  const cfg = {
    relayUrl: origin,
    readToken: $("readToken").value.trim(),
    mode: $("mode").value,
    minutes: Math.min(240, Math.max(1, Number($("minutes").value) || 15)),
    grace: Math.min(120, Math.max(0, Number($("grace").value) || 0)),
  };
  await chrome.storage.local.set(cfg);
  msg.textContent = "Testing…";
  const res = await chrome.runtime.sendMessage({ type: "status", config: cfg });
  msg.textContent = res && res.ok
    ? "Connected. Claude is " + (res.status.working ? "working" : "idle") + " right now."
    : "Couldn't connect: " + ((res && res.error) || "no answer");
});
