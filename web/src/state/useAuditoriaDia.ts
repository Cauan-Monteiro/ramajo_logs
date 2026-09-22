import { useEffect, useMemo, useRef, useState } from "react";
import * as api from "../api/endpoints";
import type { DetalheDia, LogDTO, OrdemAuditoriaDTO, OrdemResumoDTO } from "../api/types";
import { limitesDoDia } from "../domain/auditoria";
import { indexarLogs } from "../domain/derive";
import type { AppData } from "./useAppData";

/**
 * Os dados que a auditoria precisa e que `useAppData` não tem.
 *
 * `useAppData` só carrega `logsPorOrdem` das ordens em processo, e o resumo não
 * traz quem abriu, quem fechou, os lotes nem as desidrogenizações — e uma OS
 * expedida às 10h de hoje sai do conjunto "em processo" e desapareceria da
 * auditoria justamente no momento em que passou a interessar.
 *
 * Os outros quatro campos que a auditoria lê (`finalizadaEm`, `cancelada`,
 * `entregueEm`, `entreguePorNome`) já vêm no resumo desde a V17; aqui eles são
 * juntados ao resto para formar o `DetalheDia`.
 */

/**
 * A partir de que idade uma OS deixa de ser candidata a ter movimento no dia.
 * Sem este corte, abrir a auditoria varreria o histórico inteiro. Ordens ainda
 * em processo entram sempre, qualquer que seja a idade; o que a janela pode
 * perder é uma OS iniciada há mais de 30 dias e encerrada exactamente no dia
 * auditado.
 */
const JANELA_DIAS = 30;

/**
 * Quantos ids cabem num pedido. O limite real é o comprimento da URL (uns 8 KB
 * na maioria dos servidores), e 200 ids passam folgados disso; a API parte de
 * novo em blocos de 1000 antes de tocar no Postgres.
 */
const IDS_POR_PEDIDO = 200;

export interface DadosDia {
  detalhes: Record<number, DetalheDia>;
  /** Logs por OS: os das ordens em processo vêm de `AppData`, os restantes daqui. */
  logs: Record<number, LogDTO[]>;
  carregando: boolean;
}

type Entrada = { chave: string; detalhe: DetalheDia; logs: LogDTO[] | null };

const blocos = <T,>(lista: T[], tamanho: number): T[][] => {
  const out: T[][] = [];
  for (let i = 0; i < lista.length; i += tamanho) out.push(lista.slice(i, i + tamanho));
  return out;
};

/** O que a auditoria lê de uma OS: o lote de auditoria mais o fecho, que vem no resumo. */
const juntar = (resumo: OrdemResumoDTO, aud: OrdemAuditoriaDTO): DetalheDia => ({
  iniciadaPorNome: aud.iniciadaPorNome,
  finalizadaPorNome: aud.finalizadaPorNome,
  lotes: aud.lotes,
  desidrogenizacoes: aud.desidrogenizacoes,
  finalizadaEm: resumo.finalizadaEm,
  cancelada: resumo.cancelada,
  entregueEm: resumo.entregueEm,
  entreguePorNome: resumo.entreguePorNome,
});

export function useAuditoriaDia(
  data: AppData, dia: string, onErro: (e: unknown) => void,
): DadosDia {
  const cache = useRef(new Map<number, Entrada>());
  const [versao, setVersao] = useState(0);
  const [carregando, setCarregando] = useState(true);
  const emVoo = useRef(0);

  /**
   * Candidatas e a chave que diz se o que está em cache ainda serve. A chave sai
   * do resumo, que o sync já mantém fresco: enquanto ela não muda, a OS não foi
   * tocada e não há nada a rebuscar. Sem isto, cada evento do `useSync`
   * repetiria o pedido inteiro.
   */
  const candidatas = useMemo(() => {
    const { fim } = limitesDoDia(dia);
    const piso = fim - JANELA_DIAS * 86400000;
    return data.ordens
      .filter((o) => {
        if (o.emProcesso) return true;
        const t = Date.parse(o.iniciadaEm);
        return !Number.isNaN(t) && t >= piso && t < fim;
      })
      .map((o) => ({
        resumo: o,
        id: o.id,
        emProcesso: o.emProcesso,
        // `entregueEm` entra na chave porque a entrega não muda mais nada do
        // resumo: sem ela, marcar a entrega deixaria o detalhe em cache e o
        // evento nunca apareceria na auditoria do dia.
        chave: `${o.emProcesso}:${o.lotesFinalizados}:${o.totalLotes}:${o.entregueEm ?? ""}`,
      }));
  }, [data.ordens, dia]);

  useEffect(() => {
    const pendentes = candidatas.filter((c) => cache.current.get(c.id)?.chave !== c.chave);
    if (pendentes.length === 0) {
      setCarregando(false);
      return;
    }

    const meu = ++emVoo.current;
    setCarregando(true);

    /**
     * Dois pedidos por bloco, e não dois por OS. Antes eram um GET de detalhe
     * mais um de histórico por ordem — mais de cem conexões numa base pequena,
     * das quais o browser só serve seis de cada vez.
     *
     * As ordens em processo não entram no pedido de logs: `useAppData` já as
     * trouxe, e é a mesma poupança de metade dos GETs que existia antes.
     */
    void (async () => {
      try {
        const ids = pendentes.map((c) => c.id);
        const idsComLogs = pendentes.filter((c) => !c.emProcesso).map((c) => c.id);

        const [auditorias, logsPlanos] = await Promise.all([
          Promise.all(blocos(ids, IDS_POR_PEDIDO).map(api.auditoriaDeOrdens))
            .then((r) => r.flat()),
          Promise.all(blocos(idsComLogs, IDS_POR_PEDIDO).map(api.historicoDeOrdens))
            .then((r) => r.flat()),
        ]);

        // Uma carga mais nova já respondeu (o dia mudou a meio): descartar esta.
        if (meu !== emVoo.current) return;

        const audPorId = new Map(auditorias.map((a) => [a.ordemServicoId, a]));
        const logsPorId = indexarLogs(logsPlanos, idsComLogs);

        for (const c of pendentes) {
          const aud = audPorId.get(c.id);
          if (!aud) continue; // a OS sumiu entre o resumo e este pedido
          cache.current.set(c.id, {
            chave: c.chave,
            detalhe: juntar(c.resumo, aud),
            logs: c.emProcesso ? null : (logsPorId[c.id] ?? []),
          });
        }
        setVersao((v) => v + 1);
      } catch (e) {
        if (meu === emVoo.current) onErro(e);
      } finally {
        if (meu === emVoo.current) setCarregando(false);
      }
    })();
  }, [candidatas, onErro]);

  return useMemo(() => {
    const detalhes: Record<number, DetalheDia> = {};
    const logs: Record<number, LogDTO[]> = {};
    for (const c of candidatas) {
      const e = cache.current.get(c.id);
      if (!e) continue;
      detalhes[c.id] = e.detalhe;
      logs[c.id] = e.logs ?? data.logsPorOrdem[c.id] ?? [];
    }
    return { detalhes, logs, carregando };
    // `versao` é o que sinaliza que o cache mudou — a Map em si é estável.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [candidatas, versao, carregando, data.logsPorOrdem]);
}
