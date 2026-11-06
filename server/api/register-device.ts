import { handleRegisterDevice } from "../lib/handlers";
import { liveDeps } from "../lib/deps";

export async function POST(request: Request): Promise<Response> {
  return handleRegisterDevice(request, liveDeps());
}
