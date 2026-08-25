import { useEffect, useState } from "react";
import * as api from "../api/endpoints";
import type { LogDTO } from "../api/types";
import { Corners } from "../components/Blueprint";
import { Modal, Vazio } from "../components/Modal";
import { IconScan } from "../components/Icons";
import {
  SEL_CHIP, SEL_PICK, dotStyle, etapaStyle, etapaDoLog, isAberto, labelEtapaDoLog, logSub,
} from "../domain/derive";
import { ETAPAS, duracao, hhmm, iniciais, osNum, posLabel } from "../domain/format";
import { cargasDe, cargasLivres, logsDe } from "../state/useAppData";
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
  useEffect(() => {
    if (ordem && !ordem.emProcesso) {
      api.historicoOrdem(osId).then(setExtra).catch(() => setExtra([]));
    }
  }, [osId, ordem]);

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
                className="btn2 btn2-p"
                style={{ marginLeft: "auto" }}
                onClick={() => ctx.abrir({ tipo: "vinc", osId })}
              >
                Vincular cargas
              </button>
            </>
          )}
        </>
      }
    >
      <div style={{ display: "flex", gap: 20, marginBottom: 18, flexWrap: "wrap" }}>
        <div style={{ flex: 1, minWidth: 160 }}>
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
        <div>
          <div className="os-tv">Cargas na OS</div>
          <div className="os-cli" style={{ fontSize: 16 }}>
            {cargas.length ? cargas.map((c) => c.nome).join(" · ") : "—"}
          </div>
        </div>
        <div>
          <div className="os-tv">Situação</div>
          <div>
            <span className="lote-pill">
              {!ordem.emProcesso
                ? "Encerrada"
                : ordem.lotesFinalizados > 0
                  ? `2º lote — lote ${ordem.lotesFinalizados} expedido`
                  : "Em produção"}
            </span>
          </div>
        </div>
      </div>

      {abertos.length > 0 && (
        <>
          <span className="lbl" style={{ color: "#2f6b3c" }}>
            Etapas em andamento
          </span>
          <div style={{ margin: "6px 0 18px" }}>
            {abertos.map((l) => (
              <div key={l.id} className="openrow">
                <span className="live-dot" />
                <div style={{ flex: 1 }}>
                  <div style={{ font: "600 16px 'Barlow Condensed'" }}>{l.processoDescricao}</div>
                  <div className="os-tv">
                    Carga {l.cargaNome} · {l.responsavelNome}
                  </div>
                </div>
                <span className="time" style={{ marginRight: 8 }}>
                  aberto {hhmm(l.iniciadoEm)}
                </span>
                <button
                  className="btn2 btn2-p"
                  style={{ padding: "9px 16px", fontSize: 14 }}
                  disabled={ctx.ocupado}
                  onClick={() =>
                    ctx.agir({
                      fazer: () => api.finalizarLog(l.id),
                      ok: `Etapa "${l.processoDescricao}" finalizado na carga ${l.cargaNome}.`,
                    })
                  }
                >
                  Finalizar
                </button>
              </div>
            ))}
          </div>
        </>
      )}

      <span className="lbl">Histórico de etapas</span>
      <div style={{ maxHeight: 240, overflow: "auto", marginTop: 6 }}>
        {fechados.map((l) => (
          <div key={l.id} className="tline">
            <span
              className="etp"
              style={{ ...etapaStyle(etapaDoLog(l, ctx.data.processos)), marginTop: 1, flex: "none" }}
            >
              {labelEtapaDoLog(l, ctx.data.processos)}
            </span>
            <div style={{ flex: 1 }}>
              <div style={{ font: "600 16px 'Barlow Condensed'" }}>{l.processoDescricao}</div>
              <div className="os-tv">{logSub(l, duracao(l.iniciadoEm, l.finalizadoEm))}</div>
            </div>
            <span className="time">{hhmm(l.finalizadoEm)}</span>
          </div>
        ))}
        {fechados.length === 0 && <Vazio>Ainda sem etapas concluídos.</Vazio>}
      </div>
    </Modal>
  );
}

