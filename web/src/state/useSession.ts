import { useCallback, useState } from "react";
import type { OperadorDTO } from "../api/types";

const KEY = "ramajo.operador";

function ler(): OperadorDTO | null {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as OperadorDTO) : null;
  } catch {
    return null;
  }
}

/**
 * Operador do turno. Não há autenticação na API — o "login" do design é a
 * escolha de quem está a operar, e é esse id que vai como operadorId/
 * responsavelId em toda mutação. Fica no localStorage para o turno sobreviver
 * a um refresh do terminal.
 */
export function useSession() {
  const [operador, setOperador] = useState<OperadorDTO | null>(ler);

  const entrar = useCallback((op: OperadorDTO) => {
    try {
      localStorage.setItem(KEY, JSON.stringify(op));
    } catch {
      // modo privado / storage bloqueado: o turno vale só para esta aba
    }
    setOperador(op);
  }, []);

  const sair = useCallback(() => {
    try {
      localStorage.removeItem(KEY);
    } catch {
      // nada a fazer
    }
    setOperador(null);
  }, []);

  return { operador, entrar, sair, isAdmin: operador?.permissao === "ADMIN" };
}
