import { useCallback, useEffect, useRef, useState } from "react";
import * as api from "../api/endpoints";
import type {
  CargaDTO, ClienteDTO, LogDTO, OrdemResumoDTO, ProcessoDTO, ProcessoInicialDTO,
} from "../api/types";

export interface AppData {
  clientes: ClienteDTO[];
  processos: ProcessoDTO[];
  /** Processo de entrada de cada setor. No máximo uma linha por posição. */
  processosIniciais: ProcessoInicialDTO[];
  cargas: CargaDTO[];
  ordens: OrdemResumoDTO[];
  /** Histórico por OS em processo, indexado por id da OS. */
  logsPorOrdem: Record<number, LogDTO[]>;
}

const VAZIO: AppData = {
  clientes: [], processos: [], processosIniciais: [], cargas: [], ordens: [],
  logsPorOrdem: {},
};

/**
 * Fonte única de dados da tela. Carrega catálogos + ordens + os passos das
 * ordens em processo, e recarrega tudo depois de cada mutação — sem cache
 * otimista: quem manda no estado é a API, que aplica regras (fecha o passo
 * anterior, valida posição) que o cliente não tem como replicar fielmente.
 *
 * Os passos vêm num GET por ordem (N+1 assumido): não há rota que devolva os
 * logs de várias OS de uma vez, e o universo é o de uma posição de fábrica.
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
      const [revisao, clientes, processos, processosIniciais, cargas, ordens] =
        await Promise.all([
          // Lida junto com os dados, nunca depois: se algo mudar no meio desta
          // carga, a marca guardada fica atrasada e o próximo poll corrige. O
          // contrário (ler depois) perderia a alteração para sempre.
          api.revisaoEstado(),
          api.listarClientes(),
          api.listarProcessos(),
          api.listarProcessosIniciais(),
          api.listarCargas(),
          api.listarOrdens(false),
        ]);

      const emProcesso = ordens.filter((o) => o.emProcesso);
      const historicos = await Promise.all(
        emProcesso.map((o) => api.historicoOrdem(o.id).then((logs) => [o.id, logs] as const)),
      );

      // Uma recarga mais nova já respondeu: descartar esta.
      if (meu !== emVoo.current) return;

      setData({
        clientes, processos, processosIniciais, cargas, ordens,
        logsPorOrdem: Object.fromEntries(historicos),
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

/** Cargas ativas, livres e na posição pedida. */
export const cargasLivres = (data: AppData, posicao: string): CargaDTO[] =>
  data.cargas.filter((c) => c.ativo && c.ordemAtualId === null && c.posicao === posicao);
