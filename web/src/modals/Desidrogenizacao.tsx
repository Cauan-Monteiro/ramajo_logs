import { useState } from "react";
import * as api from "../api/endpoints";
import { Corners } from "../components/Blueprint";
import { Modal } from "../components/Modal";
import { SEL_CHIP } from "../domain/derive";
import { hhmm, iniciais, minutos, osNum } from "../domain/format";
import { useAgora } from "../state/useAgora";
import type { Ctx } from "./tipos";

/**
 * Aplicar uma desidrogenização a uma OS, a partir da Inspeção final.
 *
 * Não há campo de horário: o início é o relógio do SERVIDOR, carimbado no
 * INSERT (clock_timestamp), e o fim é início + duração, calculado pelo banco.
 * A previsão mostrada aqui usa o relógio do navegador e é só orientação — os
 * segundos entre o clique e o INSERT fazem o valor real diferir um pouco.
 */
export function DesidrogenizarModal({ ctx, osId }: { ctx: Ctx; osId: number }) {
  const [escolhida, setEscolhida] = useState<number | null>(null);
  // Mantém a previsão de término andando enquanto o modal fica aberto — mesmo
  // passo de meio minuto do resto da tela.
  const agora = useAgora(30000);

  const ordem = ctx.data.ordens.find((o) => o.id === osId);
  if (!ordem) return null;

  const disponiveis = ctx.data.desidrogenizacoes.filter((d) => d.ativo);
  const receita = disponiveis.find((d) => d.id === escolhida) ?? null;
  const fimPrevisto = receita ? agora + receita.duracaoMin * 60000 : null;

  function confirmar() {
    if (!receita) return;
    ctx.agir({
      fazer: () => api.aplicarDesidrogenizacao(osId, receita.id, ctx.operador.id),
      ok: `${receita.nome} aplicada à OS ${osNum(ordem!)}.`,
      depois: () => ctx.abrir({ tipo: "inspecao" }),
    });
  }

  return (
    <Modal
      kicker={`OS ${osNum(ordem)} · DESIDROGENIZAR`}
      titulo="Aplicar desidrogenização"
      onClose={ctx.fechar}
      footer={
        <>
          <button className="btn2" onClick={() => ctx.abrir({ tipo: "inspecao" })}>
            ← Voltar
          </button>
          <button
            className="btn2 btn2-p btn2-end"
            disabled={receita === null || ctx.ocupado}
            onClick={confirmar}
          >
            Iniciar agora
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
          <div className="os-tv">Temperatura do forno</div>
          <div style={{ font: "600 16px 'Barlow Condensed'" }}>
            {ctx.data.temperaturaDesidro === null
              ? "—"
              : `${ctx.data.temperaturaDesidro} °C`}
          </div>
        </div>
      </div>

      <span className="lbl">Desidrogenização</span>
      <div style={{ display: "flex", flexWrap: "wrap", gap: 10, margin: "8px 0 20px" }}>
        {disponiveis.map((d) => (
          <button
            key={d.id}
            className="cgtog"
            style={escolhida === d.id ? SEL_CHIP : undefined}
            title={d.observacao ?? undefined}
            onClick={() => setEscolhida((atual) => (atual === d.id ? null : d.id))}
          >
            {d.nome}
            <span className="tp">{minutos(d.duracaoMin)}</span>
          </button>
        ))}
        {disponiveis.length === 0 && (
          <span className="os-tv">
            Nenhuma desidrogenização cadastrada. Cadastre em Ajustes → Desidrogenização.
          </span>
        )}
      </div>

      {receita && (
        <div className="bp" style={{ padding: "16px 18px" }}>
          <Corners />
          <div style={{ display: "flex", gap: 24, flexWrap: "wrap" }}>
            <div>
              <div className="os-tv">Início</div>
              <div style={{ font: "600 20px 'Barlow Condensed'" }}>
                {hhmm(new Date(agora).toISOString())}
              </div>
            </div>
            <div>
              <div className="os-tv">Duração</div>
              <div style={{ font: "600 20px 'Barlow Condensed'" }}>
                {minutos(receita.duracaoMin)}
              </div>
            </div>
            <div>
              <div className="os-tv">Término previsto</div>
              <div style={{ font: "600 20px 'Barlow Condensed'" }}>
                {hhmm(new Date(fimPrevisto!).toISOString())}
              </div>
            </div>
          </div>
          {receita.observacao?.trim() && (
            <div className="os-tv" style={{ fontSize: 14, marginTop: 12 }}>
              {receita.observacao}
            </div>
          )}
        </div>
      )}

      <div className="os-tv" style={{ marginTop: 12 }}>
        O horário de início é o do servidor, no momento em que você confirmar · o
        término é preenchido sozinho a partir da duração
      </div>
    </Modal>
  );
}
