import { useCallback, useState } from "react";
import type { Aba } from "../components/AppNav";
import { POSICOES } from "../domain/format";

const KEY = "ramajo.aba";

const ABAS_VALIDAS: readonly Aba[] = [
  ...POSICOES.map((p) => p.key),
  "geral",
  "rel",
  "config",
];

const SO_ADMIN: readonly Aba[] = ["rel", "config"];

function ler(): Aba | null {
  try {
    const raw = localStorage.getItem(KEY);
    return raw && ABAS_VALIDAS.includes(raw as Aba) ? (raw as Aba) : null;
  } catch {
    return null;
  }
}

/**
 * Aba activa da barra de navegação. Fica no localStorage para o terminal voltar
 * ao mesmo ponto depois de um refresh, tal como o operador do turno em
 * useSession. Abas de admin só são restauradas para quem tem permissão.
 */
export function useAba(isAdmin: boolean) {
  const [aba, setAbaState] = useState<Aba>(() => {
    const guardada = ler();
    if (!guardada) return "OXIDACAO";
    if (!isAdmin && SO_ADMIN.includes(guardada)) return "OXIDACAO";
    return guardada;
  });

  const setAba = useCallback((a: Aba) => {
    try {
      localStorage.setItem(KEY, a);
    } catch {
      // modo privado / storage bloqueado: a aba vale só para esta sessão
    }
    setAbaState(a);
  }, []);

  const limparAba = useCallback(() => {
    try {
      localStorage.removeItem(KEY);
    } catch {
      // nada a fazer
    }
    setAbaState("OXIDACAO");
  }, []);

  return { aba, setAba, limparAba };
}
