import { useState } from "react";
import * as api from "../api/endpoints";
import type { Posicao, TipoCarga } from "../api/types";
import { Corners } from "../components/Blueprint";
import { IconPlus } from "../components/Icons";
import { ScanField } from "../components/ScanField";
import { SEL_SEG } from "../domain/derive";
import { POSICOES, osNum, posLabel } from "../domain/format";
import type { AppData } from "../state/useAppData";
import { SEM_API, type Ctx } from "../modals/tipos";

const TIPOS: TipoCarga[] = ["TAMBOR", "TRAVE", "CESTO"];

/** Como no Dashboard: a lista completa não cabe num ecrã, muito menos táctil. */
const POR_PAGINA = 30;
const POR_PAGINA_MOBILE = 10;

export function RegistrarCargas({
  data, posicaoAtual, agir, ocupado, isMobile,
}: {
  data: AppData;
  posicaoAtual: Posicao;
  agir: Ctx["agir"];
  ocupado: boolean;
  isMobile: boolean;
}) {
  const [nome, setNome] = useState("");
  const [tipo, setTipo] = useState<TipoCarga>("TRAVE");
  const [posicao, setPosicao] = useState<Posicao>(posicaoAtual);
  const [tag, setTag] = useState("");
  const [erro, setErro] = useState<string | null>(null);
  const [pagina, setPagina] = useState(0);

  const porPagina = isMobile ? POR_PAGINA_MOBILE : POR_PAGINA;
  const totalPaginas = Math.max(1, Math.ceil(data.cargas.length / porPagina));
  const pag = Math.min(pagina, totalPaginas - 1);
  const linhas = data.cargas.slice(pag * porPagina, pag * porPagina + porPagina);

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
        <div className="nc-form">
          <div className="nc-nome">
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
            <div className="segrow">
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
            <div className="segrow">
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
          <div className="nc-tag">
            <span className="lbl">Tag RFID (opcional)</span>
            <input
              className="inp"
              placeholder="ex: CG-0142"
              value={tag}
              onChange={(e) => setTag(e.target.value)}
            />
          </div>
          <ScanField
            rotulo="Ler etiqueta"
            titulo="Encoste a etiqueta ou digite a tag"
            placeholder="ex: CG-0142"
            onLer={setTag}
            botaoStyle={{ height: 48 }}
          />
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

      <div className="bp cargas-tbl">
        <Corners />
        <div className="cl-scroll">
          <table className="table">
            <thead>
              <tr>
                <th>Nome</th>
                <th>Tipo</th>
                <th>Posição</th>
                <th>Tag</th>
                <th>Situação</th>
                <th>Ação</th>
              </tr>
            </thead>
            <tbody>
              {linhas.map((c) => {
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
                    <td data-rot="Nome">
                      <span className="cgnome">{c.nome}</span>
                    </td>
                    <td data-rot="Tipo">{c.tipo}</td>
                    <td data-rot="Posição">{posLabel(c.posicao)}</td>
                    <td data-rot="Tag">
                      <span className="os-tv">{c.tagId ?? "—"}</span>
                    </td>
                    <td data-rot="Situação">
                      <span className="lote-pill" style={situacao.estilo}>
                        {situacao.texto}
                      </span>
                    </td>
                    <td data-rot="Ação">
                      {c.ativo ? (
                        <button
                          className="btn2 cgacao"
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
                          className="btn2 cgacao"
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

        <div className="pager">
          <button className="pgb" disabled={pag <= 0} onClick={() => setPagina((p) => Math.max(0, p - 1))}>
            ← Anterior
          </button>
          <span className="cmuted">
            Página {pag + 1} de {totalPaginas} · {data.cargas.length} carga(s)
          </span>
          <button
            className="pgb"
            style={{ marginLeft: "auto" }}
            disabled={pag >= totalPaginas - 1}
            onClick={() => setPagina((p) => p + 1)}
          >
            Próxima →
          </button>
        </div>
      </div>
    </div>
  );
}
