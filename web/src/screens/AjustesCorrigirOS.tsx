import { useEffect, useState } from "react";
import * as api from "../api/endpoints";
import type {
  OperadorDTO, OrdemAlteracaoDTO, OrdemDetalheDTO, OrdemResumoDTO, Posicao,
} from "../api/types";
import { Corners } from "../components/Blueprint";
import { BuscaCliente } from "../components/BuscaCliente";
import { HistoricoAlteracoes } from "../components/HistoricoAlteracoes";
import { Modal, Vazio } from "../components/Modal";
import { ScanField } from "../components/ScanField";
import { SEL_CHIP, SEL_SEG, isAberto } from "../domain/derive";
import { diaHora, POSICOES, posLabel } from "../domain/format";
import type { AppData } from "../state/useAppData";
import { cargasDe, cargasLivres, logsDe } from "../state/useAppData";
import type { Ctx } from "../modals/tipos";

/** O formulário: os três campos corrigíveis, mais o que a troca de setor pede. */
type Form = {
  idExterno: string;
  clienteId: number;
  posicao: Posicao;
};

/** Uma linha do "antes → depois" do diálogo de confirmação. */
type Mudanca = { campo: string; antes: string; depois: string };

/**
 * Correção de OS aberta com dado errado — Nº, cliente ou setor.
 *
 * O fluxo real: o operador erra na criação e chama o ADMIN, dizendo o Nº. Por
 * isso a tela abre num campo de Nº e não numa lista: o ADMIN digita o que ouviu
 * e a OS aparece. OS sem Nº não se acham aqui (decisão de produto).
 *
 * Só OS em produção são corrigíveis; expedida ou cancelada aparece só para
 * leitura, com o histórico. A API recusa igual (409).
 *
 * O motivo é obrigatório e vai para o histórico junto com o antes e o depois —
 * no detalhe da OS e na planilha, para quem ler o relatório saber que a
 * identificação foi mexida.
 */
