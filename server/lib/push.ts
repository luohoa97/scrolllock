import { META_LAST_EVENT_AT, PUSH_EXTEND_MS } from "./config";
import { computeStatus } from "./unlock";
import type { Pusher } from "./fcm";
import type { Device, Store } from "./types";

/**
 * Push to every device whose view of the world changed.
 * Pushes when `working` flips, or when an unlocked lease extends by more than PUSH_EXTEND_MS.
 * Locking after a silent death is covered by the `leaseUntil` the client already holds;
 * the per-minute cron calls this too, as a backstop.
 */
export async function pushChanges(store: Store, pusher: Pusher | null, now: number): Promise<number> {
  if (!pusher) return 0;
  const devices = await store.listDevices();
  if (devices.length === 0) return 0;
  const sessions = await store.listSessions();
  const last = await store.getMeta(META_LAST_EVENT_AT);
  const lastEventAt = last === null ? null : Number(last);

  let sent = 0;
  for (const d of devices) {
    const status = computeStatus(sessions, now, { mode: d.mode, myTurnMinutes: d.myTurnMinutes, graceSeconds: d.graceSeconds }, lastEventAt);
    const flipped = d.lastPushWorking !== status.working;
    const extended =
      status.working && status.leaseUntil !== null && (d.lastPushLeaseUntil === null || status.leaseUntil - d.lastPushLeaseUntil > PUSH_EXTEND_MS);
    if (!flipped && !extended) continue;

    const result = await pusher.send(d.token, status);
    if (result === "unregistered") {
      await store.deleteDevice(d.token);
      continue;
    }
    if (result === "sent") {
      sent++;
      const next: Device = { ...d, lastPushWorking: status.working, lastPushLeaseUntil: status.leaseUntil, lastPushAt: now };
      await store.putDevice(next);
    }
  }
  return sent;
}
