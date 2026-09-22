import { useMemo, useState } from "react";
import * as api from "../api/endpoints";
import type { Posicao } from "../api/types";
import { BuscaCliente } from "../components/BuscaCliente";
import { Modal } from "../components/Modal";
import { posLabel } from "../domain/format";
import type { Ctx } from "./tipos";

/**
 * Cadastro rápido de uma OS vazia, para ir de carona numa carga.
 *
 * Abre POR CIMA do modal de vínculo (criar OS ou vincular cargas) em vez de
 * trocar o `ModalState`: trocar desmontaria o modal de baixo e levaria junto
 * o Nº, o cliente, as cargas marcadas e os acoplamentos já escolhidos. Por
 * isso quem o renderiza é o modal pai, como irmão do seu `<Modal>` — dentro
 * do corpo, o overlay ficaria recortado pelo `overflow` do `.dlg-bdy`.
 *
 * A OS é criada na hora, sem cargas: é o mesmo que abrir uma OS vazia pelo
 * "Criar OS" e acoplá-la depois, só que sem sair do fluxo. Se o operador
 * cancelar o modal de baixo, ela fica aberta — como ficaria antes.
 *
 * A posição não se escolhe: acoplar exige o mesmo setor da carga.
 */
export function OSRapidaModal({
  ctx, posicao, nosReservados, onCriada, onVoltar,
}: {
  ctx: Ctx;
  posicao: Posicao;
  /** Nºs ainda não gravados mas já tomados — o da OS que o modal de baixo vai criar. */
  nosReservados: string[];
  onCriada: (osId: number) => void;
  onVoltar: () => void;
}) {
  const [externo, setExterno] = useState("");
  const [clienteId, setClienteId] = useState<number | null>(null);

  const v = externo.trim();
  const conflito = useMemo(() => {
    if (!v) return null;
    if (nosReservados.includes(v)) return "Este Nº é o da OS que você está criando.";
    // Só colide no MESMO setor: o Nº do ERP é único por posição (V20), e o
    // cadastro rápido quer uma OS nova aqui — não vincular-se à que já existe.
    const aberta = ctx.data.ordens.find(
      (o) => o.idExterno !== null && String(o.idExterno) === v
        && o.posicoes.includes(posicao) && o.emProcesso);
    return aberta
      ? `Já existe uma OS aberta com o Nº ${v} em ${posLabel(posicao)} (${aberta.clienteNome}).`
      : null;
  }, [v, nosReservados, ctx.data.ordens, posicao]);

  function criar() {
    if (clienteId === null || conflito) return;
    let novaId = 0;
    ctx.agir({
      fazer: async () => {
        const os = await api.criarOrdem({
          clienteId,
          operadorId: ctx.operador.id,
          idExterno: v ? Number(v) : null,
          posicao,
          cargaIds: [],
        });
        novaId = os.id;
      },
      ok: "OS de carona criada · ela fica aberta mesmo que você cancele o vínculo.",
      depois: () => onCriada(novaId),
    });
  }

  return (
    <Modal
      kicker="NOVA OS · CARONA"
      titulo="Cadastro rápido"
      onClose={onVoltar}
      footer={
        <>
          <button className="btn2" onClick={onVoltar}>
            ← Voltar
          </button>
          <button
            className="btn2 btn2-p btn2-end"
            disabled={clienteId === null || conflito !== null || ctx.ocupado}
            onClick={criar}
          >
            Criar e acoplar
          </button>
        </>
      }
    >
      <span className="lbl">1 · Nº da ordem de serviço</span>
      <div style={{ maxWidth: 300, marginBottom: 8 }}>
        <input
          className="inp"
          inputMode="numeric"
          placeholder="ex: 42"
          value={externo}
          autoFocus
          onChange={(e) => setExterno(e.target.value.replace(/\D/g, ""))}
        />
      </div>
      {conflito && (
        <div className="os-tv" style={{ color: "#a33", marginBottom: 12 }}>
          {conflito}
        </div>
      )}

      <div className="os-tv" style={{ margin: "8px 0 18px" }}>
        Posição: <b>{posLabel(posicao)}</b> · a mesma da carga onde ela vai de carona
      </div>

      <span className="lbl">2 · Cliente</span>
      <BuscaCliente
        clientes={ctx.data.clientes}
        selecionado={clienteId}
        onEscolher={setClienteId}
      />
    </Modal>
  );
}