export function AjustesCorrigirOS({
  data, operador, agir, ocupado,
}: {
  data: AppData;
  operador: OperadorDTO;
  agir: Ctx["agir"];
  ocupado: boolean;
}) {
  const [numero, setNumero] = useState("");
  const [detalhe, setDetalhe] = useState<OrdemDetalheDTO | null>(null);
  const [alteracoes, setAlteracoes] = useState<OrdemAlteracaoDTO[]>([]);
  const [form, setForm] = useState<Form | null>(null);
  const [cargasSel, setCargasSel] = useState<number[]>([]);
  const [motivo, setMotivo] = useState("");
  const [revisando, setRevisando] = useState(false);

  const n = numero.trim();
  // idExterno é único na API, então há no máximo uma.
  const ordem: OrdemResumoDTO | null = n
    ? data.ordens.find((o) => o.idExterno !== null && String(o.idExterno) === n) ?? null
    : null;

  // Trocou de OS: o que estava na tela era de outra.
  useEffect(() => {
    setDetalhe(null);
    setAlteracoes([]);
    setForm(null);
    setMotivo("");
    setRevisando(false);
  }, [ordem?.id]);

  // O resumo não traz o clienteId, o detalhe sim. `data` na lista repete a busca
  // depois de cada mutação (inclusive a correção feita aqui).
  useEffect(() => {
    if (!ordem) return;
    let vivo = true;
    api.buscarOrdem(ordem.id).then((d) => vivo && setDetalhe(d)).catch(() => {});
    api.alteracoesOrdem(ordem.id)
      .then((a) => vivo && setAlteracoes(a))
      .catch(() => vivo && setAlteracoes([]));
    return () => {
      vivo = false;
    };
  }, [ordem?.id, data]);

  // O formulário só volta ao estado gravado quando o GRAVADO muda. Uma recarga
  // por sincronização (outro terminal mexeu em qualquer coisa) traz os mesmos
  // valores e não pode apagar o que o ADMIN está a digitar.
  const gravado = detalhe
    ? `${detalhe.id}|${detalhe.idExterno}|${detalhe.clienteId}|${detalhe.posicao}`
    : "";
  useEffect(() => {
    if (!detalhe) return;
    setForm({
      idExterno: detalhe.idExterno === null ? "" : String(detalhe.idExterno),
      clienteId: detalhe.clienteId,
      posicao: detalhe.posicao,
    });
    setCargasSel([]);
  }, [gravado]);

  // As cargas escolhidas são do setor escolhido; trocar de setor as invalida.
  useEffect(() => {
    setCargasSel([]);
  }, [form?.posicao]);

  return (
    <>
      <div className="reg-h" style={{ fontSize: 13 }}>
        Corrigir OS
        <span className="ct">Nº, cliente e setor · só OS em produção</span>
      </div>

      <div className="os-tv" style={{ marginTop: -8 }}>
        Para quando a OS foi <b>criada com dado errado</b>. Toda correção fica
        registrada com o motivo, no detalhe da OS e na planilha.
      </div>

      <div
        style={{
          flex: 1, minHeight: 0, overflow: "auto",
          display: "flex", flexDirection: "column", gap: 18,
        }}
      >
        <div className="bp" style={{ padding: "20px 22px", flex: "none" }}>
          <Corners />
          <span className="lbl">Nº da ordem de serviço</span>
          <div style={{ maxWidth: 300 }}>
            <input
              className="inp"
              inputMode="numeric"
              placeholder="ex: 42"
              value={numero}
              onChange={(e) => setNumero(e.target.value.replace(/\D/g, ""))}
            />
          </div>
          {n && !ordem && (
            <div className="os-tv" style={{ marginTop: 10 }}>
              Nenhuma OS com o Nº {n}.
            </div>
          )}
        </div>

        {ordem && (
          <CartaoOrdem ordem={ordem} detalhe={detalhe} data={data} />
        )}

        {ordem && !ordem.emProcesso && (
          <div className="os-tv" style={{ fontSize: 14 }}>
            Esta OS não está em produção — só OS em produção podem ser corrigidas.
            Uma OS expedida pode ser reaberta pelo detalhe dela.
          </div>
        )}

        {ordem && ordem.emProcesso && detalhe && form && (
          <FormCorrecao
            data={data}
            ordem={ordem}
            detalhe={detalhe}
            form={form}
            onForm={setForm}
            cargasSel={cargasSel}
            onCargasSel={setCargasSel}
            motivo={motivo}
            onMotivo={setMotivo}
            agir={agir}
            ocupado={ocupado}
            onRevisar={() => setRevisando(true)}
          />
        )}

        {ordem && (
          <div className="bp" style={{ padding: "20px 22px", flex: "none" }}>
            <Corners />
            <div className="grp-h">
              <span>Histórico de correções</span>
              <i />
            </div>
            {alteracoes.length > 0 ? (
              <HistoricoAlteracoes alteracoes={alteracoes} />
            ) : (
              <Vazio>Nenhuma correção registrada nesta OS.</Vazio>
            )}
          </div>
        )}
      </div>

      {revisando && ordem && detalhe && form && (
        <ConfirmarCorrecaoModal
          data={data}
          ordem={ordem}
          detalhe={detalhe}
          form={form}
          cargasSel={cargasSel}
          motivo={motivo.trim()}
          ocupado={ocupado}
          onFechar={() => setRevisando(false)}
          onConfirmar={() => {
            const novoNumero = Number(form.idExterno);
            agir({
              fazer: () =>
                api.corrigirOrdem(detalhe.id, {
                  operadorId: operador.id,
                  idExterno: novoNumero,
                  clienteId: form.clienteId,
                  posicao: form.posicao,
                  cargaIds: form.posicao !== detalhe.posicao ? cargasSel : [],
                  motivo: motivo.trim(),
                }),
              ok: `OS #${novoNumero} corrigida.`,
              depois: () => {
                setRevisando(false);
                setMotivo("");
                // Se o Nº mudou, a busca segue a OS para o Nº novo — senão ela
                // sumiria da tela no instante em que foi corrigida.
                setNumero(String(novoNumero));
              },
            });
          }}
        />
      )}
    </>
  );
}

/* ── a OS como está gravada ────────────────────────────────────────────── */

