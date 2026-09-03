import type { Etapa, Permissao, Posicao } from "../api/types";

/** A API carimba Instant em UTC; a tela mostra sempre hora local. */

export function hhmm(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "—";
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

export function diaHora(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "—";
  const dia = `${String(d.getDate()).padStart(2, "0")}/${String(d.getMonth() + 1).padStart(2, "0")}`;
  return `${dia} ${hhmm(iso)}`;
}

/** "1h05" / "42 min" — igual ao durTxt do design. */
export function duracao(deIso: string, ateIso: string | null): string {
  const fim = ateIso ? new Date(ateIso).getTime() : Date.now();
  const min = Math.max(0, Math.round((fim - new Date(deIso).getTime()) / 60000));
  const h = Math.floor(min / 60);
  return h ? `${h}h${String(min % 60).padStart(2, "0")}` : `${min} min`;
}

export function horasEntre(deIso: string, ateIso: string): string {
  const h = (new Date(ateIso).getTime() - new Date(deIso).getTime()) / 3600000;
  return `${Math.floor(h)}h${String(Math.round((h - Math.floor(h)) * 60)).padStart(2, "0")}`;
}

export const POSICOES: { key: Posicao; label: string }[] = [
  { key: "OXIDACAO", label: "Oxidação" },
  { key: "AUTOMATICA", label: "Automática" },
  { key: "PENDURADO", label: "Pendurado" },
];

export function posLabel(k: Posicao | null | undefined): string {
  return POSICOES.find((p) => p.key === k)?.label ?? String(k ?? "—");
}

export const ETAPAS: { key: Etapa; label: string }[] = [
  { key: "PRE_TRATAMENTO", label: "Pré-tratamento" },
  { key: "TRATAMENTO", label: "Tratamento" },
  { key: "POS_TRATAMENTO", label: "Pós-tratamento" },
];

export function etapaLabel(e: Etapa | null | undefined): string {
  return ETAPAS.find((x) => x.key === e)?.label ?? "Sem etapa";
}

export const PERMISSOES: { key: Permissao; label: string }[] = [
  { key: "ADMIN", label: "Administrador" },
  { key: "FUNCIONARIO", label: "Funcionário" },
];

export function permissaoLabel(p: Permissao | null | undefined): string {
  return PERMISSOES.find((x) => x.key === p)?.label ?? String(p ?? "—");
}

export function iniciais(nome: string): string {
  return nome.trim().split(/\s+/).map((w) => w[0]).slice(0, 2).join("").toUpperCase();
}

/** Nº visível da OS: idExterno quando existe, senão o id interno. */
export function osNum(o: { id: number; idExterno: number | null }): string {
  return "#" + (o.idExterno ?? o.id);
}

/**
 * Dia local em `yyyy-MM-dd`. `toISOString()` converte para UTC antes de cortar:
 * à noite no fuso da fábrica (UTC-3) devolveria o dia seguinte. Os getters
 * locais montam o dia que o operador vê no calendário.
 */
export function iso(d: Date): string {
  const mes = String(d.getMonth() + 1).padStart(2, "0");
  const dia = String(d.getDate()).padStart(2, "0");
  return `${d.getFullYear()}-${mes}-${dia}`;
}

const SEMANA = ["domingo", "segunda", "terça", "quarta", "quinta", "sexta", "sábado"];

/** "31/08/2025 · sábado" — o cabeçalho do dia auditado. */
export function dataLonga(diaIso: string): string {
  const [a, m, d] = diaIso.split("-").map(Number);
  const dt = new Date(a, m - 1, d);
  if (Number.isNaN(dt.getTime())) return diaIso;
  return `${String(d).padStart(2, "0")}/${String(m).padStart(2, "0")}/${a} · ${SEMANA[dt.getDay()]}`;
}
