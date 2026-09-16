import * as api from "../api/endpoints";
import { Corners } from "../components/Blueprint";
import { Modal } from "../components/Modal";
import { diaHora, osNum, posLabel } from "../domain/format";
import type { Ctx } from "./tipos";

/* ══════════════════════════════════════════════════════════════════════════
   Entrega ao cliente
   ══════════════════════════════════════════════════════════════════════════

   A confirmação era um botão de dois toques na linha da tabela — discreto
   demais para uma ação que só a reabertura da OS desfaz, e que se confere
   contra um papel na mão. Aqui o operador vê reunido o que precisa bater:
   o Nº da OS, o cliente e a posição.

   Serve os dois caminhos: a aba Entregas e o botão do detalhe. `deDetalhe`
   diz de onde se veio — é para lá que o "← Voltar" e o pós-sucesso levam. */

export function EntregarModal(
  { ctx, osId, deDetalhe }: { ctx: Ctx; osId: number; deDetalhe?: boolean },
) {
  const ordem = ctx.data.ordens.find((o) => o.id === osId);
  if (!ordem) return null;

  const voltar = () => (deDetalhe ? ctx.abrir({ tipo: "det", osId }) : ctx.fechar());

  return (
    <Modal
      kicker={`OS ${osNum(ordem)} · ENTREGA`}
      titulo="Confirmar entrega ao cliente"
      onClose={ctx.fechar}
      footer={
        <>
          <button className="btn2" onClick={voltar}>
            ← Voltar
          </button>
          <button
            className="btn2 btn2-p btn2-end"
            disabled={ctx.ocupado}
            onClick={() =>
              ctx.agir({
                fazer: () => api.entregarOrdem(osId, ctx.operador.id),
                ok: `OS ${osNum(ordem)} marcada como entregue.`,
                depois: voltar,
              })
            }
          >
            Confirmar entrega
          </button>
        </>
      }
    >
      <div className="bp" style={{ padding: "18px 20px" }}>
        <Corners />
        {/* `flex-start`, como no cabeçalho do detalhe: um nome de cliente que
            quebre em duas linhas não empurra os rótulos vizinhos para baixo. */}
        <div className="os-resumo" style={{ alignItems: "flex-start", gap: 22 }}>
          <div>
            <div className="os-tv">Nº da OS</div>
            <div className="os-num" style={{ fontSize: 20 }}>{osNum(ordem)}</div>
          </div>
          <div style={{ flex: "1 1 160px" }}>
            <div className="os-tv">Cliente</div>
            <div className="os-cli" style={{ fontSize: 20 }}>{ordem.clienteNome}</div>
          </div>
          <div>
            <div className="os-tv">Posição</div>
            <div className="os-cli" style={{ fontSize: 16 }}>{posLabel(ordem.posicao)}</div>
          </div>
          <div>
            <div className="os-tv">Expedida em</div>
            <div className="os-cli" style={{ fontSize: 16 }}>{diaHora(ordem.finalizadaEm)}</div>
          </div>
        </div>
      </div>
      <div className="os-tv" style={{ marginTop: 14 }}>
        A entrega é carimbada agora, em seu nome. Só a reabertura da OS a desfaz — e
        ela custa o registro da expedição.
      </div>
    </Modal>
  );
}
