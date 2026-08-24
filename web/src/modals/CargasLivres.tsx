import { Modal } from "../components/Modal";
import { cargasLivres } from "../state/useAppData";
import { posLabel } from "../domain/format";
import type { Ctx } from "./tipos";

export function CargasLivresModal({ ctx }: { ctx: Ctx }) {
  const livres = cargasLivres(ctx.data, ctx.posicao);
  const label = posLabel(ctx.posicao);

  return (
    <Modal
      kicker={`POSIÇÃO ${label.toUpperCase()}`}
      titulo={`Cargas livres · ${label}`}
      onClose={ctx.fechar}
      footer={<button className="btn2" onClick={ctx.fechar}>Fechar</button>}
    >
      <div className="os-tv" style={{ marginBottom: 16 }}>
        Cargas disponíveis em <b>{label}</b> para vincular a uma OS.
      </div>
      <div style={{ display: "flex", flexWrap: "wrap", gap: 10 }}>
        {livres.map((c) => (
          <span key={c.id} className="cg-chip" style={{ minWidth: 84, padding: "12px 10px" }}>
            {c.nome}
            <span className="tp">{c.tipo}</span>
          </span>
        ))}
        {livres.length === 0 && (
          <span className="os-tv">
            Nenhuma carga livre nesta posição. Cadastre em “Registrar cargas”.
          </span>
        )}
      </div>
    </Modal>
  );
}
