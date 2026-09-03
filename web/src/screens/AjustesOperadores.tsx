import { useState } from "react";
import * as api from "../api/endpoints";
import type { OperadorDTO, Permissao } from "../api/types";
import { Corners } from "../components/Blueprint";
import { IconPlus } from "../components/Icons";
import { Modal } from "../components/Modal";
import { ScanField } from "../components/ScanField";
import { SEL_SEG } from "../domain/derive";
import { PERMISSOES, iniciais, permissaoLabel } from "../domain/format";
import type { AppData } from "../state/useAppData";
import type { Ctx } from "../modals/tipos";

/** Os campos que o POST e o PUT pedem — o cadastro e a edição partilham. */
type Form = {
  nome: string;
  permissao: Permissao;
  tag: string;
};

const FORM_VAZIO: Form = {
  nome: "",
  permissao: "FUNCIONARIO",
  tag: "",
};

const PILL_ATIVO = { background: "#d6ebff", color: "#2c455d" };
const PILL_INATIVO = { background: "#e7e7ea", color: "#5d5d60" };

/** Por que uma ação sobre este operador está bloqueada; null quando está livre. */
type Trava = string | null;

/**
 * Cadastro de operadores: quem aparece no início de turno e assina os passos.
 *
 * Duas remoções, e a diferença importa:
 *
 *   • DESATIVAR é soft — o operador some do Login e da escolha de responsável,
 *     e tudo que ele já assinou continua legível. É o caminho para quem saiu da
 *     empresa. Reversível por "Reativar".
 *   • EXCLUIR apaga a linha de verdade, e por isso só passa para quem NUNCA
 *     registrou nada: `logs.responsavel_id` é NOT NULL numa tabela append-only,
 *     então apagar o operador significaria apagar os passos que ele documenta.
 *     Serve ao cadastro errado, não ao desligamento. A API recusa o resto com
 *     409 (OPERADOR_EM_USO) — as travas daqui só poupam o round-trip.
 *
 * A outra recusa é o último ADMIN ativo: sem ele ninguém mais abre esta tela, e
 * não há rota para criar o primeiro admin de volta pela interface.
 */