/* ══════════════════════════════════════════════════════════════════════════
   Vincular cargas a uma OS já aberta
   ══════════════════════════════════════════════════════════════════════════ */

export function VincularModal({ ctx, osId }: { ctx: Ctx; osId: number }) {
  const [sel, setSel] = useState<string[]>([]);
  const ordem = ctx.data.ordens.find((o) => o.id === osId);
  if (!ordem) return null;

  const livres = cargasLivres(ctx.data, ordem.posicao);
  const jaNaOS = cargasDe(ctx.data, osId);

  function lerCarga() {
    const tag = window.prompt("Encoste a etiqueta ou digite a tag da carga:");
    if (!tag?.trim()) return;
    ctx.agir({
      fazer: async () => {
        const c = await api.cargaPorTag(tag.trim());
        if (!c) throw new Error(`Nenhuma carga com a tag "${tag.trim()}".`);
        if (!livres.some((l) => l.id === c.id)) {
          throw new Error(`A carga ${c.nome} não está livre em ${posLabel(ordem!.posicao)}.`);
        }
        setSel((s) => (s.includes(c.nome) ? s : [...s, c.nome]));
      },
    });
  }

  function confirmar() {
    const ids = livres.filter((c) => sel.includes(c.nome)).map((c) => c.id);
    ctx.agir({
      fazer: () => Promise.all(ids.map((id) => api.vincularCarga(osId, id, ctx.operador.id))),
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
            className="btn2 btn2-p"
            style={{ marginLeft: "auto" }}
            disabled={sel.length === 0 || ctx.ocupado}
            onClick={confirmar}
          >
            Vincular à OS
          </button>
        </>
      }
    >
      <div style={{ display: "flex", alignItems: "center", marginBottom: 10 }}>
        <span className="lbl" style={{ margin: 0 }}>
          Cargas livres na posição {posLabel(ordem.posicao)} (toque para vincular)
        </span>
        <button className="scan-b" style={{ marginLeft: "auto" }} onClick={lerCarga}>
          <IconScan size={16} />
          Ler carga
        </button>
      </div>
      <div style={{ display: "flex", flexWrap: "wrap", gap: 10 }}>
        {livres.map((c) => (
          <button
            key={c.id}
            className="cgtog"
            style={sel.includes(c.nome) ? SEL_CHIP : undefined}
            onClick={() =>
              setSel((s) => (s.includes(c.nome) ? s.filter((n) => n !== c.nome) : [...s, c.nome]))
            }
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
    </Modal>
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
  const permitidos = ctx.data.processos.filter((p) => p.posicoes.includes(ordem.posicao));
  const grupos = ETAPAS.map((g) => ({ ...g, itens: permitidos.filter((p) => p.etapa === g.key) }))
    .filter((g) => g.itens.length > 0);

  function confirmar() {
    const carga = cargas.find((c) => c.nome === cargaNome);
    if (!carga || processoId === null) return;
    ctx.agir({
      fazer: () => api.iniciarLog(osId, carga.id, processoId, ctx.operador.id),
      ok: `Etapa aberta na carga ${carga.nome}.`,
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
            className="btn2 btn2-p"
            style={{ marginLeft: "auto" }}
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
        style={{ padding: "12px 15px", marginBottom: 16, display: "flex", alignItems: "center", gap: 10 }}
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
      <div className="os-tv" style={{ marginTop: 12 }}>
        Abre o etapa para uma carga · etapa anterior dela é fechado automaticamente
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
            className="btn2 btn2-x"
            style={{ marginLeft: "auto" }}
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
          <b>Parcial:</b> liberaria só algumas cargas, mantendo a OS aberta. Ainda indisponível —
          depende de uma mudança na API.
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
            className="btn2 btn2-d"
            style={{ marginLeft: "auto" }}
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
