import { useEffect } from "react";

export interface Aviso {
  tipo: "erro" | "ok";
  codigo?: string;
  mensagem: string;
}

/**
 * Fica abaixo da barra de navegação de propósito: sobreposto a ela, bloqueava
 * os cliques nas abas. Confirmações somem sozinhas; erros ficam até o operador
 * fechar, porque costumam pedir uma ação.
 */
export function Toast({ aviso, onClose }: { aviso: Aviso; onClose: () => void }) {
  useEffect(() => {
    if (aviso.tipo !== "ok") return;
    const t = setTimeout(onClose, 4000);
    return () => clearTimeout(t);
  }, [aviso, onClose]);

  return (
    <div className={`toast${aviso.tipo === "ok" ? " ok" : ""}`} role="status">
      {aviso.codigo && <span className="tcode">{aviso.codigo}</span>}
      <span>{aviso.mensagem}</span>
      <button className="tx" onClick={onClose} aria-label="Fechar aviso">
        ✕
      </button>
    </div>
  );
}
