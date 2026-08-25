import * as api from "../api/endpoints";
import { Corners } from "../components/Blueprint";
import { Modal, Vazio } from "../components/Modal";
import { diaHora, osNum } from "../domain/format";
import { logsDe } from "../state/useAppData";
import { SEM_API, type Ctx } from "./tipos";

/**
 * Inspeção final: OS abertas que já não têm carga vinculada — só falta
 * expedir. "Expedir" é a expedição total (POST /{id}/finalizar).
 */
export function InspecaoModal({ ctx }: { ctx: Ctx }) {
  const semCargas = ctx.data.ordens.filter(
    (o) => o.emProcesso && !ctx.data.cargas.some((c) => c.ordemAtualId === o.id),
  );

  return (
    <Modal
      kicker="INSPEÇÃO FINAL"
      titulo="OS sem cargas vinculadas"
      onClose={ctx.fechar}
      footer={
        <button className="btn2" onClick={ctx.fechar}>
          Fechar
        </button>
      }
    >
      <span className="lbl">Ordens de serviço sem cargas vinculadas ({semCargas.length})</span>
      <div style={{ display: "flex", flexDirection: "column", gap: 10, marginTop: 8 }}>
        {semCargas.map((o) => {
          const passos = logsDe(ctx.data, o.id).filter((l) => !l.cancelado).length;
          return (
            <div
              key={o.id}
              className="bp"
              style={{
                padding: "14px 16px",
                display: "flex",
                alignItems: "center",
                gap: "18px 24px",
                flexWrap: "wrap",
              }}
            >
              <Corners />
              <div style={{ minWidth: 96 }}>
                <div className="os-tv">Nº OS</div>
                <div className="os-num" style={{ fontSize: 22 }}>
                  {osNum(o)}
                </div>
              </div>
              <div style={{ minWidth: 132 }}>
                <div className="os-tv">Início</div>
                <div className="os-cli" style={{ fontSize: 16 }}>
                  {diaHora(o.iniciadaEm)}
                </div>
              </div>
              <div style={{ minWidth: 80 }}>
                <div className="os-tv">Etapas</div>
                <div className="os-cli" style={{ fontSize: 16 }}>
                  {passos}
                </div>
              </div>
              <div style={{ display: "flex", gap: 9, flex: "1 1 100%", justifyContent: "flex-end" }}>
                <button className="btn2" style={{ whiteSpace: "nowrap" }} disabled title={SEM_API.desidrogenizar}>
                  Desidrogenizar
                  <span className="na">Indisponível</span>
                </button>
                <button
                  className="btn2"
                  style={{ whiteSpace: "nowrap" }}
                  disabled
                  title={SEM_API.expedirParcial}
                >
                  Expedir parcial
                  <span className="na">Indisponível</span>
                </button>
                <button
                  className="btn2 btn2-x"
                  style={{ whiteSpace: "nowrap" }}
                  disabled={ctx.ocupado}
                  onClick={() =>
                    ctx.agir({
                      fazer: () => api.finalizarOrdem(o.id, ctx.operador.id),
                      ok: `OS ${osNum(o)} expedida e encerrada.`,
                    })
                  }
                >
                  Expedir
                </button>
              </div>
            </div>
          );
        })}
        {semCargas.length === 0 && <Vazio>Nenhuma OS aberta sem cargas vinculadas.</Vazio>}
      </div>
    </Modal>
  );
}
