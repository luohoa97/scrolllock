const test = require("node:test");
const assert = require("node:assert/strict");
const G = require("../gate.js");

const base = { configured: true, fetchFailed: false, bypassRemainingMs: 0 };
const working = (leaseRemaining, receivedAt = 0) =>
  G.snapshotFromStatus({ working: true, unlockReason: "claude_busy", activeSessions: 1, leaseUntil: 1000 + leaseRemaining, serverTime: 1000 }, receivedAt);

test("open while the lease has time left", () => {
  const g = G.computeGate({ ...base, snap: working(60000), now: 10000 });
  assert.equal(g.kind, "open");
  assert.equal(g.remainingMs, 50000);
});

test("closes when the lease runs out with no newer status", () => {
  const g = G.computeGate({ ...base, snap: working(60000), now: 65000 });
  assert.equal(g.kind, "closed");
  assert.equal(g.reason, "lease_expired");
  assert.equal(g.elapsedMs, 5000);
});

test("a failed fetch after expiry says the relay is unreachable", () => {
  const g = G.computeGate({ ...base, fetchFailed: true, snap: working(1000), now: 5000 });
  assert.equal(g.reason, "relay_unreachable");
});

test("idle counts time since Claude stopped", () => {
  const snap = G.snapshotFromStatus({ working: false, since: 400, serverTime: 1000 }, 0);
  const g = G.computeGate({ ...base, snap, now: 2000 });
  assert.equal(g.kind, "closed");
  assert.equal(g.reason, "idle");
  assert.equal(g.elapsedMs, 600 + 2000);
});

test("no snapshot is closed, checking until a fetch fails", () => {
  assert.equal(G.computeGate({ ...base, snap: null, now: 0 }).reason, "checking");
  assert.equal(G.computeGate({ ...base, snap: null, fetchFailed: true, now: 0 }).reason, "relay_unreachable");
});

test("not configured is closed", () => {
  assert.equal(G.computeGate({ ...base, configured: false, snap: working(60000), now: 0 }).reason, "no_setup");
});

test("bypass wins", () => {
  const g = G.computeGate({ ...base, snap: null, now: 0, bypassRemainingMs: 1000 });
  assert.equal(g.kind, "bypass");
});

test("unlocked status without a lease uses the fallback", () => {
  const snap = G.snapshotFromStatus({ working: true, serverTime: 1000 }, 0);
  assert.equal(G.computeGate({ ...base, snap, now: 1000 }).kind, "open");
  assert.equal(G.computeGate({ ...base, snap, now: G.FALLBACK_LEASE_MS + 1 }).kind, "closed");
});

test("bypass is once per day and lasts five minutes", () => {
  assert.equal(G.canBypass(null, "2026-11-06"), true);
  const s = G.startBypass(1000, "2026-11-06");
  assert.equal(G.canBypass(s, "2026-11-06"), false);
  assert.equal(G.canBypass(s, "2026-11-07"), true);
  assert.equal(G.bypassRemaining(s, 1000 + 60000), 4 * 60000);
  assert.equal(G.bypassRemaining(s, 1000 + G.BYPASS_MS + 1), 0);
});

test("elapsed formatting matches the phone", () => {
  assert.equal(G.formatElapsed(45000), "45s");
  assert.equal(G.formatElapsed(724000), "12m 04s");
  assert.equal(G.formatElapsed((3 * 3600 + 12 * 60) * 1000), "3h 12m");
  assert.equal(G.formatElapsed((2 * 86400 + 3 * 3600) * 1000), "2d 3h");
  assert.equal(G.formatElapsed(-5), "0s");
});
