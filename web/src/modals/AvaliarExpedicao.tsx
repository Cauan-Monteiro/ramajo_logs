import { useEffect, useState } from "react";
import * as api from "../api/endpoints";
import { Corners } from "../components/Blueprint";
import {
  FormAvaliacao, formDe, paraInput, type FormAvaliacaoState,
} from "../components/FormAvaliacao";
import { Modal } from "../components/Modal";
import { diaHora, iniciais, osNum } from "../domain/format";
import type { Ctx } from "./tipos";

/**
 * O "Expedir" da Inspeção final: a avaliação vem antes da expedição total.
 *
 * Opcional — "Expedir sem avaliar" segue direto, e o ADMIN pode avaliar depois
 * pelo Ajustes. Com avaliação, as duas coisas vão numa chamada só
 * (POST /finalizar com `avaliacao`), e a API a grava já verificada: salvar é
 * concluir. Uma avaliação recusada não expede.
 *
 * Se a OS já tem avaliação (o ADMIN avaliou com ela ainda em produção), o
 * formulário abre com ela. Salvar a substitui; expedir sem avaliar a mantém.
 */
export function AvaliarExpedicaoModal({ ctx, osId }: { ctx: Ctx; osId: number }) {
  const ordem = ctx.data.ordens.find((o) => o.id === osId);
  const [form, setForm] = useState<FormAvaliacaoState>(() => formDe());
  const [existente, setExistente] = useState<{ nome: string; em: string } | null>(null);

  useEffect(() => {
    let vivo = true;
    api.avaliacaoOrdem(osId)
      .then((a) => {
        if (!vivo || !a) return;
        setForm(formDe(a));
        setExistente({ nome: a.avaliadaPorNome, em: a.avaliadaEm });
      })
      .catch(() => {});
    return () => {
      vivo = false;
    };
  }, [osId]);

  if (!ordem) return null;

  function expedir(comAvaliacao: boolean) {
    ctx.agir({
      fazer: () =>
        api.finalizarOrdem(osId, ctx.operador.id, comAvaliacao ? paraInput(form) : undefined),
      ok: comAvaliacao
        ? `OS ${osNum(ordem!)} avaliada, expedida e encerrada.`
        : `OS ${osNum(ordem!)} expedida e encerrada, sem avaliação.`,
      depois: () => ctx.abrir({ tipo: "inspecao" }),
    });
  }

  return (
    <Modal
      kicker={`OS ${osNum(ordem)} · INSPEÇÃO FINAL`}
      titulo="Avaliação antes de expedir"
      onClose={ctx.fechar}
      footer={
        <>
          <button className="btn2" onClick={() => ctx.abrir({ tipo: "inspecao" })}>
            ← Voltar
          </button>
          <button
            className="btn2 btn2-end"
            disabled={ctx.ocupado}
            title="Expede sem gravar avaliação. Um ADMIN pode avaliar depois, pelo Ajustes."
            onClick={() => expedir(false)}
          >
            Expedir sem avaliar
          </button>
          <button
            className="btn2 btn2-x"
            disabled={ctx.ocupado}
            title="Grava a avaliação como concluída e expede a OS."
            onClick={() => expedir(true)}
          >
            Salvar e expedir
          </button>
        </>
      }
    >
      <div
        className="bp"
        style={{
          padding: "12px 15px", marginBottom: 16,
          display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap",
        }}
      >
        <Corners />
        <span className="opav" style={{ borderColor: "rgba(89,128,166,.5)" }}>
          {iniciais(ctx.operador.nome)}
        </span>
        <div>
          <div className="os-tv">Avaliador (turno atual)</div>
          <div style={{ font: "600 16px 'Barlow Condensed'" }}>{ctx.operador.nome}</div>
        </div>
        <div style={{ marginLeft: "auto", textAlign: "right", minWidth: 0 }}>
          <div className="os-tv">Cliente</div>
          <div style={{ font: "600 16px 'Barlow Condensed'", color: "#2c455d" }}>
            {ordem.clienteNome}
          </div>
        </div>
      </div>

      {existente && (
        <div className="os-tv" style={{ fontSize: 14, marginBottom: 12 }}>
          Esta OS já tem avaliação de <b>{existente.nome}</b> ({diaHora(existente.em)}).
          Salvar substitui; expedir sem avaliar a mantém.
        </div>
      )}

      <FormAvaliacao form={form} onForm={setForm} />
    </Modal>
  );
}
