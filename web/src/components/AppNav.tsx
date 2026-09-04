import type { OperadorDTO, Posicao } from "../api/types";
import { tabStyle } from "../domain/derive";
import { POSICOES, iniciais } from "../domain/format";
import { IconLogo } from "./Icons";

export type Aba = Posicao | "geral" | "rel" | "config";

export function AppNav({
  aba, onAba, operador, isAdmin, onSair,
}: {
  aba: Aba;
  onAba: (a: Aba) => void;
  operador: OperadorDTO;
  isAdmin: boolean;
  onSair: () => void;
}) {
  return (
    <div className="appnav">
      <span className="brand"><IconLogo />RAMAJO</span>
      <div className="navdiv" />
      {/* As abas ficam num contentor próprio para que em mobile virem uma
          faixa rolável na segunda linha da barra. */}
      <div className="navtabs">
        {POSICOES.map((p) => (
          <button key={p.key} className="tabbtn" style={tabStyle(aba === p.key)} onClick={() => onAba(p.key)}>
            {p.label}
          </button>
        ))}
        <div className="navdiv" />
        <button className="tabbtn" style={tabStyle(aba === "geral")} onClick={() => onAba("geral")}>
          Visão Geral
        </button>
        {isAdmin && (
          <>
            <button className="tabbtn" style={tabStyle(aba === "rel")} onClick={() => onAba("rel")}>
              Relatórios
            </button>
            <button
              className="tabbtn"
              style={tabStyle(aba === "config")}
              onClick={() => onAba("config")}
            >
              Ajustes
            </button>
          </>
        )}
      </div>
      <div className="opchip">
        <span className="opav">{iniciais(operador.nome)}</span>
        <div className="opinfo" style={{ lineHeight: 1.1 }}>
          <div style={{ font: "600 15px 'Barlow Condensed'" }}>{operador.nome}</div>
          <span className="role-t">{operador.permissao}</span>
        </div>
        <button className="btn2 sair-b" onClick={onSair}>
          Sair
        </button>
      </div>
    </div>
  );
}
