import { useCallback, useEffect, useState } from "react";

/** Rota em hash (#/users/<id>): sem servidor de rotas, sem dados sensíveis na URL além de ids. */
export function parseHash(hash: string): string[] {
  const path = hash.replace(/^#\/?/, "").split("?")[0] ?? "";
  const parts = path.split("/").filter(Boolean).map((p) => decodeURIComponent(p));
  return parts.length ? parts : ["overview"];
}

export function href(...parts: string[]): string {
  return "#/" + parts.map(encodeURIComponent).join("/");
}

export function useRoute(): string[] {
  const [parts, setParts] = useState(() => parseHash(window.location.hash));
  useEffect(() => {
    const onChange = () => setParts(parseHash(window.location.hash));
    window.addEventListener("hashchange", onChange);
    return () => window.removeEventListener("hashchange", onChange);
  }, []);
  return parts;
}

export type Loaded<T> = { data?: T; error?: Error; loading: boolean; reload: () => void };

/** Carrega dados ao montar e quando `deps` mudam; respostas atrasadas de cargas antigas são descartadas. */
export function useLoad<T>(fn: () => Promise<T>, deps: unknown[]): Loaded<T> {
  const [tick, setTick] = useState(0);
  const [state, setState] = useState<{ data?: T; error?: Error; loading: boolean }>({ loading: true });
  useEffect(() => {
    let alive = true;
    setState((s) => ({ data: s.data, loading: true }));
    fn().then(
      (data) => alive && setState({ data, loading: false }),
      (error: Error) => alive && setState({ error, loading: false }),
    );
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);
  const reload = useCallback(() => setTick((t) => t + 1), []);
  return { ...state, reload };
}

/** Encerra a sessão após inatividade (painel com privilégios). */
export function startIdleTimer(ms: number, onIdle: () => void): () => void {
  let timer = window.setTimeout(onIdle, ms);
  const reset = () => {
    window.clearTimeout(timer);
    timer = window.setTimeout(onIdle, ms);
  };
  const events = ["pointerdown", "keydown", "wheel", "touchstart"] as const;
  events.forEach((e) => window.addEventListener(e, reset, { passive: true }));
  return () => {
    window.clearTimeout(timer);
    events.forEach((e) => window.removeEventListener(e, reset));
  };
}
