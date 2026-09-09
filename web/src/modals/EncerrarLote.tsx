import * as api from "../api/endpoints";
import type { CargaDTO } from "../api/types";
import { Corners } from "../components/Blueprint";
import { Modal, Vazio } from "../components/Modal";
import { caronasDa, etapaStyle, labelEtapaDoLog, logAbertoDaCarga } from "../domain/derive";
import { hhmm, iniciais, osNum, posLabel } from "../domain/format";
import { logsDe } from "../state/useAppData";
import type { Ctx } from "./tipos";

/**
 * Encerrar etapas em lote: fecha a etapa aberta de cada carga selecionada na
 * home e libera a carga da sua OS, que volta ao pool de livres. Cada OS afetada
 * leva um POST /api/ordens/{osId}/cargas/liberar com os seus cargaIds.
 *
 * O **lote da OS não muda** aqui, de propósito: virar o lote é decisão de
 * expedição parcial, tomada no botão "Expedir parcial" da Inspeção Final —
 * o único caminho para o 2º lote. Esta tela é rotina de chão de fábrica e
 * roda dezenas de vezes por turno.
 *
 * A seleção pode cruzar várias OS (cada carga aponta para a sua), daí o
 * agrupamento: uma chamada por OS, não uma por carga.
 *
 * Liberar uma carga acoplada encerra a etapa também para as OS que pegam carona
 * nela — o passo é um só, e fechá-lo fecha para todas. São OS que o operador
 * não selecionou, então cada uma é nomeada na linha da sua carga: encerrar
 * etapa de quem não está na lista não pode acontecer em silêncio.
 *
 * O que NÃO acontece aqui é desacoplar: as peças da carona continuam dentro do
 * tanque depois de a etapa fechar, e a carga volta ao pool ainda a levá-las. O
 * acoplamento só termina no × do detalhe da OS.
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
        caronas: caronasDa(carga, ctx.data.ordens),
      }];
    });

  /** Uma entrada por OS: [osId, cargaIds]. */
  const porOS = [...itens.reduce(
    (m, { carga, ordem }) => m.set(ordem.id, [...(m.get(ordem.id) ?? []), carga.id]),
    new Map<number, number[]>(),
  )];

  /** As OS caronas de todas as cargas da seleção — quem sai sem ter sido escolhido. */
  const caronas = [...new Map(
    itens.flatMap((i) => i.caronas).map((o) => [o.id, o]),
  ).values()];

  function confirmar() {
    if (itens.length === 0) return;
    ctx.agir({
      fazer: () =>
        Promise.all(
          porOS.map(([osId, ids]) => api.liberarCargas(osId, ctx.operador.id, ids)),
        ),
      ok: caronas.length === 0
        ? `${itens.length} carga(s) liberada(s) em ${porOS.length} OS.`
        : `${itens.length} carga(s) liberada(s) em ${porOS.length} OS`
          + ` · etapa encerrada também em ${caronas.length} OS acoplada(s),`
          + " que continuam acopladas.",
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
            className="btn2 btn2-p btn2-end"
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
      </div>

      <span className="lbl">
        Cargas a encerrar ({itens.length}) · {porOS.length} OS afetada(s)
      </span>
      <div style={{ display: "flex", flexDirection: "column", gap: 10, marginTop: 8 }}>
        {itens.map(({ carga, ordem, aberto, caronas: suas }) => (
          <div
            key={carga.id}
            className="bp"
            style={{ padding: "11px 14px", display: "flex", alignItems: "center", gap: 12,
              flexWrap: "wrap" }}
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

            {/* Largura total: estas OS não estão na seleção do operador, e uma
                nota espremida ao lado do horário passaria despercebida. */}
            {suas.length > 0 && (
              <div className="os-tv" style={{ width: "100%" }}>
                ⇋ Encerra também{" "}
                {suas.map((o, i) => (
                  <span key={o.id}>{i > 0 && " · "}<b>{osNum(o)}</b></span>
                ))}
                {" "}— acoplada(s) a esta carga. Continuam acopladas depois de encerrar.
              </div>
            )}
          </div>
        ))}
        {itens.length === 0 && (
          <Vazio>Nenhuma carga selecionada está vinculada a uma OS.</Vazio>
        )}
      </div>

      <div className="os-tv" style={{ marginTop: 16 }}>
        As cargas voltam para o pool de livres — <b>levando quem estiver acoplado</b>, que só sai
        no × do detalhe da OS — e o lote da OS não muda · a OS que ficar sem cargas aparece em
        Inspeção final, onde "Expedir parcial" encerra o lote.
      </div>
    </Modal>
  );
}
