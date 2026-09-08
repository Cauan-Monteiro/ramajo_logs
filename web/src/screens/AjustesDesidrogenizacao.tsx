import { useState } from "react";
import * as api from "../api/endpoints";
import type { DesidrogenizacaoDTO } from "../api/types";
import { Corners } from "../components/Blueprint";
import { IconPlus } from "../components/Icons";
import { Modal } from "../components/Modal";
import { minutos } from "../domain/format";
import type { AppData } from "../state/useAppData";
import type { Ctx } from "../modals/tipos";

/** Os campos que o POST e o PUT pedem — o formulário e o modal de edição partilham. */
type Form = {
  nome: string;
  /** String, não number: o campo fica vazio enquanto o operador não digita. */
  duracao: string;
  observacao: string;
};

const FORM_VAZIO: Form = { nome: "", duracao: "", observacao: "" };

const PILL_ATIVO = { background: "#d6ebff", color: "#2c455d" };
const PILL_ARQUIVADO = { background: "#e7e7ea", color: "#5d5d60" };

/** Espelha ck_desidro_duracao na V10 — 7 dias. */
const DURACAO_MAX = 10080;
/** Espelha ck_cfg_desidro_temp na V10. */
const TEMP_MAX = 999;

/**
 * Cadastro das receitas de desidrogenização e da temperatura do forno.
 *
 * A temperatura fica em cima, separada da tabela, porque é UMA para todas — não
 * é atributo da receita. O banco garante isso (config_desidrogenizacao tem uma
 * linha só, por CHECK na PK); este painel é a única forma de mudá-la.
 *
 * "Excluir" aqui é ARQUIVAR. A diferença importa menos do que no catálogo de
 * processos — cada aplicação guarda o próprio snapshot de duração e temperatura,
 * então o histórico sobreviveria de qualquer jeito —, mas a FK
 * ordem_desidrogenizacoes.desidrogenizacao_id continua NOT NULL, e o nome
 * exibido no histórico sai daqui. Reversível pelo filtro no rodapé.
 */
