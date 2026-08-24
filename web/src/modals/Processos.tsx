import { Modal, Vazio } from "../components/Modal";
import { etapaStyle, isAberto, labelEtapaDoLog } from "../domain/derive";
import { hhmm, posLabel } from "../domain/format";
import { logsDe } from "../state/useAppData";
import type { LogDTO, OrdemResumoDTO } from "../api/types";
import type { Ctx } from "./tipos";

/** OS com passo em andamento nesta posição, agrupadas por processo. */
export function ProcessosModal({ ctx }: { ctx: Ctx }) {
  const label = posLabel(ctx.posicao);
  const naPos = ctx.data.ordens.filter((o) => o.emProcesso && o.posicao === ctx.posicao);

  const grupos = new Map<string, { ordem: OrdemResumoDTO; log: LogDTO }[]>();
  for (const o of naPos) {
    for (const l of logsDe(ctx.data, o.id)) {
      if (!isAberto(l)) continue;
      const atual = grupos.get(l.processoDescricao) ?? [];
      atual.push({ ordem: o, log: l });
      grupos.set(l.processoDescricao, atual);
    }
  }

  return (
    <Modal
      kicker={`POSIÇÃO ${label.toUpperCase()}`}
      titulo="Processos em andamento"
      onClose={ctx.fechar}
      footer={<button className="btn2" onClick={ctx.fechar}>Fechar</button>}
    >
      <div className="os-tv" style={{ marginBottom: 16 }}>
        Ordens de serviço com passo <b>em andamento</b> em <b>{label}</b>, por processo.
      </div>
      <div style={{ display: "flex", flexDirection: "column", gap: 18 }}>
        {[...grupos.entries()].map(([proc, itens]) => {
          const etapa = ctx.data.processos.find((p) => p.descricao === proc)?.etapa ?? null;
          return (
            <div key={proc} className="bp" style={{ padding: "16px 18px" }}>
              <i className="corner tl" /><i className="corner tr" />
              <i className="corner bl" /><i className="corner br" />
              <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 12 }}>
                <span className="etp" style={etapaStyle(etapa)}>
                  {labelEtapaDoLog(itens[0].log, ctx.data.processos)}
                </span>
                <span style={{ font: "600 20px 'Barlow Condensed'" }}>{proc}</span>
                <span style={{
                  marginLeft: "auto", font: "400 13px 'Barlow'", color: "rgba(29,31,32,.45)",
                }}>
                  {itens.length} em andamento
                </span>
              </div>
              <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
                {itens.map(({ ordem, log }) => (
                  <div
                    key={log.id}
                    className="osrow"
                    style={{ padding: "10px 12px", border: "1px solid rgba(0,0,0,.1)", cursor: "pointer" }}
                    onClick={() => ctx.abrir({ tipo: "det", osId: ordem.id })}
                  >
                    <span className="os-num" style={{ fontSize: 19, minWidth: 66 }}>
                      #{ordem.idExterno ?? ordem.id}
                    </span>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div className="os-cli" style={{ fontSize: 15 }}>{ordem.clienteNome}</div>
                      <div className="os-tv">Carga {log.cargaNome} · {log.responsavelNome}</div>
                    </div>
                    <span className="live-pill">
                      <span className="live-dot" />desde {hhmm(log.iniciadoEm)}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          );
        })}
        {grupos.size === 0 && <Vazio>Nenhum passo em andamento nesta posição no momento.</Vazio>}
      </div>
    </Modal>
  );
}
