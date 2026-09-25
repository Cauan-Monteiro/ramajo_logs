import * as api from "../api/endpoints";
import { Corners } from "../components/Blueprint";
import { Modal } from "../components/Modal";
import { osNum, posLabels } from "../domain/format";
import type { Ctx } from "./tipos";

/* ══════════════════════════════════════════════════════════════════════════
   Entrega em lote
   ══════════════════════════════════════════════════════════════════════════

   Espelho do `EntregarModal` para várias OS. Quem carrega o caminhão marca
   na fila o que vai sair, confirma aqui e a lista inteira ganha o carimbo
   numa só transação — falhou uma, não vai nenhuma. Ver `entregarLote` no
   `OrdemServicoService`. */

export function EntregarLoteModal({
  ctx, selecao, aoConcluir,
}: {
  ctx: Ctx;
  selecao: number[];
  aoConcluir?: () => void;
}) {
  const ordens = selecao
    .map((id) => ctx.data.ordens.find((o) => o.id === id))
    .filter((o): o is NonNullable<typeof o> => !!o);

  const n = ordens.length;

  function confirmar() {
    ctx.agir({
      fazer: () => api.entregarOrdensLote(ordens.map((o) => o.id), ctx.operador.id),
      ok: `${n} OS marcada(s) como entregue(s).`,
      depois: () => { aoConcluir?.(); ctx.fechar(); },
    });
  }

  return (
    <Modal
      kicker={`ENTREGA · ${n} OS`}
      titulo="Confirmar entrega ao cliente"
      onClose={ctx.fechar}
      footer={
        <>
          <button className="btn2" onClick={ctx.fechar}>
            Cancelar
          </button>
          <button
            className="btn2 btn2-p btn2-end"
            disabled={ctx.ocupado || n === 0}
            onClick={confirmar}
          >
            Confirmar entrega · {n} OS
          </button>
        </>
      }
    >
      {/* Lista compacta: o operador confere OS, cliente e posição contra o
          papel na mão antes de disparar. */}
      <div className="bp" style={{ padding: "12px 14px" }}>
        <Corners />
        <span className="lbl">OS selecionadas ({n})</span>
        <div style={{ display: "flex", flexDirection: "column", gap: 6, marginTop: 8 }}>
          {ordens.map((o) => (
            <div
              key={o.id}
              style={{
                display: "grid",
                gridTemplateColumns: "80px 1fr auto",
                gap: 12,
                alignItems: "baseline",
                padding: "6px 0",
                borderBottom: "1px solid rgba(0,0,0,.06)",
              }}
            >
              <span className="os-num" style={{ fontSize: 15 }}>{osNum(o)}</span>
              <span className="os-cli" style={{ fontSize: 15 }}>{o.clienteNome}</span>
              <span className="os-tv">{posLabels(o.posicoes)}</span>
            </div>
          ))}
        </div>
      </div>
      <div className="os-tv" style={{ marginTop: 14 }}>
        Todas ganham o mesmo instante e ficam em seu nome. Se qualquer uma
        estiver inválida (cancelada, não expedida ou já entregue), nenhuma é
        carimbada.
      </div>
    </Modal>
  );
}
