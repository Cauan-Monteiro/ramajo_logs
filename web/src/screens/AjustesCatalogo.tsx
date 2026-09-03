import { useState } from "react";
import * as api from "../api/endpoints";
import type { Etapa, Posicao, ProcessoDTO } from "../api/types";
import { Corners } from "../components/Blueprint";
import { IconPlus } from "../components/Icons";
import { Modal } from "../components/Modal";
import { ScanField } from "../components/ScanField";
import { SEL_SEG, dotStyle } from "../domain/derive";
import { ETAPAS, POSICOES, etapaLabel, posLabel } from "../domain/format";
import type { AppData } from "../state/useAppData";
import type { Ctx } from "../modals/tipos";

/** Os campos que o POST e o PUT pedem — o formulário e o modal de edição partilham. */
type Form = {
  descricao: string;
  etapa: Etapa;
  posicoes: Posicao[];
  tag: string;
};

const FORM_VAZIO: Form = {
  descricao: "",
  etapa: "PRE_TRATAMENTO",
  posicoes: [],
  tag: "",
};

const PILL_ATIVO = { background: "#d6ebff", color: "#2c455d" };
const PILL_ARQUIVADO = { background: "#e7e7ea", color: "#5d5d60" };

/**
 * Catálogo de processos: cadastrar, editar e arquivar os tanques que aparecem
 * nas listas de escolha (entrada de setor, abertura de passo).
 *
 * "Excluir" aqui é ARQUIVAR, não apagar — e a diferença é do schema, não de
 * gosto: cada passo em `logs` aponta para o processo com FK NOT NULL e a tabela
 * é append-only por trigger. Apagar a linha levaria junto o histórico que ela
 * documenta. Arquivar tira o processo das escolhas e deixa todo registro antigo
 * legível, inclusive a cor da etapa (etapaDoLog cruza a descrição do log com
 * este catálogo, arquivados incluídos). Reversível pelo filtro abaixo.
 */