export function AjustesDesidrogenizacao({
  data, agir, ocupado,
}: {
  data: AppData;
  agir: Ctx["agir"];
  ocupado: boolean;
}) {
  const [form, setForm] = useState<Form>(FORM_VAZIO);
  const [erro, setErro] = useState<string | null>(null);
  const [verArquivadas, setVerArquivadas] = useState(false);
  const [editando, setEditando] = useState<DesidrogenizacaoDTO | null>(null);
  const [arquivando, setArquivando] = useState<DesidrogenizacaoDTO | null>(null);

  const ativas = data.desidrogenizacoes.filter((d) => d.ativo);
  const arquivadas = data.desidrogenizacoes.filter((d) => !d.ativo);
  const linhas = (verArquivadas ? data.desidrogenizacoes : ativas)
    .slice()
    .sort((a, b) => a.nome.localeCompare(b.nome, "pt"));

  function cadastrar() {
    const problema = validar(form, data.desidrogenizacoes, null);
    if (problema) {
      setErro(problema);
      return;
    }
    const nome = form.nome.trim();
    setErro(null);
    agir({
      fazer: () =>
        api.criarDesidrogenizacao({
          nome,
          duracaoMin: Number(form.duracao),
          observacao: form.observacao.trim() || null,
        }),
      ok: `Desidrogenização ${nome} cadastrada.`,
      depois: () => setForm(FORM_VAZIO),
    });
  }

  return (
    <>
      <div className="reg-h" style={{ fontSize: 13 }}>
        Desidrogenização
        <span className="ct">
          {ativas.length} ativa(s) · {arquivadas.length} arquivada(s)
        </span>
      </div>

      <TemperaturaPainel
        temperatura={data.temperaturaDesidro}
        agir={agir}
        ocupado={ocupado}
      />

      <div className="bp" style={{ padding: "20px 22px", flex: "none" }}>
        <Corners />
        <div className="grp-h">
          <span>Nova desidrogenização</span>
          <i />
        </div>

        <CamposDesidro
          form={form}
          onMudar={(f) => {
            setForm(f);
            setErro(null);
          }}
        />

        <div className="nc-acoes" style={{ marginTop: 14 }}>
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
                <th>Duração</th>
                <th>Observação</th>
                <th>Situação</th>
                <th>Ações</th>
              </tr>
            </thead>
            <tbody>
              {linhas.map((d) => (
                <tr key={d.id}>
                  <td data-rot="Nome">
                    <span className="cgnome">{d.nome}</span>
                  </td>
                  <td data-rot="Duração">{minutos(d.duracaoMin)}</td>
                  <td data-rot="Observação">
                    <span className="os-tv">{d.observacao?.trim() || "—"}</span>
                  </td>
                  <td data-rot="Situação">
                    <span className="lote-pill" style={d.ativo ? PILL_ATIVO : PILL_ARQUIVADO}>
                      {d.ativo ? "Ativa" : "Arquivada"}
                    </span>
                  </td>
                  <td data-rot="Ações">
                    <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
                      <button
                        className="btn2 cgacao"
                        disabled={ocupado}
                        onClick={() => setEditando(d)}
                      >
                        Editar
                      </button>
                      {d.ativo ? (
                        <button
                          className="btn2 cgacao"
                          disabled={ocupado}
                          onClick={() => setArquivando(d)}
                        >
                          Arquivar
                        </button>
                      ) : (
                        <button
                          className="btn2 cgacao"
                          disabled={ocupado}
                          onClick={() =>
                            agir({
                              fazer: () => api.reativarDesidrogenizacao(d.id),
                              ok: `Desidrogenização ${d.nome} reativada.`,
                            })
                          }
                        >
                          Reativar
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {linhas.length === 0 && (
            <div className="empty">Nenhuma desidrogenização cadastrada ainda.</div>
          )}
        </div>

        <div className="pager">
          <button
            className="pgb"
            style={arquivadas.length === 0 ? { opacity: 0.5 } : undefined}
            disabled={arquivadas.length === 0}
            onClick={() => setVerArquivadas((v) => !v)}
          >
            {verArquivadas ? "Ocultar arquivadas" : `Mostrar arquivadas (${arquivadas.length})`}
          </button>
          <span className="cmuted" style={{ marginLeft: "auto" }}>
            {linhas.length} desidrogenização(ões) em lista
          </span>
        </div>
      </div>

      {editando && (
        <EditarDesidroModal
          desidro={editando}
          desidrogenizacoes={data.desidrogenizacoes}
          ocupado={ocupado}
          agir={agir}
          onFechar={() => setEditando(null)}
        />
      )}

      {arquivando && (
        <ArquivarDesidroModal
          desidro={arquivando}
          ocupado={ocupado}
          agir={agir}
          onFechar={() => setArquivando(null)}
        />
      )}
    </>
  );
}

/* ── temperatura do forno ───────────────────────────────────────────────── */

/**
 * Vale para TODAS as desidrogenizações, presentes e futuras — por isso não é
 * campo do cadastro. Mudar aqui não reescreve o passado: cada aplicação gravou
 * a temperatura vigente no momento em que rodou.
 */
function TemperaturaPainel({
  temperatura, agir, ocupado,
}: {
  temperatura: number | null;
  agir: Ctx["agir"];
  ocupado: boolean;
}) {
  const atual = temperatura === null ? "" : String(temperatura);
  const [valor, setValor] = useState(atual);
  const [erro, setErro] = useState<string | null>(null);
  // O painel não desmonta entre recargas, então o estado local ficaria preso no
  // valor antigo depois de salvar. Ressincroniza quando o servidor muda.
  const [visto, setVisto] = useState(atual);
  if (visto !== atual) {
    setVisto(atual);
    setValor(atual);
  }

  const alterado = valor.trim() !== atual;

  function salvar() {
    const n = Number(valor.replace(",", "."));
    if (!Number.isFinite(n) || n <= 0 || n > TEMP_MAX) {
      setErro(`Informe uma temperatura entre 1 e ${TEMP_MAX} °C.`);
      return;
    }
    setErro(null);
    agir({
      fazer: () => api.definirTemperaturaDesidrogenizacao(n),
      ok: `Temperatura do forno definida em ${n} °C.`,
    });
  }

  return (
    <div className="bp" style={{ padding: "20px 22px", flex: "none" }}>
      <Corners />
      <div className="grp-h">
        <span>Temperatura do forno</span>
        <i />
      </div>

      <div style={{ display: "flex", gap: 12, alignItems: "flex-end", flexWrap: "wrap" }}>
        <div style={{ width: 160 }}>
          <span className="lbl">Temperatura (°C)</span>
          <input
            className="inp"
            inputMode="decimal"
            placeholder="ex: 200"
            value={valor}
            onChange={(e) => {
              setValor(e.target.value.replace(/[^\d.,]/g, ""));
              setErro(null);
            }}
          />
        </div>
        <button
          className="btn2 btn2-p"
          style={{ height: 44 }}
          disabled={ocupado || !alterado}
          onClick={salvar}
        >
          Salvar
        </button>
        <span className="os-tv" style={{ fontSize: 14, flex: 1, minWidth: 260 }}>
          A mesma para todas as desidrogenizações. As que já rodaram guardam a
          temperatura da época — mudar aqui não altera o histórico.
        </span>
      </div>

      {erro && (
        <div style={{ color: "#b4472e", font: "500 14px 'Barlow'", marginTop: 12 }}>{erro}</div>
      )}
    </div>
  );
}

/* ── formulário partilhado (cadastro e edição) ──────────────────────────── */

function CamposDesidro({
  form, onMudar,
}: {
  form: Form;
  onMudar: (f: Form) => void;
}) {
  const min = Number(form.duracao);
  const previa = form.duracao && Number.isFinite(min) && min > 0 ? minutos(min) : null;

  return (
    <div className="nc-form">
      <div className="nc-nome">
        <span className="lbl">Nome</span>
        <input
          className="inp"
          placeholder="ex: Desidrogenização 4h"
          value={form.nome}
          onChange={(e) => onMudar({ ...form, nome: e.target.value })}
        />
      </div>
      <div>
        <span className="lbl">Duração (minutos)</span>
        <input
          className="inp"
          inputMode="numeric"
          placeholder="ex: 240"
          value={form.duracao}
          // Só dígitos: a API recebe minutos inteiros.
          onChange={(e) => onMudar({ ...form, duracao: e.target.value.replace(/\D/g, "") })}
        />
        {previa && (
          <span className="os-tv" style={{ fontSize: 13 }}>
            = {previa}
          </span>
        )}
      </div>
      <div className="nc-nome">
        <span className="lbl">Observação (opcional)</span>
        <input
          className="inp"
          placeholder="ex: peças acima de 10 mm"
          value={form.observacao}
          onChange={(e) => onMudar({ ...form, observacao: e.target.value })}
        />
      </div>
    </div>
  );
}

/**
 * Pré-checagens locais, para poupar o round-trip. Os limites de duração são os
 * mesmos do CHECK na V10; o nome duplicado é barrado de verdade pelo índice
 * parcial ux_desidro_nome, que devolve 409 se colidir.
 *
 * A comparação de nome olha só as ATIVAS: o índice é parcial (WHERE ativo), e
 * um nome arquivado não deve impedir o recadastro da mesma receita.
 */
function validar(
  form: Form, desidros: DesidrogenizacaoDTO[], id: number | null,
): string | null {
  const nome = form.nome.trim();
  if (!nome) return "Informe o nome da desidrogenização.";

  const min = Number(form.duracao);
  if (!form.duracao || !Number.isFinite(min) || min <= 0) {
    return "Informe a duração em minutos.";
  }
  if (min > DURACAO_MAX) return `A duração não pode passar de ${DURACAO_MAX} minutos (7 dias).`;

  const outras = desidros.filter((d) => d.id !== id && d.ativo);
  if (outras.some((d) => d.nome.trim().toUpperCase() === nome.toUpperCase())) {
    return "Já existe uma desidrogenização ativa com esse nome.";
  }
  return null;
}

/* ── modais ─────────────────────────────────────────────────────────────── */

function EditarDesidroModal({
  desidro, desidrogenizacoes, ocupado, agir, onFechar,
}: {
  desidro: DesidrogenizacaoDTO;
  desidrogenizacoes: DesidrogenizacaoDTO[];
  ocupado: boolean;
  agir: Ctx["agir"];
  onFechar: () => void;
}) {
  const [form, setForm] = useState<Form>({
    nome: desidro.nome,
    duracao: String(desidro.duracaoMin),
    observacao: desidro.observacao ?? "",
  });
  const [erro, setErro] = useState<string | null>(null);

  function salvar() {
    const problema = validar(form, desidrogenizacoes, desidro.id);
    if (problema) {
      setErro(problema);
      return;
    }
    const nome = form.nome.trim();
    setErro(null);
    agir({
      fazer: () =>
        api.atualizarDesidrogenizacao(desidro.id, {
          nome,
          duracaoMin: Number(form.duracao),
          observacao: form.observacao.trim() || null,
        }),
      ok: `Desidrogenização ${nome} atualizada.`,
      depois: onFechar,
    });
  }

  return (
    <Modal
      kicker={`DESIDROGENIZAÇÃO #${desidro.id}`}
      titulo="Editar desidrogenização"
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
      <CamposDesidro
        form={form}
        onMudar={(f) => {
          setForm(f);
          setErro(null);
        }}
      />

      {erro && (
        <div style={{ color: "#b4472e", font: "500 14px 'Barlow'", marginTop: 12 }}>{erro}</div>
      )}

      <div className="os-tv" style={{ fontSize: 14, marginTop: 16 }}>
        Mudar a duração vale só daqui para a frente. As desidrogenizações já
        aplicadas mantêm a duração e o horário de término com que rodaram — o
        histórico não se reescreve.
      </div>
    </Modal>
  );
}

function ArquivarDesidroModal({
  desidro, ocupado, agir, onFechar,
}: {
  desidro: DesidrogenizacaoDTO;
  ocupado: boolean;
  agir: Ctx["agir"];
  onFechar: () => void;
}) {
  return (
    <Modal
      kicker={`DESIDROGENIZAÇÃO #${desidro.id}`}
      titulo="Arquivar a desidrogenização"
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
                fazer: () => api.arquivarDesidrogenizacao(desidro.id),
                ok: `Desidrogenização ${desidro.nome} arquivada.`,
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
        <div className="os-tv">Desidrogenização</div>
        <div style={{ font: "600 20px 'Barlow Condensed'", marginTop: 6 }}>{desidro.nome}</div>
        <div className="os-tv" style={{ marginTop: 4 }}>{minutos(desidro.duracaoMin)}</div>
      </div>

      <div className="os-tv" style={{ fontSize: 14, marginTop: 16 }}>
        Ela sai da lista da Inspeção final — não poderá mais ser aplicada a uma OS.
      </div>

      <div className="os-tv" style={{ fontSize: 14, marginTop: 10 }}>
        <b>As OS que já a usaram continuam intactas.</b> O detalhe da OS segue
        mostrando o horário, a duração e a temperatura com que ela rodou. Dá para
        reativá-la depois por “Mostrar arquivadas”.
      </div>
    </Modal>
  );
}
