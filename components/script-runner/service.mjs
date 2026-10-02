import http from 'node:http';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

export function createService() {
  let busy = false;
  const script = fileURLToPath(new URL('./sandbox.mjs', import.meta.url));
  return http.createServer(async (req, res) => {
    const reply = (status, body) => { res.writeHead(status, {'Content-Type': 'application/json'}); res.end(body); };
    if (req.url === '/health' && req.method === 'GET') return reply(200, '{"healthy":true}');
    if (req.url !== '/evaluate' || req.method !== 'POST') return reply(404, '{"error":"Not found"}');
    if (busy) return reply(503, '{"error":"Sandbox capacity exhausted"}');
    busy = true;
    let child;
    let deadline;
    try {
      let size = 0;
      const chunks = [];
      for await (const chunk of req) {
        size += chunk.length;
        if (size > 2_097_152) throw new Error('Input limit');
        chunks.push(chunk);
      }
      const input = Buffer.concat(chunks);
      child = spawn(process.execPath, ['--permission', `--allow-fs-read=${script}`, '--max-old-space-size=32', script], {
        stdio: ['pipe', 'pipe', 'ignore'], env: { PATH: process.env.PATH },
      });
      const result = await new Promise((resolve, reject) => {
        let bytes = 0;
        const output = [];
        deadline = setTimeout(() => { child.kill('SIGKILL'); reject(new Error('Deadline')); }, 2000);
        child.on('error', reject);
        child.stdin.on('error', reject);
        child.stdout.on('data', chunk => {
          bytes += chunk.length;
          if (bytes > 65_536) { child.kill('SIGKILL'); reject(new Error('Output limit')); }
          else output.push(chunk);
        });
        child.on('close', () => resolve(Buffer.concat(output).toString()));
        child.stdin.end(input);
      });
      JSON.parse(result);
      reply(200, result);
    } catch {
      child?.kill('SIGKILL');
      reply(400, '{"error":"Script failed or exceeded its limits"}');
    } finally { clearTimeout(deadline); busy = false; }
  });
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const server = createService();
  server.requestTimeout = 3000;
  server.headersTimeout = 3000;
  server.listen(Number(process.env.PORT ?? 8081), '0.0.0.0');
}
