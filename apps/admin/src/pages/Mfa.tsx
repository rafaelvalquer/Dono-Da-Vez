import { type FormEvent, useEffect, useState } from "react";
import { ErrorBox, Loading } from "../components/ui";
import { supabase } from "../lib/supabase";

type Enrollment = { factorId: string; qr: string; secret: string };

/** Verifica o fator TOTP existente ou cadastra o primeiro. O servidor exige aal2 em toda rota. */
export function Mfa({ onVerified }: { onVerified: () => void }) {
  const [factorId, setFactorId] = useState<string | null>(null);
  const [enrollment, setEnrollment] = useState<Enrollment | null>(null);
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    (async () => {
      const { data, error } = await supabase.auth.mfa.listFactors();
      if (error) { setError(error); setReady(true); return; }
      const verified = data.totp.find((f) => f.status === "verified");
      if (verified) { setFactorId(verified.id); setReady(true); return; }
      // Remove cadastros incompletos antes de criar outro.
      for (const f of data.all.filter((f) => f.factor_type === "totp" && f.status !== "verified")) {
        await supabase.auth.mfa.unenroll({ factorId: f.id });
      }
      const enrolled = await supabase.auth.mfa.enroll({ factorType: "totp", friendlyName: `painel-${Date.now()}` });
      if (enrolled.error) setError(enrolled.error);
      else setEnrollment({ factorId: enrolled.data.id, qr: enrolled.data.totp.qr_code, secret: enrolled.data.totp.secret });
      setReady(true);
    })();
  }, []);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    const id = factorId ?? enrollment?.factorId;
    if (!id) return;
    setBusy(true);
    setError(null);
    const { error } = await supabase.auth.mfa.challengeAndVerify({ factorId: id, code: code.trim() });
    setBusy(false);
    if (error) { setError(new Error("Código inválido ou expirado.")); return; }
    onVerified();
  }

  if (!ready) return <Loading />;
  return (
    <form className="card narrow" onSubmit={onSubmit}>
      <h2>Segundo fator</h2>
      {enrollment ? (
        <>
          <p className="muted small">Cadastre o painel no seu aplicativo autenticador (TOTP) e informe o código de 6 dígitos.</p>
          <img className="qr" src={enrollment.qr} alt="QR code para cadastrar o autenticador" width={180} height={180} />
          <p className="small">Chave manual: <code>{enrollment.secret}</code></p>
        </>
      ) : (
        <p className="muted small">Informe o código atual do seu aplicativo autenticador.</p>
      )}
      <label className="field">
        <span>Código</span>
        <input inputMode="numeric" autoComplete="one-time-code" pattern="\d{6}" maxLength={6} value={code}
               onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} required autoFocus />
      </label>
      {error && <ErrorBox error={error} />}
      <div className="row">
        <button type="submit" className="primary" disabled={busy || code.length !== 6}>{busy ? "Verificando…" : "Verificar"}</button>
        <button type="button" className="link" onClick={() => void supabase.auth.signOut()}>Sair</button>
      </div>
    </form>
  );
}
