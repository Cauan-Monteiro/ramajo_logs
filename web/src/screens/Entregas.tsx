import { useMemo, useState } from "react";
import type { OperadorDTO, OrdemResumoDTO, Posicao } from "../api/types";
import { Corners } from "../components/Blueprint";
import { OrdenarMenu, useOrdenacao, type ColunaOrd } from "../components/Ordenar";
import { SEL_SEG, aguardandoEntrega, foiEntregue, pillOrdemStyle } from "../domain/derive";
import { POSICOES, diaHora, osNum, posLabel } from "../domain/format";
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
  { chave: "posicao", label: "Posição", ascPadrao: true, valor: (o) => posLabel(o.posicao) },
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

  const colunas = useMemo(() => visiveisDe(modo), [modo]);
  /** A fila abre pela mais recente: o que está parado há menos tempo é o que
      interessa. As entregues abrem pela mais recente, que é o que se confere. */
  const { ord, ordenarPor, ordenar } = useOrdenacao(
    COLUNAS, { chave: ORDEM_NATURAL.fila, asc: false }, () => setPagina(0),
  );

  /** Trocar de lista repõe a ordem natural da nova — `ordenarPor` adopta o
      `ascPadrao` da coluna sempre que ela não é a activa, que é o caso aqui. */
  function trocarModo(novo: Modo) {
    if (novo === modo) return;
    setModo(novo);
    setPagina(0);
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
        if (posicao !== "todas" && o.posicao !== posicao) return false;
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
    posicao: osAberta?.posicao ?? POSICOES[0].key,
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
        <span>Entregas</span>
        <i />
        {isMobile && <OrdenarMenu colunas={colunas} ord={ord} ordenarPor={ordenarPor} />}
      </div>

      <div style={{ display: "flex", gap: 8, flexWrap: "wrap", marginBottom: 14 }}>
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
      </div>

      <div className="bp" style={{ padding: "14px 16px", marginBottom: 16 }}>
        <Corners />
        <span className="lbl">Filtrar</span>
        <div style={{ display: "flex", gap: 10, flexWrap: "wrap", margin: "8px 0 12px" }}>
          {/* "Todas" primeiro: a aba é global, e o filtro por setor é a exceção. */}
          <button
            className="seg-b"
            style={posicao === "todas" ? SEL_SEG : undefined}
            onClick={() => { setPosicao("todas"); setPagina(0); }}
          >
            Todas as posições
          </button>
          {POSICOES.map((p) => (
            <button
              key={p.key}
              className="seg-b"
              style={posicao === p.key ? SEL_SEG : undefined}
              onClick={() => { setPosicao(p.key); setPagina(0); }}
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
            placeholder="Nº da OS (ex: 1042)"
            aria-label="Nº da OS"
            value={qOs}
            onChange={(e) => { setQOs(e.target.value); setPagina(0); }}
          />
          <input
            className="inp"
            placeholder="ID ou nome do cliente"
            aria-label="ID ou nome do cliente"
            value={qCliente}
            onChange={(e) => { setQCliente(e.target.value); setPagina(0); }}
          />
        </div>
      </div>

      <div className="bp" style={{ padding: 0 }}>
        <Corners />
        <div className={`entr-head ${modo === "entregues" ? "entr-full" : ""}`}>
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
          {visiveis.map((o) => (
            <div
              key={o.id}
              className={`entr-row ${modo === "entregues" ? "entr-full" : ""}`}
              onClick={() => setModal({ tipo: "det", osId: o.id })}
            >
              <span className="os-num e-os">{osNum(o)}</span>
              <span className="os-cli e-cli">{o.clienteNome}</span>
              <span className="os-tv e-pos">{posLabel(o.posicao)}</span>
              <span className="os-tv e-exp">{diaHora(o.finalizadaEm)}</span>
              {modo === "entregues" ? (
                <>
                  <span className="os-tv e-ent">{diaHora(o.entregueEm)}</span>
                  <span className="os-tv e-por">{o.entreguePorNome ?? "—"}</span>
                  <span className="lote-pill e-acao" style={pillOrdemStyle(o)}>
                    Entregue
                  </span>
                </>
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
          ))}
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
    </>
  );
}
