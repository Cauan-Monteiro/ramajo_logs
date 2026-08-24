import type { OperadorDTO, Posicao } from "../api/types";
import { tabStyle } from "../domain/derive";
import { POSICOES, iniciais } from "../domain/format";
import { IconDesktop, IconLogo, IconMobile } from "./Icons";

export type Aba = Posicao | "cargas" | "rel";

export function AppNav({
  aba, onAba, operador, isAdmin, onSair, isMobile, onAlternarModo,
}: {
  aba: Aba;
  onAba: (a: Aba) => void;
  operador: OperadorDTO;
  isAdmin: boolean;
  onSair: () => void;
  isMobile: boolean;
  onAlternarModo: () => void;
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
        <button className="tabbtn" style={tabStyle(aba === "cargas")} onClick={() => onAba("cargas")}>
          Registrar cargas
        </button>
        {isAdmin && (
          <button className="tabbtn" style={tabStyle(aba === "rel")} onClick={() => onAba("rel")}>
            Relatórios
          </button>
        )}
      </div>
      <div className="opchip">
        <span className="opav">{iniciais(operador.nome)}</span>
        <div className="opinfo" style={{ lineHeight: 1.1 }}>
          <div style={{ font: "600 15px 'Barlow Condensed'" }}>{operador.nome}</div>
          <span className="role-t">{operador.permissao}</span>
        </div>
        <button
          className="btn2 modo-b"
          title={
            isMobile
              ? "Voltar à visualização padrão (1180 × 820)"
              : "Ver na proporção de celular (360 × 780, Galaxy S24)"
          }
          aria-label={isMobile ? "Visualização desktop" : "Visualização mobile"}
          onClick={onAlternarModo}
        >
          {isMobile ? <IconDesktop /> : <IconMobile />}
        </button>
        <button className="btn2 sair-b" onClick={onSair}>
          Sair
        </button>
      </div>
    </div>
  );
}
