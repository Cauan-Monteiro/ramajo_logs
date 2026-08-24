import { useState } from "react";
import * as api from "../api/endpoints";
import type { Posicao, TipoCarga } from "../api/types";
import { Corners } from "../components/Blueprint";
import { IconPlus, IconScan } from "../components/Icons";
import { SEL_SEG } from "../domain/derive";
import { POSICOES, osNum, posLabel } from "../domain/format";
import type { AppData } from "../state/useAppData";
import { SEM_API, type Ctx } from "../modals/tipos";

const TIPOS: TipoCarga[] = ["TAMBOR", "TRAVE", "CESTO"];

export function RegistrarCargas({
  data, posicaoAtual, agir, ocupado,
}: {
  data: AppData;
  posicaoAtual: Posicao;
  agir: Ctx["agir"];
  ocupado: boolean;
}) {
  const [nome, setNome] = useState("");
  const [tipo, setTipo] = useState<TipoCarga>("TRAVE");
  const [posicao, setPosicao] = useState<Posicao>(posicaoAtual);
  const [tag, setTag] = useState("");
  const [erro, setErro] = useState<string | null>(null);

  function cadastrar() {
    const n = nome.trim().toUpperCase();
    if (!n) {
      setErro("Informe o nome/código da carga.");
      return;
    }
    // Pré-checagem local para poupar o round-trip; a unicidade real é do banco
    // (índices em cargas.nome / cargas.tag_id), que devolve 409 se colidir.
    if (data.cargas.some((c) => c.nome.toUpperCase() === n)) {
      setErro("Já existe uma carga com esse nome.");
      return;
    }
    const t = tag.trim();
    if (t && data.cargas.some((c) => c.tagId?.toUpperCase() === t.toUpperCase())) {
      setErro("Tag RFID já usada por outra carga.");
      return;
    }
    setErro(null);
    agir({
      fazer: () => api.criarCarga({ nome: n, tipo, posicao, tagId: t || null }),
      ok: `Carga ${n} cadastrada em ${posLabel(posicao)}.`,
      depois: () => {
        setNome("");
        setTag("");
      },
    });
  }

  return (
    <div className="cargas-body">
      <div className="reg-h" style={{ fontSize: 13 }}>
        Registrar cargas
        <span className="ct">{data.cargas.length} cadastradas</span>
      </div>

      <div className="bp" style={{ padding: "20px 22px", flex: "none" }}>
        <Corners />
        <div style={{ display: "flex", gap: 18, flexWrap: "wrap", alignItems: "flex-end" }}>
          <div style={{ flex: 1, minWidth: 200 }}>
            <span className="lbl">Nome / código</span>
            <input
              className="inp"
              placeholder="ex: T-14"
              value={nome}
              onChange={(e) => {
                setNome(e.target.value);
                setErro(null);
              }}
            />
          </div>
          <div>
            <span className="lbl">Tipo</span>
            <div style={{ display: "flex", gap: 8 }}>
              {TIPOS.map((t) => (
                <button
                  key={t}
                  className="seg-b"
                  style={tipo === t ? SEL_SEG : undefined}
                  onClick={() => setTipo(t)}
                >
                  {t}
                </button>
              ))}
            </div>
          </div>
          <div>
            <span className="lbl">Posição</span>
            <div style={{ display: "flex", gap: 8 }}>
              {POSICOES.map((p) => (
                <button
                  key={p.key}
                  className="seg-b"
                  style={posicao === p.key ? SEL_SEG : undefined}
                  onClick={() => setPosicao(p.key)}
                >
                  {p.label}
                </button>
              ))}
            </div>
          </div>
        </div>

        <div className="nc-acoes">
          <div style={{ flex: 1, maxWidth: 280 }}>
            <span className="lbl">Tag RFID (opcional)</span>
            <input
              className="inp"
              placeholder="ex: CG-0142"
              value={tag}
              onChange={(e) => setTag(e.target.value)}
            />
          </div>
          <button
            className="scan-b"
            style={{ height: 48 }}
            onClick={() => {
              const lida = window.prompt("Encoste a etiqueta ou digite a tag:");
              if (lida?.trim()) setTag(lida.trim());
            }}
          >
            <IconScan />
            Ler etiqueta
          </button>
          <button
            className="big-cta"
            style={{ height: 48, fontSize: 18, padding: "0 26px", marginLeft: "auto" }}
            disabled={ocupado}
            onClick={cadastrar}
          >
            <IconPlus size={19} color="#f2f2f3" width={1.7} />
            Cadastrar
          </button>
        </div>

        {erro && (
          <div style={{ color: "#b4472e", font: "500 14px 'Barlow'", marginTop: 12 }}>{erro}</div>
        )}
      </div>

      <div className="bp" style={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column" }}>
        <Corners />
        <div style={{ overflow: "auto", flex: 1 }}>
          <table className="table" style={{ fontSize: 15 }}>
            <thead>
              <tr>
                <th style={{ paddingLeft: 20 }}>Nome</th>
                <th>Tipo</th>
                <th>Posição</th>
                <th>Tag</th>
                <th>Situação</th>
                <th style={{ textAlign: "right", paddingRight: 20 }}>Ação</th>
              </tr>
            </thead>
            <tbody>
              {data.cargas.map((c) => {
                const ordem = data.ordens.find((o) => o.id === c.ordemAtualId);
                const situacao = !c.ativo
                  ? { texto: "Inativa", estilo: { background: "#e7e7ea", color: "#5d5d60" } }
                  : c.ordemAtualId === null
                    ? { texto: "Disponível", estilo: { background: "#d6ebff", color: "#2c455d" } }
                    : {
                        texto: `OS ${ordem ? osNum(ordem) : c.ordemAtualId}`,
                        estilo: { background: "#eef6ff", color: "#416180" },
                      };
                return (
                  <tr key={c.id}>
                    <td style={{ paddingLeft: 20 }}>
                      <span style={{ font: "600 18px 'Barlow Condensed'", letterSpacing: ".04em" }}>
                        {c.nome}
                      </span>
                    </td>
                    <td>{c.tipo}</td>
                    <td>{posLabel(c.posicao)}</td>
                    <td>
                      <span className="os-tv">{c.tagId ?? "—"}</span>
                    </td>
                    <td>
                      <span className="lote-pill" style={situacao.estilo}>
                        {situacao.texto}
                      </span>
                    </td>
                    <td style={{ textAlign: "right", paddingRight: 20 }}>
                      {c.ativo ? (
                        <button
                          className="btn2"
                          style={{ padding: "8px 16px", fontSize: 14 }}
                          disabled={ocupado || c.ordemAtualId !== null}
                          title={
                            c.ordemAtualId !== null
                              ? "A carga está vinculada a uma OS."
                              : undefined
                          }
                          onClick={() =>
                            agir({
                              fazer: () => api.desativarCarga(c.id),
                              ok: `Carga ${c.nome} sucateada.`,
                            })
                          }
                        >
                          Sucatear
                        </button>
                      ) : (
                        <button
                          className="btn2"
                          style={{ padding: "8px 16px", fontSize: 14 }}
                          disabled
                          title={SEM_API.reativarCarga}
                        >
                          Reativar
                          <span className="na">Indisponível</span>
                        </button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {data.cargas.length === 0 && <div className="empty">Nenhuma carga cadastrada ainda.</div>}
        </div>
      </div>
    </div>
  );
}
