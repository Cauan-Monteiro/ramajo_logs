import type { CSSProperties } from "react";
import type { CargaDTO, Etapa, LogDTO, OrdemResumoDTO, Posicao, ProcessoDTO } from "../api/types";
import { etapaLabel, posOrdenadas } from "./format";

/* ── cores por etapa (dot()/etpStyle do design) ─────────────────────────── */

const ETAPA_BG: Record<Etapa, string> = {
  PRE_TRATAMENTO: "#98989b",
  TRATAMENTO: "#ED7D31",
  POS_TRATAMENTO: "#5B9BD5",
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

/** A pílula genérica: em curso (azul) ou encerrado (cinza). Serve eventos e
    barras da auditoria, que não são ordens — para uma OS, ver `pillOrdemStyle`. */
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
 * Reindexa por OS a lista PLANA que `GET /api/ordens/logs?ids=` devolve.
 *
 * As rotas em lote mandam cada passo UMA vez, mesmo quando ele pertence a três
 * ordens: o `ordemServicoId` diz de quem é a carga e o `ordensAcopladas` diz
 * quem pegou boleia. Reconstruir os dois lados aqui é o que faz o lote devolver,
 * ordem a ordem, exactamente o que `GET /api/ordens/{id}/logs` devolvia — e é a
 * mesma distinção que `ehAcoplada` faz acima, só que do lado de quem monta.
 *
 * A lista chega ordenada por (iniciadoEm, id), portanto cada grupo sai já na
 * ordem certa sem um segundo sort. Toda OS pedida ganha uma entrada, ainda que
 * vazia: quem lê distingue "sem passos" de "não carregada".
 */
export function indexarLogs(planos: LogDTO[], ids: number[]): Record<number, LogDTO[]> {
  const doConjunto = new Set(ids);
  const porOs: Record<number, LogDTO[]> = {};
  for (const id of ids) porOs[id] = [];

  for (const l of planos) {
    if (doConjunto.has(l.ordemServicoId)) porOs[l.ordemServicoId].push(l);
    for (const os of l.ordensAcopladas) {
      if (os !== l.ordemServicoId && doConjunto.has(os)) porOs[os].push(l);
    }
  }
  return porOs;
}

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

/* ── setores da OS ──────────────────────────────────────────────────────── */

/**
 * Esta OS está autorizada a rodar neste setor?
 *
 * Substitui o antigo `o.posicao === posicao` em toda filtragem por aba: uma OS
 * de dois setores aparece nas duas. O que cada aba MOSTRA continua a sair das
 * cargas, que são de um setor só — logo a aba do Pendurado não passa a ver o
 * trabalho da Automática, só a ordem a que ele pertence.
 */
export const rodaEm = (o: { posicoes: Posicao[] }, p: Posicao) => o.posicoes.includes(p);

/** A OS roda em mais de um setor — a exceção, que a UI sinaliza. */
export const multiPosicao = (o: { posicoes: Posicao[] }) => o.posicoes.length > 1;

/**
 * Os OUTROS setores desta OS, vistos de uma aba. Vazio no caso normal; é o que
 * alimenta o selo "também em ..." — quem está a decidir expedir precisa de
 * saber que há trabalho desta ordem noutro sítio.
 */
export const tambemEm = (o: { posicoes: Posicao[] }, p: Posicao): Posicao[] =>
  posOrdenadas(o.posicoes.filter((x) => x !== p));

/* ── agregados de OS ────────────────────────────────────────────────────── */

/** O design chama de "2º lote" toda OS que já expediu ao menos um lote. */
export const emSegundoLote = (o: OrdemResumoDTO) => o.lotesFinalizados > 0;

/** Já saiu da casa: a expedição tirou as peças da produção, a entrega do pátio. */
export const foiEntregue = (o: OrdemResumoDTO) => o.entregueEm !== null;

/**
 * A fila da aba Entregas: expedida, não cancelada e ainda não entregue.
 *
 * Sai do resumo — que passou a trazer `cancelada` e `entregueEm` —, então a aba
 * filtra `data.ordens` sem ir à rede. A OS cancelada também tem `emProcesso`
 * falso e nunca terá entrega; sem o teste dela a fila encheria de ordens que
 * foram abortadas, não expedidas.
 */
export const aguardandoEntrega = (o: OrdemResumoDTO) =>
  !o.emProcesso && !o.cancelada && !foiEntregue(o);

export function situacaoOrdem(o: OrdemResumoDTO): string {
  if (o.cancelada) return "Cancelada";
  if (!o.emProcesso) return foiEntregue(o) ? "Entregue" : "Aguardando entrega";
  return emSegundoLote(o) ? "2º lote" : "Em produção";
}

/**
 * A pílula da OS, no tom da sua situação: o verde da entrega é o único estado
 * que `pillStyle` sozinho não sabe dizer — expedida e entregue são as duas
 * "encerradas", e é a diferença entre elas que a aba de Entregas mostra.
 */
export function pillOrdemStyle(o: OrdemResumoDTO): CSSProperties {
  return foiEntregue(o)
    ? { background: "#dff0e3", color: "#2f6b3c" }
    : pillStyle(!o.emProcesso);
}

/**
 * Sub-linha de um passo: "Carga T-07 · Rita Salgado · 42 min", e
 * "· fechou Maria" quando quem fechou não foi quem abriu.
 *
 * Só quando difere: repetir o mesmo nome duas vezes na mesma linha não informa
 * nada e rouba espaço à duração, que é o que se lê aqui. Passo aberto e
 * histórico anterior à V21 não têm quem fechasse, e a linha fica como era.
 */
export function logSub(l: LogDTO, dur: string): string {
  const fechou = l.finalizadoPorNome && l.finalizadoPorNome !== l.responsavelNome
    ? ` · fechou ${l.finalizadoPorNome}`
    : "";
  return `Carga ${l.cargaNome} · ${l.responsavelNome}${fechou} · ${dur}`;
}
