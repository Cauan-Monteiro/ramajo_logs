import { useMemo } from "react";
import type { OrdemResumoDTO } from "../api/types";
import { Corners } from "../components/Blueprint";
import { Modal, Vazio } from "../components/Modal";
import { OrdenarMenu, useOrdenacao, type ColunaOrd } from "../components/Ordenar";
import { cargaCarona, emEspera } from "../domain/derive";
import {
  COR_NIVEL, detalhe, emCurso, nivel, porUrgencia, progresso,
} from "../domain/desidro";
import { diaHora, osNum, posLabel } from "../domain/format";
import { useAgora } from "../state/useAgora";
import { logsDe } from "../state/useAppData";
import type { Ctx } from "./tipos";

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
 * expedir. "Expedir" abre a avaliação (AvaliarExpedicaoModal), que faz a
 * expedição total (POST /{id}/finalizar) com ou sem ela.
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
            !ctx.data.cargas.some((c) => c.ordemAtualId === o.id) &&
            // Carga emprestada conta como carga: se as peças desta OS estão
            // dentro do tanque junto com as de outra ordem, ela não está
            // pronta para expedir — voltará à lista quando for desacoplada,
            // ou quando a carga que a leva for liberada.
            !cargaCarona(ctx.data.cargas, o.id) &&
            // A OS que nasceu sem carga e ainda não produziu nada não está
            // pronta para expedir — está à espera de tanque. Ela aparece no
            // painel, no aviso "sem carga", e é de lá que se acopla.
            !emEspera(o, ctx.data.cargas, logsDe(ctx.data, o.id)),
        ),
        porNumero,
      ),
    [ctx.data, ctx.posicao, ordenar],
  );

  // Mesmo passo e mesma regra de "em curso" do painel do forno: a cor do cartão
  // e a da linha lá nunca discordam, e avança com o modal aberto.
  const agora = useAgora(30000);
  const desidros = emCurso(ctx.data.desidrosEmAndamento, agora);

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
      <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap" }}>
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
          const dsOs = desidros.filter((d) => d.ordemServicoId === o.id);
          // A que ainda roda manda no botão; só sem nenhuma a rodar é que a
          // estourada aparece.
          const rodando = porUrgencia(dsOs.filter((d) => progresso(d, agora) < 1), agora);
          const pctRodando = rodando.length > 0 ? progresso(rodando[0], agora) : null;
          const estourada = pctRodando === null && dsOs.length > 0;
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
                {/* O botão é o indicativo do forno: rodando, mostra a % no fundo
                    da cor do nível; estourada (nos 15 min de `emCurso`), volta ao
                    normal com uma faixa vermelha à esquerda. Inset shadow, não
                    border-left, para não mudar a largura do botão. */}
                <button
                  className="btn2"
                  title={
                    dsOs.length > 0
                      ? detalhe(dsOs, agora)
                      : "Registrar uma desidrogenização nesta OS. O término é preenchido a partir da duração cadastrada."
                  }
                  style={
                    pctRodando !== null
                      ? {
                          background: COR_NIVEL[nivel(pctRodando)],
                          borderColor: COR_NIVEL[nivel(pctRodando)],
                        }
                      : estourada
                        ? { boxShadow: `inset 4px 0 0 ${COR_NIVEL.critico}` }
                        : undefined
                  }
                  onClick={() => ctx.abrir({ tipo: "desidro", osId: o.id })}
                >
                  {pctRodando !== null ? `${Math.round(pctRodando * 100)}%` : "Desidrogenizar"}
                </button>
                <button
                  className="btn2"
                  title="Confirmar o encerramento do lote e, se quiser, já vincular novas cargas."
                  onClick={() => ctx.abrir({ tipo: "expParcial", osId: o.id })}
                >
                  Expedir parcial
                </button>
                {/* Abre a avaliação; é de lá que a expedição total sai — com ou
                    sem avaliar. */}
                <button
                  className="btn2 btn2-x"
                  disabled={ctx.ocupado}
                  onClick={() => ctx.abrir({ tipo: "avaliarExp", osId: o.id })}
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
