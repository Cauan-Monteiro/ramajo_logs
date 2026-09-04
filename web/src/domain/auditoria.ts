import type {
  Etapa, LogDTO, OrdemDetalheDTO, OrdemResumoDTO, Posicao, ProcessoDTO,
  ProcessoInicialDTO,
} from "../api/types";
import { etapaDoLog } from "./derive";
import { ETAPAS, osNum } from "./format";

/**
 * Derivações da aba "Auditoria do dia". Tudo aqui é puro: recebe o que a API já
 * devolveu e devolve o recorte de um dia — nada de fetch, nada de JSX.
 *
 * O recorte é sempre por *evento*, nunca por OS: uma ordem aberta anteontem e
 * expedida hoje pertence ao dia de hoje pela expedição, e uma etapa que
 * atravessa a meia-noite aparece nos dois dias, cortada em cada um.
 */

export type TipoEvento =
  | "OS_ABERTA"
  | "OS_ENCERRADA"
  | "OS_CANCELADA"
  | "LOTE_FECHADO"
  | "ETAPA_ABERTA"
  | "ETAPA_FECHADA";

export const ROTULO_EVENTO: Record<TipoEvento, string> = {
  OS_ABERTA: "Abriu OS",
  OS_ENCERRADA: "Expediu OS",
  OS_CANCELADA: "Cancelou OS",
  LOTE_FECHADO: "Fechou lote",
  ETAPA_ABERTA: "Abriu etapa",
  ETAPA_FECHADA: "Fechou etapa",
};

export interface Evento {
  id: string;
  tipo: TipoEvento;
  /** Instante em ms — já resolvido, para ordenar sem reparsear. */
  em: number;
  osId: number;
  osLabel: string;
  clienteNome: string;
  posicao: Posicao;
  cargaNome: string | null;
  processoDescricao: string | null;
  etapa: Etapa | null;
  /**
   * Quem assinou. `null` quando a API não regista o autor — é o caso de
   * ETAPA_FECHADA: `PATCH /api/ordens/logs/{id}/finalizar` não recebe operador
   * e `LogDTO` não tem `finalizadoPorNome`. Nunca preencher com o responsável
   * da abertura: seria inventar uma assinatura que ninguém deu.
   */
  autor: string | null;
  duracaoMs: number | null;
}

export interface Barra {
  logId: string;
  cargaNome: string;
  processoDescricao: string;
  responsavelNome: string;
  etapa: Etapa | null;
  /** Início/fim reais, sem corte — é o que o painel de detalhe mostra. */
  iniciadoEm: string;
  finalizadoEm: string | null;
  /** Já cortados aos limites do dia, em ms: é o que a barra desenha. */
  de: number;
  ate: number;
  aberta: boolean;
  cancelado: boolean;
  vemDeOntem: boolean;
  passaDaMeiaNoite: boolean;
}

export interface Faixa {
  cargaNome: string;
  barras: Barra[];
}

export interface Grupo {
  ordem: OrdemResumoDTO;
  detalhe: OrdemDetalheDTO | undefined;
  faixas: Faixa[];
  /** Eventos da OS que não são de etapa — os marcos da faixa de topo. */
  marcos: Evento[];
}

export interface FonteDia {
  /** Já filtradas pela posição escolhida na barra de controlo. */
  ordens: OrdemResumoDTO[];
  detalhes: Record<number, OrdemDetalheDTO>;
  logs: Record<number, LogDTO[]>;
  processos: ProcessoDTO[];
  /** Dia auditado, em `yyyy-MM-dd`. */
  dia: string;
  agora: number;
}

const HORA = 3600000;

/**
 * Limites do dia em ms, `fim` exclusivo. Construído pelos componentes
 * (`new Date(a, m-1, d)`) e não por `Date.parse("2025-08-31")`, que o
 * ECMAScript manda ler como UTC — no fuso da fábrica o dia começaria às 21h do
 * dia anterior.
 */
export function limitesDoDia(dia: string): { ini: number; fim: number } {
  const [a, m, d] = dia.split("-").map(Number);
  return {
    ini: new Date(a, m - 1, d).getTime(),
    fim: new Date(a, m - 1, d + 1).getTime(),
  };
}

/** ms de um ISO da API; `null` quando o campo não veio ou não parseia. */
function ms(iso: string | null | undefined): number | null {
  if (!iso) return null;
  const t = Date.parse(iso);
  return Number.isNaN(t) ? null : t;
}

function dentro(t: number | null, ini: number, fim: number): t is number {
  return t !== null && t >= ini && t < fim;
}

