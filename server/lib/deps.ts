import { createFcmPusher, parseServiceAccount } from "./fcm";
import { createNeonStore } from "./neon-store";
import type { Deps } from "./handlers";

let cached: Deps | null = null;

/** Real dependencies from environment variables. Cached per warm function instance. */
export function liveDeps(): Deps {
  if (cached) return cached;
  const url = process.env.DATABASE_URL;
  if (!url) throw new Error("DATABASE_URL is not set");
  const sa = parseServiceAccount(process.env.FCM_SERVICE_ACCOUNT_JSON);
  cached = {
    store: createNeonStore(url),
    pusher: sa ? createFcmPusher(sa) : null,
    now: () => Date.now(),
    env: { WRITE_TOKEN: process.env.WRITE_TOKEN, READ_TOKEN: process.env.READ_TOKEN, CRON_SECRET: process.env.CRON_SECRET },
  };
  return cached;
}
