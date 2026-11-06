import { handleEvent } from "../lib/handlers";
import { liveDeps } from "../lib/deps";

export async function POST(request: Request): Promise<Response> {
  return handleEvent(request, liveDeps());
}
