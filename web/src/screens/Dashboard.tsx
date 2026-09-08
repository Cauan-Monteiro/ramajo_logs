import { useMemo, useState } from "react";
import type {
  CargaDTO, LogDTO, OperadorDTO, OrdemResumoDTO, Posicao, ProcessoDTO,
} from "../api/types";
import { Corners } from "../components/Blueprint";
import { OrdenarMenu, useOrdenacao, type ColunaOrd } from "../components/Ordenar";
import {
  IconCargas, IconCheck, IconCheckBox, IconBusca, IconInspecao, IconLogo,
  IconPasso, IconPlus, IconProcessos,
} from "../components/Icons";
import {
  emSegundoLote, etapaDoLog, etapaStyle, labelEtapaDoLog, logAbertoDaCarga,
} from "../domain/derive";
import {
  COR_INATIVO, COR_NIVEL, ROTULO_NIVEL, detalhe as detalheDesidro, emCurso, piorNivel,
} from "../domain/desidro";
import { ETAPAS, duracao, hhmm, osNum, posLabel } from "../domain/format";
import { useAgora } from "../state/useAgora";
import { cargasLivres, logsDe, type AppData } from "../state/useAppData";
import { BuscarOSModal } from "../modals/BuscarOS";
import { CargasLivresModal } from "../modals/CargasLivres";
import { CriarOSModal } from "../modals/CriarOS";
import { DesidroPainelModal } from "../modals/DesidroPainel";
import { DesidrogenizarModal } from "../modals/Desidrogenizacao";
import {
  CancelarModal, DetalheOSModal, ExpedirModal, PassoModal, VincularModal,
} from "../modals/DetalheOS";
import { EncerrarLoteModal } from "../modals/EncerrarLote";
import { ExpedirParcialModal } from "../modals/ExpedirParcial";
import { InspecaoModal } from "../modals/Inspecao";
import { PassoLoteModal } from "../modals/PassoLote";
import { ProcessosModal } from "../modals/Processos";
import type { Ctx, ModalState } from "../modals/tipos";

/* Em ecrã de telemóvel a linha da carga ocupa duas alturas e o polegar rola
   muito mais: menos linhas por página cansa menos do que uma lista infinita. */
const POR_PAGINA = 30;
const POR_PAGINA_MOBILE = 12;

/** Uma linha da tabela com os cruzamentos já feitos. Ordenar por "Vínculo" ou
    "Etapa" exige esses valores ANTES do sort — não dá para descobri-los dentro
    do map, como era antes. */
type Linha = { carga: CargaDTO; ordem?: OrdemResumoDTO; aberto?: LogDTO };

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

/**
 * As colunas ordenáveis, na mesma ordem em que aparecem na linha. O cabeçalho
 * do desktop e o menu do telemóvel leem os dois daqui: rótulo e direção natural
 * vivem num sítio só.
 *
 * `valor` devolve `null` quando a linha não tem o dado — carga sem etapa aberta,
 * OS que não veio no carregamento. Essas caem sempre para o fim, nos dois
 * sentidos: um "—" no topo da lista não informa nada.
 */
