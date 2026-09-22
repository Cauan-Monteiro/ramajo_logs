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
import { SEL_CHIP, SEL_SEG, isAberto, pillOrdemStyle, situacaoOrdem } from "../domain/derive";
import { diaHora, POSICOES, posLabels } from "../domain/format";
import type { AppData } from "../state/useAppData";
import { cargasDe, logsDe } from "../state/useAppData";
import type { Ctx } from "../modals/tipos";

/** O formulário: os três campos corrigíveis, mais o que a troca de setor pede. */
type Form = {
  idExterno: string;
  clienteId: number;
  /** O conjunto FINAL de setores, não um delta — igual ao corpo do PUT. */
  posicoes: Posicao[];
};

/**
 * Os conjuntos de setores diferem? Comparação por conteúdo e não por ordem —
 * a API devolve na ordem canônica, mas o form monta na ordem em que o ADMIN
 * tocou nos botões, e essa não significa nada.
 */
const mudouPosicoes = (a: Posicao[], b: Posicao[]) =>
  a.length !== b.length || a.some((p) => !b.includes(p));

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
  const { homonimas, ordem, escolhida, setEscolhida } = useOrdemPorNumero(data.ordens, n);

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
    ? `${detalhe.id}|${detalhe.idExterno}|${detalhe.clienteId}|${detalhe.posicoes.join()}`
    : "";
  useEffect(() => {
    if (!detalhe) return;
    setForm({
      idExterno: detalhe.idExterno === null ? "" : String(detalhe.idExterno),
      clienteId: detalhe.clienteId,
      posicoes: detalhe.posicoes,
    });
    setCargasSel([]);
  }, [gravado]);

  // As cargas escolhidas são do setor escolhido; trocar de setor as invalida.
  useEffect(() => {
    setCargasSel([]);
  }, [form?.posicoes.join()]);

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
          {n && homonimas.length === 0 && (
            <div className="os-tv" style={{ marginTop: 10 }}>
              Nenhuma OS com o Nº {n}.
            </div>
          )}
          <EscolhaIrma homonimas={homonimas} escolhida={escolhida} onEscolher={setEscolhida} />
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
                  posicoes: form.posicoes,
                  cargaIds: mudouPosicoes(form.posicoes, detalhe.posicoes) ? cargasSel : [],
                  motivo: motivo.trim(),
                }),
              // Com o setor: o Nº sozinho não diz qual das irmãs foi mexida.
              ok: `OS #${novoNumero} (${posLabels(form.posicoes)}) corrigida.`,
              depois: () => {
                setRevisando(false);
                setMotivo("");
                // Se o Nº mudou, a busca segue a OS para o Nº novo — senão ela
                // sumiria da tela no instante em que foi corrigida. E segue ESTA
                // OS: se o Nº novo tiver irmãs, não se pergunta o setor de novo.
                setNumero(String(novoNumero));
                setEscolhida(detalhe.id);
              },
            });
          }}
        />
      )}
    </>
  );
}

/* ── achar a OS pelo Nº ────────────────────────────────────────────────── */

/**
 * A OS que o ADMIN quer, a partir do Nº que ele digitou. Também usado por
 * AjustesAvaliarOS, que abre pelo mesmo fluxo.
 *
 * O Nº do ERP não identifica uma OS: é único POR SETOR (V20), e a mesma ordem
 * partida entre dois setores vira duas OS com o mesmo Nº e o mesmo cliente. Um
 * `.find()` aqui carregaria calado a primeira irmã — e a correção, ou a
 * avaliação, iria parar na OS do outro setor sem ninguém perceber.
 *
 * Por isso: uma homônima só -> é ela; várias -> nenhuma até o ADMIN escolher
 * (ver EscolhaIrma). A escolha é por id e não precisa ser limpa ao trocar de
 * Nº: um id que não esteja entre as homônimas do Nº atual simplesmente não
 * casa.
 */
export function useOrdemPorNumero(ordens: OrdemResumoDTO[], n: string) {
  const [escolhida, setEscolhida] = useState<number | null>(null);

  const homonimas = n
    ? ordens.filter((o) => o.idExterno !== null && String(o.idExterno) === n)
    : [];

  const ordem: OrdemResumoDTO | null = homonimas.length === 1
    ? homonimas[0]
    : homonimas.find((o) => o.id === escolhida) ?? null;

  return { homonimas, ordem, escolhida, setEscolhida };
}

