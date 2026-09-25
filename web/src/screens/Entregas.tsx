import { useMemo, useState } from "react";
import type { OperadorDTO, OrdemResumoDTO, Posicao } from "../api/types";
import { Corners } from "../components/Blueprint";
import { OrdenarMenu, useOrdenacao, type ColunaOrd } from "../components/Ordenar";
import { SEL_SEG, aguardandoEntrega, foiEntregue, pillOrdemStyle, rodaEm } from "../domain/derive";
import { POSICOES, diaHora, osNum, posLabels, posOrdenadas } from "../domain/format";
import { EntregarLoteModal } from "../modals/EntregarLote";
import { Modais } from "../modals/Modais";
import type { Ctx, ModalState } from "../modals/tipos";
import type { AppData } from "../state/useAppData";

/* ══════════════════════════════════════════════════════════════════════════
   Entregas
   ══════════════════════════════════════════════════════════════════════════

   A expedição tira as peças da produção; a entrega tira-as da casa. Entre uma
   e outra a OS não aparecia em lado nenhum — some do painel da posição, que só
   lista `emProcesso` —, e era esse o buraco: ninguém sabia o que estava
   expedido e ainda parado.

   GLOBAL, e não por posição como o Dashboard: quem carrega o caminhão não
   trabalha num setor, carrega o que houver. A posição fica como filtro.

   Tudo sai de `data.ordens`, que o `useAppData` já carrega inteiro (todas as
   OS, não só as em processo) e que o resumo passou a servir com `cancelada` e
   `entregueEm`. Nenhuma chamada de rede própria. */

/* Em ecrã de telemóvel cada linha ocupa duas alturas — as mesmas contas do
   Dashboard. */
const POR_PAGINA = 30;
const POR_PAGINA_MOBILE = 12;

type Modo = "fila" | "entregues";

/** Desempate estável: sem ele a ordem treme a cada sync quando há empate (duas
    OS expedidas no mesmo minuto, o caso comum de uma expedição em série). */
const porNumero = (a: OrdemResumoDTO, b: OrdemResumoDTO) =>
  osNum(a).localeCompare(osNum(b), "pt-BR", { numeric: true });

const ms = (iso: string | null) => {
  if (!iso) return null;
  const t = Date.parse(iso);
  return Number.isNaN(t) ? null : t;
};

/**
 * Todas as colunas das duas listas, sempre — é o array que o `useOrdenacao`
 * recebe, e ele precisa de conhecer também as do outro modo: ordenar por
 * "Entregue em" e voltar para a fila deixaria a ordenação a apontar para uma
 * coluna que o hook não sabe resolver, e a lista cairia num critério qualquer.
 *
 * O que muda com o modo é quais se MOSTRAM (`colunasVisiveis`): a fila não tem
 * o que dizer sobre uma entrega que não aconteceu.
 */
const COLUNAS: ColunaOrd<OrdemResumoDTO>[] = [
  {
    // Pelo número e não pelo osNum(): "#9" antes de "#10", não depois.
    chave: "os", label: "OS", ascPadrao: true,
    valor: (o) => o.idExterno ?? o.id,
  },
  { chave: "cliente", label: "Cliente", ascPadrao: true, valor: (o) => o.clienteNome },
  { chave: "posicao", label: "Posição", ascPadrao: true, valor: (o) => posLabels(o.posicoes) },
  {
    chave: "expedida", label: "Expedida em", ascPadrao: false,  // mais recente primeiro
    valor: (o) => ms(o.finalizadaEm),
  },
  {
    chave: "entregue", label: "Entregue em", ascPadrao: true,  // mais antiga primeiro
    valor: (o) => ms(o.entregueEm),
  },
  { chave: "por", label: "Entregue por", ascPadrao: true, valor: (o) => o.entreguePorNome },
];

/** As quatro comuns na fila; as seis nas entregues. */
const visiveisDe = (modo: Modo) => (modo === "fila" ? COLUNAS.slice(0, 4) : COLUNAS);

/** A coluna por onde cada lista abre — e para onde volta ao trocar de modo. */
const ORDEM_NATURAL: Record<Modo, string> = { fila: "expedida", entregues: "entregue" };

