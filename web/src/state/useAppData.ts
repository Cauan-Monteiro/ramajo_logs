import { useCallback, useEffect, useRef, useState } from "react";
import * as api from "../api/endpoints";
import { indexarLogs } from "../domain/derive";
import type {
  CargaDTO, ClienteDTO, DesidroEmAndamentoDTO, DesidrogenizacaoDTO, LogDTO,
  OperadorDTO, OrdemResumoDTO, ProcessoDTO, ProcessoInicialDTO,
} from "../api/types";

export interface AppData {
  clientes: ClienteDTO[];
  /** Ativos e inativos — Ajustes precisa dos dois; o Login filtra por conta. */
  operadores: OperadorDTO[];
  processos: ProcessoDTO[];
  /** Processo de entrada de cada setor. No máximo uma linha por posição. */
  processosIniciais: ProcessoInicialDTO[];
  cargas: CargaDTO[];
  /** Catálogo de desidrogenização, ativas e arquivadas — como `processos`. */
  desidrogenizacoes: DesidrogenizacaoDTO[];
  /** A temperatura do forno, a mesma para todas. */
  temperaturaDesidro: number | null;
  /** Desidrogenizações das OS em produção — alimentam o indicativo da barra. */
  desidrosEmAndamento: DesidroEmAndamentoDTO[];
  ordens: OrdemResumoDTO[];
  /** Histórico por OS em processo, indexado por id da OS. */
  logsPorOrdem: Record<number, LogDTO[]>;
}

const VAZIO: AppData = {
  clientes: [], operadores: [], processos: [], processosIniciais: [], cargas: [],
  desidrogenizacoes: [], temperaturaDesidro: null, desidrosEmAndamento: [],
  ordens: [], logsPorOrdem: {},
};

/**
 * Fonte única de dados da tela. Carrega catálogos + ordens + os passos das
 * ordens em processo, e recarrega tudo depois de cada mutação — sem cache
 * otimista: quem manda no estado é a API, que aplica regras (fecha o passo
 * anterior, valida posição) que o cliente não tem como replicar fielmente.
 *
 * Os passos vêm num GET só, para todas as ordens em processo. Já foram um GET
 * por ordem — e como esta recarga corre a cada mutação e a cada evento do SSE,
 * aquilo era um N+1 permanente, não só do arranque.
 *
 * `marca` é a revisão do servidor vigente quando estes dados foram lidos — é o
 * que useSync compara para saber se este terminal ficou para trás.
 */
export function useAppData(onError: (e: unknown) => void) {
  const [data, setData] = useState<AppData>(VAZIO);
  const [carregando, setCarregando] = useState(true);
  const [pronto, setPronto] = useState(false);
  const [marca, setMarca] = useState<string | null>(null);
  const emVoo = useRef(0);

  const recarregar = useCallback(async () => {
    const meu = ++emVoo.current;
    setCarregando(true);
    try {
      const [
        revisao, clientes, operadores, processos, processosIniciais, cargas,
        desidrogenizacoes, configDesidro, desidrosEmAndamento, ordens,
      ] =
        await Promise.all([
          // Lida junto com os dados, nunca depois: se algo mudar no meio desta
          // carga, a marca guardada fica atrasada e o próximo poll corrige. O
          // contrário (ler depois) perderia a alteração para sempre.
          api.revisaoEstado(),
          api.listarClientes(),
          api.listarOperadores(),
          api.listarProcessos(),
          api.listarProcessosIniciais(),
          api.listarCargas(),
          // Arquivadas junto: a tela de cadastro alterna entre as duas listas,
          // e quem oferece receita ao operador filtra por `ativo`.
          api.listarDesidrogenizacoes(true),
          api.temperaturaDesidrogenizacao(),
          // O progresso de cada uma anda sozinho no relógio da tela; esta lista
          // só precisa mudar quando alguém aplica ou expede, e disso o useSync
          // já cuida (POST -> revisão nova -> recarga).
          api.listarDesidrogenizacoesEmAndamento(),
          api.listarOrdens(false),
        ]);

      /**
       * Os passos das ordens em processo, num pedido só. Era um GET por ordem
       * — e esta recarga corre inteira a cada mutação e a cada evento do SSE,
       * portanto o N+1 não era do arranque, era de toda hora.
       *
       * A resposta vem plana, com o passo de carona uma vez só; `indexarLogs`
       * reconstrói os dois lados.
       */
      const idsEmProcesso = ordens.filter((o) => o.emProcesso).map((o) => o.id);
      const logsPorOrdem = idsEmProcesso.length === 0
        ? {}
        : indexarLogs(await api.historicoDeOrdens(idsEmProcesso), idsEmProcesso);

      // Uma recarga mais nova já respondeu: descartar esta.
      if (meu !== emVoo.current) return;

      setData({
        clientes, operadores, processos, processosIniciais, cargas,
        desidrogenizacoes, temperaturaDesidro: configDesidro.temperatura,
        desidrosEmAndamento, ordens,
        logsPorOrdem,
      });
      setMarca(`${revisao.instancia}:${revisao.revisao}`);
      setPronto(true);
    } catch (e) {
      if (meu === emVoo.current) onError(e);
    } finally {
      if (meu === emVoo.current) setCarregando(false);
    }
  }, [onError]);

  useEffect(() => {
    void recarregar();
  }, [recarregar]);

  return { data, carregando, pronto, marca, recarregar };
}

/** Logs de uma OS já carregada; [] quando a OS não está em processo. */
export const logsDe = (data: AppData, osId: number): LogDTO[] =>
  data.logsPorOrdem[osId] ?? [];

/** Cargas atualmente vinculadas a uma OS. */
export const cargasDe = (data: AppData, osId: number): CargaDTO[] =>
  data.cargas.filter((c) => c.ordemAtualId === osId);

/** Cargas ativas, livres e na posição pedida, em ordem crescente por nome. */
export const cargasLivres = (data: AppData, posicao: string): CargaDTO[] =>
  data.cargas
    .filter((c) => c.ativo && c.ordemAtualId === null && c.posicao === posicao)
    .sort((a, b) => a.nome.localeCompare(b.nome, "pt-BR", { numeric: true, sensitivity: "base" }));
