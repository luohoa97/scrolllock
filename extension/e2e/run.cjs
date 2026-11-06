// Loads the unpacked extension in real Chromium against a fake relay and a stubbed instagram.com.
// Run: node e2e/run.cjs   (needs Playwright; prints PASS/FAIL per check and exits non-zero on any FAIL)
const { chromium } = require("playwright");
const http = require("node:http");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

let relay = { working: false, since: Date.now() - 5000, leaseMs: 0 };
const server = http.createServer((req, res) => {
  if (!req.url.startsWith("/api/status")) { res.writeHead(404).end(); return; }
  if (req.headers.authorization !== "Bearer test-read-token") { res.writeHead(401).end(); return; }
  const now = Date.now();
  const body = relay.working
    ? { working: true, activeSessions: 1, unlockReason: "claude_busy", since: relay.since, leaseUntil: now + relay.leaseMs, lastEventAt: now, relayStale: false, serverTime: now }
    : { working: false, activeSessions: 0, unlockReason: "idle", since: relay.since, leaseUntil: null, lastEventAt: relay.since, relayStale: false, serverTime: now };
  res.writeHead(200, { "content-type": "application/json", "access-control-allow-origin": "*" });
  res.end(JSON.stringify(body));
});

let failed = 0;
const check = (name, ok, extra = "") => { console.log((ok ? "PASS " : "FAIL ") + name + (extra ? "  " + extra : "")); if (!ok) failed++; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitFor(fn, ms) { const end = Date.now() + ms; while (Date.now() < end) { if (await fn()) return Date.now(); await sleep(150); } return null; }

const PAGE = `<!doctype html><title>stub</title><body style="background:linear-gradient(135deg,#f09433,#bc1888);margin:0;height:100vh">
<h1 style="color:#fff;padding:40px">Fake Instagram</h1><video id="v" muted loop autoplay src="data:video/mp4;base64,"></video></body>`;

(async () => {
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const relayUrl = "http://127.0.0.1:" + server.address().port;
  const ext = path.resolve(__dirname, "..");
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "sl-e2e-"));
  const ctx = await chromium.launchPersistentContext(dir, {
    headless: false,
    args: ["--headless=new", "--disable-extensions-except=" + ext, "--load-extension=" + ext, "--no-sandbox"],
    viewport: { width: 1000, height: 700 },
  });
  await ctx.route("https://www.instagram.com/**", (route) => route.fulfill({ status: 200, contentType: "text/html", body: PAGE }));

  let sw = ctx.serviceWorkers()[0] || (await ctx.waitForEvent("serviceworker"));
  await sw.evaluate((cfg) => chrome.storage.local.set(cfg), { relayUrl, readToken: "test-read-token", mode: "claude_busy", minutes: 15, grace: 10, blockReels: true, blockExplore: true, bypassEnabled: false });

  const page = await ctx.newPage();
  const overlayUp = () => page.evaluate(() => !!document.getElementById("scrolllock-overlay"));

  // 1. Idle on Reels: overlay appears with the three lines.
  await page.goto("https://www.instagram.com/reels/");
  check("overlay appears on /reels/ while Claude is idle", !!(await waitFor(overlayUp, 4000)));
  const part = (sel) => page.evaluate((q) => document.getElementById("scrolllock-overlay")?.shadowRoot?.querySelector(q)?.textContent?.trim() || "", sel);
  // The first fetch takes a moment; until it lands the subtitle reads "Checking Claude…".
  await waitFor(async () => (await part(".sub")) === "Claude isn't working", 5000);
  check("overlay line 1 is Instagram locked", (await part("h1")) === "Instagram locked");
  check("overlay line 2 is Claude isn't working", (await part(".sub")) === "Claude isn't working", JSON.stringify(await part(".sub")));
  const elapsed = await part(".time");
  check("overlay line 3 is the time elapsed", /^\d+s$|^\d+m \d\d s$|^\d+m \d\ds$/.test(elapsed) || /^\d+(s|m \d{2}s|h \d{2}m)$/.test(elapsed), JSON.stringify(elapsed));
  const all = await page.evaluate(() => document.getElementById("scrolllock-overlay").shadowRoot.querySelector(".wrap").innerText);
  check("overlay has no Claude button", !/Back to Claude/i.test(all));
  await page.screenshot({ path: path.join(os.tmpdir(), "scrolllock-overlay.png") });

  // 2. Claude starts working: overlay should drop within about 4s (3s poll + 1s tick).
  relay = { working: true, since: Date.now(), leaseMs: 60000 };
  const t0 = Date.now();
  const gone = await waitFor(async () => !(await overlayUp()), 8000);
  check("overlay lifts after Claude starts working", !!gone, gone ? `in ${((gone - t0) / 1000).toFixed(1)}s` : "never");

  // 3. Claude stops: overlay returns.
  relay = { working: false, since: Date.now(), leaseMs: 0 };
  const t1 = Date.now();
  // The open gate polls every 20s, so allow up to the lease-free poll plus a tick.
  const back = await waitFor(overlayUp, 25000);
  check("overlay returns after Claude stops", !!back, back ? `in ${((back - t1) / 1000).toFixed(1)}s` : "never");

  // 4. DMs and normal pages are never blocked, even while idle.
  await page.goto("https://www.instagram.com/direct/inbox/");
  await sleep(2500);
  check("no overlay on /direct/inbox/", !(await overlayUp()));
  await page.goto("https://www.instagram.com/");
  await sleep(1500);
  check("no overlay on the home feed", !(await overlayUp()));

  // 5. Explore is blocked too.
  await page.goto("https://www.instagram.com/explore/");
  check("overlay appears on /explore/", !!(await waitFor(overlayUp, 4000)));

  // 6. No bypass button by default; turn it on and it lifts the lock, once.
  const hasBtn = () => page.evaluate(() => getComputedStyle(document.getElementById("scrolllock-overlay").shadowRoot.querySelector(".bypass")).display !== "none");
  check("no bypass button by default", !(await hasBtn()));
  await sw.evaluate(() => chrome.storage.local.set({ bypassEnabled: true }));
  check("bypass button appears once enabled", !!(await waitFor(hasBtn, 4000)));
  await page.evaluate(() => document.getElementById("scrolllock-overlay").shadowRoot.querySelector(".bypass").click());
  check("bypass lifts the overlay", !!(await waitFor(async () => !(await overlayUp()), 3000)));
  const stored = await sw.evaluate(() => chrome.storage.local.get("bypass"));
  check("bypass is recorded for today", !!stored.bypass && stored.bypass.day.length === 10);

  await ctx.close();
  server.close();
  console.log(failed ? `\n${failed} check(s) FAILED` : "\nall checks passed");
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(2); });
