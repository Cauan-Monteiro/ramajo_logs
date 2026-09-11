import { useEffect, useState } from "react";
import * as api from "../api/endpoints";
import type {
  AvaliacaoDTO, CargaDTO, LogDTO, OrdemAlteracaoDTO, OrdemDesidrogenizacaoDTO, OrdemDetalheDTO,
} from "../api/types";
import { Corners } from "../components/Blueprint";
import { HistoricoAlteracoes } from "../components/HistoricoAlteracoes";
import { Modal, Vazio } from "../components/Modal";
import { ResumoAvaliacao } from "../components/ResumoAvaliacao";
import { ScanField } from "../components/ScanField";
import {
  SEL_CHIP, SEL_PICK, cargaCarona, caronasDa, dotStyle, ehAcoplada, etapaStyle, etapaDoLog,
  isAberto, labelEtapaDoLog, logSub,
} from "../domain/derive";
import {
  ETAPAS, diaHora, duracao, hhmm, iniciais, minutos, osNum, posLabel,
} from "../domain/format";
import { cargasDe, cargasLivres, logsDe } from "../state/useAppData";
import type { Acoplamentos } from "./AcoplarCargas";
import { AcoplarCargas } from "./AcoplarCargas";
import { SEM_API, type Ctx } from "./tipos";

/* ══════════════════════════════════════════════════════════════════════════
   Detalhe da OS
   ══════════════════════════════════════════════════════════════════════════ */

