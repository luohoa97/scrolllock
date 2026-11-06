export type Mode = "claude_busy" | "my_turn";
export type SessionState = "working" | "idle";

/** What the hooks send. `human` is true when the prompt looks typed by a person. */
export type EventName = "hello" | "working" | "heartbeat" | "beat" | "progress" | "tool_done" | "idle" | "end";
export const EVENT_NAMES: readonly EventName[] = ["hello", "working", "heartbeat", "beat", "progress", "tool_done", "idle", "end"];

/** One working agent session. Counts are null until it reports a task list; the titles exist only if its sender opted in to sharing text. */
export interface AgentProgress {
  agent: string;
  done: number | null;
  total: number | null;
  now: string | null;
  next: string | null;
}

/** All times are epoch milliseconds. */
export interface SessionRow {
  sessionId: string;
  state: SessionState;
  lastSeen: number;
  leaseUntil: number;
  workingSince: number | null;
  lastUserPromptAt: number | null;
  lastEvent: EventName;
  agent?: string | null;
  tasksDone?: number | null;
  tasksTotal?: number | null;
  taskNow?: string | null;
  taskNext?: string | null;
}

export interface UnlockConfig {
  mode: Mode;
  myTurnMinutes: number;
  graceSeconds: number;
}

export interface Status {
  /** True when the phone should be unlocked. */
  working: boolean;
  activeSessions: number;
  unlockReason: string;
  /** When the current unlocked/locked period started, if known. */
  since: number | null;
  /** While unlocked: the moment the client should lock itself unless a newer push arrives. */
  leaseUntil: number | null;
  /** Newest event of any kind, for the "hooks look dead" warning. */
  lastEventAt: number | null;
  relayStale: boolean;
  serverTime: number;
  /** Every session working right now, newest first: which agent, and its task progress if it reports any. Never sent in pushes. */
  agents: AgentProgress[];
}

export interface Device {
  token: string;
  mode: Mode;
  myTurnMinutes: number;
  graceSeconds: number;
  lastPushWorking: boolean | null;
  lastPushLeaseUntil: number | null;
  lastPushAt: number | null;
}

export interface Store {
  getSession(id: string): Promise<SessionRow | null>;
  putSession(row: SessionRow): Promise<void>;
  listSessions(): Promise<SessionRow[]>;
  deleteSessionsIdleBefore(cutoff: number): Promise<number>;
  getMeta(key: string): Promise<string | null>;
  setMeta(key: string, value: string): Promise<void>;
  listDevices(): Promise<Device[]>;
  putDevice(device: Device): Promise<void>;
  deleteDevice(token: string): Promise<void>;
}