/** Também usado por AjustesAvaliarOS: a mesma OS, achada pelo mesmo Nº. */
export function CartaoOrdem({
  ordem, detalhe, data,
}: {
  ordem: OrdemResumoDTO;
  detalhe: OrdemDetalheDTO | null;
  data: AppData;
}) {
  const cargas = cargasDe(data, ordem.id);
  const situacao = ordem.emProcesso
    ? "Em produção"
    : detalhe?.cancelada ? "Cancelada" : "Expedida";

  return (
    <div className="bp" style={{ padding: "16px 20px", flex: "none", background: "#eef6ff" }}>
      <Corners />
      <div className="os-resumo" style={{ alignItems: "flex-start", gap: 22 }}>
        <div>
          <div className="os-tv">OS</div>
          <div className="os-cli" style={{ fontSize: 20 }}>#{ordem.idExterno}</div>
        </div>
        <div style={{ flex: "1 1 160px" }}>
          <div className="os-tv">Cliente</div>
          <div className="os-cli" style={{ fontSize: 18 }}>{ordem.clienteNome}</div>
        </div>
        <div>
          <div className="os-tv">Posição</div>
          <div className="os-cli" style={{ fontSize: 16 }}>{posLabel(ordem.posicao)}</div>
        </div>
        <div style={{ maxWidth: 220 }}>
          <div className="os-tv">Cargas na OS</div>
          <div className="os-cli" style={{ fontSize: 16 }}>
            {cargas.length ? cargas.map((c) => c.nome).join(" · ") : "—"}
          </div>
        </div>
        <div>
          <div className="os-tv">Aberta em</div>
          <div className="os-cli" style={{ fontSize: 16 }}>
            {diaHora(ordem.iniciadaEm)}
            {detalhe?.iniciadaPorNome ? ` · ${detalhe.iniciadaPorNome}` : ""}
          </div>
        </div>
        <div>
          <div className="os-tv">Situação</div>
          <span className="lote-pill">{situacao}</span>
        </div>
      </div>
    </div>
  );
}

/* ── o formulário ──────────────────────────────────────────────────────── */

/** O Nº digitado, se já pertence a OUTRA OS — a API recusaria com 409. */
function numeroDeOutra(data: AppData, form: Form, osId: number): OrdemResumoDTO | null {
  const v = form.idExterno.trim();
  if (!v) return null;
  return data.ordens.find((o) => o.id !== osId && o.idExterno !== null && String(o.idExterno) === v)
    ?? null;
}

