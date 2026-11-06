import { describe, expect, it } from "vitest";
import { LEASE_MS, STALE_RELAY_MS, TOOL_LEASE_MS } from "../lib/config";
import { computeStatus } from "../lib/unlock";
import type { SessionRow, UnlockConfig } from "../lib/types";

const T0 = 1_800_000_000_000;
const MIN = 60_000;

const busy: UnlockConfig = { mode: "claude_busy", myTurnMinutes: 15, graceSeconds: 10 };
const myTurn: UnlockConfig = { mode: "my_turn", myTurnMinutes: 15, graceSeconds: 10 };

function s(over: Partial<SessionRow> = {}): SessionRow {
  return {
    sessionId: "a",
    state: "working",
    lastSeen: T0,
    leaseUntil: T0 + LEASE_MS,
    workingSince: T0,
    lastUserPromptAt: T0,
    lastEvent: "working",
    ...over,
  };
}

describe("claude_busy", () => {
  it("is locked with no sessions", () => {
    const st = computeStatus([], T0, busy, null);
    expect(st.working).toBe(false);
    expect(st.unlockReason).toBe("locked:no_events_24h");
    expect(st.leaseUntil).toBeNull();
  });

  it("is locked with an idle table once events have been seen recently", () => {
    const st = computeStatus([], T0, busy, T0 - MIN);
    expect(st.working).toBe(false);
    expect(st.unlockReason).toBe("locked:idle");
    expect(st.relayStale).toBe(false);
  });

  it("unlocks while a session has an unexpired lease", () => {
    const st = computeStatus([s()], T0 + 30_000, busy, T0);
    expect(st.working).toBe(true);
    expect(st.activeSessions).toBe(1);
    expect(st.unlockReason).toBe("claude_busy:1 sessions");
    expect(st.since).toBe(T0);
    expect(st.leaseUntil).toBe(T0 + LEASE_MS);
  });

  it("locks exactly when the lease runs out (no Stop, no grace)", () => {
    const row = s({ lastEvent: "heartbeat" });
    expect(computeStatus([row], T0 + LEASE_MS - 1, busy, T0).working).toBe(true);
    expect(computeStatus([row], T0 + LEASE_MS, busy, T0).working).toBe(false);
  });

  it("a long tool lease keeps the phone unlocked through a silent 10 minute tool", () => {
    const row = s({ lastEvent: "heartbeat", leaseUntil: T0 + TOOL_LEASE_MS });
    expect(computeStatus([row], T0 + 9 * MIN, busy, T0).working).toBe(true);
    expect(computeStatus([row], T0 + 10 * MIN, busy, T0).working).toBe(false);
  });

  it("counts several sessions and uses the longest lease", () => {
    const rows = [s({ sessionId: "a" }), s({ sessionId: "b", leaseUntil: T0 + 5 * MIN, workingSince: T0 - MIN })];
    const st = computeStatus(rows, T0 + 1000, busy, T0);
    expect(st.activeSessions).toBe(2);
    expect(st.leaseUntil).toBe(T0 + 5 * MIN);
    expect(st.since).toBe(T0 - MIN);
  });

  it("ignores expired sessions when others are alive", () => {
    const rows = [s({ sessionId: "dead", leaseUntil: T0 - 1 }), s({ sessionId: "live" })];
    expect(computeStatus(rows, T0 + 1, busy, T0).activeSessions).toBe(1);
  });

  describe("idle grace", () => {
    const stopped = s({ state: "idle", lastEvent: "idle", lastSeen: T0, leaseUntil: T0, workingSince: null });

    it("keeps the phone unlocked for graceSeconds after Stop", () => {
      const st = computeStatus([stopped], T0 + 9_000, busy, T0);
      expect(st.working).toBe(true);
      expect(st.leaseUntil).toBe(T0 + 10_000);
      expect(st.activeSessions).toBe(0);
    });

    it("locks once the grace has passed", () => {
      const st = computeStatus([stopped], T0 + 10_000, busy, T0);
      expect(st.working).toBe(false);
      expect(st.unlockReason).toBe("locked:idle");
      expect(st.since).toBe(T0);
    });

    it("graceSeconds 0 locks immediately", () => {
      expect(computeStatus([stopped], T0, { ...busy, graceSeconds: 0 }, T0).working).toBe(false);
    });

    it("a hello (session start) never counts as busy", () => {
      const hello = s({ state: "idle", lastEvent: "hello", leaseUntil: T0, workingSince: null });
      expect(computeStatus([hello], T0 + 1, busy, T0).working).toBe(false);
    });
  });
});

describe("my_turn", () => {
  it("unlocks while busy and inside the prompt window", () => {
    const st = computeStatus([s()], T0 + 5 * MIN, myTurn, T0);
    // lease has expired at +90s in this fixture, so refresh it
    expect(st.working).toBe(false);
    const alive = s({ leaseUntil: T0 + 6 * MIN, lastEvent: "heartbeat" });
    const st2 = computeStatus([alive], T0 + 5 * MIN, myTurn, T0);
    expect(st2.working).toBe(true);
    expect(st2.unlockReason).toBe("my_turn:10m left");
    expect(st2.since).toBe(T0);
    expect(st2.leaseUntil).toBe(T0 + 6 * MIN);
  });

  it("locks when the prompt window ends even though Claude is still working", () => {
    const alive = s({ leaseUntil: T0 + 30 * MIN, lastEvent: "heartbeat" });
    const st = computeStatus([alive], T0 + 15 * MIN, myTurn, T0);
    expect(st.working).toBe(false);
    expect(st.unlockReason).toBe("locked:prompt_window_expired");
    expect(st.activeSessions).toBe(1);
  });

  it("caps the lease at the end of the prompt window", () => {
    const alive = s({ leaseUntil: T0 + 30 * MIN, lastEvent: "heartbeat" });
    expect(computeStatus([alive], T0 + MIN, myTurn, T0).leaseUntil).toBe(T0 + 15 * MIN);
  });

  it("stays locked when Claude is busy but no human prompt has ever been seen (harness wakes)", () => {
    const wake = s({ lastUserPromptAt: null });
    const st = computeStatus([wake], T0 + 1000, myTurn, T0);
    expect(st.working).toBe(false);
    expect(st.unlockReason).toBe("locked:prompt_window_expired");
  });

  it("uses the newest prompt across sessions", () => {
    const rows = [s({ sessionId: "a", lastUserPromptAt: T0 - 20 * MIN }), s({ sessionId: "b", lastUserPromptAt: T0 - MIN })];
    const st = computeStatus(rows, T0 + 1000, myTurn, T0);
    expect(st.working).toBe(true);
    expect(st.unlockReason).toBe("my_turn:14m left");
  });

  it("is locked when Claude is idle even inside the window", () => {
    const idle = s({ state: "idle", lastEvent: "idle", lastSeen: T0, leaseUntil: T0, workingSince: null });
    expect(computeStatus([idle], T0 + 60_000, myTurn, T0).working).toBe(false);
  });
});

describe("stale relay warning", () => {
  it("flags a relay that has not heard from hooks for a day", () => {
    expect(computeStatus([], T0, busy, T0 - STALE_RELAY_MS - 1).relayStale).toBe(true);
    expect(computeStatus([], T0, busy, T0 - STALE_RELAY_MS + 1).relayStale).toBe(false);
  });
});
