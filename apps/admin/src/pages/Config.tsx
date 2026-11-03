import { useMemo, useState } from "react";
import { MutationDialog } from "../components/MutationDialog";
import { DataTable, ErrorBox, JsonView, Loading, Section } from "../components/ui";
import { adminFetch } from "../lib/api";
import { dateTime } from "../lib/format";
import { useLoad } from "../lib/hooks";
import { allowed, type Role } from "../lib/roles";

type Values = Record<string, unknown>;
type ConfigResponse = {
  current: Values;
  versions: { version: number; effective_at: string; note: string | null; created_by: string | null; created_at: string; values: Values }[];
};

/**
 * Valida o patch contra a versão atual: só chaves existentes e do mesmo tipo JSON.
 * Espelha admin_api.publish_config (o servidor revalida).
 */
export function patchErrors(current: Values, patch: unknown): string[] {
  if (patch === null || typeof patch !== "object" || Array.isArray(patch)) return ["O patch deve ser um objeto JSON"];
  const errors: string[] = [];
  const kind = (v: unknown) => (v === null ? "null" : Array.isArray(v) ? "array" : typeof v);
  for (const [k, v] of Object.entries(patch)) {
    if (k === "_version" || !(k in current)) errors.push(`Chave desconhecida: ${k}`);
    else if (kind(v) !== kind(current[k])) errors.push(`Tipo inválido para ${k}: esperado ${kind(current[k])}`);
  }
  if (Object.keys(patch).length === 0) errors.push("Patch vazio");
  return errors;
}

export function Config({ roles }: { roles: Role[] }) {
  const data = useLoad(() => adminFetch<ConfigResponse>("/v1/config"), []);
  const [text, setText] = useState("{\n  \n}");
  const [effectiveAt, setEffectiveAt] = useState("");
  const [confirm, setConfirm] = useState(false);
  const canPublish = allowed(roles, []);

  const parsed = useMemo(() => {
    try { return { value: JSON.parse(text) as unknown }; } catch { return { error: "JSON inválido" }; }
  }, [text]);
  const errors = data.data ? ("error" in parsed ? [parsed.error!] : patchErrors(data.data.current, parsed.value)) : [];

  if (data.loading && !data.data) return <Loading />;
  if (data.error || !data.data) return <ErrorBox error={data.error} onRetry={data.reload} />;
  const { current, versions } = data.data;

  return (
    <>
      <h1>Parâmetros do jogo</h1>
      <p className="muted small">
        Versões são imutáveis. Operações já iniciadas guardam a versão vigente (propostas usam o snapshot da criação).
        Mudanças econômicas devem passar pelo simulador (tests/economy) antes de publicar.
      </p>
      <div className="grid">
        <Section title={`Vigente (versão ${String(current._version ?? "?")})`}>
          <JsonView value={current} />
        </Section>
        {canPublish && (
          <Section title="Nova versão">
            <label className="field"><span>Patch (somente chaves alteradas)</span>
              <textarea className="mono" rows={10} value={text} onChange={(e) => setText(e.target.value)} spellCheck={false} />
            </label>
            <label className="field"><span>Vigência (opcional, horário local)</span>
              <input type="datetime-local" value={effectiveAt} onChange={(e) => setEffectiveAt(e.target.value)} />
            </label>
            {errors.length > 0 && <ul className="bad-text small">{errors.map((e) => <li key={e}>{e}</li>)}</ul>}
            <button type="button" className="primary" disabled={errors.length > 0} onClick={() => setConfirm(true)}>Publicar…</button>
          </Section>
        )}
      </div>
      <Section title="Histórico">
        <DataTable rows={versions} rowKey={(v) => String(v.version)} columns={[
          { key: "version", label: "Versão", numeric: true },
          { key: "effective_at", label: "Vigência", render: (v) => dateTime(v.effective_at) },
          { key: "note", label: "Nota", render: (v) => v.note ?? "—" },
          { key: "values", label: "Valores", render: (v) => <details><summary>ver</summary><JsonView value={v.values} /></details> },
        ]} />
      </Section>
      {confirm && "value" in parsed && (
        <MutationDialog
          title="Publicar nova versão de parâmetros"
          description={<>Alterações: <code>{JSON.stringify(parsed.value)}</code>{effectiveAt && <> · vigência {dateTime(new Date(effectiveAt).toISOString())}</>}</>}
          reasonLabel="Nota da versão (fica na auditoria)"
          submit={(note) => adminFetch("/v1/config", {
            body: { patch: parsed.value, note, ...(effectiveAt ? { effective_at: new Date(effectiveAt).toISOString() } : {}) },
          })}
          onClose={() => setConfirm(false)} onDone={() => { setText("{\n  \n}"); setEffectiveAt(""); data.reload(); }} />
      )}
    </>
  );
}
