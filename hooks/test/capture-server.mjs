// Tiny HTTP server for hook tests. Appends each request as one JSON line to the file in argv[2].
import { createServer } from "node:http";
import { appendFileSync } from "node:fs";
const out = process.argv[2];
const srv = createServer((req, res) => {
  let body = "";
  req.on("data", (d) => (body += d));
  req.on("end", () => {
    appendFileSync(out, JSON.stringify({ url: req.url, auth: req.headers.authorization, body }) + "\n");
    res.writeHead(200, { "content-type": "application/json" }).end('{"ok":true}');
  });
});
srv.listen(0, "127.0.0.1", () => console.log(srv.address().port));
