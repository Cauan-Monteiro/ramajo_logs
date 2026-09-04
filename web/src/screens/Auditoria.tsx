import { useMemo, useState } from "react";
import type { Posicao } from "../api/types";
import { BarraDia } from "../components/BarraDia";
import { Corners } from "../components/Blueprint";
import { Kpi } from "../components/Kpi";
import { LinhaDoTempo } from "../components/LinhaDoTempo";
import { Vazio } from "../components/Modal";
import { useOrdenacao, type ColunaOrd } from "../components/Ordenar";
import {
  FECHA, ROTULO_EVENTO, eventosDoDia, faixasDoDia, janelaVisivel, porOperador, porOrdem,
  type Evento, type ResumoOrdem, type TipoEvento,
} from "../domain/auditoria";
import { SEL_SEG, dotStyle, etapaStyle, pillStyle } from "../domain/derive";
import {
  POSICOES, duracao, etapaLabel, hm, iniciais, iso, posLabel,
} from "../domain/format";
import { useAgora } from "../state/useAgora";
import { useAuditoriaDia } from "../state/useAuditoriaDia";
import type { AppData } from "../state/useAppData";

/**
 * Visão Geral — a aba, aberta a todo operador, para quando surge a dúvida de
 * se algo foi mesmo feito, por quem e a que horas. É estritamente de leitura:
 * não há um único botão aqui que mude o estado da fábrica.
 */

const TIPOS: TipoEvento[] = [
  "OS_ABERTA", "ETAPA_ABERTA", "ETAPA_FECHADA", "LOTE_FECHADO", "OS_ENCERRADA", "OS_CANCELADA",
];

export function Auditoria({ data, onErro }: { data: AppData; onErro: (e: unknown) => void }) {
  const agora = useAgora(30000);
  const [dia, setDia] = useState(() => iso(new Date()));
  const [posicao, setPosicao] = useState<Posicao | "TODAS">("TODAS");

  const ehHoje = dia === iso(new Date(agora));
  const { detalhes, logs, carregando } = useAuditoriaDia(data, dia, onErro);

  const ordens = useMemo(
    () => data.ordens.filter((o) => posicao === "TODAS" || o.posicao === posicao),
    [data.ordens, posicao],
  );

  const fonte = useMemo(
    () => ({ ordens, detalhes, logs, processos: data.processos, dia, agora }),
    [ordens, detalhes, logs, data.processos, dia, agora],
  );

  const eventos = useMemo(() => eventosDoDia(fonte), [fonte]);
  const grupos = useMemo(() => faixasDoDia(fonte), [fonte]);

  const janela = useMemo(() => {
    const t: number[] = eventos.map((e) => e.em);
    for (const g of grupos) for (const f of g.faixas) for (const b of f.barras) t.push(b.de, b.ate);
    return janelaVisivel(t, dia, agora);
  }, [eventos, grupos, dia, agora]);

  const abertasAgora = useMemo(() => {
    let n = 0;
    for (const g of grupos) for (const f of g.faixas) for (const b of f.barras) if (b.aberta) n++;
    return n;
  }, [grupos]);

  const conta = (t: TipoEvento) => eventos.filter((e) => e.tipo === t).length;
  const operadores = useMemo(() => porOperador(eventos), [eventos]);
  const cargas = useMemo(
    () => porOrdem(grupos, data.processosIniciais),
    [grupos, data.processosIniciais],
  );

  return (
    <>
      <div className="reg-h" style={{ fontSize: 13 }}>Visão Geral</div>

      <BarraDia dia={dia} onDia={setDia} agora={agora} carregando={carregando}>
        <div className="aud-seg">
          {([["TODAS", "Todas"], ...POSICOES.map((p) => [p.key, p.label] as const)] as const).map(
            ([k, label]) => (
              <button
                key={k}
                className="seg-b"
                style={posicao === k ? SEL_SEG : undefined}
                onClick={() => setPosicao(k as Posicao | "TODAS")}
              >
                {label}
              </button>
            ),
          )}
        </div>
      </BarraDia>

      <div className="aud-kpis">
        <Kpi n={conta("OS_ABERTA")} label="OS abertas" />
        <Kpi n={conta("OS_ENCERRADA")} label="OS expedidas" />
        <Kpi n={conta("LOTE_FECHADO")} label="Lotes fechados" />
        <Kpi n={conta("ETAPA_ABERTA")} label="Etapas iniciadas" />
        <Kpi n={conta("ETAPA_FECHADA")} label="Etapas concluídas" />
        <Kpi n={abertasAgora} label={ehHoje ? "Em curso agora" : "Ficaram em curso"} />
      </div>

      <LinhaDoTempo
        grupos={grupos}
        de={janela.de}
        ate={janela.ate}
        ehHoje={ehHoje}
        agora={agora}
      />

      <div className="aud-cols">
        <OrdensDoDia eventos={eventos} />
        <QuemFez operadores={operadores} />
        <CargasDoDia cargas={cargas} />
      </div>

      <Feed eventos={eventos} />

      <div className="aud-nota os-tv">
        Este recorte é montado a partir do histórico da API. Um passo só aparece
        depois de ser aberto no terminal — o que não foi registado não existe aqui.
        A API guarda quem <b>abriu</b> cada etapa, mas não quem a fechou.
      </div>
    </>
  );
}

