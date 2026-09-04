import { useState } from "react";
import * as api from "../api/endpoints";
import { Corners } from "../components/Blueprint";
import { Modal } from "../components/Modal";
import { ScanField } from "../components/ScanField";
import { SEL_CHIP } from "../domain/derive";
import { iniciais, osNum, posLabel } from "../domain/format";
import { cargasLivres } from "../state/useAppData";
import type { Ctx } from "./tipos";

/**
 * Expedição parcial: encerra o lote corrente da OS e abre o seguinte, deixando
 * a OS aberta. É o único caminho do sistema para uma OS entrar em 2º lote, e
 * por ser irreversível só acontece depois desta confirmação — o botão da
 * Inspeção final apenas abre este diálogo.
 *
 * A OS fica aberta justamente à espera de novas cargas, então elas podem ser
 * escolhidas aqui mesmo. Escolher nenhuma é um caminho válido: o lote vira e as
 * cargas entram depois, pelo detalhe da OS.
 */
export function ExpedirParcialModal({ ctx, osId }: { ctx: Ctx; osId: number }) {
  const [sel, setSel] = useState<string[]>([]);
  const ordem = ctx.data.ordens.find((o) => o.id === osId);
  if (!ordem) return null;

  const livres = cargasLivres(ctx.data, ordem.posicao);
  // O lote aberto é sempre o último da OS — ver o ciclo de vida em Lote.java.
  const loteAtual = ordem.totalLotes;

  function lerCarga(tag: string) {
    ctx.agir({
      fazer: async () => {
        const c = await api.cargaPorTag(tag);
        if (!c) throw new Error(`Nenhuma carga com a tag "${tag}".`);
        if (!livres.some((l) => l.id === c.id)) {
          throw new Error(`A carga ${c.nome} não está livre em ${posLabel(ordem!.posicao)}.`);
        }
        setSel((s) => (s.includes(c.nome) ? s : [...s, c.nome]));
      },
    });
  }

  function confirmar() {
    const ids = livres.filter((c) => sel.includes(c.nome)).map((c) => c.id);
    ctx.agir({
      fazer: async () => {
        // A ordem importa: vincular antes de fechar o lote poria as cargas
        // novas no lote que está sendo expedido. Primeiro o lote vira, depois
        // as cargas entram — já no lote seguinte.
        await api.finalizarLote(osId, ctx.operador.id, []);
        if (ids.length) {
          await Promise.all(ids.map((id) => api.vincularCarga(osId, id, ctx.operador.id)));
        }
      },
      ok: ids.length
        ? `OS ${osNum(ordem!)} — lote ${loteAtual} encerrado; ${ids.length} carga(s) no lote ${loteAtual + 1}.`
        : `OS ${osNum(ordem!)} — lote ${loteAtual} encerrado; a OS segue aberta.`,
      depois: () => ctx.abrir({ tipo: "inspecao" }),
    });
  }

  return (
    <Modal
      kicker={`OS ${osNum(ordem)} · EXPEDIR PARCIAL`}
      titulo="Encerrar o lote e abrir o seguinte"
      onClose={ctx.fechar}
      footer={
        <>
          <button className="btn2" onClick={() => ctx.abrir({ tipo: "inspecao" })}>
            ← Voltar
          </button>
          {/* Sem `disabled` por seleção vazia: vincular depois é um caminho
              legítimo, e é o que acontece quando nada é escolhido. */}
          <button
            className="btn2 btn2-p"
            style={{ marginLeft: "auto" }}
            disabled={ctx.ocupado}
            onClick={confirmar}
          >
            {sel.length
              ? `Encerrar lote e vincular ${sel.length} carga(s)`
              : "Encerrar lote (vincular depois)"}
          </button>
        </>
      }
    >
      <div
        className="bp"
        style={{
          padding: "12px 15px",
          marginBottom: 16,
          display: "flex",
          alignItems: "center",
          gap: 10,
          flexWrap: "wrap",
        }}
      >
        <Corners />
        <span className="opav" style={{ borderColor: "rgba(89,128,166,.5)" }}>
          {iniciais(ctx.operador.nome)}
        </span>
        <div>
          <div className="os-tv">Responsável (turno atual)</div>
          <div style={{ font: "600 16px 'Barlow Condensed'" }}>{ctx.operador.nome}</div>
        </div>
        <div style={{ marginLeft: "auto", textAlign: "right" }}>
          <div className="os-tv">Lote</div>
          <div style={{ font: "600 16px 'Barlow Condensed'", color: "#2c455d" }}>
            {loteAtual} → {loteAtual + 1} · a OS continua aberta
          </div>
        </div>
      </div>

      <div className="scanhd">
        <span className="lbl">
          Cargas para o lote {loteAtual + 1} — opcional · livres em {posLabel(ordem.posicao)}
        </span>
        <ScanField
          rotulo="Ler carga"
          titulo="Encoste a etiqueta ou digite a tag da carga"
          placeholder="ex: CG-0142"
          onLer={lerCarga}
        />
      </div>
      <div style={{ display: "flex", flexWrap: "wrap", gap: 10 }}>
        {livres.map((c) => (
          <button
            key={c.id}
            className="cgtog"
            style={sel.includes(c.nome) ? SEL_CHIP : undefined}
            onClick={() =>
              setSel((s) => (s.includes(c.nome) ? s.filter((n) => n !== c.nome) : [...s, c.nome]))
            }
          >
            {c.nome}
            <span className="tp">{c.tipo}</span>
          </button>
        ))}
        {livres.length === 0 && <span className="os-tv">Nenhuma carga livre nesta posição.</span>}
      </div>

      <div className="os-tv" style={{ marginTop: 16 }}>
        {sel.length
          ? `${sel.length} carga(s) entram no lote ${loteAtual + 1} com a etapa inicial já aberta.`
          : "Sem cargas selecionadas: o lote vira e a OS fica à espera — dá para vincular depois "
            + "pelo detalhe da OS (Buscar OS → Vincular cargas)."}
      </div>
    </Modal>
  );
}
