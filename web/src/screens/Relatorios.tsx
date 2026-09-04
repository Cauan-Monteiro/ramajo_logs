import { useEffect, useMemo, useState } from "react";
import * as api from "../api/endpoints";
import type { LogDTO, OrdemDetalheDTO } from "../api/types";
import { Corners } from "../components/Blueprint";
import { Vazio } from "../components/Modal";
import { etapaStyle, etapaDoLog, labelEtapaDoLog, logSub, pillStyle, situacaoOrdem } from "../domain/derive";
import { diaHora, duracao, hhmm, horasEntre, iso, osNum, posLabel } from "../domain/format";
import type { AppData } from "../state/useAppData";

type Rel = 0 | 1 | 2 | 3;

export function Relatorios({ data, onErro }: { data: AppData; onErro: (e: unknown) => void }) {
  const [rel, setRel] = useState<Rel>(0);

  return (
    <div className="rel-body">
      <div className="rel-side">
        <div className="reg-h" style={{ padding: "0 22px", fontSize: 13 }}>
          Relatórios
        </div>
        {(
          [
            [0, "Histórico completo de uma OS", "Todos os passos em ordem cronológica"],
            [1, "OS por cliente", "Todas as ordens de um cliente"],
            [2, "Tempo médio de conclusão", "Média entre abertura e expedição total"],
            [3, "Relatório por período", "Planilha .xlsx das OSs de um intervalo"],
          ] as const
        ).map(([n, titulo, sub]) => (
          <div
            key={n}
            className="bp repcard"
            style={rel === n ? { background: "#eef6ff", borderColor: "#5980a6" } : undefined}
            onClick={() => setRel(n)}
          >
            <Corners />
            <span style={{ font: "600 18px 'Barlow Condensed'" }}>{titulo}</span>
            <span className="os-tv">{sub}</span>
          </div>
        ))}
      </div>

      <div className="rel-main">
        {rel === 0 && <HistoricoOS data={data} onErro={onErro} />}
        {rel === 1 && <OSPorCliente data={data} />}
        {rel === 2 && <TempoMedio data={data} onErro={onErro} />}
        {rel === 3 && <PlanilhaPeriodo onErro={onErro} />}
      </div>
    </div>
  );
}

/* ── 1 · histórico completo de uma OS ───────────────────────────────────── */

function HistoricoOS({ data, onErro }: { data: AppData; onErro: (e: unknown) => void }) {
  const [osId, setOsId] = useState<number | null>(null);
  const [logs, setLogs] = useState<LogDTO[]>([]);
  const [baixando, setBaixando] = useState(false);
  const [busca, setBusca] = useState("");
  const ordem = data.ordens.find((o) => o.id === osId);

  // A lista crescia sem limite: com algumas centenas de OS, o painel virava uma
  // parede de botões que empurrava o relatório para fora do ecrã.
  const termo = busca.trim().toLowerCase();
  const visiveis = termo
    ? data.ordens.filter(
      (o) => osNum(o).toLowerCase().includes(termo) || o.clienteNome.toLowerCase().includes(termo),
    )
    : data.ordens;

  function baixarPlanilha(id: number) {
    setBaixando(true);
    api
      .planilhaOrdem(id)
      .catch(onErro)
      .finally(() => setBaixando(false));
  }

  useEffect(() => {
    if (osId === null) return;
    api.historicoOrdem(osId).then(setLogs).catch(onErro);
  }, [osId, onErro]);

  return (
    <>
      <div className="reg-h" style={{ fontSize: 13 }}>
        Histórico completo de uma OS
      </div>
      <div className="rel-filtro">
        <input
          className="inp"
          placeholder="Filtrar por nº da OS ou cliente"
          value={busca}
          onChange={(e) => setBusca(e.target.value)}
        />
      </div>
      <div className="rel-oslist">
        {visiveis.map((o) => (
          <button
            key={o.id}
            className="pick"
            style={osId === o.id ? { background: "#eef6ff", borderColor: "#5980a6" } : undefined}
            onClick={() => setOsId(o.id)}
          >
            {osNum(o)} · {o.clienteNome}
          </button>
        ))}
        {data.ordens.length === 0 && <span className="os-tv">Nenhuma OS cadastrada.</span>}
        {data.ordens.length > 0 && visiveis.length === 0 && (
          <span className="os-tv">Nenhuma OS corresponde a "{busca.trim()}".</span>
        )}
      </div>

      {ordem && (
        <div className="bp" style={{ padding: "22px 24px" }}>
          <Corners />
          <div className="rel-oshd">
            <span className="os-num">{osNum(ordem)}</span>
            <span className="os-cli" style={{ fontSize: 19 }}>
              {ordem.clienteNome}
            </span>
            <button
              className="pick"
              style={{ marginLeft: "auto" }}
              disabled={baixando}
              onClick={() => baixarPlanilha(ordem.id)}
            >
              {baixando ? "Baixando..." : "Baixar planilha"}
            </button>
          </div>
          <div className="os-tv" style={{ marginBottom: 16 }}>
            Aberta {diaHora(ordem.iniciadaEm)} · {posLabel(ordem.posicao)} ·{" "}
            {ordem.emProcesso ? "em aberto" : "encerrada"}
          </div>
          {logs.map((l) => (
            <div key={l.id} className="tline">
              <span
                className="etp"
                style={{ ...etapaStyle(etapaDoLog(l, data.processos)), marginTop: 1, flex: "none" }}
              >
                {labelEtapaDoLog(l, data.processos)}
              </span>
              <div style={{ flex: 1 }}>
                <div style={{ font: "600 16px 'Barlow Condensed'" }}>{l.processoDescricao}</div>
                <div className="os-tv">
                  {logSub(l, l.finalizadoEm ? duracao(l.iniciadoEm, l.finalizadoEm) : "em andamento")}
                </div>
              </div>
              <span className="time">{hhmm(l.finalizadoEm ?? l.iniciadoEm)}</span>
            </div>
          ))}
          {logs.length === 0 && <Vazio>Ainda sem etapas registradas.</Vazio>}
        </div>
      )}
    </>
  );
}

