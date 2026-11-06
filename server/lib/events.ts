import { LEASE_MS, META_LAST_EVENT_AT, TOOL_LEASE_MS } from "./config";
import type { EventName, SessionRow, Store } from "./types";

/**
 * Apply one hook event to the store. Heartbeats are idempotent upserts that set
 * `working`, so a dropped first event repairs itself on the next tool call.
 */
export async function applyEvent(
  store: Store,
  ev: { sessionId: string; event: EventName; human?: boolean; leaseMs?: number; agent?: string | null; reason?: string | null; label?: string | null; tasks?: { done: number; total: number; now: string | null; next: string | null } },
  now: number,
): Promise<SessionRow> {
  const prev = await store.getSession(ev.sessionId);
  const wasWorking = prev !== null && prev.state === "working" && prev.leaseUntil > now;
  const keepSince = wasWorking ? (prev.workingSince ?? prev.lastSeen) : now;
  const lease = ev.leaseMs ?? LEASE_MS;
  // A task list outlives a single turn, so every event except `end` carries the last one forward.
  const carry = {
    agent: ev.agent ?? prev?.agent ?? null,
    tasksDone: prev?.tasksDone ?? null,
    tasksTotal: prev?.tasksTotal ?? null,
    taskNow: prev?.taskNow ?? null,
    taskNext: prev?.taskNext ?? null,
  };
  // A pending "waiting on you" survives beats, heartbeats and progress, and ends when the human answers:
  // a typed prompt, a tool that then ran (a permission was granted), the turn ending, or an explicit resolved.
  const keepNeeds = { needsYouAt: prev?.needsYouAt ?? null, needsReason: prev?.needsReason ?? null, needsLabel: prev?.needsLabel ?? null };
  const noNeeds = { needsYouAt: null, needsReason: null, needsLabel: null };

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
        leaseUntil: now + lease,
        workingSince: keepSince,
        lastUserPromptAt: ev.human ? now : (prev?.lastUserPromptAt ?? null),
        lastEvent: "working",
        ...carry,
        ...(ev.human ? noNeeds : keepNeeds),
      };
      break;
    case "heartbeat":
      row = {
        sessionId: ev.sessionId,
        state: "working",
        lastSeen: now,
        leaseUntil: now + (ev.leaseMs ?? TOOL_LEASE_MS),
        workingSince: keepSince,
        lastUserPromptAt: prev?.lastUserPromptAt ?? null,
        lastEvent: "heartbeat",
        ...carry,
        ...keepNeeds,
      };
      break;
    case "tool_done":
      if (!prev || !wasWorking) return prev ?? emptyIdle(ev.sessionId, now); // late PostToolUse after Stop: ignore
      row = { ...prev, ...carry, ...noNeeds, lastSeen: now, leaseUntil: now + lease, lastEvent: "tool_done" };
      break;
    case "beat":
      // A keep-alive from the hook's background loop. It only extends a session that is already
      // working: a stray beat after Stop, or from a dead session's leftover loop, can't unlock anything.
      if (!prev || !wasWorking) return prev ?? emptyIdle(ev.sessionId, now);
      row = { ...prev, ...carry, lastSeen: now, leaseUntil: now + lease, lastEvent: "beat" };
      break;
    case "progress":
      // The agent updated its task list. Like a beat it only counts for a session that is already working.
      if (!prev || !wasWorking) return prev ?? emptyIdle(ev.sessionId, now);
      row = {
        ...prev,
        ...carry,
        lastSeen: now,
        leaseUntil: now + lease,
        lastEvent: "progress",
        tasksDone: ev.tasks?.done ?? null,
        tasksTotal: ev.tasks?.total ?? null,
        taskNow: ev.tasks?.now ?? null,
        taskNext: ev.tasks?.next ?? null,
      };
      break;
    case "needs_you":
      // Blocked on the human. Works for a session that isn't working too (the coordinator), and changes nothing else about it.
      row = { ...(prev ?? emptyIdle(ev.sessionId, now)), ...carry, lastSeen: now, needsYouAt: now, needsReason: ev.reason ?? "other", needsLabel: ev.label ?? null, lastEvent: "needs_you" };
      break;
    case "resolved":
      if (!prev) return emptyIdle(ev.sessionId, now);
      row = { ...prev, ...carry, ...noNeeds, lastSeen: now, lastEvent: "resolved" };
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
        ...noNeeds,
        ...(ev.event === "idle" ? carry : { agent: carry.agent, tasksDone: null, tasksTotal: null, taskNow: null, taskNext: null }),
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
