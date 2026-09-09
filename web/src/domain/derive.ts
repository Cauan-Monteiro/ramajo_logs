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

/* ── acoplamento de OS numa carga ───────────────────────────────────────── */

/**
 * Este passo é de carona para a OS pedida? Peças dela estavam na carga, mas
 * quem executou foi outra ordem — a titular, dona da carga.
 *
 * A API devolve o passo no histórico de todas as OS envolvidas, sempre com a
 * titular em `ordemServicoId`; a divergência é o próprio sinal.
 */
export const ehAcoplada = (l: LogDTO, osId: number) => l.ordemServicoId !== osId;

/**
 * A carga em que esta OS pega carona, se houver. Vale como "tem carga
 * vinculada": as peças dela estão dentro de um tanque alheio, e a OS não pode
 * ser tratada como pronta para inspeção.
 *
 * Deriva da CARGA e não dos passos de propósito — o vínculo sobrevive ao
 * fecho de uma etapa, e entre uma etapa e a seguinte a OS continua a ter peças
 * lá dentro.
 */
export const cargaCarona = (cargas: CargaDTO[], osId: number) =>
  cargas.find((c) => c.ordensAcopladas.includes(osId));

/** As OS que pegam carona nesta carga, resolvidas para os resumos. */
export const caronasDa = (carga: CargaDTO, ordens: OrdemResumoDTO[]) =>
  carga.ordensAcopladas
    .map((id) => ordens.find((o) => o.id === id))
    .filter((o): o is OrdemResumoDTO => !!o);

/**
 * OS aberta que ainda não tem tanque nenhum e nunca produziu: nasceu sem carga
 * própria e aguarda vínculo ou acoplamento.
 *
 * Não é caso de inspeção final — sem passo algum não há o que expedir, e
 * oferecer "expedir parcial" aqui seria oferecer o encerramento de uma produção
 * que não começou. O teste é o histórico e não o lote: `lotes` nasce sempre com
 * o nº 1, então só os passos distinguem "nunca produziu" de "já rodou e voltou
 * a ficar sem carga".
 */
export const emEspera = (o: OrdemResumoDTO, cargas: CargaDTO[], logs: LogDTO[]) =>
  o.emProcesso &&
  !cargas.some((c) => c.ordemAtualId === o.id) &&
  !cargaCarona(cargas, o.id) &&
  logs.length === 0;

/* ── agregados de OS ────────────────────────────────────────────────────── */

/** O design chama de "2º lote" toda OS que já expediu ao menos um lote. */
export const emSegundoLote = (o: OrdemResumoDTO) => o.lotesFinalizados > 0;

export function situacaoOrdem(o: OrdemResumoDTO): string {
  if (!o.emProcesso) return "Expedida";
  return emSegundoLote(o) ? "2º lote" : "Em produção";
}

/** Sub-linha de um passo: "Carga T-07 · Rita Salgado · 42 min". */
export function logSub(l: LogDTO, dur: string): string {
  return `Carga ${l.cargaNome} · ${l.responsavelNome} · ${dur}`;
}
