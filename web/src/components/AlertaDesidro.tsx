import { useLayoutEffect, useRef } from "react";
import type { DesidroEmAndamentoDTO } from "../api/types";
import { hhmm, minutos, osNum, posLabel } from "../domain/format";
import { useAgora } from "../state/useAgora";
import { SILENCIO_MS } from "../state/useAlertaDesidro";

/**
 * A faixa de desidrogenização estourada, logo abaixo da barra de navegação.
 *
 * Mora no App e não no Dashboard: o forno é um só, e quem está em Ajustes ou
 * Relatórios precisa de saber tanto quanto quem está na posição.
 *
 * Fica no fluxo da página, não sobreposta: um aviso que só sai com "Ciente" não
 * pode tapar a primeira linha da tabela durante minutos. O Toast, esse sim
 * flutuante, desce a altura da faixa pela variável `--alerta-h`.
 */
export function AlertaDesidro({
  pendentes, onCiente, onCienteTodas, onVerOS, silenciadoAte, onSilenciar, onReativarSom,
}: {
  pendentes: DesidroEmAndamentoDTO[];
  onCiente: (id: number) => void;
  onCienteTodas: (ids: number[]) => void;
  onVerOS: (d: DesidroEmAndamentoDTO) => void;
  /** Instante (ms) até quando o alarme está calado; `null` = tocando. */
  silenciadoAte: number | null;
  onSilenciar: () => void;
  onReativarSom: () => void;
}) {
  const agora = useAgora(30000);
  const ref = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    const el = ref.current;
    const pai = el?.parentElement;
    if (!el || !pai) return;
    const medir = () => pai.style.setProperty("--alerta-h", `${el.offsetHeight}px`);
    medir();
    const ro = new ResizeObserver(medir);
    ro.observe(el);
    return () => {
      ro.disconnect();
      pai.style.removeProperty("--alerta-h");
    };
  }, []);

  const varias = pendentes.length > 1;

  return (
    <div ref={ref} className="alerta-desidro" role="alert">
      <div className="ad-cab">
        <i className="turn-dot ad-dot" />
        <span className="ad-tit">
          {varias
            ? `${pendentes.length} desidrogenizações terminaram · retirar do forno`
            : "Desidrogenização terminou · retirar do forno"}
        </span>
        <div className="ad-acoes">
          {silenciadoAte === null ? (
            <button type="button" className="ad-b" onClick={onSilenciar}>
              Silenciar {SILENCIO_MS / 60000} min
            </button>
          ) : (
            <button type="button" className="ad-b" onClick={onReativarSom}>
              Som silenciado até {hhmm(new Date(silenciadoAte).toISOString())} · reativar
            </button>
          )}
          {varias && (
            <button
              type="button"
              className="ad-b"
              onClick={() => onCienteTodas(pendentes.map((d) => d.id))}
            >
              Ciente em todas
            </button>
          )}
        </div>
      </div>

      <div className="ad-lista">
        {pendentes.map((d) => (
          <div key={d.id} className="ad-linha">
            <span className="ad-os">
              OS {osNum({ id: d.ordemServicoId, idExterno: d.ordemIdExterno })}
            </span>
            <span className="ad-tx">
              {d.nome} · {posLabel(d.posicao)} · terminou {hhmm(d.finalizadaEm)}
              {" "}(há {minutos((agora - new Date(d.finalizadaEm).getTime()) / 60000)})
            </span>
            <button type="button" className="ad-b" onClick={() => onVerOS(d)}>
              Ver OS
            </button>
            <button type="button" className="ad-b forte" onClick={() => onCiente(d.id)}>
              Ciente
            </button>
          </div>
        ))}
      </div>
    </div>
  );
}
