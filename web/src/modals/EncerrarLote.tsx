import * as api from "../api/endpoints";
import type { CargaDTO } from "../api/types";
import { Corners } from "../components/Blueprint";
import { Modal, Vazio } from "../components/Modal";
import { etapaStyle, labelEtapaDoLog, logAbertoDaCarga } from "../domain/derive";
import { hhmm, iniciais, osNum, posLabel } from "../domain/format";
import { logsDe } from "../state/useAppData";
import type { Ctx } from "./tipos";

/**
 * Encerrar etapas em lote: fecha a etapa aberta de cada carga selecionada na
 * home e libera a carga da sua OS. É a expedição parcial vista pelo lado da
 * carga — cargas que saem da OS *são* um lote, então cada OS afetada leva um
 * POST /api/ordens/{osId}/lotes/finalizar com os seus cargaIds, o que fecha o
 * lote corrente e abre o seguinte.
 *
 * A seleção pode cruzar várias OS (cada carga aponta para a sua), daí o
 * agrupamento: uma chamada por OS, não uma por carga.
 */
export function EncerrarLoteModal({ ctx, selecao }: { ctx: Ctx; selecao: string[] }) {
  const label = posLabel(ctx.posicao);

  const itens = selecao
    .map((nome) => ctx.data.cargas.find((c) => c.nome === nome))
    .filter((c): c is CargaDTO => !!c && c.ordemAtualId !== null)
    // flatMap e não map+filter: descartar a OS ausente aqui já estreita o tipo,
    // e `ordem` fica não-nulo no resto do componente.
    .flatMap((carga) => {
      const ordem = ctx.data.ordens.find((o) => o.id === carga.ordemAtualId);
      if (!ordem) return [];
      return [{
        carga,
        ordem,
        // Carga sem etapa aberta entra na chamada do mesmo jeito: o backend só
        // fecha o passo se existir, então ela é apenas liberada — sem 409.
        aberto: logAbertoDaCarga(carga.nome, logsDe(ctx.data, ordem.id)),
      }];
    });

  /** Uma entrada por OS: [osId, cargaIds]. */
  const porOS = [...itens.reduce(
    (m, { carga, ordem }) => m.set(ordem.id, [...(m.get(ordem.id) ?? []), carga.id]),
    new Map<number, number[]>(),
  )];

  function confirmar() {
    if (itens.length === 0) return;
    ctx.agir({
      fazer: () =>
        Promise.all(
          porOS.map(([osId, ids]) => api.finalizarLote(osId, ctx.operador.id, ids)),
        ),
      ok: `${itens.length} carga(s) liberada(s) em ${porOS.length} OS.`,
      depois: ctx.fechar,
    });
  }

  return (
    <Modal
      kicker={`POSIÇÃO ${label.toUpperCase()} · EM LOTE`}
      titulo="Encerrar etapas das cargas selecionadas"
      onClose={ctx.fechar}
      footer={
        <>
          <button className="btn2" onClick={ctx.fechar}>
            Cancelar
          </button>
          <button
            className="btn2 btn2-p"
            style={{ marginLeft: "auto" }}
            disabled={itens.length === 0 || ctx.ocupado}
            onClick={confirmar}
          >
            Encerrar · {itens.length} carga(s)
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
      </div>

      <span className="lbl">
        Cargas a encerrar ({itens.length}) · {porOS.length} OS afetada(s)
      </span>
      <div style={{ display: "flex", flexDirection: "column", gap: 10, marginTop: 8 }}>
        {itens.map(({ carga, ordem, aberto }) => (
          <div
            key={carga.id}
            className="bp"
            style={{ padding: "11px 14px", display: "flex", alignItems: "center", gap: 12 }}
          >
            <Corners />
            <span className="cg-chip" style={{ flex: "none" }}>
              {carga.nome}
              <span className="tp">OS {osNum(ordem)}</span>
            </span>

            <span
              className="etp"
              style={
                aberto
                  ? etapaStyle(
                    ctx.data.processos.find((p) => p.descricao === aberto.processoDescricao)
                      ?.etapa ?? null,
                  )
                  : { background: "#c9c9cc", color: "#f2f2f3" }
              }
            >
              {aberto ? labelEtapaDoLog(aberto, ctx.data.processos) : "○"}
            </span>

            <span
              style={
                aberto
                  ? { color: "#2c455d", font: "600 15px 'Barlow Condensed'" }
                  : { color: "rgba(29,31,32,.4)", font: "italic 500 15px 'Barlow Condensed'" }
              }
            >
              {aberto ? aberto.processoDescricao : "Sem etapa aberta · só será liberada"}
            </span>

            {aberto && (
              <span className="cmuted" style={{ marginLeft: "auto" }}>
                desde {hhmm(aberto.iniciadoEm)}
              </span>
            )}
          </div>
        ))}
        {itens.length === 0 && (
          <Vazio>Nenhuma carga selecionada está vinculada a uma OS.</Vazio>
        )}
      </div>

      <div className="os-tv" style={{ marginTop: 16 }}>
        Cada OS afetada fecha o lote atual e abre o seguinte · as cargas voltam para o pool de
        livres, e a OS que ficar sem cargas aparece em Inspeção final.
      </div>
    </Modal>
  );
}
