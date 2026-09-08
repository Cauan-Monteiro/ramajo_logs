import { useMemo, useState } from "react";
import type { OrdemResumoDTO, Posicao } from "../api/types";
import { SEL_CHIP } from "../domain/derive";
import { osNum, posLabel } from "../domain/format";
import type { Ctx } from "./tipos";

/** Quantas candidatas listar antes de exigir busca — a fábrica inteira não cabe na tela. */
const MAX_VISIVEIS = 40;

/**
 * "Quais outras OS vão neste mesmo tanque."
 *
 * Peças de 2-3 ordens entram na MESMA carga e passam juntas pelo processo. O
 * passo é registado UMA vez, na OS dona da carga (a titular), e as demais
 * penduram-se nele — é isso que impede um evento físico de contar como dois ou
 * três na produção.
 *
 * Vive fora do modal porque os dois caminhos de abrir etapa precisam dele: o
 * detalhe da OS (`DetalheOS.tsx`) e o lote da home (`PassoLote.tsx`).
 *
 * Recolhido por omissão: a esmagadora maioria dos passos não acopla, e quem
 * não acopla não deve ver a lista.
 */
export function AcoplarOs({
  ctx, posicao, osIdTitular, valor, onChange,
}: {
  ctx: Ctx;
  posicao: Posicao;
  /** A OS dona da carga. Não entra na lista: já é o passo dela. */
  osIdTitular: number | undefined;
  valor: number[];
  onChange: (ids: number[]) => void;
}) {
  const [aberto, setAberto] = useState(false);
  const [busca, setBusca] = useState("");

  const titular = ctx.data.ordens.find((o) => o.id === osIdTitular);

  /**
   * Candidatas: abertas, no MESMO setor (uma carga está num lugar só) e que não
   * sejam a titular. A API recusa as três coisas de qualquer forma — aqui é só
   * para não oferecer o que vai dar erro.
   */
  const candidatas = useMemo(() => {
    const q = busca.trim().toLowerCase();
    return ctx.data.ordens
      .filter((o) => o.emProcesso && o.posicao === posicao && o.id !== osIdTitular)
      .filter((o) =>
        q === "" ||
        osNum(o).toLowerCase().includes(q) ||
        o.clienteNome.toLowerCase().includes(q))
      .slice(0, MAX_VISIVEIS);
  }, [ctx.data.ordens, posicao, osIdTitular, busca]);

  const marcadas = valor
    .map((id) => ctx.data.ordens.find((o) => o.id === id))
    .filter((o): o is OrdemResumoDTO => !!o);

  const alternar = (id: number) =>
    onChange(valor.includes(id) ? valor.filter((x) => x !== id) : [...valor, id]);

  // Recolher não pode esconder uma escolha já feita: se há OS marcadas, o
  // resumo continua à vista mesmo fechado.
  const mostrarLista = aberto || valor.length > 0;

  return (
    <div style={{ marginTop: 22 }}>
      <button
        className="btn2"
        style={{ padding: "8px 14px", fontSize: 14 }}
        onClick={() => setAberto((a) => !a)}
      >
        {mostrarLista ? "−" : "+"} Acoplar outras OS{" "}
        <span className="os-tv">
          {valor.length > 0 ? `· ${valor.length} marcada(s)` : "(opcional)"}
        </span>
      </button>

      {mostrarLista && (
        <>
          <div className="os-tv" style={{ margin: "10px 0" }}>
            Marque as OS cujas peças vão nesta mesma carga.
          </div>
          <input
            className="inp"
            placeholder={`Buscar OS de ${posLabel(posicao)} por nº ou cliente`}
            value={busca}
            onChange={(e) => setBusca(e.target.value)}
          />
          <div style={{ display: "flex", flexWrap: "wrap", gap: 10, marginTop: 10 }}>
            {candidatas.map((o) => (
              <button
                key={o.id}
                className="cgtog"
                style={valor.includes(o.id) ? SEL_CHIP : undefined}
                onClick={() => alternar(o.id)}
              >
                {osNum(o)}
                <span className="tp">{o.clienteNome}</span>
              </button>
            ))}
            {candidatas.length === 0 && (
              <span className="os-tv">
                {busca.trim()
                  ? "Nenhuma OS desta posição bate com a busca."
                  : `Nenhuma outra OS aberta em ${posLabel(posicao)}.`}
              </span>
            )}
          </div>
        </>
      )}

      {/* O que vai acontecer, dito antes de acontecer: o conceito de titular e
          o efeito na Inspeção final não são adivinháveis. */}
      {marcadas.length > 0 && (
        <div className="bp" style={{ padding: "11px 14px", marginTop: 12 }}>
          <div className="os-tv">
            Esta etapa é registada <b>uma vez</b> e vale para{" "}
            {marcadas.length + 1} OS:{" "}
            {titular ? <b>{osNum(titular)}</b> : "esta OS"} (dona da carga)
            {marcadas.map((o) => (
              <span key={o.id}> · <b>{osNum(o)}</b></span>
            ))}
          </div>
          <div className="os-tv" style={{ marginTop: 6 }}>
            Enquanto estiver aberta, as acopladas não aparecem na Inspeção final —
            as peças delas estão dentro do tanque.
          </div>
        </div>
      )}
    </div>
  );
}