/**
 * Pergunta qual das irmãs, quando o Nº tem mais de uma. Mesmo Nº e mesmo
 * cliente — o setor é o que as distingue, e é o que o botão mostra. Uma OS
 * multi-setor aparece como um botão só ("Pendurado + Automática").
 */
export function EscolhaIrma({
  homonimas, escolhida, onEscolher,
}: {
  homonimas: OrdemResumoDTO[];
  escolhida: number | null;
  onEscolher: (osId: number) => void;
}) {
  if (homonimas.length < 2) return null;

  return (
    <div style={{ marginTop: 14 }}>
      <span className="lbl">
        O Nº {homonimas[0].idExterno} está em {homonimas.length} setores · qual?
      </span>
      <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
        {homonimas.map((o) => (
          <button
            key={o.id}
            className="seg-b"
            style={escolhida === o.id ? SEL_SEG : undefined}
            onClick={() => onEscolher(o.id)}
          >
            {posLabels(o.posicoes)}
            {!o.emProcesso && " · encerrada"}
          </button>
        ))}
      </div>
    </div>
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
          <div className="os-tv">{ordem.posicoes.length > 1 ? "Posições" : "Posição"}</div>
          <div className="os-cli" style={{ fontSize: 16 }}>{posLabels(ordem.posicoes)}</div>
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
          {/* O resumo já traz `cancelada` e a entrega: `situacaoOrdem` responde
              sozinho, sem depender do detalhe ter chegado. */}
          <span className="lote-pill" style={pillOrdemStyle(ordem)}>
            {situacaoOrdem(ordem)}
          </span>
        </div>
      </div>
    </div>
  );
}

/* ── o formulário ──────────────────────────────────────────────────────── */

/**
 * O Nº digitado, se já pertence a OUTRA OS QUE RODA EM ALGUM DOS SETORES
 * pedidos — a API recusaria com 409.
 *
 * A colisão é por setor, não pelo número: o Nº do ERP é único por posição
 * (V20), então a 42 da Automática convive com a 42 da Oxidação. Por isso a
 * checagem lê `form.posicoes`, e não só `form.idExterno`: acrescentar um setor
 * pode levar esta OS para dentro do setor onde a irmã já roda.
 */
