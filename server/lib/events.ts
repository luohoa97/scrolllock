import { LEASE_MS, META_LAST_EVENT_AT, TOOL_LEASE_MS } from "./config";
import type { EventName, SessionRow, Store } from "./types";

/**
 * Apply one hook event to the store. Heartbeats are idempotent upserts that set
 * `working`, so a dropped first event repairs itself on the next tool call.
 */
export async function applyEvent(
  store: Store,
  ev: { sessionId: string; event: EventName; human?: boolean },
  now: number,
): Promise<SessionRow> {
  const prev = await store.getSession(ev.sessionId);
  const wasWorking = prev !== null && prev.state === "working" && prev.leaseUntil > now;
  const keepSince = wasWorking ? (prev.workingSince ?? prev.lastSeen) : now;

  let row: SessionRow;
  switch (ev.event) {
    case "hello":
      row = prev ?? {
        sessionId: ev.sessionId,
        state: "idle",
        lastSeen: now,
        leaseUntil: now,
        workingSince: null,
        lastUserPromptAt: null,
        lastEvent: "hello",
      };
      row = { ...row, lastEvent: prev ? prev.lastEvent : "hello" };
      break;
    case "working":
      row = {
        sessionId: ev.sessionId,
        state: "working",
        lastSeen: now,
        leaseUntil: now + LEASE_MS,
        workingSince: keepSince,
        lastUserPromptAt: ev.human ? now : (prev?.lastUserPromptAt ?? null),
        lastEvent: "working",
      };
      break;
    case "heartbeat":
      row = {
        sessionId: ev.sessionId,
        state: "working",
        lastSeen: now,
        leaseUntil: now + TOOL_LEASE_MS,
        workingSince: keepSince,
        lastUserPromptAt: prev?.lastUserPromptAt ?? null,
        lastEvent: "heartbeat",
      };
      break;
    case "tool_done":
      if (!prev || !wasWorking) return prev ?? emptyIdle(ev.sessionId, now); // late PostToolUse after Stop: ignore
      row = { ...prev, lastSeen: now, leaseUntil: now + LEASE_MS, lastEvent: "tool_done" };
      break;
    case "idle":
    case "end":
      row = {
        sessionId: ev.sessionId,
        state: "idle",
        lastSeen: now,
        leaseUntil: now,
        workingSince: null,
        lastUserPromptAt: prev?.lastUserPromptAt ?? null,
        lastEvent: ev.event,
      };
      break;
  }

  await store.putSession(row);
  await store.setMeta(META_LAST_EVENT_AT, String(now));
  return row;
}

function emptyIdle(sessionId: string, now: number): SessionRow {
  return { sessionId, state: "idle", lastSeen: now, leaseUntil: now, workingSince: null, lastUserPromptAt: null, lastEvent: "hello" };
}
