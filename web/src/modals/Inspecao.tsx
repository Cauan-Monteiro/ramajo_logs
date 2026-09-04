import { useMemo } from "react";
import * as api from "../api/endpoints";
import type { OrdemResumoDTO } from "../api/types";
import { Corners } from "../components/Blueprint";
import { Modal, Vazio } from "../components/Modal";
import { OrdenarMenu, useOrdenacao, type ColunaOrd } from "../components/Ordenar";
import { diaHora, osNum, posLabel } from "../domain/format";
import { logsDe } from "../state/useAppData";
import { SEM_API, type Ctx } from "./tipos";

/** Nº da OS como número, não como texto: "#9" antes de "#10", não depois —
    mesma razão da coluna "Vínculo" do painel. */
const numDe = (o: OrdemResumoDTO) => o.idExterno ?? o.id;

const COLUNAS: ColunaOrd<OrdemResumoDTO>[] = [
  { chave: "os", label: "Nº OS", ascPadrao: true, valor: numDe },
  { chave: "cliente", label: "Cliente", ascPadrao: true, valor: (o) => o.clienteNome },
];

/** Desempate pelo nº da OS: dois clientes com o mesmo nome não fazem a lista
    trocar de ordem a cada sync. */
const porNumero = (a: OrdemResumoDTO, b: OrdemResumoDTO) => numDe(a) - numDe(b);

/**
 * Inspeção final: OS abertas que já não têm carga vinculada — só falta
 * expedir. "Expedir" é a expedição total (POST /{id}/finalizar).
 *
 * "Expedir parcial" encerra só o lote corrente e abre o seguinte, deixando a OS
 * aberta à espera de novas cargas — é o único caminho do sistema para uma OS
 * entrar em 2º lote. Sendo irreversível, o botão daqui só abre o diálogo de
 * confirmação (ExpedirParcialModal), que é quem chama a API.
 *
 * Só as OS da posição onde o modal foi aberto: `data.ordens` traz a fábrica
 * inteira, e expedir daqui a OS de outra posição seria um engano irreversível
 * para quem está no terminal.
 */
export function InspecaoModal({ ctx }: { ctx: Ctx }) {
  const { ord, ordenarPor, ordenar } = useOrdenacao(COLUNAS, { chave: "os", asc: true });
  const label = posLabel(ctx.posicao);

  const semCargas = useMemo(
    () =>
      ordenar(
        ctx.data.ordens.filter(
          (o) =>
            o.emProcesso &&
            o.posicao === ctx.posicao &&
            !ctx.data.cargas.some((c) => c.ordemAtualId === o.id),
        ),
        porNumero,
      ),
    [ctx.data, ctx.posicao, ordenar],
  );

  return (
    <Modal
      kicker={`INSPEÇÃO FINAL · ${label.toUpperCase()}`}
      titulo="OS sem cargas vinculadas"
      onClose={ctx.fechar}
      footer={
        <button className="btn2" onClick={ctx.fechar}>
          Fechar
        </button>
      }
    >
      {/* Cartões, não tabela: não há cabeçalho onde clicar, por isso a
          ordenação vive no menu — em qualquer largura de ecrã. */}
      <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
        {/* O `.lbl` traz margem inferior própria — aqui quem espaça é o wrapper,
            senão o rótulo fica desalinhado do botão. */}
        <span className="lbl" style={{ margin: 0 }}>
          Ordens de serviço sem cargas vinculadas em {label} ({semCargas.length})
        </span>
        <OrdenarMenu
          colunas={COLUNAS}
          ord={ord}
          ordenarPor={ordenarPor}
          style={{ marginLeft: "auto" }}
        />
      </div>
      <div style={{ display: "flex", flexDirection: "column", gap: 10, marginTop: 8 }}>
        {semCargas.map((o) => {
          const passos = logsDe(ctx.data, o.id).filter((l) => !l.cancelado).length;
          return (
            <div
              key={o.id}
              className="bp"
              style={{
                padding: "14px 16px",
                display: "flex",
                alignItems: "center",
                gap: "18px 24px",
                flexWrap: "wrap",
              }}
            >
              <Corners />
              <div style={{ minWidth: 96 }}>
                <div className="os-tv">Nº OS</div>
                <div className="os-num" style={{ fontSize: 22 }}>
                  {osNum(o)}
                </div>
              </div>
              {/* Único campo de largura imprevisível: encolhe em vez de empurrar
                  as ações para fora do cartão. */}
              <div style={{ flex: "1 1 160px", minWidth: 0 }}>
                <div className="os-tv">Cliente</div>
                <div
                  className="os-cli"
                  style={{
                    fontSize: 16,
                    overflow: "hidden",
                    textOverflow: "ellipsis",
                    whiteSpace: "nowrap",
                  }}
                  title={o.clienteNome}
                >
                  {o.clienteNome}
                </div>
              </div>
              <div style={{ minWidth: 132 }}>
                <div className="os-tv">Início</div>
                <div className="os-cli" style={{ fontSize: 16 }}>
                  {diaHora(o.iniciadaEm)}
                </div>
              </div>
              <div style={{ minWidth: 80 }}>
                <div className="os-tv">Etapas</div>
                <div className="os-cli" style={{ fontSize: 16 }}>
                  {passos}
                </div>
              </div>
              <div className="insp-acoes">
                <button className="btn2" disabled title={SEM_API.desidrogenizar}>
                  Desidrogenizar
                  <span className="na">Indisponível</span>
                </button>
                <button
                  className="btn2"
                  title="Confirmar o encerramento do lote e, se quiser, já vincular novas cargas."
                  onClick={() => ctx.abrir({ tipo: "expParcial", osId: o.id })}
                >
                  Expedir parcial
                </button>
                <button
                  className="btn2 btn2-x"
                  disabled={ctx.ocupado}
                  onClick={() =>
                    ctx.agir({
                      fazer: () => api.finalizarOrdem(o.id, ctx.operador.id),
                      ok: `OS ${osNum(o)} expedida e encerrada.`,
                    })
                  }
                >
                  Expedir
                </button>
              </div>
            </div>
          );
        })}
        {semCargas.length === 0 && (
          <Vazio>Nenhuma OS aberta sem cargas vinculadas em {label}.</Vazio>
        )}
      </div>
    </Modal>
  );
}
