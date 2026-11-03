import { createClient } from "@supabase/supabase-js";

const url = import.meta.env.VITE_SUPABASE_URL as string | undefined;
const key = import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY as string | undefined;

export const configError =
  !url || !key ? "Defina VITE_SUPABASE_URL e VITE_SUPABASE_PUBLISHABLE_KEY em apps/admin/.env.local (ver .env.example)." : null;

export const environment = (import.meta.env.VITE_DV_ENVIRONMENT as string | undefined) ?? "local";

export const adminApiUrl =
  ((import.meta.env.VITE_ADMIN_API_URL as string | undefined) || `${(url ?? "").replace(/\/$/, "")}/functions/v1/admin`).replace(/\/$/, "");

export const publishableKey = key ?? "";

// Sessão em sessionStorage: some ao fechar a aba (painel com privilégios).
export const supabase = createClient(url ?? "http://invalid.local", key ?? "invalid", {
  auth: { storage: window.sessionStorage, persistSession: true, autoRefreshToken: true, detectSessionInUrl: false },
});