function colunasDe(processos: ProcessoDTO[]): ColunaOrd<Linha>[] {
  return [
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
      valor: (l) => {
        if (!l.aberto) return null;
        const e = etapaDoLog(l.aberto, processos);
        const i = e ? ETAPAS.findIndex((x) => x.key === e) : ETAPAS.length;
        return `${i}${l.aberto.processoDescricao}`;
      },
    },
    {
      // A ordem com que a lista abre: o que acabou de entrar em etapa está no
      // topo. Cargas ainda sem etapa caem para o fim, pela regra dos nulos.
      chave: "desde", label: "Desde", ascPadrao: false,  // mais recente primeiro
      valor: (l) => msDe(l.aberto),
    },
    {
      // Toda etapa aberta tem `finalizadoEm: null`, logo a duração é só
      // `agora - iniciadoEm`: maior duração ⇔ início mais antigo. Daí o sinal —
      // no valor esta coluna é o espelho de "Desde". No padrão não: "Desde" abre
      // no mais recente e esta no que está parado há mais tempo, que é o que se
      // procura quando se clica em "Duração".
      chave: "duracao", label: "Duração", ascPadrao: false,  // maior primeiro
      valor: (l) => {
        const ms = msDe(l.aberto);
        return ms === null ? null : -ms;
      },
    },
  ];
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

  const colunas = useMemo(() => colunasDe(data.processos), [data.processos]);
  /** Reordenar volta à página 1: manter a página 3 depois de reordenar
      mostraria um pedaço arbitrário de uma lista nova. */
  const { ord, ordenarPor, ordenar } = useOrdenacao(
    colunas, { chave: "desde", asc: false }, () => setPagina(0),
  );

  // O único valor da tela que muda sozinho com o tempo é o progresso das
  // desidrogenizações; o resto chega pelo useSync. Meio minuto é fino de mais
  // para se notar o salto numa janela medida em horas.
  const agora = useAgora(30000);

  /**
   * Desidrogenizações que ainda importam. Deliberadamente SEM filtro por
   * posição, ao contrário dos vizinhos: o forno é um só para a fábrica, então
   * uma desidro prestes a estourar precisa aparecer em qualquer aba.
   */
  const desidros = emCurso(data.desidrosEmAndamento, agora);
  const nivelDesidro = piorNivel(desidros, agora);
  // Forno livre não é "tranquilo", é ausência de estado: sai da escala de cores
  // para o verde não competir com os avisos verdadeiros.
  const temDesidro = desidros.length > 0;
  const corDesidro = temDesidro ? COR_NIVEL[nivelDesidro] : COR_INATIVO;

  const label = posLabel(posicao);
  const naPos = data.ordens.filter((o) => o.emProcesso && o.posicao === posicao);
  const emProducao = naPos.filter((o) => !emSegundoLote(o));
  const emLote = naPos.filter(emSegundoLote);
  const livres = cargasLivres(data, posicao);

  /** Cargas desta posição já vinculadas a alguma OS — as linhas da tabela,
      com OS e etapa aberta já cruzadas e na ordem pedida pelo cabeçalho. */
  const linhasNaPos = useMemo(
    () =>
      ordenar(
        data.cargas
          .filter((c) => c.posicao === posicao && c.ordemAtualId !== null)
          .map<Linha>((carga) => {
            const ordem = data.ordens.find((o) => o.id === carga.ordemAtualId);
            return {
              carga,
              ordem,
              aberto: ordem ? logAbertoDaCarga(carga.nome, logsDe(data, ordem.id)) : undefined,
            };
          }),
        porNome,
      ),
    [data, posicao, ordenar],
  );

  const porPagina = isMobile ? POR_PAGINA_MOBILE : POR_PAGINA;
  const totalPaginas = Math.max(1, Math.ceil(linhasNaPos.length / porPagina));
  const pag = Math.min(pagina, totalPaginas - 1);
  const linhas = linhasNaPos.slice(pag * porPagina, pag * porPagina + porPagina);

  const selSet = new Set(sel);
  const todasSelecionadas =
    linhasNaPos.length > 0 && linhasNaPos.every((l) => selSet.has(l.carga.nome));

  /** Mesma conta do modal de inspeção final — e, como lá, só desta posição. */
  const semCargasCount = naPos.filter(
    (o) => !data.cargas.some((c) => c.ordemAtualId === o.id),
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

  return (
    <div className="dash-root">
      <div className="posbar">
        <div className="posbar-id">
          <IconLogo size={40} width={1.4} />
          <div style={{ lineHeight: 1 }}>
            <div className="plbl">Posição em operação</div>
            <div className="pname">{label}</div>
          </div>
        </div>

        {/* Ao centro e sempre presente, ao contrário dos stats à direita, que são
            desta posição: o forno é um só para a fábrica, e um lugar fixo é o que
            se aprende de uma vez. Clicar abre o painel com o detalhe que antes só
            existia no `title` — que some ao mover o rato e não existe em toque. */}
        <button
          type="button"
          className="posmini desidro-ind"
          title={
            temDesidro
              ? detalheDesidro(desidros, agora)
              : "Nenhuma desidrogenização em curso."
          }
          onClick={() => setModal({ tipo: "desidroPainel" })}
        >
          <span className="n" style={{ color: corDesidro }}>
            {/* .turn-dot é o dot cru do app — a cor sempre vem inline. */}
            <i
              className="turn-dot"
              style={{
                background: corDesidro,
                display: "inline-block",
                // Sem isto o dot assenta na linha de base e fica baixo
                // demais ao lado de um número de 30px.
                verticalAlign: "middle",
                marginRight: 8,
                // Pulsa só no crítico, reaproveitando o @keyframes do
                // .live-dot: piscar quando está tudo calmo é ruído.
                animation:
                  nivelDesidro === "critico" && temDesidro
                    ? "pulse 1.4s infinite"
                    : undefined,
              }}
            />
            {desidros.length}
          </span>
          {/* O nível vai escrito, não só na cor: a barra é estreita nos
              breakpoints menores, mas quem não distingue verde de vermelho
              também precisa ler o estado. */}
          <span className="t" style={{ whiteSpace: "nowrap" }}>
            Desidro · {temDesidro ? ROTULO_NIVEL[nivelDesidro] : "forno livre"}
          </span>
        </button>

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
            {/* No desktop o próprio `.clhead` ordena; aqui ele está escondido. */}
            {isMobile && <OrdenarMenu colunas={colunas} ord={ord} ordenarPor={ordenarPor} />}
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
                    <span className="cvinc">
                      {ordem ? `OS ${osNum(ordem)}` : "—"}
                      {/* A carga leva peças de outras OS neste passo: o
                          vínculo mostra só a titular, o selo conta o resto. */}
                      {aberto && aberto.ordensAcopladas.length > 0 && (
                        <span
                          className="tp"
                          title={`Etapa acoplada: mais ${aberto.ordensAcopladas.length} OS nesta carga`}
                        >
                          +{aberto.ordensAcopladas.length}
                        </span>
                      )}
                    </span>
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
      {modal?.tipo === "expParcial" && <ExpedirParcialModal ctx={ctx} osId={modal.osId} />}
      {modal?.tipo === "desidro" && <DesidrogenizarModal ctx={ctx} osId={modal.osId} />}
      {modal?.tipo === "desidroPainel" && <DesidroPainelModal ctx={ctx} />}
      {modal?.tipo === "cancel" && <CancelarModal ctx={ctx} osId={modal.osId} />}
    </div>
  );
}

