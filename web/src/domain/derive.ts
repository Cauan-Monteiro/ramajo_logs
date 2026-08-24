import type { CSSProperties } from "react";
import type { CargaDTO, Etapa, LogDTO, OrdemResumoDTO, ProcessoDTO } from "../api/types";
import { etapaLabel } from "./format";

/* ── cores por etapa (dot()/etpStyle do design) ─────────────────────────── */

const ETAPA_BG: Record<Etapa, string> = {
  PRE_TRATAMENTO: "#98989b",
  TRATAMENTO: "#5980a6",
  POS_TRATAMENTO: "#416180",
};

export function etapaStyle(e: Etapa | null | undefined): CSSProperties {
  return { background: e ? ETAPA_BG[e] : "#c9c9cc", color: "#f2f2f3" };
}

export function dotStyle(e: Etapa | null | undefined): CSSProperties {
  return { background: e ? ETAPA_BG[e] : "#d4d4d7" };
}

/* ── estilos de seleção reaproveitados por vários modais ────────────────── */

export const SEL_PICK: CSSProperties = { borderColor: "#5980a6", background: "#eef6ff" };
export const SEL_CHIP: CSSProperties = {
  background: "#5980a6", color: "#f2f2f3", borderColor: "#5980a6",
};
export const SEL_SEG: CSSProperties = {
  background: "#5980a6", borderColor: "#5980a6", color: "#f2f2f3",
};

export function tabStyle(on: boolean): CSSProperties {
  return on
    ? { color: "#fff", borderBottomColor: "#94bce3" }
    : { color: "#b7b7ba", borderBottomColor: "transparent" };
}

export function pillStyle(encerrada: boolean): CSSProperties {
  return encerrada
    ? { background: "#e7e7ea", color: "#5d5d60" }
    : { background: "#d6ebff", color: "#2c455d" };
}

/* ── joins que os DTOs não trazem prontos ───────────────────────────────── */

/**
 * LogDTO traz só `processoDescricao` — nem etapa, nem processoId. O chip
 * colorido de etapa precisa da Etapa, então cruzamos a descrição com o
 * catálogo de /api/processos. Descrição sem correspondência (processo
 * renomeado depois do passo) cai no chip neutro.
 */
export function etapaDoLog(log: LogDTO, processos: ProcessoDTO[]): Etapa | null {
  return processos.find((p) => p.descricao === log.processoDescricao)?.etapa ?? null;
}

export function labelEtapaDoLog(log: LogDTO, processos: ProcessoDTO[]): string {
  const e = etapaDoLog(log, processos);
  return e ? etapaLabel(e) : "○";
}

/** LogDTO identifica a carga por nome; o POST de passo exige o id. */
export function cargaPorNome(nome: string, cargas: CargaDTO[]): CargaDTO | undefined {
  return cargas.find((c) => c.nome === nome);
}

export const isAberto = (l: LogDTO) => !l.finalizadoEm && !l.cancelado;

export function logAbertoDaCarga(nome: string, logs: LogDTO[]): LogDTO | undefined {
  return logs.find((l) => l.cargaNome === nome && isAberto(l));
}

/* ── agregados de OS ────────────────────────────────────────────────────── */

/** O design chama de "2º lote" toda OS que já expediu ao menos um lote. */
export const emSegundoLote = (o: OrdemResumoDTO) => o.lotesFinalizados > 0;

export function situacaoOrdem(o: OrdemResumoDTO): string {
  if (!o.emProcesso) return "Concluída";
  return emSegundoLote(o) ? "2º lote" : "Em produção";
}

/** Sub-linha de um passo: "Carga T-07 · Rita Salgado · 42 min". */
export function logSub(l: LogDTO, dur: string): string {
  return `Carga ${l.cargaNome} · ${l.responsavelNome} · ${dur}`;
}
