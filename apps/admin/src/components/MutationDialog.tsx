import { type FormEvent, type ReactNode, useEffect, useRef, useState } from "react";
import { AdminError, newIdempotencyKey } from "../lib/api";
import { ErrorBox } from "./ui";

/**
 * Diálogo de mutação auditada: exige motivo (≥ 3 caracteres) e mantém UMA chave de idempotência
 * enquanto estiver aberto — repetir após falha de rede não duplica a operação no servidor.
 */
export function MutationDialog({ title, description, confirmLabel = "Confirmar", danger, reasonLabel = "Motivo (fica na auditoria)",
  requireReason = true, children, canSubmit = true, submit, onClose, onDone }: {
  title: string;
  description?: ReactNode;
  confirmLabel?: string;
  danger?: boolean;
  reasonLabel?: string;
  /** false para ações sem motivo informado pelo operador (o servidor registra um padrão). */
  requireReason?: boolean;
  children?: ReactNode;
  canSubmit?: boolean;
  submit: (reason: string, idempotencyKey: string) => Promise<unknown>;
  onClose: () => void;
  onDone?: (result: unknown) => void;
}) {
  const [key] = useState(newIdempotencyKey);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const ref = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const d = ref.current;
    if (d && !d.open) d.showModal?.();
  }, []);

  const valid = (!requireReason || reason.trim().length >= 3) && canSubmit;

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!valid || busy) return;
    setBusy(true);
    setError(null);
    try {
      const result = await submit(reason.trim(), key);
      onDone?.(result);
      onClose();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  const retryable = error instanceof AdminError && (error.status === 0 || error.status >= 500);

  return (
    <dialog ref={ref} className="dialog" onCancel={(e) => { e.preventDefault(); if (!busy) onClose(); }}>
      <form onSubmit={onSubmit}>
        <h2>{title}</h2>
        {description && <div className="muted">{description}</div>}
        {children}
        {requireReason && (
          <label className="field">
            <span>{reasonLabel}</span>
            <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={3} maxLength={500} required minLength={3} autoFocus />
          </label>
        )}
        {error != null && <ErrorBox error={error} />}
        {retryable && <p className="muted small">Repetir usa a mesma chave de idempotência.</p>}
        <div className="row end">
          <button type="button" onClick={onClose} disabled={busy}>Cancelar</button>
          <button type="submit" className={danger ? "danger" : "primary"} disabled={!valid || busy}>
            {busy ? "Enviando…" : retryable ? "Repetir" : confirmLabel}
          </button>
        </div>
      </form>
    </dialog>
  );
}
