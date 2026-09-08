import { useState } from "react";
import * as api from "../api/endpoints";
import { Corners } from "../components/Blueprint";
import { Modal } from "../components/Modal";
import { SEL_PICK, dotStyle } from "../domain/derive";
import { ETAPAS, iniciais, osNum, posLabel } from "../domain/format";
import { AcoplarOs } from "./AcoplarOs";
import type { Ctx } from "./tipos";

/**
 * Abre o mesmo processo para todas as cargas selecionadas na home. Cada carga
 * vira um POST /api/ordens/{osId}/logs na SUA própria OS — o service fecha o
 * passo anterior de cada carga sozinho (abrirLog), por isso não há chamada de
 * finalizar aqui.
 */
export function PassoLoteModal({ ctx, selecao }: { ctx: Ctx; selecao: string[] }) {
  const [processoId, setProcessoId] = useState<number | null>(null);
  const [acopladas, setAcopladas] = useState<number[]>([]);
  const label = posLabel(ctx.posicao);

  const cargas = selecao
    .map((nome) => ctx.data.cargas.find((c) => c.nome === nome))
    .filter((c): c is NonNullable<typeof c> => !!c && c.ordemAtualId !== null);

  const permitidos = ctx.data.processos.filter(
    (p) => p.ativo && p.posicoes.includes(ctx.posicao),
  );

  const grupos = ETAPAS.map((g) => ({
    ...g,
    itens: permitidos.filter((p) => p.etapa === g.key),
  })).filter((g) => g.itens.length > 0);

  /**
   * Acoplar exige UMA carga: com duas ou mais não há como dizer em qual tanque
   * as peças das outras OS entraram. Com uma só, a titular é a OS dessa carga.
   */
  const cargaUnica = cargas.length === 1 ? cargas[0] : undefined;
  const acopladasEfetivas = cargaUnica ? acopladas : [];

  function confirmar() {
    if (processoId === null) return;
    ctx.agir({
      fazer: () =>
        Promise.all(
          cargas.map((c) =>
            api.iniciarLog(
              c.ordemAtualId as number, c.id, processoId, ctx.operador.id,
              // Só a carga única leva acopladas; no lote a lista é sempre vazia.
              c.id === cargaUnica?.id ? acopladasEfetivas : [],
            ),
          ),
        ),
      ok: acopladasEfetivas.length > 0
        ? `Etapa aberta na carga ${cargaUnica?.nome} para ${acopladasEfetivas.length + 1} OS.`
        : `Etapa aberta em ${cargas.length} carga(s).`,
      depois: ctx.fechar,
    });
  }

  return (
    <Modal
      kicker={`POSIÇÃO ${label.toUpperCase()} · EM LOTE`}
      titulo="Abrir etapa nas cargas selecionadas"
      onClose={ctx.fechar}
      footer={
        <>
          <button className="btn2" onClick={ctx.fechar}>
            Cancelar
          </button>
          <button
            className="btn2 btn2-p"
            style={{ marginLeft: "auto" }}
            disabled={processoId === null || cargas.length === 0 || ctx.ocupado}
            onClick={confirmar}
          >
            Abrir etapa · {cargas.length} carga(s)
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

      <span className="lbl">Cargas selecionadas ({cargas.length})</span>
      <div style={{ display: "flex", flexWrap: "wrap", gap: 9, marginBottom: 20 }}>
        {cargas.map((c) => {
          const ordem = ctx.data.ordens.find((o) => o.id === c.ordemAtualId);
          return (
            <span key={c.id} className="cg-chip">
              {c.nome}
              <span className="tp">OS {ordem ? osNum(ordem) : "—"}</span>
            </span>
          );
        })}
      </div>

      <span className="lbl">Processo a abrir</span>
      <div style={{ display: "flex", flexDirection: "column", gap: 16, marginTop: 8 }}>
        {grupos.map((g) => (
          <div key={g.key}>
            <div
              style={{
                font: "600 11px 'Barlow Condensed'",
                letterSpacing: ".12em",
                textTransform: "uppercase",
                color: "rgba(29,31,32,.55)",
                display: "flex",
                alignItems: "center",
                gap: 8,
                marginBottom: 9,
              }}
            >
              <span className="turn-dot" style={dotStyle(g.key)} />
              {g.label}
            </div>
            <div style={{ display: "flex", flexWrap: "wrap", gap: 10 }}>
              {g.itens.map((p) => (
                <button
                  key={p.id}
                  className="pick"
                  style={{ flex: "none", ...(processoId === p.id ? SEL_PICK : {}) }}
                  onClick={() => setProcessoId(p.id)}
                >
                  <span className="turn-dot" style={dotStyle(p.etapa)} />
                  {p.descricao}
                </button>
              ))}
            </div>
          </div>
        ))}
        {grupos.length === 0 && (
          <span className="os-tv">Nenhum processo habilitado para {label}.</span>
        )}
      </div>

      {cargaUnica ? (
        <AcoplarOs
          ctx={ctx}
          posicao={ctx.posicao}
          osIdTitular={cargaUnica.ordemAtualId ?? undefined}
          valor={acopladas}
          onChange={setAcopladas}
        />
      ) : (
        // Com 2+ cargas a seção some — mas a dica fica, porque é ela que ensina
        // o recurso a existir para quem só usa este caminho.
        <div className="os-tv" style={{ marginTop: 20 }}>
          Peças de outra OS neste mesmo tanque? Selecione <b>apenas 1 carga</b> para
          poder acoplar as demais OS a ela.
        </div>
      )}

      <div className="os-tv" style={{ marginTop: 16 }}>
        Uma etapa é aberta para cada carga, na sua própria OS · a etapa anterior de cada uma é
        fechado automaticamente.
      </div>
    </Modal>
  );
}
