import react from "@vitejs/plugin-react";
import { loadEnv, type Plugin } from "vite";
import { defineConfig } from "vitest/config";

// CSP só no build (o servidor de desenvolvimento injeta scripts inline de HMR).
// O host estático de produção deve enviar a mesma política como cabeçalho (ver README).
function csp(...urls: (string | undefined)[]): Plugin {
  const origin = [...new Set(urls.filter((u) => u).map((u) => new URL(u!).origin))].join(" ");
  const policy = [
    "default-src 'self'",
    `connect-src 'self' ${origin}`.trim(),
    "img-src 'self' data:",
    "style-src 'self' 'unsafe-inline'",
    "script-src 'self'",
    "frame-ancestors 'none'",
    "base-uri 'none'",
    "form-action 'none'",
  ].join("; ");
  return {
    name: "dv-csp",
    apply: "build",
    transformIndexHtml: () => [{ tag: "meta", attrs: { "http-equiv": "Content-Security-Policy", content: policy }, injectTo: "head-prepend" }],
  };
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, ".", "VITE_");
  return {
    plugins: [react(), csp(env.VITE_SUPABASE_URL, env.VITE_ADMIN_API_URL)],
    build: { sourcemap: false, target: "es2022" },
    test: { environment: "node", include: ["src/**/*.test.ts"] },
  };
});
