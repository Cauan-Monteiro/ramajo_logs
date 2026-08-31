import { useState } from "react";
import type { Barra, Evento, Grupo } from "../domain/auditoria";
import { ROTULO_EVENTO, fracao, horasDaJanela } from "../domain/auditoria";
import { etapaStyle, pillStyle, situacaoOrdem } from "../domain/derive";
import { diaHora, duracao, etapaLabel, hhmm, osNum, posLabel } from "../domain/format";
import { Corners } from "./Blueprint";
import { Vazio } from "./Modal";

/**
 * O swimlane do dia: uma faixa por carga, um bloco por etapa, o eixo em horas.
 *
 * Os blocos são posicionados em percentagem dentro da faixa — não há biblioteca
 * de gráficos no projeto e não faz falta: a única conta é `fracao()`, e ficar em
 * percentagem faz o desenho acompanhar a largura da coluna sem recalcular nada
 * em JS quando a janela do browser muda.
 */

/** Largura mínima de uma hora. Abaixo disto os blocos deixam de ser clicáveis
    com o dedo, por isso a linha do tempo passa a rolar de lado. */
const PX_POR_HORA = 76;
const COL_NOME = 104;

const SIMBOLO: Record<Evento["tipo"], string> = {
  OS_ABERTA: "◆",
  OS_ENCERRADA: "●",
  OS_CANCELADA: "✕",
  LOTE_FECHADO: "▣",
  ETAPA_ABERTA: "·",
  ETAPA_FECHADA: "·",
};

const horaLabel = (t: number) => `${String(new Date(t).getHours()).padStart(2, "0")}h`;

const pct = (n: number) => `${(n * 100).toFixed(3)}%`;

export function LinhaDoTempo({
  grupos, de, ate, ehHoje, agora,
}: {
  grupos: Grupo[];
  de: number;
  ate: number;
  ehHoje: boolean;
  agora: number;
}) {
  /** Um bloco de cada vez: o painel de detalhe abre por baixo da própria faixa,
      e não num modal — esta aba é de leitura, nada aqui interrompe o operador. */
  const [sel, setSel] = useState<string | null>(null);

  const horas = horasDaJanela(de, ate);
  const intervalos = Math.max(1, horas.length - 1);
  const grade = {
    backgroundImage: "linear-gradient(to right, rgba(0,0,0,.07) 1px, transparent 1px)",
    backgroundSize: `${100 / intervalos}% 100%`,
  };
  const larguraAgora = ehHoje && agora >= de && agora <= ate ? fracao(agora, de, ate) : null;

  const linhaAgora =
    larguraAgora === null ? null : <i className="aud-agora" style={{ left: pct(larguraAgora) }} />;

  if (grupos.length === 0) {
    return (
      <div className="bp aud-tl">
        <Corners />
        <Vazio>Nenhuma ordem teve movimento neste dia.</Vazio>
      </div>
    );
  }

  return (
    <div className="bp aud-tl">
      <Corners />
      <div className="aud-scroll">
        <div
          className="aud-inner"
          style={{ minWidth: COL_NOME + intervalos * PX_POR_HORA }}
        >
          <div className="aud-faixa aud-regua">
            <span className="aud-nome" />
            <div className="aud-lane" style={grade}>
              {horas.map((h) => (
                <span key={h} className="aud-hora" style={{ left: pct(fracao(h, de, ate)) }}>
                  {horaLabel(h)}
                </span>
              ))}
            </div>
          </div>

          {grupos.map((g) => (
            <div key={g.ordem.id} className="aud-grupo">
              <div className="aud-oshd">
                <span className="os-num">{osNum(g.ordem)}</span>
                <span className="os-cli">{g.ordem.clienteNome}</span>
                <span className="os-tv">{posLabel(g.ordem.posicao)}</span>
                <span className="lote-pill" style={pillStyle(!g.ordem.emProcesso)}>
                  {situacaoOrdem(g.ordem)}
                </span>
                <span className="os-tv" style={{ marginLeft: "auto" }}>
                  {g.faixas.length} {g.faixas.length === 1 ? "carga" : "cargas"}
                </span>
              </div>

              {g.marcos.length > 0 && (
                <div className="aud-faixa">
                  <span className="aud-nome aud-nome-os">Ordem</span>
                  <div className="aud-lane aud-lane-m" style={grade}>
                    {linhaAgora}
                    {g.marcos.map((m) => (
                      <span
                        key={m.id}
                        className={`aud-marco m-${m.tipo}`}
                        style={{ left: pct(fracao(m.em, de, ate)) }}
                        title={textoMarco(m)}
                      >
                        {SIMBOLO[m.tipo]}
                      </span>
                    ))}
                  </div>
                </div>
              )}

              {g.faixas.map((f, iF) => {
                const aberta = f.barras.find((b) => b.logId === sel);
                return (
                  <div key={f.cargaNome}>
                    <div className="aud-faixa">
                      <span className="aud-nome">{f.cargaNome}</span>
                      <div className="aud-lane" style={grade}>
                        {linhaAgora}
                        {f.barras.map((b, iB) => (
                          <button
                            key={b.logId}
                            type="button"
                            className={
                              "aud-b" +
                              (b.aberta ? " on" : "") +
                              (b.cancelado ? " cx" : "") +
                              (b.logId === sel ? " sel" : "")
                            }
                            style={{
                              left: pct(fracao(b.de, de, ate)),
                              width: pct(fracao(b.ate, de, ate) - fracao(b.de, de, ate)),
                              // Escalonar a entrada dá a leitura da esquerda para
                              // a direita; o atraso satura para a lista não
                              // demorar meio minuto a aparecer inteira.
                              animationDelay: `${Math.min(600, (iF * 3 + iB) * 40)}ms`,
                              ...etapaStyle(b.etapa),
                            }}
                            onClick={() => setSel((s) => (s === b.logId ? null : b.logId))}
                            title={textoBarra(b)}
                          >
                            {b.vemDeOntem && <i className="aud-chev e">◀</i>}
                            <span className="aud-b-txt">
                              <b>{b.processoDescricao}</b>
                              <i>{b.responsavelNome}</i>
                            </span>
                            {b.passaDaMeiaNoite && <i className="aud-chev d">▶</i>}
                          </button>
                        ))}
                      </div>
                    </div>
                    {aberta && <Detalhe b={aberta} />}
                  </div>
                );
              })}
            </div>
          ))}
        </div>
      </div>

      <div className="aud-leg">
        <span className="etp" style={etapaStyle("PRE_TRATAMENTO")}>Pré-tratamento</span>
        <span className="etp" style={etapaStyle("TRATAMENTO")}>Tratamento</span>
        <span className="etp" style={etapaStyle("POS_TRATAMENTO")}>Pós-tratamento</span>
        <span className="etp" style={etapaStyle(null)}>Sem etapa</span>
        <span className="os-tv">◆ abertura · ▣ lote fechado · ● expedição · ✕ cancelamento</span>
        <span className="os-tv">Contorno tracejado = etapa ainda aberta · ◀▶ = atravessa a meia-noite</span>
      </div>
    </div>
  );
}

