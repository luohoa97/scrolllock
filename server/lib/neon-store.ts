import { neon } from "@neondatabase/serverless";
import type { Device, EventName, Mode, SessionRow, SessionState, Store } from "./types";

const ms = (v: unknown): number | null => (v === null || v === undefined ? null : new Date(v as string | Date).getTime());
const iso = (v: number | null): string | null => (v === null ? null : new Date(v).toISOString());

interface SessionDb {
  session_id: string;
  state: string;
  last_seen: string | Date;
  lease_until: string | Date;
  working_since: string | Date | null;
  last_user_prompt_at: string | Date | null;
  last_event: string;
}
interface DeviceDb {
  token: string;
  mode: string;
  my_turn_minutes: number;
  grace_seconds: number;
  last_push_working: boolean | null;
  last_push_lease_until: string | Date | null;
  last_push_at: string | Date | null;
}

const toSession = (r: SessionDb): SessionRow => ({
  sessionId: r.session_id,
  state: r.state as SessionState,
  lastSeen: ms(r.last_seen) as number,
  leaseUntil: ms(r.lease_until) as number,
  workingSince: ms(r.working_since),
  lastUserPromptAt: ms(r.last_user_prompt_at),
  lastEvent: r.last_event as EventName,
});
const toDevice = (r: DeviceDb): Device => ({
  token: r.token,
  mode: r.mode as Mode,
  myTurnMinutes: r.my_turn_minutes,
  graceSeconds: r.grace_seconds,
  lastPushWorking: r.last_push_working,
  lastPushLeaseUntil: ms(r.last_push_lease_until),
  lastPushAt: ms(r.last_push_at),
});

/** Neon Postgres over HTTP (works in serverless without connection pooling headaches). */
export function createNeonStore(databaseUrl: string): Store {
  const sql = neon(databaseUrl);
  return {
    async getSession(id) {
      const rows = (await sql`select * from sessions where session_id = ${id}`) as SessionDb[];
      return rows[0] ? toSession(rows[0]) : null;
    },
    async putSession(r) {
      await sql`
        insert into sessions (session_id, state, last_seen, lease_until, working_since, last_user_prompt_at, last_event)
        values (${r.sessionId}, ${r.state}, ${iso(r.lastSeen)}, ${iso(r.leaseUntil)}, ${iso(r.workingSince)}, ${iso(r.lastUserPromptAt)}, ${r.lastEvent})
        on conflict (session_id) do update set
          state = excluded.state, last_seen = excluded.last_seen, lease_until = excluded.lease_until,
          working_since = excluded.working_since, last_user_prompt_at = excluded.last_user_prompt_at,
          last_event = excluded.last_event`;
    },
    async listSessions() {
      const rows = (await sql`select * from sessions`) as SessionDb[];
      return rows.map(toSession);
    },
    async deleteSessionsIdleBefore(cutoff) {
      const rows = (await sql`delete from sessions where state = 'idle' and last_seen < ${iso(cutoff)} returning session_id`) as unknown[];
      return rows.length;
    },
    async getMeta(key) {
      const rows = (await sql`select value from meta where key = ${key}`) as { value: string }[];
      return rows[0]?.value ?? null;
    },
    async setMeta(key, value) {
      await sql`insert into meta (key, value) values (${key}, ${value}) on conflict (key) do update set value = excluded.value`;
    },
    async listDevices() {
      const rows = (await sql`select * from devices`) as DeviceDb[];
      return rows.map(toDevice);
    },
    async putDevice(d) {
      await sql`
        insert into devices (token, mode, my_turn_minutes, grace_seconds, last_push_working, last_push_lease_until, last_push_at)
        values (${d.token}, ${d.mode}, ${d.myTurnMinutes}, ${d.graceSeconds}, ${d.lastPushWorking}, ${iso(d.lastPushLeaseUntil)}, ${iso(d.lastPushAt)})
        on conflict (token) do update set
          mode = excluded.mode, my_turn_minutes = excluded.my_turn_minutes, grace_seconds = excluded.grace_seconds,
          last_push_working = excluded.last_push_working, last_push_lease_until = excluded.last_push_lease_until,
          last_push_at = excluded.last_push_at`;
    },
    async deleteDevice(token) {
      await sql`delete from devices where token = ${token}`;
    },
  };
}
