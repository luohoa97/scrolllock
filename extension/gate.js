// The same gate the Android app uses, in plain JS. Every duration is measured on a monotonic clock
// at the moment the status arrives, so the system clock cannot extend a lease or shorten a lock.
(function (root) {
  const FALLBACK_LEASE_MS = 90000;
  const BYPASS_MS = 5 * 60000;

  /** receivedAt is performance.now() when the response arrived. */
  function snapshotFromStatus(s, receivedAt) {
    return {
      working: !!s.working,
      reason: s.unlockReason || "",
      sessions: s.activeSessions || 0,
      sinceAgoMs: s.since != null ? Math.max(0, s.serverTime - s.since) : null,
      leaseRemainingMs: s.leaseUntil != null ? Math.max(0, s.leaseUntil - s.serverTime) : null,
      receivedAt,
    };
  }

  /** Returns { kind: "open" | "closed" | "bypass", reason, elapsedMs, remainingMs }. */
  function computeGate({ configured, snap, fetchFailed, now, bypassRemainingMs }) {
    if (bypassRemainingMs > 0) return { kind: "bypass", remainingMs: bypassRemainingMs };
    if (!configured) return { kind: "closed", reason: "no_setup", elapsedMs: null };
    if (!snap) return { kind: "closed", reason: fetchFailed ? "relay_unreachable" : "checking", elapsedMs: null };

    const sinceReceipt = Math.max(0, now - snap.receivedAt);
    if (snap.working) {
      const lease = snap.leaseRemainingMs != null ? snap.leaseRemainingMs : FALLBACK_LEASE_MS;
      const left = lease - sinceReceipt;
      if (left > 0) return { kind: "open", reason: snap.reason, sessions: snap.sessions, remainingMs: left };
      return { kind: "closed", reason: fetchFailed ? "relay_unreachable" : "lease_expired", elapsedMs: -left };
    }
    return { kind: "closed", reason: "idle", elapsedMs: (snap.sinceAgoMs || 0) + sinceReceipt };
  }

  /** 45s, 12m 04s, 3h 12m, 2d 3h. */
  function formatElapsed(ms) {
    const s = Math.max(0, Math.floor(ms / 1000));
    const d = Math.floor(s / 86400);
    const h = Math.floor((s % 86400) / 3600);
    const m = Math.floor((s % 3600) / 60);
    const sec = s % 60;
    if (s < 60) return sec + "s";
    if (s < 3600) return m + "m " + String(sec).padStart(2, "0") + "s";
    if (s < 86400) return h + "h " + String(m).padStart(2, "0") + "m";
    return d + "d " + h + "h";
  }

  /** One bypass per calendar day, per device. */
  function dayKey(date) {
    const d = date || new Date();
    return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0");
  }
  function canBypass(state, today) { return !state || state.day !== today; }
  function startBypass(nowMs, today) { return { until: nowMs + BYPASS_MS, day: today }; }
  function bypassRemaining(state, nowMs) { return state ? Math.max(0, state.until - nowMs) : 0; }

  const api = { snapshotFromStatus, computeGate, formatElapsed, dayKey, canBypass, startBypass, bypassRemaining, FALLBACK_LEASE_MS, BYPASS_MS };
  root.ScrollLockGate = api;
  if (typeof module !== "undefined") module.exports = api;
})(typeof globalThis !== "undefined" ? globalThis : this);
