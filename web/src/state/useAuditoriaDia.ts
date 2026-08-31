import { useEffect, useMemo, useRef, useState } from "react";
import * as api from "../api/endpoints";
import type { LogDTO, OrdemDetalheDTO } from "../api/types";
import { limitesDoDia } from "../domain/auditoria";
import type { AppData } from "./useAppData";

/**
 * Os dados que a auditoria precisa e que `useAppData` não tem.
 *
 * `useAppData` só carrega `logsPorOrdem` das ordens em processo, e o resumo não
 * traz `finalizadaEm`, `cancelada` nem os nomes de quem abriu/encerrou — tudo
 * isso vive no `OrdemDetalheDTO`. Uma OS expedida às 10h de hoje sai do conjunto
 * "em processo" e desapareceria da auditoria justamente no momento em que
 * passou a interessar.
 */

/**
 * A partir de que idade uma OS deixa de ser candidata a ter movimento no dia.
 * Sem este corte, abrir a auditoria varreria o histórico inteiro (um GET de
 * detalhe + um de logs por ordem). Ordens ainda em processo entram sempre,
 * qualquer que seja a idade; o que a janela pode perder é uma OS iniciada há
 * mais de 30 dias e encerrada exactamente no dia auditado.
 */
const JANELA_DIAS = 30;

export interface DadosDia {
  detalhes: Record<number, OrdemDetalheDTO>;
  /** Logs por OS: os das ordens em processo vêm de `AppData`, os restantes daqui. */
  logs: Record<number, LogDTO[]>;
  carregando: boolean;
}

type Entrada = { chave: string; detalhe: OrdemDetalheDTO; logs: LogDTO[] | null };

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
   * tocada e não há nada a rebuscar. Sem isto, cada tique de 4s do `useSync`
   * repetiria N requisições.
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
        id: o.id,
        emProcesso: o.emProcesso,
        chave: `${o.emProcesso}:${o.lotesFinalizados}:${o.totalLotes}`,
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
    Promise.all(
      pendentes.map(async (c) => {
        const detalhe = await api.buscarOrdem(c.id);
        // A OS em processo já tem os logs em `AppData`; poupa-se metade dos GETs.
        const logs = c.emProcesso ? null : await api.historicoOrdem(c.id);
        return { c, entrada: { chave: c.chave, detalhe, logs } as Entrada };
      }),
    )
      .then((res) => {
        // Uma carga mais nova já respondeu (o dia mudou a meio): descartar esta.
        if (meu !== emVoo.current) return;
        for (const r of res) cache.current.set(r.c.id, r.entrada);
        setVersao((v) => v + 1);
      })
      .catch((e) => {
        if (meu === emVoo.current) onErro(e);
      })
      .finally(() => {
        if (meu === emVoo.current) setCarregando(false);
      });
  }, [candidatas, onErro]);

  return useMemo(() => {
    const detalhes: Record<number, OrdemDetalheDTO> = {};
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
