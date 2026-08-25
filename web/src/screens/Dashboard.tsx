import { useMemo, useState } from "react";
import type { OperadorDTO, Posicao } from "../api/types";
import { Corners } from "../components/Blueprint";
import {
  IconCargas, IconCheck, IconCheckBox, IconBusca, IconInspecao, IconLogo,
  IconPasso, IconPlus, IconProcessos,
} from "../components/Icons";
import { emSegundoLote, etapaStyle, labelEtapaDoLog, logAbertoDaCarga } from "../domain/derive";
import { hhmm, osNum, posLabel } from "../domain/format";
import { cargasLivres, logsDe, type AppData } from "../state/useAppData";
import { BuscarOSModal } from "../modals/BuscarOS";
import { CargasLivresModal } from "../modals/CargasLivres";
import { CriarOSModal } from "../modals/CriarOS";
import {
  CancelarModal, DetalheOSModal, ExpedirModal, PassoModal, VincularModal,
} from "../modals/DetalheOS";
import { InspecaoModal } from "../modals/Inspecao";
import { PassoLoteModal } from "../modals/PassoLote";
import { ProcessosModal } from "../modals/Processos";
import { SEM_API, type Ctx, type ModalState } from "../modals/tipos";

const POR_PAGINA = 30;

export function Dashboard({
  data, posicao, operador, isAdmin, agir, ocupado,
}: {
  data: AppData;
  posicao: Posicao;
  operador: OperadorDTO;
  isAdmin: boolean;
  agir: Ctx["agir"];
  ocupado: boolean;
}) {
  const [modal, setModal] = useState<ModalState>(null);
  const [sel, setSel] = useState<string[]>([]);
  const [pagina, setPagina] = useState(0);

  const label = posLabel(posicao);
  const naPos = data.ordens.filter((o) => o.emProcesso && o.posicao === posicao);
  const emProducao = naPos.filter((o) => !emSegundoLote(o));
  const emLote = naPos.filter(emSegundoLote);
  const livres = cargasLivres(data, posicao);

  /** Cargas desta posição já vinculadas a alguma OS — as linhas da tabela. */
  const cargasNaPos = useMemo(
    () =>
      data.cargas
        .filter((c) => c.posicao === posicao && c.ordemAtualId !== null)
        .slice()
        .sort((a, b) => a.nome.localeCompare(b.nome)),
    [data.cargas, posicao],
  );

  const totalPaginas = Math.max(1, Math.ceil(cargasNaPos.length / POR_PAGINA));
  const pag = Math.min(pagina, totalPaginas - 1);
  const linhas = cargasNaPos.slice(pag * POR_PAGINA, pag * POR_PAGINA + POR_PAGINA);

  const selSet = new Set(sel);
  const todasSelecionadas =
    cargasNaPos.length > 0 && cargasNaPos.every((c) => selSet.has(c.nome));

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

  return (
    <div style={{ flex: 1, display: "flex", flexDirection: "column", minHeight: 0 }}>
      <div className="posbar">
        <IconLogo size={40} width={1.4} />
        <div style={{ lineHeight: 1 }}>
          <div className="plbl">Posição em operação</div>
          <div className="pname">{label}</div>
        </div>
        <div style={{ marginLeft: "auto", display: "flex", gap: 30 }}>
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
          <div className="grp-h">
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

            {/* Fecharia os passos abertos E desvincularia as cargas — a segunda
                metade não existe na API, então o botão fica desabilitado em vez
                de fazer só metade do que promete. */}
            <button className="hub bp" disabled title={SEM_API.desvincular}>
              <Corners />
              <IconCheckBox className="hicon" />
              <span className="htitle">Encerrar etapas</span>
              <span className="hsub">Indisponível — pendente de backend</span>
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
          </div>

          <div
            className="bp"
            style={{ flex: 1, display: "flex", flexDirection: "column", minHeight: 0, padding: 0 }}
          >
            <Corners />
            <div className="clhead">
              <span
                className={`ckbox ${todasSelecionadas ? "on" : ""}`}
                onClick={() => setSel(todasSelecionadas ? [] : cargasNaPos.map((c) => c.nome))}
              >
                <IconCheck />
              </span>
              <span>Carga</span>
              <span>Vínculo</span>
              <span>Etapa atual</span>
              <span>Desde</span>
            </div>

            <div style={{ flex: 1, overflow: "auto" }}>
              {linhas.map((c) => {
                const ordem = data.ordens.find((o) => o.id === c.ordemAtualId);
                const aberto = ordem ? logAbertoDaCarga(c.nome, logsDe(data, ordem.id)) : undefined;
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
                            ? etapaStyle(
                              data.processos.find((p) => p.descricao === aberto.processoDescricao)
                                ?.etapa ?? null,
                            )
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
                  </div>
                );
              })}
              {cargasNaPos.length === 0 && (
                <div className="empty">Nenhuma carga vinculada a OS em {label}.</div>
              )}
            </div>

            <div className="pager">
              <button className="pgb" disabled={pag <= 0} onClick={() => setPagina((p) => Math.max(0, p - 1))}>
                ← Anterior
              </button>
              <span className="cmuted">
                Página {pag + 1} de {totalPaginas} · {cargasNaPos.length} carga(s) em OS
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