export function AjustesOperadores({
  data, operador, agir, ocupado,
}: {
  data: AppData;
  /** O operador do turno — não pode remover a si mesmo. */
  operador: OperadorDTO;
  agir: Ctx["agir"];
  ocupado: boolean;
}) {
  const [form, setForm] = useState<Form>(FORM_VAZIO);
  const [erro, setErro] = useState<string | null>(null);
  const [verInativos, setVerInativos] = useState(false);
  const [editando, setEditando] = useState<OperadorDTO | null>(null);
  const [excluindo, setExcluindo] = useState<OperadorDTO | null>(null);

  const ativos = data.operadores.filter((o) => o.ativo);
  const inativos = data.operadores.filter((o) => !o.ativo);
  const linhas = (verInativos ? data.operadores : ativos)
    .slice()
    .sort((a, b) => a.nome.localeCompare(b.nome, "pt"));

  const admins = ativos.filter((o) => o.permissao === "ADMIN");
  const soAdminRestante = (o: OperadorDTO) =>
    o.ativo && o.permissao === "ADMIN" && admins.length <= 1;

  /** Espelha as guardas do OperadorService; a recusa real é do servidor. */
  const travaDe = (o: OperadorDTO): Trava => {
    if (o.id === operador.id) return "Você não pode remover a si mesmo.";
    if (soAdminRestante(o)) {
      return "É o único administrador ativo. Promova outro operador antes.";
    }
    return null;
  };

  function cadastrar() {
    const problema = validar(form, data.operadores, null);
    if (problema) {
      setErro(problema);
      return;
    }
    const nome = form.nome.trim();
    setErro(null);
    agir({
      fazer: () =>
        api.criarOperador({
          nome,
          permissao: form.permissao,
          tagId: form.tag.trim() || null,
        }),
      ok: `Operador ${nome} cadastrado.`,
      depois: () => setForm(FORM_VAZIO),
    });
  }

  return (
    <>
      <div className="reg-h" style={{ fontSize: 13 }}>
        Operadores
        <span className="ct">
          {ativos.length} ativo(s) · {inativos.length} inativo(s)
        </span>
      </div>

      <div className="bp" style={{ padding: "20px 22px", flex: "none" }}>
        <Corners />
        <div className="grp-h">
          <span>Novo operador</span>
          <i />
        </div>

        <CamposOperador
          form={form}
          onMudar={(f) => {
            setForm(f);
            setErro(null);
          }}
        />

        <div className="nc-acoes" style={{ marginTop: 14 }}>
          <div className="nc-tag">
            <span className="lbl">Crachá RFID (opcional)</span>
            <input
              className="inp"
              placeholder="ex: OP-0031"
              value={form.tag}
              onChange={(e) => setForm({ ...form, tag: e.target.value })}
            />
          </div>
          <ScanField
            rotulo="Ler crachá"
            titulo="Encoste o crachá ou digite a tag"
            placeholder="ex: OP-0031"
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
                <th>Nome</th>
                <th>Permissão</th>
                <th>Crachá</th>
                <th>Situação</th>
                <th>Ações</th>
              </tr>
            </thead>
            <tbody>
              {linhas.map((o) => {
                const trava = travaDe(o);
                const euMesmo = o.id === operador.id;
                return (
                  <tr key={o.id}>
                    <td data-rot="Nome">
                      {/* .opav é display:flex — sem este wrapper o avatar cairia
                          numa linha só dele, acima do nome. */}
                      <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
                        <span className="opav" style={{ borderColor: "rgba(89,128,166,.5)" }}>
                          {iniciais(o.nome)}
                        </span>
                        <span className="cgnome">{o.nome}</span>
                        {euMesmo && <span className="os-tv">(você)</span>}
                      </div>
                    </td>
                    <td data-rot="Permissão">{permissaoLabel(o.permissao)}</td>
                    <td data-rot="Crachá">
                      <span className="os-tv">{o.tagId ?? "—"}</span>
                    </td>
                    <td data-rot="Situação">
                      <span className="lote-pill" style={o.ativo ? PILL_ATIVO : PILL_INATIVO}>
                        {o.ativo ? (o.permissao === "ADMIN" ? "Admin ativo" : "Ativo") : "Inativo"}
                      </span>
                    </td>
                    <td data-rot="Ações">
                      <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
                        <button
                          className="btn2 cgacao"
                          disabled={ocupado}
                          onClick={() => setEditando(o)}
                        >
                          Editar
                        </button>
                        {o.ativo ? (
                          <button
                            className="btn2 cgacao"
                            disabled={ocupado || trava !== null}
                            title={trava ?? undefined}
                            onClick={() =>
                              agir({
                                fazer: () => api.desativarOperador(o.id),
                                ok: `Operador ${o.nome} desativado.`,
                              })
                            }
                          >
                            Desativar
                          </button>
                        ) : (
                          <button
                            className="btn2 cgacao"
                            disabled={ocupado}
                            onClick={() =>
                              agir({
                                fazer: () => api.reativarOperador(o.id),
                                ok: `Operador ${o.nome} reativado.`,
                              })
                            }
                          >
                            Reativar
                          </button>
                        )}
                        <button
                          className="btn2 cgacao"
                          disabled={ocupado || trava !== null}
                          title={trava ?? undefined}
                          onClick={() => setExcluindo(o)}
                        >
                          Excluir
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {linhas.length === 0 && <div className="empty">Nenhum operador cadastrado ainda.</div>}
        </div>

        <div className="pager">
          <button
            className="pgb"
            style={inativos.length === 0 ? { opacity: 0.5 } : undefined}
            disabled={inativos.length === 0}
            onClick={() => setVerInativos((v) => !v)}
          >
            {verInativos ? "Ocultar inativos" : `Mostrar inativos (${inativos.length})`}
          </button>
          <span className="cmuted" style={{ marginLeft: "auto" }}>
            {linhas.length} operador(es) em lista
          </span>
        </div>
      </div>

      {editando && (
        <EditarOperadorModal
          operador={editando}
          operadores={data.operadores}
          /* Rebaixar o último admin tranca a tela; o servidor recusa igual. */
          travarRebaixar={soAdminRestante(editando)}
          ocupado={ocupado}
          agir={agir}
          onFechar={() => setEditando(null)}
        />
      )}

      {excluindo && (
        <ExcluirOperadorModal
          operador={excluindo}
          ocupado={ocupado}
          agir={agir}
          onFechar={() => setExcluindo(null)}
        />
      )}
    </>
  );
}

/* ── formulário partilhado (cadastro e edição) ──────────────────────────── */

function CamposOperador({
  form, onMudar, travarRebaixar = false,
}: {
  form: Form;
  onMudar: (f: Form) => void;
  /** Quando o operador é o último admin ativo, FUNCIONARIO fica fora. */
  travarRebaixar?: boolean;
}) {
  return (
    <div className="nc-form">
      <div className="nc-nome">
        <span className="lbl">Nome</span>
        <input
          className="inp"
          placeholder="ex: Ana Ribeiro"
          value={form.nome}
          onChange={(e) => onMudar({ ...form, nome: e.target.value })}
        />
      </div>
      <div>
        <span className="lbl">Permissão</span>
        <div className="segrow">
          {PERMISSOES.map((p) => {
            const bloqueado = travarRebaixar && p.key !== "ADMIN";
            return (
              <button
                key={p.key}
                className="seg-b"
                style={form.permissao === p.key ? SEL_SEG : undefined}
                disabled={bloqueado}
                title={
                  bloqueado
                    ? "É o único administrador ativo. Promova outro operador antes."
                    : undefined
                }
                onClick={() => onMudar({ ...form, permissao: p.key })}
              >
                {p.label}
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
}

/**
 * Pré-checagens locais, para poupar o round-trip. A unicidade do crachá é do
 * banco (índice parcial ux_operadores_tag), que devolve 409 se colidir.
 *
 * Nome repetido NÃO bloqueia: `operadores.nome` não tem unique de propósito
 * (ver V5__clientes.sql), e homónimos acontecem. Quem distingue é o crachá.
 */
function validar(form: Form, operadores: OperadorDTO[], id: number | null): string | null {
  const n = form.nome.trim();
  if (!n) return "Informe o nome do operador.";

  const outros = operadores.filter((o) => o.id !== id);
  const t = form.tag.trim();
  if (t && outros.some((o) => o.tagId?.toUpperCase() === t.toUpperCase())) {
    return "Crachá RFID já usado por outro operador.";
  }
  return null;
}

/* ── modais ─────────────────────────────────────────────────────────────── */

function EditarOperadorModal({
  operador, operadores, travarRebaixar, ocupado, agir, onFechar,
}: {
  operador: OperadorDTO;
  operadores: OperadorDTO[];
  travarRebaixar: boolean;
  ocupado: boolean;
  agir: Ctx["agir"];
  onFechar: () => void;
}) {
  const [form, setForm] = useState<Form>({
    nome: operador.nome,
    permissao: operador.permissao,
    tag: operador.tagId ?? "",
  });
  const [erro, setErro] = useState<string | null>(null);

  function salvar() {
    const problema = validar(form, operadores, operador.id);
    if (problema) {
      setErro(problema);
      return;
    }
    const nome = form.nome.trim();
    setErro(null);
    agir({
      fazer: () =>
        api.atualizarOperador(operador.id, {
          nome,
          permissao: form.permissao,
          tagId: form.tag.trim() || null,
        }),
      ok: `Operador ${nome} atualizado.`,
      depois: onFechar,
    });
  }

  return (
    <Modal
      kicker={`OPERADOR #${operador.id}`}
      titulo="Editar operador"
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
      <CamposOperador
        form={form}
        travarRebaixar={travarRebaixar}
        onMudar={(f) => {
          setForm(f);
          setErro(null);
        }}
      />

      <div style={{ marginTop: 14 }}>
        <span className="lbl">Crachá RFID (opcional)</span>
        <input
          className="inp"
          placeholder="ex: OP-0031"
          value={form.tag}
          onChange={(e) => setForm({ ...form, tag: e.target.value })}
        />
      </div>

      {erro && (
        <div style={{ color: "#b4472e", font: "500 14px 'Barlow'", marginTop: 12 }}>{erro}</div>
      )}

      <div className="os-tv" style={{ fontSize: 14, marginTop: 16 }}>
        Renomear é preferível a excluir e recriar: os passos já registrados
        continuam apontando para <b>este</b> operador, então o histórico
        acompanha o nome novo em vez de ficar partido em dois.
      </div>

      <div className="os-tv" style={{ fontSize: 14, marginTop: 10 }}>
        Quem já está em turno noutro terminal só vê a mudança ao entrar de novo —
        o turno fica guardado no próprio terminal.
      </div>
    </Modal>
  );
}

function ExcluirOperadorModal({
  operador, ocupado, agir, onFechar,
}: {
  operador: OperadorDTO;
  ocupado: boolean;
  agir: Ctx["agir"];
  onFechar: () => void;
}) {
  return (
    <Modal
      kicker={`OPERADOR #${operador.id}`}
      titulo="Excluir o operador"
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
                fazer: () => api.excluirOperador(operador.id),
                ok: `Operador ${operador.nome} excluído.`,
                depois: onFechar,
              })
            }
          >
            Excluir definitivamente
          </button>
        </>
      }
    >
      <div className="bp" style={{ padding: "18px 20px" }}>
        <Corners />
        <div className="os-tv">Operador</div>
        <div
          style={{
            display: "flex", alignItems: "center", gap: 11,
            font: "600 20px 'Barlow Condensed'", marginTop: 6,
          }}
        >
          <span className="opav" style={{ borderColor: "rgba(89,128,166,.5)" }}>
            {iniciais(operador.nome)}
          </span>
          {operador.nome}
          <span className="role-t" style={{ borderColor: "rgba(89,128,166,.5)", color: "#416180" }}>
            {permissaoLabel(operador.permissao)}
          </span>
        </div>
      </div>

      <div className="os-tv" style={{ fontSize: 14, marginTop: 16 }}>
        <b>O cadastro é apagado e não tem volta.</b> Diferente de “Desativar”, a
        linha sai do banco — não há como reativá-la depois.
      </div>

      <div className="os-tv" style={{ fontSize: 14, marginTop: 10 }}>
        Só é possível excluir quem ainda <b>não registrou nenhum passo</b>. Se
        este operador já assinou passos, ordens ou lotes, a API recusa a
        exclusão para não apagar o histórico junto — nesse caso use{" "}
        <b>Desativar</b>, que o tira do início de turno e preserva tudo.
      </div>
    </Modal>
  );
}
