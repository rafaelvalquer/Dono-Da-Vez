import { type FormEvent, useState } from "react";
import { ErrorBox } from "../components/ui";
import { supabase } from "../lib/supabase";

export function Login() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error | null>(null);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const { error } = await supabase.auth.signInWithPassword({ email: email.trim(), password });
    setBusy(false);
    // Mensagem genérica: não revela se o e-mail existe.
    if (error) setError(new Error("E-mail ou senha inválidos, ou conta sem acesso."));
  }

  return (
    <form className="card narrow" onSubmit={onSubmit}>
      <h2>Entrar no painel</h2>
      <p className="muted small">Acesso restrito à equipe. O segundo fator (TOTP) é obrigatório.</p>
      <label className="field">
        <span>E-mail</span>
        <input type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} required />
      </label>
      <label className="field">
        <span>Senha</span>
        <input type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
      </label>
      {error && <ErrorBox error={error} />}
      <button type="submit" className="primary" disabled={busy}>{busy ? "Entrando…" : "Entrar"}</button>
    </form>
  );
}