/* ── ordens do dia ──────────────────────────────────────────────────────── */

function OrdensDoDia({ eventos }: { eventos: Evento[] }) {
  const abertas = eventos.filter((e) => e.tipo === "OS_ABERTA");
  const fechadas = eventos.filter(
    (e) => e.tipo === "OS_ENCERRADA" || e.tipo === "OS_CANCELADA" || e.tipo === "LOTE_FECHADO",
  );

  return (
    <div className="bp aud-card">
      <Corners />
      <div className="aud-card-h">Ordens do dia</div>

      <div className="lbl" style={{ marginTop: 4 }}>Abertas</div>
      {abertas.map((e) => (
        <div key={e.id} className="aud-linha">
          <span className="aud-hh">{hm(e.em)}</span>
          <span className="os-num" style={{ fontSize: 17 }}>{e.osLabel}</span>
          <span className="aud-el">{e.clienteNome}</span>
          <span className="os-tv">{posLabel(e.posicao)}</span>
          <Autor nome={e.autor} />
        </div>
      ))}
      {abertas.length === 0 && <Vazio>Nenhuma OS aberta neste dia.</Vazio>}

      <div className="lbl" style={{ marginTop: 18 }}>Fechamentos</div>
      {fechadas.map((e) => (
        <div key={e.id} className="aud-linha">
          <span className="aud-hh">{hm(e.em)}</span>
          <span className="os-num" style={{ fontSize: 17 }}>{e.osLabel}</span>
          <span className="lote-pill" style={pillStyle(e.tipo !== "LOTE_FECHADO")}>
            {ROTULO_EVENTO[e.tipo]}
          </span>
          <span className="aud-el os-tv">
            {e.duracaoMs === null ? "" : duracao(new Date(e.em - e.duracaoMs).toISOString(), new Date(e.em).toISOString())}
          </span>
          <Autor nome={e.autor} />
        </div>
      ))}
      {fechadas.length === 0 && <Vazio>Nada foi fechado neste dia.</Vazio>}
    </div>
  );
}

/** Nome de quem assinou; o traço é literal — a API não registou ninguém. */
function Autor({ nome }: { nome: string | null }) {
  return nome ? (
    <span className="aud-quem">{nome}</span>
  ) : (
    <span className="aud-quem sem" title="A API não regista o autor desta ação">—</span>
  );
}

/* ── quem fez o quê ─────────────────────────────────────────────────────── */

function QuemFez({ operadores }: { operadores: ReturnType<typeof porOperador> }) {
  const topo = operadores[0]?.total ?? 1;
  return (
    <div className="bp aud-card">
      <Corners />
      <div className="aud-card-h">Quem fez o quê</div>
      {operadores.map((o) => (
        <div key={o.nome} className="aud-op">
          <span className="opav">{iniciais(o.nome)}</span>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ font: "600 16px 'Barlow Condensed'" }}>{o.nome}</div>
            <div className="os-tv">
              {o.etapasAbertas} etapas · {o.osAbertas} OS abertas · {o.lotes} lotes ·{" "}
              {o.osEncerradas} encerramentos
            </div>
            <div className="aud-op-bar">
              <i style={{ width: `${(o.total / topo) * 100}%` }} />
            </div>
          </div>
          <span className="aud-op-n">{o.total}</span>
        </div>
      ))}
      {operadores.length === 0 && <Vazio>Ninguém registou ações neste dia.</Vazio>}
    </div>
  );
}

/* ── cargas do dia ──────────────────────────────────────────────────────── */

/**
 * Quanta carga cada OS moveu. A conta é por *entrada*: duas etapas da mesma
 * carga dentro da mesma ordem são uma entrada só, mas a carga desvinculada e
 * vinculada outra vez à mesma OS entra de novo — ver `porOrdem`.
 */
function CargasDoDia({ cargas }: { cargas: ResumoOrdem[] }) {
  const topo = cargas[0]?.total ?? 1;
  // Soma das entradas: a mesma carga em três OS conta três vezes, e o número
  // não muda com o reagrupamento por OS.
  const entradas = cargas.reduce((s, c) => s + c.total, 0);
  return (
    <div className="bp aud-card aud-cargas">
      <Corners />
      <div className="aud-card-h">Cargas processadas · {entradas}</div>
      {cargas.map((c) => (
        <div key={c.osLabel} className="aud-op">
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ display: "flex", alignItems: "center", gap: 7 }}>
              {c.etapas.map((e) => (
                <span
                  key={e}
                  className="turn-dot"
                  style={dotStyle(e)}
                  title={etapaLabel(e)}
                />
              ))}
              <span className="os-num" style={{ fontSize: 17 }}>{c.osLabel}</span>
            </div>
            {/* O title guarda a lista inteira: .aud-el trunca numa linha só. */}
            <div className="os-tv aud-el" title={c.cargaNomes.join(" · ")}>
              {c.cargaNomes.join(" · ")}
            </div>
            <div className="aud-op-bar">
              <i style={{ width: `${(c.total / topo) * 100}%` }} />
            </div>
          </div>
          <span className="aud-op-n">{c.total}</span>
        </div>
      ))}
      {cargas.length === 0 && <Vazio>Nenhuma carga entrou em processo neste dia.</Vazio>}
    </div>
  );
}