/* ── eventos ────────────────────────────────────────────────────────────── */

export function eventosDoDia(f: FonteDia): Evento[] {
  const { ini, fim } = limitesDoDia(f.dia);
  const out: Evento[] = [];

  for (const o of f.ordens) {
    const det = f.detalhes[o.id];
    const base = {
      osId: o.id,
      osLabel: osNum(o),
      clienteNome: o.clienteNome,
      posicao: o.posicao,
      cargaNome: null,
      processoDescricao: null,
      etapa: null,
      duracaoMs: null,
    };

    const abertaEm = ms(o.iniciadaEm);
    if (dentro(abertaEm, ini, fim)) {
      out.push({
        ...base,
        id: `OS_ABERTA:${o.id}`,
        tipo: "OS_ABERTA",
        em: abertaEm,
        autor: det?.iniciadaPorNome ?? null,
      });
    }

    const fechadaEm = ms(det?.finalizadaEm);
    if (dentro(fechadaEm, ini, fim)) {
      out.push({
        ...base,
        id: `OS_FIM:${o.id}`,
        tipo: det?.cancelada ? "OS_CANCELADA" : "OS_ENCERRADA",
        em: fechadaEm,
        autor: det?.finalizadaPorNome ?? null,
        duracaoMs: abertaEm === null ? null : fechadaEm - abertaEm,
      });
    }

    for (const lote of det?.lotes ?? []) {
      const t = ms(lote.finalizadoEm);
      // O último lote de uma OS encerrada fecha junto com ela; os dois eventos
      // aparecem, porque foram de facto duas linhas do histórico.
      if (dentro(t, ini, fim)) {
        const abriu = ms(lote.iniciadoEm);
        out.push({
          ...base,
          id: `LOTE:${lote.id}`,
          tipo: "LOTE_FECHADO",
          em: t,
          autor: lote.finalizadoPorNome,
          duracaoMs: abriu === null ? null : t - abriu,
        });
      }
    }

    for (const l of f.logs[o.id] ?? []) {
      const comum = {
        ...base,
        cargaNome: l.cargaNome,
        processoDescricao: l.processoDescricao,
        etapa: etapaDoLog(l, f.processos),
      };
      const de = ms(l.iniciadoEm);
      const ate = ms(l.finalizadoEm);

      if (dentro(de, ini, fim)) {
        out.push({
          ...comum,
          id: `LOG_INI:${l.id}`,
          tipo: "ETAPA_ABERTA",
          em: de,
          autor: l.responsavelNome,
        });
      }
      if (dentro(ate, ini, fim)) {
        out.push({
          ...comum,
          id: `LOG_FIM:${l.id}`,
          tipo: "ETAPA_FECHADA",
          em: ate,
          autor: null, // ver o comentário de `Evento.autor`
          duracaoMs: de === null ? null : ate - de,
        });
      }
    }
  }

  // Empate resolvido pelo id: dois eventos no mesmo milissegundo (fechar o lote
  // e expedir a OS) não podem trocar de lugar a cada sync de 4s.
  return out.sort((a, b) => a.em - b.em || a.id.localeCompare(b.id));
}

/* ── faixas do swimlane ─────────────────────────────────────────────────── */

