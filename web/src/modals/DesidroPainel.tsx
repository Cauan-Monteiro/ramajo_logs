import type { DesidroEmAndamentoDTO } from "../api/types";
import { Corners } from "../components/Blueprint";
import { Modal, Vazio } from "../components/Modal";
import {
  COR_NIVEL, JANELA_ESTOURADA_MIN, ROTULO_NIVEL, emCurso, nivel, porUrgencia, progresso,
} from "../domain/desidro";
import { hhmm, minutos, osNum, posLabel } from "../domain/format";
import { useAgora } from "../state/useAgora";
import type { Ctx } from "./tipos";

/**
 * O painel do forno: o que o indicativo da barra resumia num número só.
 *
 * Substitui o `title` nativo que era, até aqui, o único caminho para o detalhe —
 * um tooltip some ao mover o rato, não existe em toque, e de lá não se navega
 * para lado nenhum. Aqui cada linha leva ao Detalhe da OS.
 *
 * Estritamente de leitura: aplicar uma desidrogenização continua a ser gesto da
 * Inspeção final (`{ tipo: "desidro", osId }`), onde está o contexto de quem a
 * aplica. Aqui não há nada que mude o estado da fábrica.
 */
export function DesidroPainelModal({ ctx }: { ctx: Ctx }) {
  // Mesmo passo do resto do app: numa janela medida em horas, meio minuto é fino
  // de mais para se notar o salto, e a barra anda com o modal aberto.
  const agora = useAgora(30000);
  const linhas = porUrgencia(emCurso(ctx.data.desidrosEmAndamento, agora), agora);

  return (
    <Modal
      kicker="FORNO"
      titulo="Desidrogenizações em curso"
      onClose={ctx.fechar}
      footer={<button className="btn2" onClick={ctx.fechar}>Fechar</button>}
    >
      <div style={{ display: "flex", flexDirection: "column", gap: 12, maxHeight: 400, overflow: "auto" }}>
        {linhas.map((d) => (
          <LinhaDesidro
            key={d.id}
            d={d}
            agora={agora}
            abrir={() => ctx.abrir({ tipo: "det", osId: d.ordemServicoId })}
          />
        ))}
        {linhas.length === 0 && <Vazio>Nenhuma desidrogenização em curso.</Vazio>}
      </div>

      <div className="os-tv" style={{ marginTop: 14, lineHeight: 1.7 }}>
        O progresso é medido contra o horário de término gravado no servidor. Uma que
        passou da hora continua listada por {JANELA_ESTOURADA_MIN} minutos, para quem
        saiu da tela ficar a saber que estourou · a temperatura e quem aplicou estão no
        detalhe de cada OS.
      </div>
    </Modal>
  );
}

function LinhaDesidro({
  d, agora, abrir,
}: {
  d: DesidroEmAndamentoDTO;
  agora: number;
  abrir: () => void;
}) {
  const pct = progresso(d, agora);
  const cor = COR_NIVEL[nivel(pct)];
  const estourou = pct >= 1;
  // Restante em minutos: negativo quando já passou, e é isso que vira "há X".
  const restanteMin = (new Date(d.finalizadaEm).getTime() - agora) / 60000;

  return (
    <div className="bp osrow" onClick={abrir} style={{ alignItems: "stretch" }}>
      <Corners />
      <span className="os-num" style={{ minWidth: 74, alignSelf: "center" }}>
        {osNum({ id: d.ordemServicoId, idExterno: d.ordemIdExterno })}
      </span>

      <div style={{ flex: 1, minWidth: 0 }}>
        <div className="os-cli">{d.nome}</div>
        <div className="os-tv">
          {posLabel(d.posicao)} · {hhmm(d.iniciadaEm)} → {hhmm(d.finalizadaEm)}
        </div>

        {/* A barra repete em forma o que o número diz em texto: de relance, o
            comprimento responde "quanto falta" sem se ler nada. */}
        <div
          style={{
            marginTop: 8,
            height: 6,
            background: "rgba(29,31,32,.1)",
            overflow: "hidden",
          }}
        >
          <div
            style={{
              // Passar de 100% transbordaria a barra; quem estourou diz-se com
              // a palavra ao lado, não com uma barra maior que o seu leito.
              width: `${Math.min(100, Math.max(0, pct * 100))}%`,
              height: "100%",
              background: cor,
              transition: "width .3s",
            }}
          />
        </div>
      </div>

      <div style={{ flex: "none", textAlign: "right", alignSelf: "center", minWidth: 96 }}>
        <div style={{ font: "600 22px 'Barlow Condensed'", color: cor, lineHeight: 1 }}>
          {estourou ? "ESTOUROU" : `${Math.round(pct * 100)}%`}
        </div>
        <div className="os-tv" style={{ marginTop: 4 }}>
          {estourou
            ? `há ${minutos(-restanteMin)}`
            : `faltam ${minutos(restanteMin)} · ${ROTULO_NIVEL[nivel(pct)]}`}
        </div>
      </div>
    </div>
  );
}