function numeroDeOutra(data: AppData, form: Form, osId: number): OrdemResumoDTO | null {
  const v = form.idExterno.trim();
  if (!v) return null;
  return data.ordens.find(
    (o) => o.id !== osId && o.idExterno !== null && String(o.idExterno) === v
      && o.posicoes.some((p) => form.posicoes.includes(p)),
  ) ?? null;
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
  const mudaPosicao = mudouPosicoes(form.posicoes, detalhe.posicoes);
  // Os setores que SAEM: é deles que o aviso fala, e só as cargas deles são
  // soltas. Os que ficam continuam a produzir sem interrupção.
  const removidas = detalhe.posicoes.filter((p) => !form.posicoes.includes(p));
  const acrescentadas = form.posicoes.filter((p) => !detalhe.posicoes.includes(p));
  const algumaMudanca = mudaNumero || mudaCliente || mudaPosicao;

  const outra = numeroDeOutra(data, form, ordem.id);
  const problema = !form.idExterno
    ? "Informe o Nº da OS — ele não pode ficar em branco."
    : outra
      ? `O Nº ${form.idExterno} já é de outra OS em ${posLabels(outra.posicoes)}`
        + ` (${outra.clienteNome}).`
      : null;

  const podeRevisar = algumaMudanca && !problema && motivo.trim().length > 0;

  // O que a remoção de setor leva junto — e SÓ ela: acrescentar não desfaz
  // nada. Só os passos em que esta OS é a titular: os de carona são da carga de
  // outra OS, e a API não os toca.
  const cargasAtuais = cargasDe(data, ordem.id);
  const cargasSaindo = cargasAtuais.filter((c) => removidas.includes(c.posicao));
  const nomesSaindo = new Set(cargasSaindo.map((c) => c.nome));
  const passosSaindo = logsDe(data, ordem.id).filter(
    (l) => isAberto(l) && l.ordemServicoId === ordem.id && nomesSaindo.has(l.cargaNome),
  );
  // As cargas a vincular são dos setores ACRESCENTADOS — nos que já existiam a
  // OS já tem as suas, e nos removidos não faria sentido entrar carga nova.
  const livres = data.cargas.filter(
    (c) => c.ativo && c.ordemAtualId === null && acrescentadas.includes(c.posicao),
  );

  function lerCarga(tag: string) {
    agir({
      fazer: async () => {
        const c = await api.cargaPorTag(tag);
        if (!c) throw new Error(`Nenhuma carga com a tag "${tag}".`);
        if (!livres.some((l) => l.id === c.id)) {
          throw new Error(`A carga ${c.nome} não está livre em ${posLabels(acrescentadas)}.`);
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

      <span className="lbl">Posições / setores</span>
      <div style={{ display: "flex", gap: 8, marginBottom: 6, flexWrap: "wrap" }}>
        {POSICOES.map((p) => {
          const ligada = form.posicoes.includes(p.key);
          // Desligar a última é recusado pela API (CORRECAO_SEM_POSICAO) e pela
          // trigger do banco: uma OS sem setor não roda em lugar nenhum.
          const ultima = ligada && form.posicoes.length === 1;
          return (
            <button
              key={p.key}
              className="seg-b"
              style={ligada ? SEL_SEG : undefined}
              disabled={ultima}
              title={ultima ? "A OS precisa de pelo menos um setor" : undefined}
              onClick={() =>
                onForm({
                  ...form,
                  posicoes: ligada
                    ? form.posicoes.filter((k) => k !== p.key)
                    : [...form.posicoes, p.key],
                })
              }
            >
              {p.label}
            </button>
          );
        })}
      </div>
      <div className="os-tv" style={{ fontSize: 13, marginBottom: 18 }}>
        Quase toda OS roda num setor só. Marcar dois é a exceção: as peças ficam
        partidas entre os dois, produzem em paralelo, e a OS expede uma vez só.
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
            {removidas.length > 0
              ? `Retirar ${posLabels(removidas)}`
              : `Acrescentar ${posLabels(acrescentadas)}`}
            {removidas.length > 0 && acrescentadas.length > 0
              && ` e acrescentar ${posLabels(acrescentadas)}`}
          </div>
          <div className="os-tv" style={{ fontSize: 14 }}>
            {removidas.length === 0 ? (
              <>Nada sai: acrescentar um setor não solta carga nem cancela etapa</>
            ) : cargasSaindo.length > 0 ? (
              <>
                As cargas <b>{cargasSaindo.map((c) => c.nome).join(", ")}</b> voltam a ficar
                livres em {posLabels(removidas)}
              </>
            ) : (
              <>A OS não tem cargas em {posLabels(removidas)}</>
            )}
            {passosSaindo.length > 0 && (
              <> e <b>{passosSaindo.length} etapa(s) em andamento</b> serão marcadas como canceladas</>
            )}
            {removidas.length > 0 && form.posicoes.length > 0 && (
              <> — o que corre em {posLabels(form.posicoes)} não é tocado</>
            )}
            . Se a OS ia de carona na carga de outra OS, sai dela. Etapas já encerradas
            continuam no histórico.
          </div>

          <div className="scanhd" style={{ marginTop: 14 }}>
            <span className="lbl">
              Cargas livres em {posLabels(acrescentadas)} para vincular (opcional)
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
              <span className="os-tv">Sem cargas livres em {posLabels(acrescentadas)}.</span>
            )}
          </div>
          <div className="os-tv" style={{ marginTop: 10 }}>
            {cargasSel.length} carga(s) selecionada(s) · cada uma abre etapa no processo
            inicial do setor dela
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
  if (mudouPosicoes(form.posicoes, detalhe.posicoes)) {
    mudancas.push({
      campo: detalhe.posicoes.length > 1 || form.posicoes.length > 1 ? "Posições" : "Posição",
      antes: posLabels(detalhe.posicoes),
      depois: posLabels(form.posicoes),
    });
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