export function AjustesCatalogo({
  data, agir, ocupado,
}: {
  data: AppData;
  agir: Ctx["agir"];
  ocupado: boolean;
}) {
  const [form, setForm] = useState<Form>(FORM_VAZIO);
  const [erro, setErro] = useState<string | null>(null);
  const [verArquivados, setVerArquivados] = useState(false);
  const [editando, setEditando] = useState<ProcessoDTO | null>(null);
  const [arquivando, setArquivando] = useState<ProcessoDTO | null>(null);

  const ativos = data.processos.filter((p) => p.ativo);
  const arquivados = data.processos.filter((p) => !p.ativo);
  const linhas = (verArquivados ? data.processos : ativos)
    .slice()
    .sort((a, b) => a.descricao.localeCompare(b.descricao, "pt"));

  /** Setores que têm este processo como entrada — bloqueiam o arquivamento. */
  const entradaDe = (p: ProcessoDTO): Posicao[] =>
    data.processosIniciais.filter((pi) => pi.processoId === p.id).map((pi) => pi.posicao);

  function cadastrar() {
    const problema = validar(form, data.processos, null);
    if (problema) {
      setErro(problema);
      return;
    }
    const descricao = form.descricao.trim();
    setErro(null);
    agir({
      fazer: () =>
        api.criarProcesso({
          descricao,
          etapa: form.etapa,
          tagId: form.tag.trim() || null,
          posicoes: form.posicoes,
        }),
      ok: `Processo ${descricao} cadastrado.`,
      depois: () => setForm(FORM_VAZIO),
    });
  }

  return (
    <>
      <div className="reg-h" style={{ fontSize: 13 }}>
        Catálogo de processos
        <span className="ct">
          {ativos.length} ativo(s) · {arquivados.length} arquivado(s)
        </span>
      </div>

      <div className="bp" style={{ padding: "20px 22px", flex: "none" }}>
        <Corners />
        <div className="grp-h">
          <span>Novo processo</span>
          <i />
        </div>

        <CamposProcesso
          form={form}
          onMudar={(f) => {
            setForm(f);
            setErro(null);
          }}
        />

        <div className="nc-acoes" style={{ marginTop: 14 }}>
          <div className="nc-tag">
            <span className="lbl">Tag RFID (opcional)</span>
            <input
              className="inp"
              placeholder="ex: PR-0142"
              value={form.tag}
              onChange={(e) => setForm({ ...form, tag: e.target.value })}
            />
          </div>
          <ScanField
            rotulo="Ler etiqueta"
            titulo="Encoste a etiqueta ou digite a tag"
            placeholder="ex: PR-0142"
            onLer={(t) => setForm({ ...form, tag: t })}
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
                <th>Descrição</th>
                <th>Etapa</th>
                <th>Posições</th>
                <th>Tag</th>
                <th>Situação</th>
                <th>Ações</th>
              </tr>
            </thead>
            <tbody>
              {linhas.map((p) => {
                const setores = entradaDe(p);
                return (
                  <tr key={p.id}>
                    <td data-rot="Descrição">
                      <span className="cgnome">{p.descricao}</span>
                    </td>
                    <td data-rot="Etapa">
                      <span className="turn-dot" style={{ ...dotStyle(p.etapa), marginRight: 8 }} />
                      {etapaLabel(p.etapa)}
                    </td>
                    <td data-rot="Posições">
                      {p.posicoes.length === 0
                        ? "—"
                        : POSICOES.filter((x) => p.posicoes.includes(x.key))
                            .map((x) => x.label)
                            .join(" · ")}
                    </td>
                    <td data-rot="Tag">
                      <span className="os-tv">{p.tagId ?? "—"}</span>
                    </td>
                    <td data-rot="Situação">
                      <span
                        className="lote-pill"
                        style={p.ativo ? PILL_ATIVO : PILL_ARQUIVADO}
                        title={
                          setores.length > 0
                            ? `Entrada de ${setores.map(posLabel).join(", ")}.`
                            : undefined
                        }
                      >
                        {p.ativo
                          ? setores.length > 0
                            ? "Entrada de setor"
                            : "Ativo"
                          : "Arquivado"}
                      </span>
                    </td>
                    <td data-rot="Ações">
                      <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
                        <button
                          className="btn2 cgacao"
                          disabled={ocupado}
                          onClick={() => setEditando(p)}
                        >
                          Editar
                        </button>
                        {p.ativo ? (
                          <button
                            className="btn2 cgacao"
                            // O 409 do servidor é a guarda real; desabilitar aqui
                            // só evita o round-trip perdido, já que a lista de
                            // entradas está carregada.
                            disabled={ocupado || setores.length > 0}
                            title={
                              setores.length > 0
                                ? `É a entrada de ${setores.map(posLabel).join(", ")}.` +
                                  " Troque a entrada desse(s) setor(es) antes de arquivar."
                                : undefined
                            }
                            onClick={() => setArquivando(p)}
                          >
                            Arquivar
                          </button>
                        ) : (
                          <button
                            className="btn2 cgacao"
                            disabled={ocupado}
                            onClick={() =>
                              agir({
                                fazer: () => api.reativarProcesso(p.id),
                                ok: `Processo ${p.descricao} reativado.`,
                              })
                            }
                          >
                            Reativar
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {linhas.length === 0 && <div className="empty">Nenhum processo cadastrado ainda.</div>}
        </div>

        <div className="pager">
          <button
            className="pgb"
            style={arquivados.length === 0 ? { opacity: 0.5 } : undefined}
            disabled={arquivados.length === 0}
            onClick={() => setVerArquivados((v) => !v)}
          >
            {verArquivados ? "Ocultar arquivados" : `Mostrar arquivados (${arquivados.length})`}
          </button>
          <span className="cmuted" style={{ marginLeft: "auto" }}>
            {linhas.length} processo(s) em lista
          </span>
        </div>
      </div>

      {editando && (
        <EditarProcessoModal
          processo={editando}
          processos={data.processos}
          ocupado={ocupado}
          agir={agir}
          onFechar={() => setEditando(null)}
        />
      )}

      {arquivando && (
        <ArquivarProcessoModal
          processo={arquivando}
          ocupado={ocupado}
          agir={agir}
          onFechar={() => setArquivando(null)}
        />
      )}
    </>
  );
}

/* ── formulário partilhado (cadastro e edição) ──────────────────────────── */

function CamposProcesso({
  form, onMudar,
}: {
  form: Form;
  onMudar: (f: Form) => void;
}) {
  // Multi-seleção: `posicoes` é um Set do lado da API, e um processo costuma
  // rodar em mais de um setor.
  function alternarPosicao(p: Posicao) {
    onMudar({
      ...form,
      posicoes: form.posicoes.includes(p)
        ? form.posicoes.filter((x) => x !== p)
        : [...form.posicoes, p],
    });
  }

  return (
    <div className="nc-form">
      <div className="nc-nome">
        <span className="lbl">Descrição</span>
        <input
          className="inp"
          placeholder="ex: Desengraxante 2"
          value={form.descricao}
          onChange={(e) => onMudar({ ...form, descricao: e.target.value })}
        />
      </div>
      <div>
        <span className="lbl">Etapa</span>
        <div className="segrow">
          {ETAPAS.map((g) => (
            <button
              key={g.key}
              className="seg-b"
              style={form.etapa === g.key ? SEL_SEG : undefined}
              onClick={() => onMudar({ ...form, etapa: g.key })}
            >
              <span
                className="turn-dot"
                style={{ ...dotStyle(g.key), marginRight: 7 }}
              />
              {g.label}
            </button>
          ))}
        </div>
      </div>
      <div>
        <span className="lbl">Posições (uma ou mais)</span>
        <div className="segrow">
          {POSICOES.map((p) => (
            <button
              key={p.key}
              className="seg-b"
              style={form.posicoes.includes(p.key) ? SEL_SEG : undefined}
              onClick={() => alternarPosicao(p.key)}
            >
              {p.label}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}

/**
 * Pré-checagens locais, para poupar o round-trip. A unicidade da tag é do banco
 * (índice parcial ux_processos_tag), que devolve 409 se colidir.
 *
 * A descrição repetida é só AVISO na origem — não há unique em processos.descricao
 * —, mas duas linhas homónimas com etapas diferentes tornam ambígua a cor do
 * histórico, que casa log↔processo por descrição (domain/derive.ts). Por isso a
 * comparação inclui os arquivados.
 */
function validar(form: Form, processos: ProcessoDTO[], id: number | null): string | null {
  const d = form.descricao.trim();
  if (!d) return "Informe a descrição do processo.";
  if (form.posicoes.length === 0) return "Escolha ao menos uma posição.";

  const outros = processos.filter((p) => p.id !== id);
  if (outros.some((p) => p.descricao.trim().toUpperCase() === d.toUpperCase())) {
    return "Já existe um processo com essa descrição (ativo ou arquivado).";
  }
  const t = form.tag.trim();
  if (t && outros.some((p) => p.tagId?.toUpperCase() === t.toUpperCase())) {
    return "Tag RFID já usada por outro processo.";
  }
  return null;
}

/* ── modais ─────────────────────────────────────────────────────────────── */

function EditarProcessoModal({
  processo, processos, ocupado, agir, onFechar,
}: {
  processo: ProcessoDTO;
  processos: ProcessoDTO[];
  ocupado: boolean;
  agir: Ctx["agir"];
  onFechar: () => void;
}) {
  const [form, setForm] = useState<Form>({
    descricao: processo.descricao,
    etapa: processo.etapa,
    posicoes: [...processo.posicoes],
    tag: processo.tagId ?? "",
  });
  const [erro, setErro] = useState<string | null>(null);

  function salvar() {
    const problema = validar(form, processos, processo.id);
    if (problema) {
      setErro(problema);
      return;
    }
    const descricao = form.descricao.trim();
    setErro(null);
    agir({
      fazer: () =>
        api.atualizarProcesso(processo.id, {
          descricao,
          etapa: form.etapa,
          tagId: form.tag.trim() || null,
          posicoes: form.posicoes,
        }),
      ok: `Processo ${descricao} atualizado.`,
      depois: onFechar,
    });
  }

  return (
    <Modal
      kicker={`PROCESSO #${processo.id}`}
      titulo="Editar processo"
      onClose={onFechar}
      footer={
        <>
          <button className="btn2" onClick={onFechar}>
            Cancelar
          </button>
          <button
            className="btn2 btn2-p"
            style={{ marginLeft: "auto" }}
            disabled={ocupado}
            onClick={salvar}
          >
            Salvar
          </button>
        </>
      }
    >
      <CamposProcesso
        form={form}
        onMudar={(f) => {
          setForm(f);
          setErro(null);
        }}
      />

      <div style={{ marginTop: 14 }}>
        <span className="lbl">Tag RFID (opcional)</span>
        <input
          className="inp"
          placeholder="ex: PR-0142"
          value={form.tag}
          onChange={(e) => setForm({ ...form, tag: e.target.value })}
        />
      </div>

      {erro && (
        <div style={{ color: "#b4472e", font: "500 14px 'Barlow'", marginTop: 12 }}>{erro}</div>
      )}

      <div className="os-tv" style={{ fontSize: 14, marginTop: 16 }}>
        Renomear é preferível a arquivar e recriar: os passos já registrados
        continuam apontando para <b>este</b> processo, então o histórico
        acompanha o nome novo em vez de ficar partido em dois.
      </div>
    </Modal>
  );
}

function ArquivarProcessoModal({
  processo, ocupado, agir, onFechar,
}: {
  processo: ProcessoDTO;
  ocupado: boolean;
  agir: Ctx["agir"];
  onFechar: () => void;
}) {
  return (
    <Modal
      kicker={`PROCESSO #${processo.id}`}
      titulo="Arquivar o processo"
      onClose={onFechar}
      footer={
        <>
          <button className="btn2" onClick={onFechar}>
            Cancelar
          </button>
          <button
            className="btn2 btn2-p"
            style={{ marginLeft: "auto" }}
            disabled={ocupado}
            onClick={() =>
              agir({
                fazer: () => api.arquivarProcesso(processo.id),
                ok: `Processo ${processo.descricao} arquivado.`,
                depois: onFechar,
              })
            }
          >
            Confirmar
          </button>
        </>
      }
    >
      <div className="bp" style={{ padding: "18px 20px" }}>
        <Corners />
        <div className="os-tv">Processo</div>
        <div style={{ font: "600 20px 'Barlow Condensed'", marginTop: 6 }}>
          <span className="turn-dot" style={{ ...dotStyle(processo.etapa), marginRight: 8 }} />
          {processo.descricao}
        </div>
      </div>

      <div className="os-tv" style={{ fontSize: 14, marginTop: 16 }}>
        O processo sai das listas de escolha — não poderá mais ser aberto num
        passo nem definido como entrada de um setor.
      </div>

      <div className="os-tv" style={{ fontSize: 14, marginTop: 10 }}>
        <b>Os registros que já o usaram continuam intactos.</b> Os passos das OS,
        o histórico e as planilhas seguem mostrando este processo normalmente —
        nada é apagado. Dá para reativá-lo depois por “Mostrar arquivados”.
      </div>
    </Modal>
  );
}
