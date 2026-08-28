import { useMemo, useState } from "react";
import type {
  CargaDTO, LogDTO, OperadorDTO, OrdemResumoDTO, Posicao, ProcessoDTO,
} from "../api/types";
import { Corners } from "../components/Blueprint";
import {
  IconCargas, IconCheck, IconCheckBox, IconBusca, IconInspecao, IconLogo,
  IconPasso, IconPlus, IconProcessos,
} from "../components/Icons";
import {
  emSegundoLote, etapaDoLog, etapaStyle, labelEtapaDoLog, logAbertoDaCarga,
} from "../domain/derive";
import { ETAPAS, duracao, hhmm, osNum, posLabel } from "../domain/format";
import { cargasLivres, logsDe, type AppData } from "../state/useAppData";
import { BuscarOSModal } from "../modals/BuscarOS";
import { CargasLivresModal } from "../modals/CargasLivres";
import { CriarOSModal } from "../modals/CriarOS";
import {
  CancelarModal, DetalheOSModal, ExpedirModal, PassoModal, VincularModal,
} from "../modals/DetalheOS";
import { EncerrarLoteModal } from "../modals/EncerrarLote";
import { InspecaoModal } from "../modals/Inspecao";
import { PassoLoteModal } from "../modals/PassoLote";
import { ProcessosModal } from "../modals/Processos";
import type { Ctx, ModalState } from "../modals/tipos";

/* Em ecrã de telemóvel a linha da carga ocupa duas alturas e o polegar rola
   muito mais: menos linhas por página cansa menos do que uma lista infinita. */
const POR_PAGINA = 30;
const POR_PAGINA_MOBILE = 12;

type ChaveOrd = "nome" | "vinculo" | "etapa" | "desde" | "duracao";
type Ord = { chave: ChaveOrd; asc: boolean };

/** Uma linha da tabela com os cruzamentos já feitos. Ordenar por "Vínculo" ou
    "Etapa" exige esses valores ANTES do sort — não dá para descobri-los dentro
    do map, como era antes. */
type Linha = { carga: CargaDTO; ordem?: OrdemResumoDTO; aberto?: LogDTO };

/**
 * As colunas ordenáveis, na mesma ordem em que aparecem na linha. O cabeçalho
 * do desktop e o menu do telemóvel leem os dois daqui: rótulo e direção natural
 * vivem num sítio só.
 *
 * `valor` devolve `null` quando a linha não tem o dado — carga sem etapa aberta,
 * OS que não veio no carregamento. Essas caem sempre para o fim, nos dois
 * sentidos: um "—" no topo da lista não informa nada.
 */
const COLUNAS: {
  chave: ChaveOrd;
  label: string;
  ascPadrao: boolean;
  valor: (l: Linha, processos: ProcessoDTO[]) => string | number | null;
}[] = [
  { chave: "nome", label: "Carga", ascPadrao: true, valor: (l) => l.carga.nome },
  {
    chave: "vinculo", label: "Vínculo", ascPadrao: true,
    // Pelo número e não pelo osNum(): "#9" antes de "#10", não depois.
    valor: (l) => (l.ordem ? l.ordem.idExterno ?? l.ordem.id : null),
  },
  {
    chave: "etapa", label: "Etapa atual", ascPadrao: true,
    // Agrupa por etapa na ordem do processo (pré → tratamento → pós) e, dentro
    // dela, pela descrição — é o que a célula mostra, chip e texto.
    valor: (l, processos) => {
      if (!l.aberto) return null;
      const e = etapaDoLog(l.aberto, processos);
      const i = e ? ETAPAS.findIndex((x) => x.key === e) : ETAPAS.length;
      return `${i}${l.aberto.processoDescricao}`;
    },
  },
  {
    chave: "desde", label: "Desde", ascPadrao: true,  // mais antigo primeiro
    valor: (l) => msDe(l.aberto),
  },
  {
    // Toda etapa aberta tem `finalizadoEm: null`, logo a duração é só
    // `agora - iniciadoEm`: maior duração ⇔ início mais antigo. Daí o sinal,
    // e daí esta coluna ser o espelho de "Desde" — de propósito, porque clicar
    // num cabeçalho e ele não reagir surpreende mais do que a redundância.
    chave: "duracao", label: "Duração", ascPadrao: false,  // maior primeiro
    valor: (l) => {
      const ms = msDe(l.aberto);
      return ms === null ? null : -ms;
    },
  },
];