function Detalhe({ b }: { b: Barra }) {
  return (
    <div className="aud-det">
      <div className="aud-det-g">
        <span className="etp" style={etapaStyle(b.etapa)}>{etapaLabel(b.etapa)}</span>
        <span style={{ font: "600 17px 'Barlow Condensed'" }}>{b.processoDescricao}</span>
        <span className="os-tv">Carga {b.cargaNome}</span>
        {b.cancelado && <span className="lote-pill" style={pillStyle(true)}>Cancelada</span>}
      </div>
      <div className="aud-det-g os-tv">
        <span>Abriu <b>{b.responsavelNome}</b> às {hhmm(b.iniciadoEm)}</span>
        <span>
          {b.finalizadoEm
            ? `Fechou às ${hhmm(b.finalizadoEm)}`
            : b.cancelado ? "Cancelada" : "Ainda aberta"}
        </span>
        <span>{duracao(b.iniciadoEm, b.finalizadoEm)}</span>
        {(b.vemDeOntem || b.passaDaMeiaNoite) && (
          <span>
            {b.vemDeOntem ? `Começou em ${diaHora(b.iniciadoEm)}` : "Continua no dia seguinte"}
          </span>
        )}
      </div>
      {/* A API não regista quem fecha um passo: só o responsável da abertura. */}
      {b.finalizadoEm && <div className="os-tv">Quem fechou não é registado pela API.</div>}
    </div>
  );
}

function textoMarco(m: Evento): string {
  const quem = m.autor ? ` · ${m.autor}` : "";
  return `${hhmm(new Date(m.em).toISOString())} · ${ROTULO_EVENTO[m.tipo]}${quem}`;
}

function textoBarra(b: Barra): string {
  return (
    `${b.processoDescricao} · carga ${b.cargaNome} · abriu ${b.responsavelNome}` +
    ` · ${hhmm(b.iniciadoEm)}–${b.finalizadoEm ? hhmm(b.finalizadoEm) : "em aberto"}` +
    ` · ${duracao(b.iniciadoEm, b.finalizadoEm)}`
  );
}
