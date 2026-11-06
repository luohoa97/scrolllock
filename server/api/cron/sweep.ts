import { handleSweep } from "../../lib/handlers";
import { liveDeps } from "../../lib/deps";

export async function GET(request: Request): Promise<Response> {
  return handleSweep(request, liveDeps());
}