function msDe(log: LogDTO | undefined): number | null {
  if (!log) return null;
  const ms = Date.parse(log.iniciadoEm);
  return Number.isNaN(ms) ? null : ms;
}

/** Desempate sempre crescente por nome: sem ele a ordem treme a cada sync
    quando há empate (várias cargas na mesma OS, na mesma etapa, no mesmo
    minuto). `numeric` para o dia em que existir "T 100" — os nomes de hoje são
    zero-padded, por isso a ordem actual não muda. */
const porNome = (a: Linha, b: Linha) =>
  a.carga.nome.localeCompare(b.carga.nome, "pt-BR", { numeric: true });

function comparar(
  a: Linha, b: Linha, col: (typeof COLUNAS)[number], asc: boolean,
  processos: ProcessoDTO[],
): number {
  const va = col.valor(a, processos);
  const vb = col.valor(b, processos);
  if (va === null || vb === null) {
    if (va === vb) return porNome(a, b);
    return va === null ? 1 : -1;  // sem dado vai ao fim nos dois sentidos
  }
  const d =
    typeof va === "number" && typeof vb === "number"
      ? va - vb
      : String(va).localeCompare(String(vb), "pt-BR", { numeric: true });
  return (asc ? d : -d) || porNome(a, b);
}

export function Dashboard({
  data, posicao, operador, isAdmin, agir, ocupado, isMobile,
}: {
  data: AppData;
  posicao: Posicao;
  operador: OperadorDTO;
  isAdmin: boolean;
  agir: Ctx["agir"];
  ocupado: boolean;
  isMobile: boolean;
}) {
  const [modal, setModal] = useState<ModalState>(null);
  const [sel, setSel] = useState<string[]>([]);
  const [pagina, setPagina] = useState(0);
  const [ord, setOrd] = useState<Ord>({ chave: "nome", asc: true });

  const label = posLabel(posicao);
  const naPos = data.ordens.filter((o) => o.emProcesso && o.posicao === posicao);
  const emProducao = naPos.filter((o) => !emSegundoLote(o));
  const emLote = naPos.filter(emSegundoLote);
  const livres = cargasLivres(data, posicao);

  /** Cargas desta posição já vinculadas a alguma OS — as linhas da tabela,
      com OS e etapa aberta já cruzadas e na ordem pedida pelo cabeçalho. */
  const linhasNaPos = useMemo(() => {
    const col = COLUNAS.find((c) => c.chave === ord.chave) ?? COLUNAS[0];
    return data.cargas
      .filter((c) => c.posicao === posicao && c.ordemAtualId !== null)
      .map<Linha>((carga) => {
        const ordem = data.ordens.find((o) => o.id === carga.ordemAtualId);
        return {
          carga,
          ordem,
          aberto: ordem ? logAbertoDaCarga(carga.nome, logsDe(data, ordem.id)) : undefined,
        };
      })
      .sort((a, b) => comparar(a, b, col, ord.asc, data.processos));
  }, [data, posicao, ord]);

  const porPagina = isMobile ? POR_PAGINA_MOBILE : POR_PAGINA;
  const totalPaginas = Math.max(1, Math.ceil(linhasNaPos.length / porPagina));
  const pag = Math.min(pagina, totalPaginas - 1);
  const linhas = linhasNaPos.slice(pag * porPagina, pag * porPagina + porPagina);

  const selSet = new Set(sel);
  const todasSelecionadas =
    linhasNaPos.length > 0 && linhasNaPos.every((l) => selSet.has(l.carga.nome));

  const semCargasCount = data.ordens.filter(
    (o) => o.emProcesso && !data.cargas.some((c) => c.ordemAtualId === o.id),
  ).length;

  const ctx: Ctx = {
    data, posicao, operador, isAdmin, ocupado,
    fechar: () => setModal(null),
    abrir: setModal,
    agir: ({ fazer, ok, depois }) =>
      agir({
        fazer,
        ok,
        depois: () => {
          setSel([]);
          depois?.();
        },
      }),
  };

  const alternar = (nome: string) =>
    setSel((s) => (s.includes(nome) ? s.filter((n) => n !== nome) : [...s, nome]));

  /** Coluna activa outra vez inverte; coluna nova adopta a direção natural
      dela. Volta à página 1: manter a página 3 depois de reordenar mostraria
      um pedaço arbitrário de uma lista nova. */
  const ordenarPor = (chave: ChaveOrd) => {
    setOrd((o) =>
      o.chave === chave
        ? { chave, asc: !o.asc }
        : { chave, asc: COLUNAS.find((c) => c.chave === chave)!.ascPadrao },
    );
    setPagina(0);
  };

  return (
    <div className="dash-root">
      <div className="posbar">
        <IconLogo size={40} width={1.4} />
        <div style={{ lineHeight: 1 }}>
          <div className="plbl">Posição em operação</div>
          <div className="pname">{label}</div>
        </div>
        <div className="pos-stats">
          <div className="posmini">
            <span className="n">{emProducao.length}</span>
            <span className="t">OS em processo</span>
          </div>
          <div className="posmini">
            <span className="n">{emLote.length}</span>
            <span className="t">Em 2º lote</span>
          </div>
          <div className="posmini">
            <span className="n">{livres.length}</span>
            <span className="t">Cargas livres</span>
          </div>
        </div>
      </div>

      <div className="dash-body">
        <div className="dash-main">
          <div className="grp-h grp-acoes">
            <span>Ações</span>
            <i />
          </div>
          <div className="hub-grid">
            <button
              className="hub hub-x bp"
              disabled={sel.length > 0}
              title={sel.length > 0 ? "Limpe a seleção de cargas para criar uma OS." : undefined}
              onClick={() => setModal({ tipo: "cad" })}
            >
              <Corners />
              <IconPlus className="hicon" />
              <span className="htitle">Criar</span>
              <span className="hsub">
                {sel.length > 0
                  ? "Limpe a seleção de cargas para criar uma OS"
                  : "Nova ordem de serviço nesta posição"}
              </span>
            </button>

            <button
              className="hub bp"
              disabled={sel.length === 0}
              onClick={() => setModal({ tipo: "passoLote" })}
            >
              <Corners />
              <IconPasso className="hicon" />
              <span className="htitle">Abrir etapa</span>
              <span className="hsub">
                {sel.length ? `${sel.length} carga(s) selecionada(s)` : "Selecione cargas na lista"}
              </span>
            </button>

            <button
              className="hub bp"
              disabled={sel.length === 0}
              onClick={() => setModal({ tipo: "encerrarLote" })}
            >
              <Corners />
              <IconCheckBox className="hicon" />
              <span className="htitle">Encerrar etapas</span>
              <span className="hsub">
                {sel.length ? `${sel.length} carga(s) selecionada(s)` : "Selecione cargas na lista"}
              </span>
            </button>

            <button className="hub bp" onClick={() => setModal({ tipo: "inspecao" })}>
              <Corners />
              <IconInspecao className="hicon" />
              <span className="htitle">Inspeção final</span>
              <span className="hsub">
                {semCargasCount
                  ? `${semCargasCount} OS sem cargas vinculadas`
                  : "Nenhuma OS sem cargas"}
              </span>
            </button>
          </div>

          <div className="grp-h" style={{ marginTop: 2 }}>
            <span>Cargas na posição</span>
            <i />
            {isMobile && <OrdenarMenu ord={ord} ordenarPor={ordenarPor} />}
          </div>

          <div className="bp cl-wrap">
            <Corners />
            <div className="clhead">
              <span
                className={`ckbox ${todasSelecionadas ? "on" : ""}`}
                onClick={() =>
                  setSel(todasSelecionadas ? [] : linhasNaPos.map((l) => l.carga.nome))
                }
              >
                <IconCheck />
              </span>
              {COLUNAS.map((c) => (
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
            </div>

            <div className="cl-scroll">
              {linhas.map(({ carga: c, ordem, aberto }) => {
                const marcada = selSet.has(c.nome);
                return (
                  <div
                    key={c.id}
                    className={`clrow ${marcada ? "sel" : ""}`}
                    onClick={() => alternar(c.nome)}
                  >
                    <span className={`ckbox ${marcada ? "on" : ""}`}>
                      <IconCheck />
                    </span>
                    <span className="cnome">{c.nome}</span>
                    <span className="cvinc">{ordem ? `OS ${osNum(ordem)}` : "—"}</span>
                    <span
                      className="passocell"
                      style={
                        aberto
                          ? { background: "#eef6ff", borderColor: "rgba(89,128,166,.45)" }
                          : undefined
                      }
                    >
                      <span
                        className="etp"
                        style={
                          aberto
                            ? etapaStyle(etapaDoLog(aberto, data.processos))
                            : { background: "#c9c9cc", color: "#f2f2f3" }
                        }
                      >
                        {aberto ? labelEtapaDoLog(aberto, data.processos) : "○"}
                      </span>
                      <span
                        className="passotxt"
                        style={
                          aberto
                            ? { color: "#2c455d" }
                            : { color: "rgba(29,31,32,.4)", fontStyle: "italic" }
                        }
                      >
                        {aberto ? aberto.processoDescricao : "Aguardando etapa"}
                      </span>
                    </span>
                    <span className="cmuted csince">{aberto ? hhmm(aberto.iniciadoEm) : "—"}</span>
                    <span className="cmuted cdur">{aberto ? duracao(aberto.iniciadoEm, null) : "—"}</span>
                  </div>
                );
              })}
              {linhasNaPos.length === 0 && (
                <div className="empty">Nenhuma carga vinculada a OS em {label}.</div>
              )}
            </div>

            <div className="pager">
              <button className="pgb" disabled={pag <= 0} onClick={() => setPagina((p) => Math.max(0, p - 1))}>
                ← Anterior
              </button>
              <span className="cmuted">
                Página {pag + 1} de {totalPaginas} · {linhasNaPos.length} carga(s) em OS
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
          </div>
        </div>

        <div className="visbar">
          <button className="visb" onClick={() => setModal({ tipo: "buscar" })}>
            <IconBusca />
            <span>Buscar OS</span>
          </button>
          <button className="visb" onClick={() => setModal({ tipo: "processos" })}>
            <IconProcessos />
            <span>Processos</span>
          </button>
          <button className="visb" onClick={() => setModal({ tipo: "livres" })}>
            <IconCargas />
            <span>Cargas livres</span>
            <b>{livres.length}</b>
          </button>
        </div>
      </div>

      {modal?.tipo === "cad" && <CriarOSModal ctx={ctx} />}
      {modal?.tipo === "passoLote" && <PassoLoteModal ctx={ctx} selecao={sel} />}
      {modal?.tipo === "encerrarLote" && <EncerrarLoteModal ctx={ctx} selecao={sel} />}
      {modal?.tipo === "inspecao" && <InspecaoModal ctx={ctx} />}
      {modal?.tipo === "buscar" && <BuscarOSModal ctx={ctx} />}
      {modal?.tipo === "processos" && <ProcessosModal ctx={ctx} />}
      {modal?.tipo === "livres" && <CargasLivresModal ctx={ctx} />}
      {modal?.tipo === "det" && <DetalheOSModal ctx={ctx} osId={modal.osId} />}
      {modal?.tipo === "vinc" && <VincularModal ctx={ctx} osId={modal.osId} />}
      {modal?.tipo === "passo" && <PassoModal ctx={ctx} osId={modal.osId} />}
      {modal?.tipo === "exp" && <ExpedirModal ctx={ctx} osId={modal.osId} />}
      {modal?.tipo === "cancel" && <CancelarModal ctx={ctx} osId={modal.osId} />}
    </div>
  );
}

/**
 * Ordenação no telemóvel em pé, onde `.clhead` não existe: ali a linha vira um
 * cartão de duas linhas e os rótulos de coluna não alinhariam com nada. Mesma
 * regra do cabeçalho — tocar na coluna activa inverte, tocar noutra troca —
 * porque as duas passam pelo mesmo `ordenarPor`.
 */
function OrdenarMenu({
  ord, ordenarPor,
}: {
  ord: Ord;
  ordenarPor: (chave: ChaveOrd) => void;
}) {
  const [aberto, setAberto] = useState(false);
  const atual = COLUNAS.find((c) => c.chave === ord.chave) ?? COLUNAS[0];
  const seta = ord.asc ? "↑" : "↓";

  return (
    <div className="ordwrap">
      <button
        type="button"
        className="ordb"
        aria-expanded={aberto}
        onClick={() => setAberto((v) => !v)}
      >
        ⇅ {atual.label} {seta}
      </button>
      {aberto && (
        <>
          {/* Fecha ao tocar fora. Um backdrop, e não um listener no document,
              porque o listener corre o risco de disparar no mesmo toque que já
              vai marcar/desmarcar a carga da linha por baixo. */}
          <div className="ordbd" onClick={() => setAberto(false)} />
          <div className="ordmenu bp">
            <Corners />
            {COLUNAS.map((c) => (
              <button
                key={c.chave}
                type="button"
                className={`ordi ${ord.chave === c.chave ? "on" : ""}`}
                onClick={() => {
                  ordenarPor(c.chave);
                  setAberto(false);
                }}
              >
                <span>{c.label}</span>
                {ord.chave === c.chave && <i className="ordseta">{seta}</i>}
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  );
}