export function Entregas({
  data, operador, isAdmin, agir, ocupado, isMobile,
}: {
  data: AppData;
  operador: OperadorDTO;
  isAdmin: boolean;
  agir: Ctx["agir"];
  ocupado: boolean;
  isMobile: boolean;
}) {
  const [modo, setModo] = useState<Modo>("fila");
  const [posicao, setPosicao] = useState<Posicao | "todas">("todas");
  /** Os dois pontos de busca; ver o bloco de filtro mais abaixo. */
  const [qOs, setQOs] = useState("");
  const [qCliente, setQCliente] = useState("");
  const [pagina, setPagina] = useState(0);
  const [modal, setModal] = useState<ModalState>(null);
  /** Modo de múltipla seleção: entra por um botão explícito e desliga
      qualquer troca de aba/filtro. Fora dele a tela funciona como antes —
      clique na linha abre o detalhe, e cada linha tem seu botão "Entregar". */
  const [selMode, setSelMode] = useState(false);
  /** Ids das OS marcadas para entrega em lote. Só faz sentido em `selMode`. */
  const [sel, setSel] = useState<number[]>([]);
  const limparSel = () => setSel([]);
  const sairSelMode = () => { setSelMode(false); limparSel(); };
  const resetPagina = () => { setPagina(0); limparSel(); };

  const colunas = useMemo(() => visiveisDe(modo), [modo]);
  /** A fila abre pela mais recente: o que está parado há menos tempo é o que
      interessa. As entregues abrem pela mais recente, que é o que se confere. */
  const { ord, ordenarPor, ordenar } = useOrdenacao(
    COLUNAS, { chave: ORDEM_NATURAL.fila, asc: false }, resetPagina,
  );

  /** Trocar de lista repõe a ordem natural da nova — `ordenarPor` adopta o
      `ascPadrao` da coluna sempre que ela não é a activa, que é o caso aqui. */
  function trocarModo(novo: Modo) {
    if (novo === modo) return;
    setModo(novo);
    resetPagina();
    sairSelMode();
    ordenarPor(ORDEM_NATURAL[novo]);
  }

  const fila = useMemo(() => data.ordens.filter(aguardandoEntrega), [data.ordens]);
  const entregues = useMemo(() => data.ordens.filter(foiEntregue), [data.ordens]);

  /** O resumo não traz `clienteId` (só o detalhe) — o id do cliente sai do
      catálogo, cruzado pelo nome. Mesma travessia do modal de busca global. */
  const clientesPorNome = useMemo(
    () => new Map(data.clientes.map((c) => [c.nome, c.id])),
    [data.clientes],
  );

  const termoOs = qOs.trim().toLowerCase();
  const termoCliente = qCliente.trim().toLowerCase();
  const lista = useMemo(() => {
    const base = modo === "fila" ? fila : entregues;
    return ordenar(
      base.filter((o) => {
        if (posicao !== "todas" && !rodaEm(o, posicao)) return false;
        // Os dois campos CRUZAM-SE (E, não OU): quem carrega o caminhão tem em
        // mãos dois dados independentes — o Nº da OS e o cliente —, e o que
        // procura é a linha onde eles se encontram. Campo vazio não filtra.
        if (
          termoOs
          && !String(o.idExterno ?? "").includes(termoOs)
          && !String(o.id).includes(termoOs)
        ) return false;
        if (termoCliente) {
          const id = clientesPorNome.get(o.clienteNome);
          if (
            !String(id ?? "").includes(termoCliente)
            && !o.clienteNome.toLowerCase().includes(termoCliente)
          ) return false;
        }
        return true;
      }),
      porNumero,
    );
  }, [modo, fila, entregues, posicao, termoOs, termoCliente, clientesPorNome, ordenar]);

  const porPagina = isMobile ? POR_PAGINA_MOBILE : POR_PAGINA;
  const totalPaginas = Math.max(1, Math.ceil(lista.length / porPagina));
  const pag = Math.min(pagina, totalPaginas - 1);
  const visiveis = lista.slice(pag * porPagina, pag * porPagina + porPagina);

  /** Só há seleção quando o botão de múltipla seleção está ligado e o modo
      é fila; em "entregues" não faz sentido marcar. */
  const podeSelecionar = modo === "fila" && selMode;
  const selSet = useMemo(() => new Set(sel), [sel]);
  const todasVisiveisSelecionadas =
    podeSelecionar && visiveis.length > 0 && visiveis.every((o) => selSet.has(o.id));

  function alternarUma(id: number) {
    setSel((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]));
  }

  function alternarTodasVisiveis() {
    if (todasVisiveisSelecionadas) {
      const idsVisiveis = new Set(visiveis.map((o) => o.id));
      setSel((s) => s.filter((id) => !idsVisiveis.has(id)));
    } else {
      setSel((s) => Array.from(new Set([...s, ...visiveis.map((o) => o.id)])));
    }
  }

  /**
   * `Ctx` local para o detalhe da OS. A tela é global, então a `posicao` — que
   * o modal usa para oferecer cargas e processos — é a da OS aberta; fora
   * disso não há posição nenhuma em jogo e o valor não é lido.
   */
  const osAberta = modal && "osId" in modal
    ? data.ordens.find((o) => o.id === modal.osId)
    : undefined;

  const ctx: Ctx = {
    data,
    posicao:
      (posicao !== "todas" && osAberta && rodaEm(osAberta, posicao) ? posicao : null)
      ?? (osAberta && posOrdenadas(osAberta.posicoes)[0])
      ?? POSICOES[0].key,
    operador,
    isAdmin,
    ocupado,
    fechar: () => setModal(null),
    abrir: setModal,
    agir,
  };

  const modos: { key: Modo; label: string; n: number }[] = [
    { key: "fila", label: "Aguardando entrega", n: fila.length },
    { key: "entregues", label: "Entregues", n: entregues.length },
  ];

  return (
    <>
      <div className="grp-h">
        <span>Expedição</span>
        <i />
        {isMobile && <OrdenarMenu colunas={colunas} ord={ord} ordenarPor={ordenarPor} />}
      </div>

      <div style={{ display: "flex", gap: 8, flexWrap: "wrap", marginBottom: 14, alignItems: "center" }}>
        {modos.map((m) => (
          <button
            key={m.key}
            className="seg-b"
            style={modo === m.key ? SEL_SEG : undefined}
            onClick={() => trocarModo(m.key)}
          >
            {m.label} ({m.n})
          </button>
        ))}
        {/* Toggle de múltipla seleção. Só faz sentido na fila — em
            "entregues" o botão não aparece. Ligar mostra os checkboxes e a
            barra de ação; desligar limpa a seleção. */}
        {modo === "fila" && fila.length > 0 && (
          <button
            className="seg-b"
            style={{ marginLeft: "auto", ...(selMode ? SEL_SEG : {}) }}
            onClick={() => (selMode ? sairSelMode() : setSelMode(true))}
          >
            {selMode ? "Cancelar seleção" : "Múltipla seleção"}
          </button>
        )}
      </div>

      <div className="bp" style={{ padding: "14px 16px", marginBottom: 16 }}>
        <Corners />
        <span className="lbl">Filtrar</span>
        <div style={{ display: "flex", gap: 10, flexWrap: "wrap", margin: "8px 0 12px" }}>
          {/* "Todas" primeiro: a aba é global, e o filtro por setor é a exceção. */}
          <button
            className="seg-b"
            style={posicao === "todas" ? SEL_SEG : undefined}
            onClick={() => { setPosicao("todas"); resetPagina(); }}
          >
            Todas as posições
          </button>
          {POSICOES.map((p) => (
            <button
              key={p.key}
              className="seg-b"
              style={posicao === p.key ? SEL_SEG : undefined}
              onClick={() => { setPosicao(p.key); resetPagina(); }}
            >
              {p.label}
            </button>
          ))}
        </div>
        {/* Dois pontos de entrada que se cruzam: o Nº da OS e o cliente. Um
            sozinho já filtra; juntos, restam só as OS que casam nos dois. */}
        <div className="entr-filtros">
          <input
            className="inp"
            placeholder="Nº da OS)"
            aria-label="Nº da OS"
            value={qOs}
            onChange={(e) => { setQOs(e.target.value); resetPagina(); }}
          />
          <input
            className="inp"
            placeholder="ID ou nome do cliente"
            aria-label="ID ou nome do cliente"
            value={qCliente}
            onChange={(e) => { setQCliente(e.target.value); resetPagina(); }}
          />
        </div>
      </div>

      {/* Barra de ação em lote: aparece enquanto o modo de múltipla seleção
          estiver ligado. Mostra a contagem e o botão que dispara a entrega
          em lote. Sair do modo (botão do topo) limpa a seleção. */}
      {podeSelecionar && (
        <div
          className="bp"
          style={{
            padding: "10px 14px",
            marginBottom: 12,
            display: "flex",
            alignItems: "center",
            gap: 12,
            flexWrap: "wrap",
          }}
        >
          <Corners />
          <span className="lbl" style={{ marginRight: "auto" }}>
            {sel.length === 0
              ? "Marque as OS que sairão juntas."
              : `${sel.length} OS selecionada(s)`}
          </span>
          {sel.length > 0 && (
            <button className="btn2" onClick={limparSel}>
              Limpar
            </button>
          )}
          <button
            className="btn2 btn2-p"
            style={{ padding: "8px 14px", fontSize: 14 }}
            disabled={ocupado || sel.length === 0}
            onClick={() => setModal({ tipo: "entregarLote" })}
          >
            Entregar {sel.length > 0 ? `${sel.length} OS` : "selecionadas"}
          </button>
        </div>
      )}

      <div className="bp" style={{ padding: 0 }}>
        <Corners />
        <div
          className={`entr-head ${modo === "entregues" ? "entr-full" : ""} ${podeSelecionar ? "entr-sel" : ""}`}
        >
          {podeSelecionar && (
            <span className="e-sel">
              <input
                type="checkbox"
                aria-label="Selecionar todas as OS visíveis"
                checked={todasVisiveisSelecionadas}
                onChange={alternarTodasVisiveis}
              />
            </span>
          )}
          {colunas.map((c) => (
            <button
              key={c.chave}
              type="button"
              className="clsort"
              aria-label={`Ordenar por ${c.label}`}
              onClick={() => ordenarPor(c.chave)}
            >
              {c.label}
              {ord.chave === c.chave && <i className="ordseta">{ord.asc ? "↑" : "↓"}</i>}
            </button>
          ))}
          <span />
        </div>

        <div>
          {visiveis.map((o) => {
            const marcada = podeSelecionar && selSet.has(o.id);
            return (
              <div
                key={o.id}
                /* No modo de múltipla seleção a linha inteira é o alvo do
                   toque — abrir o detalhe atrapalha quem só quer marcar. Fora
                   dele, comportamento antigo: clique abre o detalhe. */
                className={`entr-row ${modo === "entregues" ? "entr-full" : ""} ${podeSelecionar ? "entr-sel" : ""} ${marcada ? "sel" : ""}`}
                onClick={() =>
                  podeSelecionar
                    ? alternarUma(o.id)
                    : setModal({ tipo: "det", osId: o.id })
                }
              >
                {podeSelecionar && (
                  <span className="e-sel">
                    <input
                      type="checkbox"
                      aria-label={`Selecionar OS ${osNum(o)}`}
                      checked={marcada}
                      /* O clique já é tratado no wrapper da linha; o checkbox
                         segue o estado sem disparar por conta própria. */
                      onChange={() => { /* controlado pela linha */ }}
                      onClick={(e) => e.stopPropagation()}
                    />
                  </span>
                )}
                <span className="os-num e-os">{osNum(o)}</span>
                <span className="os-cli e-cli">{o.clienteNome}</span>
                <span className="os-tv e-pos">{posLabels(o.posicoes)}</span>
                <span className="os-tv e-exp">{diaHora(o.finalizadaEm)}</span>
                {modo === "entregues" ? (
                  <>
                    <span className="os-tv e-ent">{diaHora(o.entregueEm)}</span>
                    <span className="os-tv e-por">{o.entreguePorNome ?? "—"}</span>
                    <span className="lote-pill e-acao" style={pillOrdemStyle(o)}>
                      Entregue
                    </span>
                  </>
                ) : podeSelecionar ? (
                  /* Em múltipla seleção a coluna de ação vira só o rótulo
                     do estado — o botão "Entregar" da linha some porque a
                     entrega é do lote inteiro. */
                  <span className="os-tv e-acao">{marcada ? "Marcada" : ""}</span>
                ) : (
                  /* A confirmação é um diálogo, e não um segundo toque na
                     própria linha: só a reabertura desfaz uma entrega, e quem
                     confirma precisa de ver a OS, o cliente e a posição.
                     stopPropagation porque a linha abre o detalhe. */
                  <button
                    className="btn2 btn2-p e-acao"
                    style={{ padding: "8px 14px", fontSize: 14 }}
                    disabled={ocupado}
                    onClick={(e) => {
                      e.stopPropagation();
                      setModal({ tipo: "entregar", osId: o.id });
                    }}
                  >
                    Entregar
                  </button>
                )}
              </div>
            );
          })}
          {lista.length === 0 && (
            <div className="empty">
              {modo === "fila"
                ? fila.length === 0
                  ? "Nenhuma OS aguardando entrega."
                  : "Nenhuma OS da fila corresponde ao filtro."
                : entregues.length === 0
                  ? "Nenhuma entrega registrada ainda."
                  : "Nenhuma entrega corresponde ao filtro."}
            </div>
          )}
        </div>
      </div>

      <div className="pager">
        <button
          className="pgb"
          disabled={pag <= 0}
          onClick={() => setPagina((p) => Math.max(0, p - 1))}
        >
          ← Anterior
        </button>
        <span className="cmuted">
          Página {pag + 1} de {totalPaginas} · {lista.length} OS
        </span>
        <button
          className="pgb"
          style={{ marginLeft: "auto" }}
          disabled={pag >= totalPaginas - 1}
          onClick={() => setPagina((p) => p + 1)}
        >
          Próxima →
        </button>
      </div>

      {/* A teia inteira, e não só o detalhe: reabrir uma OS daqui emenda no
          modal de vínculo, e de lá abre-se etapa, expede-se, e por aí fora. */}
      <Modais ctx={ctx} modal={modal} />
      {/* O modal em lote fica fora do dispatch central porque depende do
          estado local da tela (`sel`) — mesma convenção do PassoLote no
          Dashboard. */}
      {modal?.tipo === "entregarLote" && (
        <EntregarLoteModal ctx={ctx} selecao={sel} aoConcluir={limparSel} />
      )}
    </>
  );
}
