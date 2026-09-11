import { useState } from "react";
import type { AvaliacaoDTO } from "../api/types";
import { diaHora } from "../domain/format";
import { ITENS_AVALIACAO, contarAvaliados, situacaoItem } from "./FormAvaliacao";

/** A linha do cabeçalho: dá para saber o essencial sem expandir. */
function resumo(a: AvaliacaoDTO | null): string {
  if (!a) return "Sem avaliação";
  const { avaliados, observacoes } = contarAvaliados(a);
  const obs = observacoes === 0 ? "" : observacoes === 1 ? " · 1 observação" : ` · ${observacoes} observações`;
  return `${avaliados} de ${ITENS_AVALIACAO.length} tópicos${obs}`;
}

/**
 * A avaliação da inspeção final, só leitura e recolhida por padrão — no
 * detalhe da OS ela é consulta ocasional, e aberta empurraria as etapas para
 * baixo.
 */
export function ResumoAvaliacao({ avaliacao }: { avaliacao: AvaliacaoDTO | null }) {
  const [aberta, setAberta] = useState(false);

  return (
    <div style={{ margin: "0 0 18px" }}>
      <button
        type="button"
        className="lbl"
        aria-expanded={aberta}
        disabled={!avaliacao}
        onClick={() => setAberta((v) => !v)}
        style={{
          display: "flex", alignItems: "center", gap: 8, width: "100%",
          background: "none", border: 0, padding: 0, textAlign: "left",
          cursor: avaliacao ? "pointer" : "default",
        }}
      >
        {avaliacao && <span aria-hidden>{aberta ? "▾" : "▸"}</span>}
        Avaliação · {resumo(avaliacao)}
      </button>

      {aberta && avaliacao && (
        <div style={{ marginTop: 6 }}>
          {ITENS_AVALIACAO.map(({ key, label }) => {
            const v = avaliacao[key];
            return (
              <div key={key} className="tline">
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ font: "600 16px 'Barlow Condensed'" }}>{label}</div>
                  {typeof v === "string" && (
                    <div className="os-tv" style={{ fontSize: 14 }}>{v}</div>
                  )}
                </div>
                <span className="time" style={{ opacity: v === null ? 0.55 : undefined }}>
                  {situacaoItem(v)}
                </span>
              </div>
            );
          })}
          {avaliacao.observacao && (
            <div className="os-tv" style={{ fontSize: 14, margin: "10px 0 0", color: "#1d1f20" }}>
              “{avaliacao.observacao}”
            </div>
          )}
          <div className="os-tv" style={{ fontSize: 14, marginTop: 8 }}>
            {avaliacao.isVerificado ? "Verificada" : "Não verificada"} · {avaliacao.avaliadaPorNome} ·{" "}
            {diaHora(avaliacao.avaliadaEm)}
          </div>
        </div>
      )}
    </div>
  );
}
