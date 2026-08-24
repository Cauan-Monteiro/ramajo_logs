import { useState } from "react";
import { Modal, Vazio } from "../components/Modal";
import { Corners } from "../components/Blueprint";
import { pillStyle, situacaoOrdem } from "../domain/derive";
import { diaHora, osNum, posLabel } from "../domain/format";
import type { Ctx } from "./tipos";

type Modo = "os" | "cliente";

/**
 * Busca global. A API não tem rota de pesquisa (nem por idExterno nem por
 * cliente), então o filtro corre sobre a lista já carregada de /api/ordens.
 */
export function BuscarOSModal({ ctx }: { ctx: Ctx }) {
  const [modo, setModo] = useState<Modo>("os");
  const [q, setQ] = useState("");

  const termo = q.trim().toLowerCase();
  const clientesPorNome = new Map(ctx.data.clientes.map((c) => [c.nome, c.id]));

  const resultados = ctx.data.ordens.filter((o) => {
    if (!termo) return true;
    if (modo === "cliente") {
      const id = clientesPorNome.get(o.clienteNome);
      return String(id ?? "").includes(termo) || o.clienteNome.toLowerCase().includes(termo);
    }
    return String(o.id).includes(termo) || String(o.idExterno ?? "").includes(termo);
  });

  const modos: { key: Modo; label: string }[] = [
    { key: "os", label: "Nº da ordem de serviço" },
    { key: "cliente", label: "ID do cliente" },
  ];

  return (
    <Modal
      kicker="BUSCA GLOBAL"
      titulo="Buscar Ordem de Serviço"
      onClose={ctx.fechar}
      footer={<button className="btn2" onClick={ctx.fechar}>Fechar</button>}
    >
      <span className="lbl">Buscar por</span>
      <div style={{ display: "flex", gap: 8, marginBottom: 14 }}>
        {modos.map((m) => (
          <button
            key={m.key}
            className={`seg-b${modo === m.key ? " sel" : ""}`}
            onClick={() => { setModo(m.key); setQ(""); }}
          >
            {m.label}
          </button>
        ))}
      </div>
      <input
        className="inp"
        style={{ marginBottom: 16 }}
        placeholder={modo === "cliente" ? "Digite o ID do cliente..." : "Digite o Nº da OS..."}
        value={q}
        onChange={(e) => setQ(e.target.value)}
      />
      <div style={{ display: "flex", flexDirection: "column", gap: 12, maxHeight: 360, overflow: "auto" }}>
        {resultados.map((o) => (
          <div
            key={o.id}
            className="bp osrow"
            onClick={() => ctx.abrir({ tipo: "det", osId: o.id })}
          >
            <Corners />
            <span className="os-num" style={{ minWidth: 74 }}>{osNum(o)}</span>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="os-cli">{o.clienteNome}</div>
              <div className="os-tv">{posLabel(o.posicao)} · aberta {diaHora(o.iniciadaEm)}</div>
            </div>
            <span className="lote-pill" style={{ ...pillStyle(!o.emProcesso), flex: "none" }}>
              {situacaoOrdem(o)}
            </span>
          </div>
        ))}
        {resultados.length === 0 && <Vazio>Nenhuma OS encontrada.</Vazio>}
      </div>
    </Modal>
  );
}