export function DetalheOSModal({ ctx, osId }: { ctx: Ctx; osId: number }) {
  const ordem = ctx.data.ordens.find((o) => o.id === osId);
  const cargas = cargasDe(ctx.data, osId);
  const logs = logsDe(ctx.data, osId);

  // OS encerrada não está em logsPorOrdem (só carregamos as em processo):
  // buscamos o histórico sob demanda ao abrir o detalhe.
  const [extra, setExtra] = useState<LogDTO[] | null>(null);
  const [desidros, setDesidros] = useState<OrdemDesidrogenizacaoDTO[]>([]);
  const [alteracoes, setAlteracoes] = useState<OrdemAlteracaoDTO[]>([]);
  const [avaliacao, setAvaliacao] = useState<AvaliacaoDTO | null>(null);
  // O resumo em `ctx.data.ordens` não traz `finalizadaEm` nem `cancelada` —
  // a data de encerramento só existe no detalhe, buscado junto do histórico.
  const [detalhe, setDetalhe] = useState<OrdemDetalheDTO | null>(null);
  /** Dois toques para reabrir: ver o botão no footer. */
  const [reabrindo, setReabrindo] = useState(false);
  useEffect(() => {
    if (ordem && !ordem.emProcesso) {
      api.historicoOrdem(osId).then(setExtra).catch(() => setExtra([]));
      api.buscarOrdem(osId).then(setDetalhe).catch(() => setDetalhe(null));
    }
  }, [osId, ordem]);

  // Etapa opcional e quase sempre vazia — não vale carregá-la para TODAS as OS
  // no useAppData, então vem sob demanda, como o histórico acima. `ctx.data` na
  // lista de dependências faz a busca repetir depois de cada mutação (agir
  // recarrega os dados e troca a identidade do objeto).
  useEffect(() => {
    api.desidrogenizacoesDaOrdem(osId).then(setDesidros).catch(() => setDesidros([]));
    // Mesma lógica: correção do ADMIN é rara, então vem sob demanda também.
    api.alteracoesOrdem(osId).then(setAlteracoes).catch(() => setAlteracoes([]));
    api.avaliacaoOrdem(osId).then(setAvaliacao).catch(() => setAvaliacao(null));
  }, [osId, ctx.data]);

  /**
   * Reabre a OS num lote novo e emenda no vínculo de cargas: o lote nasce
   * vazio, então sem esse passo a OS voltaria a produzir sem nada dentro.
   *
   * A resposta traz as cargas que aquela expedição soltou e que ainda estão
   * livres — nenhuma foi revinculada. Vão para o modal como pré-seleção: o
   * operador confirma, desmarca ou acrescenta, e é essa confirmação que
   * vincula. Guardada numa variável porque `agir` não passa o resultado do
   * `fazer` ao `depois`; ele só corre depois do recarregar, então `ctx.data`
   * já está fresco quando o modal abre.
   */
  function reabrir() {
    setReabrindo(false);
    let sugeridas: CargaDTO[] = [];
    ctx.agir({
      fazer: async () => {
        sugeridas = (await api.reabrirOrdem(osId, ctx.operador.id)).cargasSugeridas;
      },
      ok: "OS reaberta num lote novo.",
      depois: () =>
        ctx.abrir({ tipo: "vinc", osId, preSel: sugeridas.map((c) => c.nome) }),
    });
  }

  if (!ordem) return null;
  const todos = ordem.emProcesso ? logs : extra ?? [];
  const abertos = todos.filter(isAberto);
  const fechados = todos.filter((l) => l.finalizadoEm);

  return (
    <Modal
      kicker={`ORDEM DE SERVIÇO ${osNum(ordem)}`}
      titulo={ordem.clienteNome}
      onClose={ctx.fechar}
      footer={
        <>
          <button className="btn2" onClick={ctx.fechar}>
            Fechar
          </button>
          {/* Desfazer a expedição custa o registro dela: `finalizadaEm` é o que
              marca a OS como concluída e some ao reabrir. Daí os dois toques,
              o mesmo cuidado dado ao encerramento de passo acoplado.
              Só sobre OS expedida — cancelada é um fim, não uma pausa —, e só
              depois de `detalhe` chegar, porque é ele que distingue as duas. */}
          {!ordem.emProcesso && detalhe && !detalhe.cancelada && (
            <button
              className="btn2 btn2-p"
              disabled={ctx.ocupado}
              onClick={() => (reabrindo ? reabrir() : setReabrindo(true))}
              onBlur={() => setReabrindo(false)}
            >
              {reabrindo ? "Confirmar · abre novo lote" : "Reabrir OS"}
            </button>
          )}
          {ctx.isAdmin && ordem.emProcesso && (
            <button className="btn2 btn2-d" onClick={() => ctx.abrir({ tipo: "cancel", osId })}>
              Cancelar OS
            </button>
          )}
          {ordem.emProcesso && (
            <>
              <button className="btn2" onClick={() => ctx.abrir({ tipo: "passo", osId })}>
                Abrir etapa
              </button>
              <button className="btn2 btn2-x" onClick={() => ctx.abrir({ tipo: "exp", osId })}>
                Expedir
              </button>
              <button
                className="btn2 btn2-p btn2-end"
                onClick={() => ctx.abrir({ tipo: "vinc", osId })}
              >
                Vincular cargas
              </button>
            </>
          )}
        </>
      }
    >
      {/* `.os-resumo` (o mesmo cabeçalho do Criar OS) traz `align-items:center`,
          quebra de linha e `min-width:0` nos filhos — sem isso as seis células
          desalinham (o valor do Cliente é 20px, os outros 16, e a Situação é
          uma pill) e nenhuma delas encolhe. */}
      <div
        className="os-resumo"
        /* `flex-start` e não o `center` da classe: os rótulos ficam todos na
           mesma linha, e uma célula que quebre em duas (nome de cliente longo)
           deixa de empurrar os rótulos vizinhos para baixo. */
        style={{ alignItems: "flex-start", gap: 20, marginBottom: 18 }}
      >
        <div style={{ flex: "1 1 160px" }}>
          <div className="os-tv">Cliente</div>
          <div className="os-cli" style={{ fontSize: 20 }}>
            {ordem.clienteNome}
          </div>
        </div>
        <div>
          <div className="os-tv">Posição</div>
          <div className="os-cli" style={{ fontSize: 16 }}>
            {posLabel(ordem.posicao)}
          </div>
        </div>
        <div style={{ maxWidth: 220 }}>
          <div className="os-tv">Cargas na OS</div>
          <div
            className="os-cli"
            style={{
              fontSize: 16,
              overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap",
            }}
            title={cargas.map((c) => c.nome).join(" · ")}
          >
            {cargas.length ? cargas.map((c) => c.nome).join(" · ") : "—"}
          </div>
        </div>
        <div>
          <div className="os-tv">Aberta em</div>
          <div className="os-cli" style={{ fontSize: 16 }}>
            {diaHora(ordem.iniciadaEm)}
          </div>
        </div>
        {!ordem.emProcesso && (
          <div>
            <div className="os-tv">{detalhe?.cancelada ? "Encerrada" : "Expedida em"}</div>
            <div className="os-cli" style={{ fontSize: 16 }}>
              {/* O cancelamento não carimba `finalizadaEm` — não há data a mostrar. */}
              {detalhe?.cancelada ? "Cancelada" : diaHora(detalhe?.finalizadaEm)}
            </div>
          </div>
        )}
        <div>
          <div className="os-tv">Situação</div>
          <div>
            <span className="lote-pill">
              {!ordem.emProcesso
                ? detalhe?.cancelada
                  ? "Cancelada"
                  : "Expedida"
                : ordem.lotesFinalizados > 0
                  ? `${ordem.lotesFinalizados}º lote — Expedido`
                  : "Em produção"}
            </span>
          </div>
        </div>
      </div>

      <Acoplamento ctx={ctx} osId={osId} cargas={cargas} />

      {abertos.length > 0 && (
        <>
          <span className="lbl" style={{ color: "#2f6b3c" }}>
            Etapas em andamento
          </span>
          <div style={{ margin: "6px 0 18px" }}>
            {abertos.map((l) => (
              <PassoAberto key={l.id} ctx={ctx} log={l} osId={osId} />
            ))}
          </div>
        </>
      )}

      {desidros.length > 0 && (
        <>
          <span className="lbl">Desidrogenizações</span>
          <div style={{ margin: "6px 0 18px" }}>
            {desidros.map((d) => (
              <div key={d.id} className="tline">
                <div style={{ flex: 1 }}>
                  <div style={{ font: "600 16px 'Barlow Condensed'" }}>{d.nome}</div>
                  {/* Duração e temperatura são as GRAVADAS na aplicação, não as
                      do cadastro de hoje. */}
                  <div className="os-tv">
                    {minutos(d.duracaoMin)} · {d.temperatura} °C
                    {d.aplicadaPorNome ? ` · ${d.aplicadaPorNome}` : ""}
                  </div>
                </div>
                <span className="time">
                  {hhmm(d.iniciadaEm)} → {hhmm(d.finalizadaEm)}
                </span>
              </div>
            ))}
          </div>
        </>
      )}

      {/* Em produção só aparece se o ADMIN já avaliou; expedida, sempre — "sem
          avaliação" também é informação sobre uma OS que saiu. */}
      {(avaliacao || !ordem.emProcesso) && <ResumoAvaliacao avaliacao={avaliacao} />}

      {/* Antes das etapas: quem lê o detalhe precisa saber logo que o Nº, o
          cliente ou o setor que vê no cabeçalho foram corrigidos. */}
      {alteracoes.length > 0 && (
        <>
          <span className="lbl" style={{ color: "#8f3421" }}>Correções do ADMIN</span>
          <div style={{ margin: "6px 0 18px" }}>
            <HistoricoAlteracoes alteracoes={alteracoes} />
          </div>
        </>
      )}

      <span className="lbl">Histórico de etapas</span>
      <div className="os-hist">
        {fechados.map((l) => (
          <div key={l.id} className="tline">
            <span className="etp" style={etapaStyle(etapaDoLog(l, ctx.data.processos))}>
              {labelEtapaDoLog(l, ctx.data.processos)}
            </span>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ font: "600 16px 'Barlow Condensed'" }}>
                {ehAcoplada(l, osId) && <span className="aud-ac">⇋ </span>}
                {l.processoDescricao}
              </div>
              <div className="os-tv">
                {logSub(l, duracao(l.iniciadoEm, l.finalizadoEm))}
                {ehAcoplada(l, osId) && ` · acoplada à OS #${l.ordemServicoId}`}
              </div>
            </div>
            <span className="time">Encerrado em {hhmm(l.finalizadoEm)}</span>
          </div>
        ))}
        {fechados.length === 0 && <Vazio>Ainda sem etapas concluídos.</Vazio>}
      </div>
    </Modal>
  );
}