export function faixasDoDia(f: FonteDia): Grupo[] {
  const { ini, fim } = limitesDoDia(f.dia);
  const marcosPorOs = new Map<number, Evento[]>();
  for (const e of eventosDoDia(f)) {
    if (e.tipo === "ETAPA_ABERTA" || e.tipo === "ETAPA_FECHADA") continue;
    const lista = marcosPorOs.get(e.osId) ?? [];
    lista.push(e);
    marcosPorOs.set(e.osId, lista);
  }

  const grupos: Grupo[] = [];

  for (const o of f.ordens) {
    const porCarga = new Map<string, Barra[]>();

    for (const l of f.logs[o.id] ?? []) {
      const de = ms(l.iniciadoEm);
      if (de === null) continue;
      // Uma etapa por fechar corre até agora; num dia passado, até ao fim dele.
      const fimReal = ms(l.finalizadoEm) ?? f.agora;
      // Sobrepõe-se ao dia? Uma etapa inteira de ontem não entra; uma que
      // começou ontem às 23h e fechou hoje às 2h entra, cortada.
      if (de >= fim || fimReal <= ini) continue;

      const lista = porCarga.get(l.cargaNome) ?? [];
      lista.push({
        logId: l.id,
        cargaNome: l.cargaNome,
        processoDescricao: l.processoDescricao,
        responsavelNome: l.responsavelNome,
        etapa: etapaDoLog(l, f.processos),
        iniciadoEm: l.iniciadoEm,
        finalizadoEm: l.finalizadoEm,
        de: Math.max(de, ini),
        ate: Math.min(fimReal, fim),
        aberta: !l.finalizadoEm && !l.cancelado,
        cancelado: l.cancelado,
        vemDeOntem: de < ini,
        passaDaMeiaNoite: fimReal > fim,
      });
      porCarga.set(l.cargaNome, lista);
    }

    const marcos = marcosPorOs.get(o.id) ?? [];
    if (porCarga.size === 0 && marcos.length === 0) continue; // OS parada no dia

    grupos.push({
      ordem: o,
      detalhe: f.detalhes[o.id],
      marcos,
      faixas: [...porCarga.entries()]
        .map(([cargaNome, barras]) => ({
          cargaNome,
          barras: barras.sort((a, b) => a.de - b.de),
        }))
        .sort((a, b) => a.cargaNome.localeCompare(b.cargaNome, "pt-BR", { numeric: true })),
    });
  }

  // OS tocada mais recentemente no topo: é onde costuma estar a dúvida da vez.
  return grupos.sort((a, b) => ultimoToque(b) - ultimoToque(a));
}

function ultimoToque(g: Grupo): number {
  let t = 0;
  for (const f of g.faixas) for (const b of f.barras) t = Math.max(t, b.ate);
  for (const m of g.marcos) t = Math.max(t, m.em);
  return t;
}

/* ── janela visível do eixo ─────────────────────────────────────────────── */

/**
 * Recorte horizontal do eixo: da hora cheia do primeiro movimento à hora cheia
 * do último (ou de "agora", no dia corrente). Espremer as 24h no ecrã tornava
 * ilegível justamente o turno onde tudo acontece; um dia sem movimento abre em
 * 06h–18h, e nenhuma janela é mais estreita do que 6h.
 */
export function janelaVisivel(
  instantes: number[], dia: string, agora: number,
): { de: number; ate: number } {
  const { ini, fim } = limitesDoDia(dia);
  const MIN = 6 * HORA;
  const pontos = instantes.filter((t) => t >= ini && t <= fim);
  if (agora >= ini && agora < fim) pontos.push(agora);
  if (pontos.length === 0) return { de: ini + 6 * HORA, ate: ini + 18 * HORA };

  let de = ini + Math.floor((Math.min(...pontos) - ini) / HORA) * HORA;
  let ate = ini + Math.ceil((Math.max(...pontos) - ini) / HORA) * HORA;
  if (ate - de < MIN) ate = de + MIN;
  if (ate > fim) ate = fim;
  if (ate - de < MIN) de = Math.max(ini, ate - MIN);
  return { de, ate };
}

/** Horas cheias a marcar na régua, os dois extremos incluídos. */
export function horasDaJanela(de: number, ate: number): number[] {
  const out: number[] = [];
  for (let t = de; t <= ate; t += HORA) out.push(t);
  return out;
}

/** Posição de um instante na janela, em fração de 0 a 1. */
export function fracao(t: number, de: number, ate: number): number {
  if (ate <= de) return 0;
  return Math.min(1, Math.max(0, (t - de) / (ate - de)));
}

/* ── agregados ──────────────────────────────────────────────────────────── */

export interface ResumoOperador {
  nome: string;
  etapasAbertas: number;
  osAbertas: number;
  osEncerradas: number;
  lotes: number;
  total: number;
}

/**
 * Quem fez o quê. Agrupa por *nome* porque é só isso que os DTOs trazem —
 * `responsavelNome`, `iniciadaPorNome`, `finalizadaPorNome` e
 * `finalizadoPorNome` nunca vêm acompanhados do id do operador. Eventos sem
 * autor (o fecho de etapa) ficam de fora: não há a quem atribuí-los.
 */
export function porOperador(eventos: Evento[]): ResumoOperador[] {
  const mapa = new Map<string, ResumoOperador>();
  for (const e of eventos) {
    if (!e.autor) continue;
    const r = mapa.get(e.autor) ?? {
      nome: e.autor, etapasAbertas: 0, osAbertas: 0, osEncerradas: 0, lotes: 0, total: 0,
    };
    if (e.tipo === "ETAPA_ABERTA") r.etapasAbertas++;
    else if (e.tipo === "OS_ABERTA") r.osAbertas++;
    else if (e.tipo === "OS_ENCERRADA" || e.tipo === "OS_CANCELADA") r.osEncerradas++;
    else if (e.tipo === "LOTE_FECHADO") r.lotes++;
    r.total++;
    mapa.set(e.autor, r);
  }
  return [...mapa.values()].sort(
    (a, b) => b.total - a.total || a.nome.localeCompare(b.nome, "pt-BR"),
  );
}