function FormCorrecao({
  data, ordem, detalhe, form, onForm, cargasSel, onCargasSel, motivo, onMotivo,
  agir, ocupado, onRevisar,
}: {
  data: AppData;
  ordem: OrdemResumoDTO;
  detalhe: OrdemDetalheDTO;
  form: Form;
  onForm: (f: Form) => void;
  cargasSel: number[];
  onCargasSel: (ids: number[] | ((s: number[]) => number[])) => void;
  motivo: string;
  onMotivo: (m: string) => void;
  agir: Ctx["agir"];
  ocupado: boolean;
  onRevisar: () => void;
}) {
  const mudaNumero = form.idExterno !== "" && Number(form.idExterno) !== detalhe.idExterno;
  const mudaCliente = form.clienteId !== detalhe.clienteId;
  const mudaPosicao = form.posicao !== detalhe.posicao;
  const algumaMudanca = mudaNumero || mudaCliente || mudaPosicao;

  const outra = numeroDeOutra(data, form, ordem.id);
  const problema = !form.idExterno
    ? "Informe o Nº da OS — ele não pode ficar em branco."
    : outra
      ? `O Nº ${form.idExterno} já é de outra OS (${outra.clienteNome}).`
      : null;

  const podeRevisar = algumaMudanca && !problema && motivo.trim().length > 0;

  // O que a troca de setor leva junto. Só os passos em que esta OS é a titular:
  // os de carona são da carga de outra OS, e a API não os toca.
  const cargasAtuais = cargasDe(data, ordem.id);
  const passosAbertos = logsDe(data, ordem.id)
    .filter((l) => isAberto(l) && l.ordemServicoId === ordem.id);
  const livres = cargasLivres(data, form.posicao);

  function lerCarga(tag: string) {
    agir({
      fazer: async () => {
        const c = await api.cargaPorTag(tag);
        if (!c) throw new Error(`Nenhuma carga com a tag "${tag}".`);
        if (!livres.some((l) => l.id === c.id)) {
          throw new Error(`A carga ${c.nome} não está livre em ${posLabel(form.posicao)}.`);
        }
        onCargasSel((s) => (s.includes(c.id) ? s : [...s, c.id]));
      },
    });
  }

  return (
    <div className="bp" style={{ padding: "20px 22px", flex: "none" }}>
      <Corners />
      <div className="grp-h">
        <span>Correção</span>
        <i />
      </div>

      <span className="lbl">Nº da OS</span>
      <div style={{ maxWidth: 300, marginBottom: 6 }}>
        <input
          className="inp"
          inputMode="numeric"
          value={form.idExterno}
          onChange={(e) => onForm({ ...form, idExterno: e.target.value.replace(/\D/g, "") })}
        />
      </div>
      {problema ? (
        <div style={{ color: "#b4472e", font: "500 14px 'Barlow'", marginBottom: 18 }}>{problema}</div>
      ) : (
        <div style={{ marginBottom: 18 }} />
      )}

      <span className="lbl">Posição / setor</span>
      <div style={{ display: "flex", gap: 8, marginBottom: 18, flexWrap: "wrap" }}>
        {POSICOES.map((p) => (
          <button
            key={p.key}
            className="seg-b"
            style={form.posicao === p.key ? SEL_SEG : undefined}
            onClick={() => onForm({ ...form, posicao: p.key })}
          >
            {p.label}
          </button>
        ))}
      </div>

      {mudaPosicao && (
        <div
          className="bp"
          style={{
            padding: "14px 16px", marginBottom: 18,
            background: "color-mix(in srgb,#b4472e 6%,transparent)",
            borderColor: "rgba(180,71,46,.4)",
          }}
        >
          <Corners />
          <div style={{ font: "600 16px 'Barlow Condensed'", color: "#8f3421", marginBottom: 6 }}>
            Trocar de {posLabel(detalhe.posicao)} para {posLabel(form.posicao)}
          </div>
          <div className="os-tv" style={{ fontSize: 14 }}>
            {cargasAtuais.length > 0 ? (
              <>
                As cargas <b>{cargasAtuais.map((c) => c.nome).join(", ")}</b> voltam a ficar
                livres em {posLabel(detalhe.posicao)}
              </>
            ) : (
              <>A OS não tem cargas em {posLabel(detalhe.posicao)}</>
            )}
            {passosAbertos.length > 0 && (
              <> e <b>{passosAbertos.length} etapa(s) em andamento</b> serão marcadas como canceladas</>
            )}
            . Se a OS ia de carona na carga de outra OS, sai dela. Etapas já encerradas
            continuam no histórico.
          </div>

          <div className="scanhd" style={{ marginTop: 14 }}>
            <span className="lbl">
              Cargas livres em {posLabel(form.posicao)} para vincular (opcional)
            </span>
            <ScanField
              rotulo="Ler carga"
              titulo="Encoste a etiqueta ou digite a tag da carga"
              placeholder="ex: CG-0142"
              onLer={lerCarga}
            />
          </div>
          <div style={{ display: "flex", flexWrap: "wrap", gap: 10 }}>
            {livres.map((c) => {
              const marcada = cargasSel.includes(c.id);
              return (
                <button
                  key={c.id}
                  className="cgtog"
                  style={marcada ? SEL_CHIP : undefined}
                  onClick={() =>
                    onCargasSel((s) => (marcada ? s.filter((id) => id !== c.id) : [...s, c.id]))
                  }
                >
                  {c.nome}
                  <span className="tp">{c.tipo}</span>
                </button>
              );
            })}
            {livres.length === 0 && (
              <span className="os-tv">Sem cargas livres em {posLabel(form.posicao)}.</span>
            )}
          </div>
          <div className="os-tv" style={{ marginTop: 10 }}>
            {cargasSel.length} carga(s) selecionada(s) · cada uma abre etapa no processo
            inicial de {posLabel(form.posicao)}
          </div>
        </div>
      )}

      <span className="lbl">Cliente</span>
      <div style={{ marginBottom: 18 }}>
        <BuscaCliente
          clientes={data.clientes}
          selecionado={form.clienteId}
          onEscolher={(id) => onForm({ ...form, clienteId: id })}
          maxHeight={200}
        />
      </div>

      <span className="lbl">Motivo da correção (obrigatório)</span>
      <textarea
        className="inp"
        rows={3}
        maxLength={500}
        placeholder="ex: operador digitou o Nº 421 em vez de 412"
        value={motivo}
        onChange={(e) => onMotivo(e.target.value)}
        style={{ resize: "vertical" }}
      />

      <div style={{ display: "flex", alignItems: "center", gap: 12, marginTop: 16, flexWrap: "wrap" }}>
        <span className="os-tv">
          {!algumaMudanca
            ? "Nenhum campo alterado."
            : !motivo.trim()
              ? "Informe o motivo para revisar."
              : "Pronto para revisar."}
        </span>
        <button
          className="btn2 btn2-p"
          style={{ marginLeft: "auto" }}
          disabled={!podeRevisar || ocupado}
          onClick={onRevisar}
        >
          Revisar correção
        </button>
      </div>
    </div>
  );
}

