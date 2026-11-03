import type { Session } from "@supabase/supabase-js";
import { type ReactNode, useEffect, useState } from "react";
import { ErrorBox, Loading } from "./components/ui";
import { adminFetch } from "./lib/api";
import { href, startIdleTimer, useLoad, useRoute } from "./lib/hooks";
import { allowed, type Role } from "./lib/roles";
import { configError, environment, supabase } from "./lib/supabase";
import { Login } from "./pages/Login";
import { Mfa } from "./pages/Mfa";
import { Overview } from "./pages/Overview";
import { Search } from "./pages/Search";
import { UserDetail } from "./pages/UserDetail";
import { PropertyDetail } from "./pages/PropertyDetail";
import { Alerts, Reports, ReviewCases, RiskReviews } from "./pages/Queues";
import { Proposals } from "./pages/Proposals";
import { Imports, Regions } from "./pages/Geo";
import { Economy } from "./pages/Economy";
import { Config } from "./pages/Config";
import { Audit } from "./pages/Audit";

type WhoAmI = { profile: { id: string; nickname: string }; roles: Role[] };

const IDLE_MS = 15 * 60 * 1000;

type NavItem = { path: string; label: string; roles: Role[] };
const NAV: NavItem[] = [
  { path: "overview", label: "Operação", roles: ["support", "finance", "geo"] },
  { path: "search", label: "Buscar", roles: ["support", "finance", "geo"] },
  { path: "review-cases", label: "Revisão geográfica", roles: ["geo", "support"] },
  { path: "reports", label: "Relatos", roles: ["support", "geo"] },
  { path: "proposals", label: "Propostas", roles: ["support", "finance"] },
  { path: "risk", label: "Risco", roles: ["finance", "support"] },
  { path: "alerts", label: "Conciliação", roles: ["finance", "support"] },
  { path: "imports", label: "Importações", roles: ["geo"] },
  { path: "regions", label: "Regiões", roles: ["geo", "support"] },
  { path: "economy", label: "Economia", roles: ["finance"] },
  { path: "config", label: "Configuração", roles: ["finance"] },
  { path: "audit", label: "Auditoria", roles: [] },
];

export function App() {
  const [session, setSession] = useState<Session | null | undefined>(undefined);
  const [aal, setAal] = useState<string | null>(null);

  useEffect(() => {
    if (configError) return;
    supabase.auth.getSession().then(({ data }) => setSession(data.session));
    const { data } = supabase.auth.onAuthStateChange((_e, s) => setSession(s));
    return () => data.subscription.unsubscribe();
  }, []);

  useEffect(() => {
    if (!session) { setAal(null); return; }
    supabase.auth.mfa.getAuthenticatorAssuranceLevel().then(({ data }) => setAal(data?.currentLevel ?? "aal1"));
  }, [session]);

  useEffect(() => {
    if (!session) return;
    return startIdleTimer(IDLE_MS, () => void supabase.auth.signOut());
  }, [session]);

  if (configError) return <Shell><ErrorBox error={new Error(configError)} /></Shell>;
  if (session === undefined || (session && aal === null)) return <Shell><Loading /></Shell>;
  if (!session) return <Shell><Login /></Shell>;
  if (aal !== "aal2") return <Shell><Mfa onVerified={() => setAal("aal2")} /></Shell>;
  return <Console />;
}

function Shell({ children }: { children: ReactNode }) {
  return (
    <main className="auth-shell">
      <h1>Dono da Vez <span className="env">{environment}</span></h1>
      {children}
    </main>
  );
}

function Console() {
  const me = useLoad(() => adminFetch<WhoAmI>("/v1/whoami"), []);
  const route = useRoute();

  if (me.loading && !me.data) return <Shell><Loading /></Shell>;
  if (me.error || !me.data) return <Shell><ErrorBox error={me.error} onRetry={me.reload} /><SignOut /></Shell>;
  const roles = me.data.roles;
  if (roles.length === 0) {
    return <Shell><ErrorBox error={new Error("Esta conta não tem papel administrativo ativo.")} /><SignOut /></Shell>;
  }
  const nav = NAV.filter((n) => allowed(roles, n.roles));

  return (
    <div className="layout">
      <aside className="sidebar">
        <div className="brand">Dono da Vez <span className="env">{environment}</span></div>
        <nav>
          {nav.map((n) => (
            <a key={n.path} href={href(n.path)} className={route[0] === n.path ? "active" : undefined}>{n.label}</a>
          ))}
        </nav>
        <div className="me">
          <div>{me.data.profile.nickname}</div>
          <div className="muted small">{roles.join(", ")}</div>
          <SignOut />
        </div>
      </aside>
      <main className="content">
        <Page route={route} roles={roles} />
      </main>
    </div>
  );
}

function SignOut() {
  return <button type="button" className="link" onClick={() => void supabase.auth.signOut()}>Sair</button>;
}

function Page({ route, roles }: { route: string[]; roles: Role[] }) {
  const [head, id] = route;
  const item = NAV.find((n) => n.path === head);
  if (item && !allowed(roles, item.roles)) return <ErrorBox error={new Error("Sem permissão para esta área.")} />;
  switch (head) {
    case "overview": return <Overview />;
    case "search": return <Search />;
    case "users": return id ? <UserDetail id={id} roles={roles} /> : <Search />;
    case "properties": return id ? <PropertyDetail id={id} roles={roles} /> : <Search />;
    case "review-cases": return <ReviewCases />;
    case "reports": return <Reports />;
    case "proposals": return <Proposals userId={id} />;
    case "risk": return <RiskReviews />;
    case "alerts": return <Alerts roles={roles} />;
    case "imports": return <Imports />;
    case "regions": return <Regions roles={roles} />;
    case "economy": return <Economy />;
    case "config": return <Config roles={roles} />;
    case "audit": return <Audit />;
    default: return <ErrorBox error={new Error("Página não encontrada.")} />;
  }
}