export interface ResumoOrdem {
  osLabel: string;
  /** Nº de entradas de carga nesta OS no dia — ver `entradasDaFaixa`. */
  total: number;
  /**
   * Nomes das cargas, na ordem em que o swimlane as mostra. A que entrou mais
   * de uma vez leva o multiplicador: "TB01 ×2".
   */
  cargaNomes: string[];
  /** Etapas por que a OS passou, sem repetir e na ordem canónica. */
  etapas: Etapa[];
}

/** Descrição do processo de entrada de um setor; `null` quando não configurado. */
function entradaDaPosicao(pos: Posicao, iniciais: ProcessoInicialDTO[]): string | null {
  return iniciais.find((pi) => pi.posicao === pos)?.processoDescricao ?? null;
}

/**
 * Quantas vezes a carga entrou nesta OS dentro do dia. Toda vinculação abre um
 * passo no processo de entrada do setor (`OrdemServicoService.vincularCarga`, e
 * a criação da OS), e só a vinculação o faz — então cada barra nesse processo é
 * uma entrada. É o que distingue a carga que percorreu o fluxo de uma vez da que
 * foi desvinculada e mais tarde vinculada de novo à *mesma* OS.
 *
 * A primeira barra do dia que não seja de entrada é uma carga que já vinha de
 * ontem: conta como a entrada em curso. Mínimo de 1 — a faixa só existe porque a
 * carga esteve na OS.
 *
 * Duas limitações, ambas do cruzamento por descrição (a única chave que `LogDTO`
 * dá, tal como em `etapaDoLog`): posição sem processo inicial configurado cai no
 * fallback global da API, que o cliente não conhece — aí conta 1, como antes; e
 * trocar o processo inicial de um setor deixa as entradas anteriores à troca sem
 * reconhecimento.
 *
 * `barras` tem de vir na ordem do tempo — é como `faixasDoDia` as devolve.
 */
export function entradasDaFaixa(barras: Barra[], entrada: string | null): number {
  if (entrada === null) return 1;
  const n = barras.filter((b) => b.processoDescricao === entrada).length;
  return Math.max(1, barras[0]?.processoDescricao === entrada ? n : n + 1);
}

/**
 * Quanta carga cada OS moveu no dia. A conta é *por entrada*: uma carga que
 * percorreu três processos da mesma ordem entrou nela uma vez só, mas a que foi
 * desvinculada e depois vinculada outra vez à mesma OS conta duas — ver
 * `entradasDaFaixa`. É a leitura que o chão de fábrica faz ao perguntar quanta
 * coisa uma ordem moveu hoje.
 *
 * Parte de `faixasDoDia` e não dos eventos porque as faixas já são exatamente
 * isso — uma por par (OS, carga), com o recorte do dia feito, incluindo a etapa
 * que atravessa a meia-noite e a que ficou por fechar. Um `Grupo` já É uma OS,
 * então aqui não há o que agrupar: basta somar as entradas das suas faixas.
 */
export function porOrdem(
  grupos: Grupo[], processosIniciais: ProcessoInicialDTO[],
): ResumoOrdem[] {
  const ordem = (e: Etapa) => ETAPAS.findIndex((x) => x.key === e);
  return grupos
    // OS que só teve marcos no dia (ver a guarda em faixasDoDia) não moveu
    // carga nenhuma: fora da lista, senão entraria com barra de comprimento
    // zero a dizer que foi processada.
    .filter((g) => g.faixas.length > 0)
    .map((g) => {
      const etapas: Etapa[] = [];
      for (const f of g.faixas) {
        for (const b of f.barras) {
          if (b.etapa && !etapas.includes(b.etapa)) etapas.push(b.etapa);
        }
      }
      const entrada = entradaDaPosicao(g.ordem.posicao, processosIniciais);
      const vezes = g.faixas.map((f) => entradasDaFaixa(f.barras, entrada));
      return {
        osLabel: osNum(g.ordem),
        total: vezes.reduce((s, n) => s + n, 0),
        cargaNomes: g.faixas.map(
          (f, i) => (vezes[i] > 1 ? `${f.cargaNome} ×${vezes[i]}` : f.cargaNome),
        ),
        etapas: etapas.sort((a, b) => ordem(a) - ordem(b)),
      };
    })
    .sort(
      (a, b) =>
        b.total - a.total
        || a.osLabel.localeCompare(b.osLabel, "pt-BR", { numeric: true }),
    );
}