/* ── confirmação ───────────────────────────────────────────────────────── */

function ConfirmarCorrecaoModal({
  data, ordem, detalhe, form, cargasSel, motivo, ocupado, onFechar, onConfirmar,
}: {
  data: AppData;
  ordem: OrdemResumoDTO;
  detalhe: OrdemDetalheDTO;
  form: Form;
  cargasSel: number[];
  motivo: string;
  ocupado: boolean;
  onFechar: () => void;
  onConfirmar: () => void;
}) {
  const nomeCliente = (id: number) => {
    const c = data.clientes.find((x) => x.id === id);
    return c ? `#${c.id} ${c.nome}` : `#${id}`;
  };
  const nomesCargas = (nomes: string[]) => (nomes.length ? nomes.join(", ") : "nenhuma");

  const mudancas: Mudanca[] = [];
  if (Number(form.idExterno) !== detalhe.idExterno) {
    mudancas.push({ campo: "Nº da OS", antes: `#${detalhe.idExterno}`, depois: `#${form.idExterno}` });
  }
  if (form.clienteId !== detalhe.clienteId) {
    mudancas.push({
      campo: "Cliente",
      antes: nomeCliente(detalhe.clienteId),
      depois: nomeCliente(form.clienteId),
    });
  }
  if (form.posicao !== detalhe.posicao) {
    mudancas.push({ campo: "Posição", antes: posLabel(detalhe.posicao), depois: posLabel(form.posicao) });
    mudancas.push({
      campo: "Cargas",
      antes: nomesCargas(cargasDe(data, ordem.id).map((c) => c.nome)),
      depois: nomesCargas(
        data.cargas.filter((c) => cargasSel.includes(c.id)).map((c) => c.nome),
      ),
    });
  }

  return (
    <Modal
      kicker={`OS #${detalhe.idExterno} · CORREÇÃO`}
      titulo="Confirmar correção da OS"
      onClose={onFechar}
      footer={
        <>
          <button className="btn2" onClick={onFechar}>
            ← Voltar
          </button>
          <button
            className="btn2 btn2-p btn2-end"
            disabled={ocupado}
            onClick={onConfirmar}
          >
            Confirmar correção
          </button>
        </>
      }
    >
      <div className="bp" style={{ padding: "8px 18px" }}>
        <Corners />
        {mudancas.map((m) => (
          <div key={m.campo} className="tline" style={{ alignItems: "baseline", flexWrap: "wrap" }}>
            <span className="lbl" style={{ minWidth: 90, margin: 0 }}>{m.campo}</span>
            <span style={{ font: "600 17px 'Barlow Condensed'", opacity: 0.55 }}>{m.antes}</span>
            <span style={{ font: "600 17px 'Barlow Condensed'", opacity: 0.4 }}>→</span>
            <span style={{ font: "600 18px 'Barlow Condensed'", color: "#416180" }}>{m.depois}</span>
          </div>
        ))}
      </div>

      <span className="lbl" style={{ marginTop: 16 }}>Motivo</span>
      <div className="os-tv" style={{ fontSize: 15, color: "#1d1f20" }}>“{motivo}”</div>

      <div className="os-tv" style={{ fontSize: 14, marginTop: 16 }}>
        A correção fica registrada em seu nome, com data e motivo, no detalhe da OS e na
        planilha. Não há como desfazê-la — só com outra correção.
      </div>
    </Modal>
  );
}