/* ══════════════════════════════════════════════════════════════════════════
   Composição das cargas: quem mais está dentro delas
   ══════════════════════════════════════════════════════════════════════════ */

/**
 * Teto de OS acopladas a uma mesma carga. Espelha MAX_OS_ACOPLADAS do service:
 * a API recusa de qualquer forma — aqui é só para não oferecer o que vai falhar.
 */
const MAX_ACOPLADAS = 5;

/**
 * O acoplamento visto dos dois lados, e o único lugar onde se faz e se desfaz
 * à mão depois de a OS estar aberta.
 *
 * Fica no cabeçalho, e não junto das etapas, porque o vínculo é da CARGA: ele
 * existe entre uma etapa e a seguinte, e sumiria da tela se só o mostrássemos
 * quando há passo aberto. É pela mesma razão que é aqui que se acopla — uma OS
 * que nasceu sem carga não tem etapa nenhuma onde o botão pudesse morar.
 *
 * Dois toques para acoplar, um para desacoplar: acoplar encerra as etapas
 * abertas da carona e `logs` é append-only, então desacoplar depois não as
 * reabre. Só a ação irreversível pede confirmação.
 */
function Acoplamento({ ctx, osId, cargas }: { ctx: Ctx; osId: number; cargas: CargaDTO[] }) {
  /** Qual seletor está aberto: o de uma carga desta OS, ou o do lado carona. */
  const [escolhendo, setEscolhendo] = useState<number | "carona" | null>(null);
  const [confirmando, setConfirmando] = useState<string | null>(null);

  const ordem = ctx.data.ordens.find((o) => o.id === osId);
  const emprestada = cargaCarona(ctx.data.cargas, osId);

  // Peças ficam num tanque só: uma OS que já tem carga própria, ou que já pega
  // carona, não pede boleia. É a mesma recusa que a API dá.
  const podeIrDeCarona = !!ordem && ordem.emProcesso && !emprestada && cargas.length === 0;

  if (!ordem || (cargas.length === 0 && !emprestada && !podeIrDeCarona)) return null;

  const rotulo = (id: number) => {
    const o = ctx.data.ordens.find((x) => x.id === id);
    return o ? osNum(o) : `#${id}`;
  };

  /** Já caronas em qualquer carga: oferecê-las seria oferecer erro. */
  const jaCaronas = new Set(ctx.data.cargas.flatMap((c) => c.ordensAcopladas));

  /** OS que podem entrar NESTA carga: abertas, mesmo setor, nem esta nem carona. */
  const candidatas = (carga: CargaDTO) =>
    ctx.data.ordens.filter(
      (o) =>
        o.emProcesso &&
        o.posicao === ordem.posicao &&
        o.id !== osId &&
        o.id !== carga.ordemAtualId &&
        !jaCaronas.has(o.id),
    );

  /**
   * Cargas de OUTRAS OS onde esta pode pegar carona. Só as que têm titular:
   * sem alguém a dar boleia a API recusa (CargaNaoVinculadaException), e é a
   * recusa certa — uma carga livre não está a caminho de lado nenhum.
   */
  const cargasComTitular = ctx.data.cargas.filter(
    (c) =>
      c.ativo &&
      c.posicao === ordem.posicao &&
      c.ordemAtualId !== null &&
      c.ordemAtualId !== osId &&
      c.ordensAcopladas.length < MAX_ACOPLADAS,
  );

  /** Dois toques: o primeiro arma, o segundo executa. */
  function tocar(chave: string, fazer: () => void) {
    if (confirmando === chave) {
      setConfirmando(null);
      fazer();
      return;
    }
    setConfirmando(chave);
  }

  function acoplar(cargaId: number, quem: number, nomeCarga: string) {
    ctx.agir({
      fazer: () => api.acoplarNaCarga(cargaId, quem),
      ok: `OS ${rotulo(quem)} acoplada à carga ${nomeCarga}.`,
      depois: () => setEscolhendo(null),
    });
  }

  return (
    <div className="bp" style={{ padding: "11px 14px", marginBottom: 18 }}>
      <Corners />

      {/* Lado titular: quem mais está dentro dos tanques desta OS. */}
      {cargas.map((c) => {
        const caronas = caronasDa(c, ctx.data.ordens);
        const cheia = c.ordensAcopladas.length >= MAX_ACOPLADAS;
        const abertoAqui = escolhendo === c.id;
        const oferecer = candidatas(c);

        return (
          <div key={c.id} style={{ marginBottom: 8 }}>
            <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap" }}>
              <span className="os-tv">
                Na carga <b>{c.nome}</b>
                {caronas.length > 0 ? ", acopladas:" : " · nenhuma OS acoplada"}
              </span>
              {caronas.map((o) => (
                <span key={o.id} className="cg-chip chip-row">
                  {osNum(o)}
                  <button
                    className="chip-x"
                    title="Desacoplar esta OS da carga"
                    disabled={ctx.ocupado}
                    onClick={() =>
                      ctx.agir({
                        fazer: () => api.desacoplarDaCarga(c.id, o.id),
                        ok: `OS ${osNum(o)} desacoplada da carga ${c.nome}.`,
                      })
                    }
                  >
                    ×
                  </button>
                </span>
              ))}
              <button
                className="btn2"
                style={{ padding: "6px 12px", fontSize: 13 }}
                disabled={ctx.ocupado || cheia}
                title={
                  cheia
                    ? `Máximo de ${MAX_ACOPLADAS} OS acopladas nesta carga.`
                    : "Acoplar outra OS a esta carga"
                }
                onClick={() => {
                  setConfirmando(null);
                  setEscolhendo(abertoAqui ? null : c.id);
                }}
              >
                {abertoAqui ? "− Fechar" : "+ Acoplar OS"}
              </button>
            </div>

            {abertoAqui && (
              <>
                <div className="os-tv" style={{ margin: "8px 0" }}>
                  Acoplar <b>encerra as etapas abertas</b> da OS escolhida — as peças
                  dela entram nesta carga e só saem no × acima.
                </div>
                <div className="ac-chips">
                  {oferecer.map((o) => {
                    const chave = `${c.id}:${o.id}`;
                    const aguardando = confirmando === chave;
                    return (
                      <button
                        key={o.id}
                        className="cgtog"
                        style={aguardando ? SEL_CHIP : undefined}
                        disabled={ctx.ocupado}
                        onClick={() => tocar(chave, () => acoplar(c.id, o.id, c.nome))}
                        onBlur={() => setConfirmando(null)}
                      >
                        {aguardando ? `Confirmar ${osNum(o)}` : osNum(o)}
                        <span className="tp">{o.clienteNome}</span>
                      </button>
                    );
                  })}
                  {oferecer.length === 0 && (
                    <span className="os-tv">
                      Nenhuma outra OS aberta em {posLabel(ordem.posicao)} disponível.
                    </span>
                  )}
                </div>
              </>
            )}
          </div>
        );
      })}

      {/* Lado carona: a carga não é desta OS, e dizê-lo é o essencial. */}
      {emprestada && (
        <div
          style={{
            display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap",
            marginTop: cargas.length > 0 ? 10 : 0,
          }}
        >
          <span className="os-tv">
            Peças desta OS estão na carga <b>{emprestada.nome}</b>
            {emprestada.ordemAtualId !== null ? (
              <> · da OS {rotulo(emprestada.ordemAtualId)}</>
            ) : (
              // A carga foi liberada e voltou ao pool ainda a levá-las: o
              // acoplamento não morre com a etapa, morre no botão abaixo.
              <> · carga liberada, ainda a levá-las</>
            )}
          </span>
          <button
            className="btn2"
            style={{ padding: "6px 12px", fontSize: 13 }}
            disabled={ctx.ocupado}
            onClick={() =>
              ctx.agir({
                fazer: () => api.desacoplarDaCarga(emprestada.id, osId),
                ok: `OS desacoplada da carga ${emprestada.nome}.`,
              })
            }
          >
            Desacoplar
          </button>
        </div>
      )}

      {/* Lado carona, antes de existir: a OS sem tanque nenhum pede boleia. */}
      {podeIrDeCarona && (
        <div>
          <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap" }}>
            <span className="os-tv">
              Esta OS não tem carga própria — as peças dela podem ir dentro da carga de outra OS.
            </span>
            <button
              className="btn2"
              style={{ padding: "6px 12px", fontSize: 13 }}
              disabled={ctx.ocupado}
              onClick={() => {
                setConfirmando(null);
                setEscolhendo(escolhendo === "carona" ? null : "carona");
              }}
            >
              {escolhendo === "carona" ? "− Fechar" : "Acoplar a uma carga"}
            </button>
          </div>

          {escolhendo === "carona" && (
            <div className="ac-chips" style={{ marginTop: 8 }}>
              {cargasComTitular.map((c) => {
                const chave = `carona:${c.id}`;
                const aguardando = confirmando === chave;
                return (
                  <button
                    key={c.id}
                    className="cgtog"
                    style={aguardando ? SEL_CHIP : undefined}
                    disabled={ctx.ocupado}
                    onClick={() => tocar(chave, () => acoplar(c.id, osId, c.nome))}
                    onBlur={() => setConfirmando(null)}
                  >
                    {aguardando ? `Confirmar ${c.nome}` : c.nome}
                    <span className="tp">
                      {c.ordemAtualId !== null ? rotulo(c.ordemAtualId) : c.tipo}
                    </span>
                  </button>
                );
              })}
              {cargasComTitular.length === 0 && (
                <span className="os-tv">
                  Nenhuma carga de {posLabel(ordem.posicao)} com OS titular e espaço livre —
                  uma carga só dá boleia enquanto estiver vinculada a alguma OS.
                </span>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}


/* ══════════════════════════════════════════════════════════════════════════
   Vincular cargas a uma OS já aberta
   ══════════════════════════════════════════════════════════════════════════ */

export function VincularModal(
  { ctx, osId, preSel }: { ctx: Ctx; osId: number; preSel?: string[] },
) {
  // `preSel` só semeia o estado inicial: a partir daí a seleção é do operador,
  // e uma recarga do `ctx.data` não pode remarcar o que ele desmarcou.
  const [sel, setSel] = useState<string[]>(preSel ?? []);
  const [acopladas, setAcopladas] = useState<Acoplamentos>({});
  const ordem = ctx.data.ordens.find((o) => o.id === osId);
  if (!ordem) return null;

  const livres = cargasLivres(ctx.data, ordem.posicao);
  const jaNaOS = cargasDe(ctx.data, osId);
  const cargasSel = livres.filter((c) => sel.includes(c.nome));

  /** Desmarcar uma carga leva junto quem ia dentro dela: o tanque saiu da OS. */
  function marcar(nome: string, cargaId: number) {
    const sai = sel.includes(nome);
    setSel((s) => (sai ? s.filter((n) => n !== nome) : [...s, nome]));
    if (sai) {
      setAcopladas(({ [cargaId]: _, ...resto }) => resto);
    }
  }

  function lerCarga(tag: string) {
    ctx.agir({
      fazer: async () => {
        const c = await api.cargaPorTag(tag);
        if (!c) throw new Error(`Nenhuma carga com a tag "${tag}".`);
        if (!livres.some((l) => l.id === c.id)) {
          throw new Error(`A carga ${c.nome} não está livre em ${posLabel(ordem!.posicao)}.`);
        }
        setSel((s) => (s.includes(c.nome) ? s : [...s, c.nome]));
      },
    });
  }

  function confirmar() {
    const ids = cargasSel.map((c) => c.id);
    ctx.agir({
      // Uma chamada por carga, cada uma com as suas caronas: o vínculo já abre
      // o passo inicial, e a composição vale para todas as etapas seguintes.
      fazer: () => Promise.all(ids.map((id) =>
        api.vincularCarga(osId, id, ctx.operador.id, acopladas[id] ?? []))),
      ok: `${ids.length} carga(s) vinculada(s).`,
      depois: () => ctx.abrir({ tipo: "det", osId }),
    });
  }

  return (
    <Modal
      kicker={`OS ${osNum(ordem)} · VINCULAR CARGAS`}
      titulo="Vincular cargas à OS"
      onClose={ctx.fechar}
      footer={
        <>
          <button className="btn2" onClick={() => ctx.abrir({ tipo: "det", osId })}>
            ← Voltar
          </button>
          <button
            className="btn2 btn2-p btn2-end"
            disabled={sel.length === 0 || ctx.ocupado}
            onClick={confirmar}
          >
            Vincular à OS
          </button>
        </>
      }
    >
      <div className="scanhd">
        <span className="lbl">
          Cargas livres na posição {posLabel(ordem.posicao)} (toque para vincular)
        </span>
        <ScanField
          rotulo="Ler carga"
          titulo="Encoste a etiqueta ou digite a tag da carga"
          placeholder="ex: CG-0142"
          onLer={lerCarga}
        />
      </div>
      <div style={{ display: "flex", flexWrap: "wrap", gap: 10 }}>
        {livres.map((c) => (
          <button
            key={c.id}
            className="cgtog"
            style={sel.includes(c.nome) ? SEL_CHIP : undefined}
            onClick={() => marcar(c.nome, c.id)}
          >
            {c.nome}
            <span className="tp">{c.tipo}</span>
          </button>
        ))}
        {livres.length === 0 && <span className="os-tv">Nenhuma carga livre nesta posição.</span>}
      </div>
      <div className="os-tv" style={{ marginTop: 14 }}>
        {sel.length} selecionada(s) · a OS já tem {jaNaOS.length} carga(s)
      </div>
      <AcoplarCargas
        ctx={ctx}
        posicao={ordem.posicao}
        cargas={cargasSel}
        osIdTitular={osId}
        valor={acopladas}
        onChange={setAcopladas}
      />
    </Modal>
  );
}

/**
 * Uma etapa em andamento na lista do detalhe.
 *
 * Componente próprio por causa do acoplamento: a mesma linha aparece na OS
 * dona da carga e nas que pegaram carona, e o que ela precisa dizer (e
 * permitir) é diferente dos dois lados.
 *
 * Fechar um passo acoplado fecha para TODAS as OS envolvidas — é um tanque só.
 * Como `logs` é append-only, isso não se desfaz, então o encerramento pede
 * dois toques: o mesmo princípio já aplicado à expedição parcial.
 */
function PassoAberto({ ctx, log, osId }: { ctx: Ctx; log: LogDTO; osId: number }) {
  const [confirmando, setConfirmando] = useState(false);

  const acoplada = ehAcoplada(log, osId);
  const outras = acoplada
    // Na carona: a titular mais as demais caronas, menos ela própria.
    ? [log.ordemServicoId, ...log.ordensAcopladas.filter((id) => id !== osId)]
    : log.ordensAcopladas;
  const total = outras.length + 1;

  const rotuloOs = (id: number) => {
    const o = ctx.data.ordens.find((x) => x.id === id);
    return o ? osNum(o) : `#${id}`;
  };

  function finalizar() {
    setConfirmando(false);
    ctx.agir({
      fazer: () => api.finalizarLog(log.id),
      ok: total > 1
        ? `Etapa "${log.processoDescricao}" finalizada para ${total} OS.`
        : `Etapa "${log.processoDescricao}" finalizada na carga ${log.cargaNome}.`,
    });
  }

  return (
    <div className="openrow">
      <span className="live-dot" />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ font: "600 16px 'Barlow Condensed'" }}>
          {acoplada && <span className="aud-ac">⇋ </span>}
          {log.processoDescricao}
        </div>
        <div className="os-tv">
          {/* Na carona, dizer de quem é a carga é o essencial: ela NÃO é desta OS. */}
          {acoplada
            ? `Etapa da OS ${rotuloOs(log.ordemServicoId)} · carga ${log.cargaNome}`
            : `Carga ${log.cargaNome} · ${log.responsavelNome}`}
        </div>
        {/* Do lado da titular, quem mais está no tanque. Só informativo: a
            composição é da carga, e corrigi-la aqui deixaria a etapa seguinte
            a contradizer esta. O × vive no cabeçalho, sobre a carga. */}
        {!acoplada && log.ordensAcopladas.length > 0 && (
          <div style={{ display: "flex", flexWrap: "wrap", gap: 6, marginTop: 6 }}>
            {log.ordensAcopladas.map((id) => (
              <span key={id} className="cg-chip chip-row">
                {rotuloOs(id)}
              </span>
            ))}
          </div>
        )}
      </div>
      <span className="time">aberto {hhmm(log.iniciadoEm)}</span>
      <button
        className="btn2 btn2-p"
        style={{ padding: "9px 16px", fontSize: 14 }}
        disabled={ctx.ocupado}
        onClick={() => (total > 1 && !confirmando ? setConfirmando(true) : finalizar())}
        onBlur={() => setConfirmando(false)}
      >
        {total > 1
          ? confirmando
            ? `Confirmar · encerra ${total} OS`
            : `Finalizar (${total} OS)`
          : "Finalizar"}
      </button>
    </div>
  );
}

/* ══════════════════════════════════════════════════════════════════════════
   Abrir passo numa carga da OS
   ══════════════════════════════════════════════════════════════════════════ */

export function PassoModal({ ctx, osId }: { ctx: Ctx; osId: number }) {
  const [processoId, setProcessoId] = useState<number | null>(null);
  const [cargaNome, setCargaNome] = useState<string | null>(null);

  const ordem = ctx.data.ordens.find((o) => o.id === osId);
  if (!ordem) return null;

  const cargas = cargasDe(ctx.data, osId);
  const permitidos = ctx.data.processos.filter(
    (p) => p.ativo && p.posicoes.includes(ordem.posicao),
  );
  const grupos = ETAPAS.map((g) => ({ ...g, itens: permitidos.filter((p) => p.etapa === g.key) }))
    .filter((g) => g.itens.length > 0);

  function confirmar() {
    const carga = cargas.find((c) => c.nome === cargaNome);
    if (!carga || processoId === null) return;
    // Sem lista de acopladas: a composição vem da carga, declarada quando ela
    // foi vinculada. Quem abre a etapa não a redigita.
    const juntas = carga.ordensAcopladas.length;
    ctx.agir({
      fazer: () => api.iniciarLog(osId, carga.id, processoId, ctx.operador.id),
      ok: juntas === 0
        ? `Etapa aberta na carga ${carga.nome}.`
        : `Etapa aberta na carga ${carga.nome} para ${juntas + 1} OS.`,
      depois: () => ctx.abrir({ tipo: "det", osId }),
    });
  }

  return (
    <Modal
      kicker={`OS ${osNum(ordem)} · ABRIR ETAPA`}
      titulo="Abrir etapa (início do intervalo)"
      onClose={ctx.fechar}
      footer={
        <>
          <button className="btn2" onClick={() => ctx.abrir({ tipo: "det", osId })}>
            ← Voltar
          </button>
          <button
            className="btn2 btn2-p btn2-end"
            disabled={processoId === null || cargaNome === null || ctx.ocupado}
            onClick={confirmar}
          >
            Abrir etapa
          </button>
        </>
      }
    >
      <div
        className="bp"
        style={{
          padding: "12px 15px", marginBottom: 16,
          display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap",
        }}
      >
        <Corners />
        <span className="opav" style={{ borderColor: "rgba(89,128,166,.5)" }}>
          {iniciais(ctx.operador.nome)}
        </span>
        <div>
          <div className="os-tv">Responsável (turno atual)</div>
          <div style={{ font: "600 16px 'Barlow Condensed'" }}>{ctx.operador.nome}</div>
        </div>
      </div>

      <span className="lbl">Processo</span>
      <div style={{ display: "flex", flexDirection: "column", gap: 16, margin: "8px 0 20px" }}>
        {grupos.map((g) => (
          <div key={g.key}>
            <div
              style={{
                font: "600 11px 'Barlow Condensed'",
                letterSpacing: ".12em",
                textTransform: "uppercase",
                color: "rgba(29,31,32,.55)",
                display: "flex",
                alignItems: "center",
                gap: 8,
                marginBottom: 9,
              }}
            >
              <span className="turn-dot" style={dotStyle(g.key)} />
              {g.label}
            </div>
            <div style={{ display: "flex", flexWrap: "wrap", gap: 10 }}>
              {g.itens.map((p) => (
                <button
                  key={p.id}
                  className="pick"
                  style={{ flex: "none", ...(processoId === p.id ? SEL_PICK : {}) }}
                  onClick={() => setProcessoId(p.id)}
                >
                  <span className="turn-dot" style={dotStyle(p.etapa)} />
                  {p.descricao}
                </button>
              ))}
            </div>
          </div>
        ))}
        {grupos.length === 0 && (
          <span className="os-tv">Nenhum processo habilitado para {posLabel(ordem.posicao)}.</span>
        )}
      </div>

      <span className="lbl">Carga</span>
      <div style={{ display: "flex", flexWrap: "wrap", gap: 10, marginTop: 8 }}>
        {cargas.map((c) => (
          <button
            key={c.id}
            className="cgtog"
            style={cargaNome === c.nome ? SEL_CHIP : undefined}
            onClick={() => setCargaNome((n) => (n === c.nome ? null : c.nome))}
          >
            {c.nome}
            <span className="tp">{c.tipo}</span>
          </button>
        ))}
        {cargas.length === 0 && <span className="os-tv">Vincule uma carga à OS primeiro.</span>}
      </div>

      {/* A composição da carga escolhida, dita antes de abrir: finalizar esta
          etapa vai encerrá-la para todas, e isso tem de ser visível agora. */}
      {(() => {
        const carga = cargas.find((c) => c.nome === cargaNome);
        if (!carga || carga.ordensAcopladas.length === 0) return null;
        return (
          <div className="bp" style={{ padding: "11px 14px", marginTop: 14 }}>
            <div className="os-tv">
              A carga {carga.nome} leva também{" "}
              {caronasDa(carga, ctx.data.ordens).map((o, i) => (
                <span key={o.id}>{i > 0 && " · "}<b>{osNum(o)}</b></span>
              ))}
              {" "}— a etapa é registada uma vez e vale para todas.
            </div>
          </div>
        );
      })()}

      <div className="os-tv" style={{ marginTop: 12 }}>
        Abre a etapa para uma carga · a etapa anterior dela é fechada automaticamente
      </div>
    </Modal>
  );
}

/* ══════════════════════════════════════════════════════════════════════════
   Expedição
   ══════════════════════════════════════════════════════════════════════════ */

export function ExpedirModal({ ctx, osId }: { ctx: Ctx; osId: number }) {
  const ordem = ctx.data.ordens.find((o) => o.id === osId);
  if (!ordem) return null;
  const cargas = cargasDe(ctx.data, osId);

  return (
    <Modal
      kicker={`OS ${osNum(ordem)} · EXPEDIR`}
      titulo="Expedição de cargas"
      onClose={ctx.fechar}
      footer={
        <>
          <button className="btn2" onClick={() => ctx.abrir({ tipo: "det", osId })}>
            ← Voltar
          </button>
          <button
            className="btn2 btn2-x btn2-end"
            disabled
            title={SEM_API.expedirParcial}
          >
            Expedição parcial
            <span className="na">Indisponível</span>
          </button>
          <button
            className="btn2 btn2-p"
            disabled={ctx.ocupado}
            onClick={() =>
              ctx.agir({
                fazer: () => api.finalizarOrdem(osId, ctx.operador.id),
                ok: `OS ${osNum(ordem)} encerrada; ${cargas.length} carga(s) liberada(s).`,
                depois: ctx.fechar,
              })
            }
          >
            Expedição total (encerrar)
          </button>
        </>
      }
    >
      <span className="lbl">Cargas que serão liberadas ({cargas.length})</span>
      <div style={{ display: "flex", flexWrap: "wrap", gap: 10, marginTop: 8 }}>
        {cargas.map((c) => (
          <span key={c.id} className="cg-chip">
            {c.nome}
            <span className="tp">{c.tipo}</span>
          </span>
        ))}
        {cargas.length === 0 && <span className="os-tv">Nenhuma carga vinculada a esta OS.</span>}
      </div>
      <div className="bp" style={{ padding: "14px 16px", marginTop: 18, background: "#eef6ff" }}>
        <Corners />
        <div className="os-tv">
          <b>Total:</b> libera todas as cargas restantes e encerra a OS.
          <br />
          <b>Parcial:</b> encerra o lote corrente e abre o seguinte, mantendo a OS aberta. Feita na
          Inspeção final, sobre OS que já não têm cargas — para liberar cargas daqui sem mexer no
          lote, use o hub “Encerrar etapas” da home.
        </div>
      </div>
    </Modal>
  );
}

/* ══════════════════════════════════════════════════════════════════════════
   Cancelamento (admin)
   ══════════════════════════════════════════════════════════════════════════ */

export function CancelarModal({ ctx, osId }: { ctx: Ctx; osId: number }) {
  const ordem = ctx.data.ordens.find((o) => o.id === osId);
  if (!ordem) return null;

  return (
    <Modal
      kicker={`OS ${osNum(ordem)} · CANCELAR`}
      titulo="Cancelar Ordem de Serviço"
      onClose={ctx.fechar}
      footer={
        <>
          <button className="btn2" onClick={() => ctx.abrir({ tipo: "det", osId })}>
            ← Voltar
          </button>
          <button
            className="btn2 btn2-d btn2-end"
            disabled={ctx.ocupado}
            onClick={() =>
              ctx.agir({
                fazer: () => api.cancelarOrdem(osId, ctx.operador.id),
                ok: `OS ${osNum(ordem)} cancelada.`,
                depois: ctx.fechar,
              })
            }
          >
            Confirmar cancelamento
          </button>
        </>
      }
    >
      <div
        className="bp"
        style={{
          padding: "18px 20px",
          background: "color-mix(in srgb,#b4472e 6%,transparent)",
          borderColor: "rgba(180,71,46,.4)",
        }}
      >
        <Corners />
        <div style={{ font: "600 18px 'Barlow Condensed'", color: "#8f3421", marginBottom: 8 }}>
          Cancelar {osNum(ordem)}?
        </div>
        <div className="os-tv" style={{ fontSize: 14 }}>
          A OS é marcada como cancelada (soft-cancel), todas as cargas vinculadas são liberadas e o
          histórico de etapas é preservado. Esta ação fica registrada em seu nome.
        </div>
      </div>
    </Modal>
  );
}
