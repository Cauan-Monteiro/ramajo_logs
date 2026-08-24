import type { ReactNode } from "react";

/**
 * Casca de diálogo do design: cabeçalho escuro com kicker + título, corpo
 * rolável e rodapé de ações. Cada modal fornece corpo e rodapé.
 */
export function Modal({
  kicker, titulo, onClose, children, footer,
}: {
  kicker: string;
  titulo: string;
  onClose: () => void;
  children: ReactNode;
  footer: ReactNode;
}) {
  return (
    <div className="dlg-bd" onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="dlg">
        <div className="dlg-hd">
          <div style={{ lineHeight: 1.15 }}>
            <span style={{
              display: "block", font: "600 12px 'Barlow Condensed'",
              letterSpacing: ".14em", opacity: 0.7,
            }}>
              {kicker}
            </span>
            <h3>{titulo}</h3>
          </div>
          <button
            className="btn2"
            style={{
              marginLeft: "auto", background: "transparent",
              borderColor: "rgba(255,255,255,.3)", color: "#f2f2f3", padding: "8px 14px",
            }}
            onClick={onClose}
          >
            ✕
          </button>
        </div>
        <div className="dlg-bdy">{children}</div>
        <div className="dlg-ft">{footer}</div>
      </div>
    </div>
  );
}

export const Vazio = ({ children }: { children: ReactNode }) => (
  <div className="empty">{children}</div>
);

export const Lbl = ({ children, style }: { children: ReactNode; style?: React.CSSProperties }) => (
  <span className="lbl" style={style}>{children}</span>
);
