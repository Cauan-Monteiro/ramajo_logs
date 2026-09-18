import { useMemo, useState } from "react";
import * as api from "../api/endpoints";
import type { CargaDTO, OrdemResumoDTO, Posicao, TipoCarga } from "../api/types";
import { Corners } from "../components/Blueprint";
import { IconPlus } from "../components/Icons";
import { OrdenarMenu, useOrdenacao, type ColunaOrd } from "../components/Ordenar";
import { ScanField } from "../components/ScanField";
import { SEL_SEG } from "../domain/derive";
import { POSICOES, osNum, posLabel } from "../domain/format";
import type { AppData } from "../state/useAppData";
import type { Ctx } from "../modals/tipos";

const TIPOS: TipoCarga[] = ["TAMBOR", "TRAVE", "CESTO"];

/** Como no Dashboard: a lista completa não cabe num ecrã, muito menos táctil. */
const POR_PAGINA = 30;
const POR_PAGINA_MOBILE = 10;

/**
 * A situação não é um campo: sai de `ativo` + `ordemAtualId`. A pílula precisa do
 * texto e do estilo; a ordenação precisa de um posto estável — e o texto não serve
 * para isso, porque "OS 1042" ordenaria entre "Disponível" e "Inativa" por acaso
 * alfabético. Daí os dois saírem da MESMA função: o que a linha mostra e o que a
 * coluna ordena nunca discordam.
 */
const ORDEM_SITUACAO = { disponivel: 0, emUso: 1, inativa: 2 } as const;

function situacaoDe(c: CargaDTO, ordens: OrdemResumoDTO[]) {
  if (!c.ativo) {
    return {
      texto: "Inativa",
      estilo: { background: "#e7e7ea", color: "#5d5d60" },
      rank: ORDEM_SITUACAO.inativa,
    };
  }
  if (c.ordemAtualId === null) {
    return {
      texto: "Disponível",
      estilo: { background: "#d6ebff", color: "#2c455d" },
      rank: ORDEM_SITUACAO.disponivel,
    };
  }
  const ordem = ordens.find((o) => o.id === c.ordemAtualId);
  return {
    texto: `OS ${ordem ? osNum(ordem) : c.ordemAtualId}`,
    estilo: { background: "#eef6ff", color: "#416180" },
    rank: ORDEM_SITUACAO.emUso,
  };
}

/**
 * As cinco colunas de dado da tabela — a de "Ação" não ordena nada. O mesmo array
 * serve o cabeçalho clicável (desktop) e o menu `⇅` (telemóvel, onde o `thead`
 * está escondido).
 */
const colunasDe = (ordens: OrdemResumoDTO[]): ColunaOrd<CargaDTO>[] => [
  // O localeCompare numérico do comparador põe "T-9" antes de "T-10", não depois.
  { chave: "nome", label: "Nome", ascPadrao: true, valor: (c) => c.nome },
  { chave: "tipo", label: "Tipo", ascPadrao: true, valor: (c) => c.tipo },
  { chave: "posicao", label: "Posição", ascPadrao: true, valor: (c) => posLabel(c.posicao) },
  // Sem tag -> null, e o comparador manda-a para o fim nos dois sentidos.
  { chave: "tag", label: "Tag", ascPadrao: true, valor: (c) => c.tagId ?? null },
  {
    chave: "situacao", label: "Situação", ascPadrao: true,
    valor: (c) => situacaoDe(c, ordens).rank,
  },
];

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

  const colunas = useMemo(() => colunasDe(data.ordens), [data.ordens]);
  /** Abre pelo nome: é por ele que se procura uma carga na prateleira. */
  const { ord, ordenarPor, ordenar } = useOrdenacao(
    colunas, { chave: "nome", asc: true }, () => setPagina(0),
  );

  /** `.slice()` porque `ordenar` usa `sort`, que é in-place: `data.cargas` é o
      estado partilhado do useAppData, reposto por SSE, e não é nosso para mexer.
      Desempate pelo id para a ordem não tremer a cada sincronização. */
  const cargas = useMemo(
    () => ordenar(data.cargas.slice(), (a, b) => a.id - b.id),
    [data.cargas, ordenar],
  );

  const porPagina = isMobile ? POR_PAGINA_MOBILE : POR_PAGINA;
  const totalPaginas = Math.max(1, Math.ceil(cargas.length / porPagina));
  const pag = Math.min(pagina, totalPaginas - 1);
  const linhas = cargas.slice(pag * porPagina, pag * porPagina + porPagina);

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
    // Sem raiz própria: é montada dentro do .cargas-body de Ajustes.
    <>
      <div className="reg-h" style={{ fontSize: 13 }}>
        Registrar cargas
        <span className="ct">{data.cargas.length} cadastradas</span>
        {/* No desktop o próprio cabeçalho da tabela ordena; aqui ele está escondido. */}
        {isMobile && <OrdenarMenu colunas={colunas} ord={ord} ordenarPor={ordenarPor} />}
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
                {colunas.map((c) => (
                  <th key={c.chave}>
                    {/* <button> de verdade, e não um <th onClick>: chega-se lá por
                        teclado. O .clsort herda a tipografia do .table th. */}
                    <button
                      type="button"
                      className="clsort"
                      aria-label={`Ordenar por ${c.label}`}
                      onClick={() => ordenarPor(c.chave)}
                    >
                      {c.label}
                      {ord.chave === c.chave && (
                        <i className="ordseta">{ord.asc ? "↑" : "↓"}</i>
                      )}
                    </button>
                  </th>
                ))}
                <th>Ação</th>
              </tr>
            </thead>
            <tbody>
              {linhas.map((c) => {
                const situacao = situacaoDe(c, data.ordens);
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
                          disabled={ocupado}
                          onClick={() =>
                            agir({
                              fazer: () => api.reativarCarga(c.id),
                              ok: `Carga ${c.nome} reativada.`,
                            })
                          }
                        >
                          Reativar
                        </button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {cargas.length === 0 && <div className="empty">Nenhuma carga cadastrada ainda.</div>}
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
    </>
  );
}