/* ── feed cronológico ───────────────────────────────────────────────────── */

const COLUNAS: ColunaOrd<Evento>[] = [
  { chave: "hora", label: "Hora", ascPadrao: true, valor: (e) => e.em },
  { chave: "evento", label: "Evento", ascPadrao: true, valor: (e) => ROTULO_EVENTO[e.tipo] },
  { chave: "os", label: "OS", ascPadrao: true, valor: (e) => e.osId },
  { chave: "carga", label: "Carga", ascPadrao: true, valor: (e) => e.cargaNome },
  { chave: "processo", label: "Processo", ascPadrao: true, valor: (e) => e.processoDescricao },
  { chave: "autor", label: "Responsável", ascPadrao: true, valor: (e) => e.autor },
];

function Feed({ eventos }: { eventos: Evento[] }) {
  const [tipos, setTipos] = useState<TipoEvento[]>([]);
  const [quem, setQuem] = useState("");
  const { ord, ordenarPor, ordenar } = useOrdenacao(COLUNAS, { chave: "hora", asc: true });

  const nomes = useMemo(
    () => [...new Set(eventos.map((e) => e.autor).filter((n): n is string => !!n))].sort(),
    [eventos],
  );

  const linhas = useMemo(() => {
    const filtradas = eventos.filter(
      (e) =>
        (tipos.length === 0 || tipos.includes(e.tipo)) &&
        (quem === "" || e.autor === quem),
    );
    // `ordenar` faz sort no lugar: a cópia impede que a lista memoizada de
    // eventos seja reordenada por baixo do swimlane.
    return ordenar([...filtradas], (a, b) => a.id.localeCompare(b.id));
  }, [eventos, tipos, quem, ordenar]);

  const alternar = (t: TipoEvento) =>
    setTipos((s) => (s.includes(t) ? s.filter((x) => x !== t) : [...s, t]));

  return (
    <div className="bp aud-card" style={{ marginTop: 22 }}>
      <Corners />
      <div className="aud-card-h">Tudo o que aconteceu · {linhas.length} registos</div>

      <div className="aud-chips">
        {TIPOS.map((t) => (
          <button
            key={t}
            className={`aud-chip${tipos.includes(t) ? " on" : ""}`}
            onClick={() => alternar(t)}
          >
            {ROTULO_EVENTO[t]}
          </button>
        ))}
        <select className="inp aud-sel" value={quem} onChange={(e) => setQuem(e.target.value)}>
          <option value="">Todos os operadores</option>
          {nomes.map((n) => (
            <option key={n} value={n}>{n}</option>
          ))}
        </select>
      </div>

      <div className="tblwrap">
        <table className="table">
          <thead>
            <tr>
              {COLUNAS.map((c) => (
                <th
                  key={c.chave}
                  className="aud-th"
                  onClick={() => ordenarPor(c.chave)}
                >
                  {c.label}
                  {ord.chave === c.chave && <i className="aud-seta">{ord.asc ? "↑" : "↓"}</i>}
                </th>
              ))}
              <th>Duração</th>
            </tr>
          </thead>
          <tbody>
            {linhas.map((e) => (
              <tr key={e.id}>
                <td style={{ whiteSpace: "nowrap" }}>{hm(e.em)}</td>
                <td>
                  <span className="lote-pill" style={pillStyle(FECHA.includes(e.tipo))}>
                    {ROTULO_EVENTO[e.tipo]}
                  </span>
                </td>
                <td style={{ font: "600 16px 'Barlow Condensed'" }}>{e.osLabel}</td>
                <td>{e.cargaNome ?? "—"}</td>
                <td>
                  {e.processoDescricao ? (
                    <>
                      <span className="etp" style={{ ...etapaStyle(e.etapa), marginRight: 8 }}>
                        {etapaLabel(e.etapa)}
                      </span>
                      {e.processoDescricao}
                    </>
                  ) : (
                    e.clienteNome
                  )}
                </td>
                <td><Autor nome={e.autor} /></td>
                <td style={{ whiteSpace: "nowrap" }}>
                  {e.duracaoMs === null
                    ? "—"
                    : duracao(
                      new Date(e.em - e.duracaoMs).toISOString(),
                      new Date(e.em).toISOString(),
                    )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {linhas.length === 0 && <Vazio>Nenhum registo com estes filtros.</Vazio>}
    </div>
  );
}
