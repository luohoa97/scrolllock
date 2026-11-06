import { describe, expect, it } from "vitest";
import { LEASE_MS, TOOL_LEASE_MS } from "../lib/config";
import { applyEvent } from "../lib/events";
import { MemoryStore } from "../lib/memory-store";
import { computeStatus } from "../lib/unlock";

const T0 = 1_800_000_000_000;
const cfg = { mode: "claude_busy", myTurnMinutes: 15, graceSeconds: 10 } as const;

async function setup() {
  return new MemoryStore();
}
const status = async (st: MemoryStore, now: number) =>
  computeStatus(await st.listSessions(), now, cfg, Number(await st.getMeta("lastEventAt")));

describe("applyEvent", () => {
  it("working creates a session with a 90s lease and records the human prompt", async () => {
    const st = await setup();
    const row = await applyEvent(st, { sessionId: "a", event: "working", human: true }, T0);
    expect(row.state).toBe("working");
    expect(row.leaseUntil).toBe(T0 + LEASE_MS);
    expect(row.lastUserPromptAt).toBe(T0);
    expect(row.workingSince).toBe(T0);
  });

  it("a non-human working event keeps the earlier prompt time", async () => {
    const st = await setup();
    await applyEvent(st, { sessionId: "a", event: "working", human: true }, T0);
    const row = await applyEvent(st, { sessionId: "a", event: "working", human: false }, T0 + 1000);
    expect(row.lastUserPromptAt).toBe(T0);
  });

  it("heartbeat repairs a dropped first event", async () => {
    const st = await setup();
    const row = await applyEvent(st, { sessionId: "a", event: "heartbeat" }, T0);
    expect(row.state).toBe("working");
    expect(row.leaseUntil).toBe(T0 + TOOL_LEASE_MS);
    expect((await status(st, T0 + 1000)).working).toBe(true);
  });

  it("keeps workingSince across heartbeats", async () => {
    const st = await setup();
    await applyEvent(st, { sessionId: "a", event: "working", human: true }, T0);
    const row = await applyEvent(st, { sessionId: "a", event: "heartbeat" }, T0 + 30_000);
    expect(row.workingSince).toBe(T0);
  });

  it("tool_done shortens the long tool lease back to 90s", async () => {
    const st = await setup();
    await applyEvent(st, { sessionId: "a", event: "heartbeat" }, T0);
    const row = await applyEvent(st, { sessionId: "a", event: "tool_done" }, T0 + 5000);
    expect(row.leaseUntil).toBe(T0 + 5000 + LEASE_MS);
  });

  it("a late tool_done after Stop does not revive the session", async () => {
    const st = await setup();
    await applyEvent(st, { sessionId: "a", event: "working" }, T0);
    await applyEvent(st, { sessionId: "a", event: "idle" }, T0 + 1000);
    await applyEvent(st, { sessionId: "a", event: "tool_done" }, T0 + 1500);
    expect((await st.getSession("a"))?.state).toBe("idle");
  });

  it("idle then working quickly stays unlocked (grace)", async () => {
    const st = await setup();
    await applyEvent(st, { sessionId: "a", event: "working", human: true }, T0);
    await applyEvent(st, { sessionId: "a", event: "idle" }, T0 + 5000);
    expect((await status(st, T0 + 8000)).working).toBe(true);
    await applyEvent(st, { sessionId: "a", event: "working", human: true }, T0 + 9000);
    expect((await status(st, T0 + 60_000)).working).toBe(true);
  });

  it("idle locks after grace", async () => {
    const st = await setup();
    await applyEvent(st, { sessionId: "a", event: "working" }, T0);
    await applyEvent(st, { sessionId: "a", event: "idle" }, T0 + 5000);
    expect((await status(st, T0 + 16_000)).working).toBe(false);
  });

  it("a dead session (no Stop) is locked 90s after its last event", async () => {
    const st = await setup();
    await applyEvent(st, { sessionId: "a", event: "working" }, T0);
    await applyEvent(st, { sessionId: "a", event: "tool_done" }, T0 + 20_000);
    expect((await status(st, T0 + 20_000 + LEASE_MS - 1)).working).toBe(true);
    expect((await status(st, T0 + 20_000 + LEASE_MS)).working).toBe(false);
  });

  it("hello records lastEventAt but never unlocks", async () => {
    const st = await setup();
    await applyEvent(st, { sessionId: "a", event: "hello" }, T0);
    const s1 = await status(st, T0 + 1);
    expect(s1.working).toBe(false);
    expect(s1.relayStale).toBe(false);
  });

  it("sweep removes only old idle rows", async () => {
    const st = await setup();
    await applyEvent(st, { sessionId: "old", event: "idle" }, T0);
    await applyEvent(st, { sessionId: "live", event: "working" }, T0);
    expect(await st.deleteSessionsIdleBefore(T0 + 1000)).toBe(1);
    expect(await st.getSession("live")).not.toBeNull();
  });
});