/** Tipos de evento que representam um fecho — a pílula deles é a "encerrada". */
export const FECHA: TipoEvento[] = [
  "ETAPA_FECHADA", "LOTE_FECHADO", "OS_ENCERRADA", "OS_CANCELADA",
];

/**
 * Junta ao ranking os operadores do cadastro que não aparecem nos eventos.
 * `porOperador` só conhece quem assinou alguma coisa; a lista de escolha do
 * relatório precisa também dos zerados — "este não fez nada hoje" é uma
 * resposta, e um nome que some da lista parece antes um bug.
 */
export function rankingComCadastro(
  resumos: ResumoOperador[], nomes: string[],
): ResumoOperador[] {
  const out = [...resumos];
  const vistos = new Set(resumos.map((r) => r.nome));
  for (const nome of nomes) {
    if (vistos.has(nome)) continue;
    vistos.add(nome);
    out.push({ nome, etapasAbertas: 0, osAbertas: 0, osEncerradas: 0, lotes: 0, total: 0 });
  }
  return out.sort((a, b) => b.total - a.total || a.nome.localeCompare(b.nome, "pt-BR"));
}

export interface AtividadeOperador {
  /** Só os eventos que este operador assinou. */
  eventos: Evento[];
  /** Os grupos de `faixasDoDia`, podados às barras dele — o que o swimlane desenha. */
  grupos: Grupo[];
  /**
   * Soma de (ate - de) das barras dele, já recortadas ao dia. É um somatório e
   * não um relógio de parede: três etapas abertas em paralelo contam três
   * vezes, e o total pode passar da duração do turno. O rótulo na tela tem de
   * dizer isso.
   */
  msEmEtapas: number;
  /** Entradas de carga que ele tocou no dia — conta reentradas, ver `entradasDaFaixa`. */
  cargas: number;
  /** OS distintas em que mexeu. */
  osTocadas: number;
  /**
   * Etapas que ele **abriu** e que já fecharam. Não é "etapas que ele
   * concluiu": a API não regista quem fecha um passo (ver `Evento.autor`), por
   * isso o fecho pode ter sido de outra pessoa.
   */
  etapasConcluidas: number;
}

/**
 * O recorte de um operador dentro do dia já apurado. Recebe o que
 * `eventosDoDia` e `faixasDoDia` devolveram — não refaz a apuração — para que
 * a tela possa trocar de operador sem recalcular o dia inteiro.
 *
 * O cruzamento é por *nome*, único elo que os DTOs oferecem (ver `porOperador`).
 * Eventos sem autor — o fecho de etapa — não entram em ninguém.
 */
export function atividadeDoOperador(
  eventos: Evento[], grupos: Grupo[], nome: string,
  processosIniciais: ProcessoInicialDTO[],
): AtividadeOperador {
  const meus = eventos.filter((e) => e.autor === nome);

  const podados: Grupo[] = [];
  let msEmEtapas = 0;
  let cargas = 0;
  let etapasConcluidas = 0;

  for (const g of grupos) {
    const faixas: Faixa[] = [];
    const entrada = entradaDaPosicao(g.ordem.posicao, processosIniciais);
    for (const f of g.faixas) {
      const barras = f.barras.filter((b) => b.responsavelNome === nome);
      if (barras.length === 0) continue;
      for (const b of barras) {
        msEmEtapas += b.ate - b.de;
        if (b.finalizadoEm && !b.cancelado) etapasConcluidas++;
      }
      // O filtro preserva a ordem do tempo que faixasDoDia deu às barras.
      cargas += entradasDaFaixa(barras, entrada);
      faixas.push({ cargaNome: f.cargaNome, barras });
    }
    const marcos = g.marcos.filter((m) => m.autor === nome);
    // Uma OS onde ele só assinou um marco (abriu e não mexeu mais) continua a
    // ser uma OS que ele tocou: entra, tal como entra na Visão Geral.
    if (faixas.length === 0 && marcos.length === 0) continue;
    podados.push({ ordem: g.ordem, detalhe: g.detalhe, faixas, marcos });
  }

  return {
    eventos: meus,
    grupos: podados,
    msEmEtapas,
    cargas,
    osTocadas: podados.length,
    etapasConcluidas,
  };
}
