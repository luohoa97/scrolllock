import type { Device, SessionRow, Store } from "./types";

/** In-memory Store for tests and local experiments. */
export class MemoryStore implements Store {
  sessions = new Map<string, SessionRow>();
  meta = new Map<string, string>();
  devices = new Map<string, Device>();

  async getSession(id: string) {
    const s = this.sessions.get(id);
    return s ? { ...s } : null;
  }
  async putSession(row: SessionRow) {
    this.sessions.set(row.sessionId, { ...row });
  }
  async listSessions() {
    return [...this.sessions.values()].map((s) => ({ ...s }));
  }
  async deleteSessionsIdleBefore(cutoff: number) {
    let n = 0;
    for (const [id, s] of this.sessions) {
      if (s.state === "idle" && s.lastSeen < cutoff) {
        this.sessions.delete(id);
        n++;
      }
    }
    return n;
  }
  async getMeta(key: string) {
    return this.meta.get(key) ?? null;
  }
  async setMeta(key: string, value: string) {
    this.meta.set(key, value);
  }
  async listDevices() {
    return [...this.devices.values()].map((d) => ({ ...d }));
  }
  async putDevice(device: Device) {
    this.devices.set(device.token, { ...device });
  }
  async deleteDevice(token: string) {
    this.devices.delete(token);
  }
}
