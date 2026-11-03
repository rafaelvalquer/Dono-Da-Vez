// Proxy de falhas entre o cliente e a Edge Function. Quando armado para uma rota, repassa a requisição,
// espera a resposta do servidor (a operação já foi confirmada no banco) e então derruba a conexão ("drop") ou
// segura a resposta além do timeout do cliente ("delay"). Reproduz a falha mais perigosa para dinheiro:
// o servidor gravou, o app não soube.
import http from "node:http";

const HOP = new Set(["host", "connection", "content-length", "keep-alive", "transfer-encoding"]);
const SKIP_RES = new Set(["content-length", "content-encoding", "transfer-encoding", "connection", "keep-alive"]);

export async function startFaultProxy(upstreamBase) {
  const upstream = new URL(upstreamBase);
  const rules = [];

  const server = http.createServer(async (req, res) => {
    res.on("error", () => {});
    try {
      const chunks = [];
      for await (const c of req) chunks.push(c);
      const body = Buffer.concat(chunks);
      const i = rules.findIndex((r) => r.method === req.method && new URL(req.url, "http://x").pathname.endsWith(r.path));
      const rule = i >= 0 ? rules.splice(i, 1)[0] : null;

      const headers = Object.fromEntries(Object.entries(req.headers).filter(([k]) => !HOP.has(k)));
      const up = await fetch(new URL(req.url, upstream.origin), { method: req.method, headers, body: body.length ? body : undefined });
      const buf = Buffer.from(await up.arrayBuffer());
      if (rule) {
        let parsed = null;
        try { parsed = JSON.parse(buf.toString("utf8")); } catch { /* corpo não-JSON */ }
        rule.resolve({ status: up.status, body: parsed });
      }
      if (rule?.action === "drop") {
        req.socket.destroy();
        return;
      }
      if (rule?.action === "delay") await new Promise((r) => setTimeout(r, rule.ms));
      if (req.socket.destroyed) return;
      const out = {};
      up.headers.forEach((v, k) => { if (!SKIP_RES.has(k)) out[k] = v; });
      res.writeHead(up.status, out);
      res.end(buf);
    } catch (e) {
      if (!res.headersSent && !req.socket.destroyed) {
        res.writeHead(502, { "content-type": "application/json" });
        res.end(JSON.stringify({ ok: false, error: { code: "PROXY", message: String(e?.message ?? e) } }));
      }
    }
  });

  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const { port } = server.address();

  return {
    /** Base equivalente à da API, passando pelo proxy. */
    baseUrl: `http://127.0.0.1:${port}${upstream.pathname.replace(/\/$/, "")}`,
    /**
     * Arma uma falha para a próxima requisição `method` cujo caminho termina em `path`.
     * Devolve uma promessa com a resposta que o servidor produziu (e o cliente não recebeu).
     */
    arm({ method, path, action, ms = 0 }) {
      return new Promise((resolve) => rules.push({ method, path, action, ms, resolve }));
    },
    async close() {
      server.closeAllConnections?.();
      await new Promise((r) => server.close(r));
    },
  };
}