/* ── 2 · OS por cliente ─────────────────────────────────────────────────── */

function OSPorCliente({ data }: { data: AppData }) {
  const [clienteId, setClienteId] = useState<number | null>(data.clientes[0]?.id ?? null);
  const cliente = data.clientes.find((c) => c.id === clienteId);

  // OrdemResumoDTO não traz clienteId, só clienteNome — o filtro é por nome.
  const linhas = data.ordens.filter((o) => cliente && o.clienteNome === cliente.nome);

  return (
    <>
      <div className="reg-h" style={{ fontSize: 13 }}>
        OS por cliente
      </div>
      <div className="rel-filtro">
        <span className="lbl">Cliente</span>
        <select
          className="inp"
          value={clienteId ?? ""}
          onChange={(e) => setClienteId(Number(e.target.value))}
        >
          {data.clientes.map((c) => (
            <option key={c.id} value={c.id}>
              #{c.id} · {c.nome}
            </option>
          ))}
        </select>
      </div>
      <div className="bp" style={{ minHeight: 120 }}>
        <Corners />
        <div className="tblwrap">
          <table className="table">
            <thead>
              <tr>
                <th style={{ paddingLeft: 18 }}>OS</th>
                <th>Posição</th>
                <th>Aberta em</th>
                <th>Situação</th>
                <th style={{ paddingRight: 18 }}>Lotes</th>
              </tr>
            </thead>
            <tbody>
              {linhas.map((o) => (
                <tr key={o.id}>
                  <td style={{ paddingLeft: 18, font: "600 17px 'Barlow Condensed'" }}>{osNum(o)}</td>
                  <td>{posLabel(o.posicao)}</td>
                  <td>{diaHora(o.iniciadaEm)}</td>
                  <td>
                    <span className="lote-pill" style={pillStyle(!o.emProcesso)}>
                      {situacaoOrdem(o)}
                    </span>
                  </td>
                  <td style={{ paddingRight: 18 }}>
                    {o.lotesFinalizados} de {o.totalLotes}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {linhas.length === 0 && <Vazio>Nenhuma OS para este cliente.</Vazio>}
      </div>
    </>
  );
}

/* ── 3 · tempo médio de conclusão ───────────────────────────────────────── */

function TempoMedio({ data, onErro }: { data: AppData; onErro: (e: unknown) => void }) {
  const [detalhes, setDetalhes] = useState<OrdemDetalheDTO[]>([]);
  const [carregando, setCarregando] = useState(true);

  // OrdemResumoDTO não traz finalizadaEm nem o flag de cancelada: só o detalhe.
  // Por isso este relatório busca uma vez cada OS já encerrada.
  const encerradas = useMemo(() => data.ordens.filter((o) => !o.emProcesso), [data.ordens]);

  useEffect(() => {
    let vivo = true;
    setCarregando(true);
    Promise.all(encerradas.map((o) => api.buscarOrdem(o.id)))
      .then((lista) => {
        if (vivo) setDetalhes(lista);
      })
      .catch(onErro)
      .finally(() => {
        if (vivo) setCarregando(false);
      });
    return () => {
      vivo = false;
    };
  }, [encerradas, onErro]);

  const concluidas = detalhes.filter((o) => !o.cancelada && o.finalizadaEm);
  const media =
    concluidas.length === 0
      ? null
      : concluidas.reduce(
        (acc, o) =>
          acc + (new Date(o.finalizadaEm as string).getTime() - new Date(o.iniciadaEm).getTime()),
        0,
      ) / concluidas.length;

  const mediaTexto =
    media === null
      ? "—"
      : `${Math.floor(media / 3600000)}h${String(
        Math.round((media % 3600000) / 60000),
      ).padStart(2, "0")}`;

  return (
    <>
      <div className="reg-h" style={{ fontSize: 13 }}>
        Tempo médio de conclusão
      </div>
      {carregando ? (
        <div className="os-tv">Carregando ordens encerradas...</div>
      ) : (
        <>
          <div style={{ display: "flex", gap: 20, flexWrap: "wrap" }}>
            <div className="bp" style={{ padding: "28px 34px", minWidth: 240 }}>
              <Corners />
              <div className="os-tv" style={{ marginBottom: 6 }}>
                Média geral
              </div>
              <div style={{ font: "600 52px 'Barlow Condensed'", color: "#416180", lineHeight: 1 }}>
                {mediaTexto}
              </div>
              <div className="os-tv" style={{ marginTop: 8 }}>
                {concluidas.length} OS concluídas
              </div>
            </div>
          </div>
          <div className="bp" style={{ marginTop: 22 }}>
            <Corners />
            <div className="tblwrap">
              <table className="table">
                <thead>
                  <tr>
                    <th style={{ paddingLeft: 18 }}>OS</th>
                    <th>Cliente</th>
                    <th>Aberta</th>
                    <th style={{ paddingRight: 18 }}>Concluída em</th>
                  </tr>
                </thead>
                <tbody>
                  {concluidas.map((o) => (
                    <tr key={o.id}>
                      <td style={{ paddingLeft: 18, font: "600 17px 'Barlow Condensed'" }}>
                        {osNum(o)}
                      </td>
                      <td>{o.clienteNome}</td>
                      <td>{diaHora(o.iniciadaEm)}</td>
                      <td style={{ paddingRight: 18 }}>
                        {diaHora(o.finalizadaEm)} (
                        {horasEntre(o.iniciadaEm, o.finalizadaEm as string)})
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {concluidas.length === 0 && <Vazio>Nenhuma OS concluída ainda.</Vazio>}
          </div>
        </>
      )}
    </>
  );
}

/* ── 4 · relatório por período (.xlsx) ──────────────────────────────────── */

function PlanilhaPeriodo({ onErro }: { onErro: (e: unknown) => void }) {
  // Abre no mês corrente: é o recorte pedido na esmagadora maioria das vezes.
  const [inicio, setInicio] = useState(() => {
    const h = new Date();
    return iso(new Date(h.getFullYear(), h.getMonth(), 1));
  });
  const [fim, setFim] = useState(() => iso(new Date()));
  const [baixando, setBaixando] = useState(false);

  // Strings ISO comparam como datas: o formato é ordenável lexicograficamente.
  const invertido = Boolean(inicio && fim && fim < inicio);

  function baixar() {
    setBaixando(true);
    api
      .planilhaPeriodo(inicio, fim)
      .catch(onErro)
      .finally(() => setBaixando(false));
  }

  return (
    <>
      <div className="reg-h" style={{ fontSize: 13 }}>
        Relatório por período
      </div>

      <div className="rel-filtro" style={{ display: "flex", gap: 14, alignItems: "flex-end", flexWrap: "wrap" }}>
        <div style={{ flex: "1 1 150px", minWidth: 0 }}>
          <span className="lbl">De</span>
          <input
            className="inp"
            type="date"
            value={inicio}
            max={fim || undefined}
            onChange={(e) => setInicio(e.target.value)}
          />
        </div>
        <div style={{ flex: "1 1 150px", minWidth: 0 }}>
          <span className="lbl">Até</span>
          <input
            className="inp"
            type="date"
            value={fim}
            min={inicio || undefined}
            onChange={(e) => setFim(e.target.value)}
          />
        </div>
        <button
          className="btn2 btn2-p"
          style={{ minHeight: 48 }}
          disabled={baixando || !inicio || !fim || invertido}
          onClick={baixar}
        >
          {baixando ? "Baixando..." : "Baixar planilha"}
        </button>
      </div>

      {/* A API rejeita o mesmo caso com 400 PERIODO_INVALIDO; barrar aqui evita
          a ida à rede e diz o que houve junto do campo errado. */}
      {invertido && (
        <div className="os-tv" style={{ color: "#b4472e", marginTop: -16, marginBottom: 24 }}>
          A data final é anterior à inicial.
        </div>
      )}

      <div className="bp" style={{ padding: "22px 24px", maxWidth: 560 }}>
        <Corners />
        <div style={{ font: "600 18px 'Barlow Condensed'", marginBottom: 10 }}>
          O que vem na planilha
        </div>
        <div className="os-tv" style={{ lineHeight: 1.7 }}>
          Uma linha por OS <b>iniciada</b> no intervalo — ambos os dias entram inteiros.
          <br />
          Aba <b>Relatório</b>: resumo do período, indicadores (no período, finalizadas, em
          processo, canceladas) e a tabela, pronta para imprimir.
          <br />
          Aba <b>Dados</b>: a mesma tabela crua, com autofiltro, para filtrar ou pivotar.
          <br />
          <b>Duração total</b> é o relógio de parede entre abrir e fechar a OS;{" "}
          <b>tempo trabalhado</b> é a soma das etapas efetivamente concluídas nela.
        </div>
      </div>
    </>
  );
}
