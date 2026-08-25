import type { Etapa, Posicao } from "../api/types";

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

export function iniciais(nome: string): string {
  return nome.trim().split(/\s+/).map((w) => w[0]).slice(0, 2).join("").toUpperCase();
}

/** Nº visível da OS: idExterno quando existe, senão o id interno. */
export function osNum(o: { id: number; idExterno: number | null }): string {
  return "#" + (o.idExterno ?? o.id);
}
