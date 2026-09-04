import type { ReactNode } from "react";
import { dataLonga, deslocar, iso } from "../domain/format";

/**
 * A barra de controlo de uma tela por dia: recuar, o dia por extenso, avançar e
 * o atalho para hoje. Avançar e "Hoje" ficam mortos no dia corrente — não há
 * histórico do futuro. O `children` entra entre as setas e a pílula, para quem
 * precise de mais filtros (a Visão Geral põe lá o segmentado de posição).
 */
export function BarraDia({
  dia, onDia, agora, carregando, children,
}: {
  dia: string;
  onDia: (d: string) => void;
  agora: number;
  carregando: boolean;
  children?: ReactNode;
}) {
  const hoje = iso(new Date(agora));
  const ehHoje = dia === hoje;

  return (
    <div className="aud-bar">
      <button className="btn2" onClick={() => onDia(deslocar(dia, -1))}>◀</button>
      <span className="aud-data">{dataLonga(dia)}</span>
      <button className="btn2" disabled={ehHoje} onClick={() => onDia(deslocar(dia, 1))}>
        ▶
      </button>
      <button className="btn2" disabled={ehHoje} onClick={() => onDia(hoje)}>Hoje</button>

      {children}

      {ehHoje && (
        <span className="live-pill" style={{ marginLeft: "auto" }}>
          <i className="live-dot" /> ao vivo
        </span>
      )}
      {carregando && <span className="spin" style={{ marginLeft: ehHoje ? 0 : "auto" }} />}
    </div>
  );
}
