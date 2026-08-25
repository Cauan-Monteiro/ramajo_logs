import { useEffect, useRef, useState } from "react";
import * as api from "../api/endpoints";

/** Frequência da sonda. Barato o bastante para não pesar, curto o bastante
 *  para que dois terminais na mesma posição nunca discordem por muito tempo. */
const INTERVALO_MS = 4000;

interface Args {
  /** Marca do estado no momento em que os dados atuais foram lidos. */
  marca: string | null;
  recarregar: () => Promise<void>;
  /** Falso na tela de login e antes do primeiro boot: não há o que sincronizar. */
  ativo: boolean;
  ocupado: boolean;
  carregando: boolean;
}

/**
 * Mantém todos os terminais no mesmo ponto: pergunta ao servidor, de poucos em
 * poucos segundos, se o estado mudou, e só então dispara o recarregar() — que é
 * caro (catálogos + ordens + um GET de logs por OS em processo).
 *
 * A comparação é sempre contra a marca lida junto com os dados em uso, então o
 * terminal que fez a mutação já está em dia e não recarrega duas vezes.
 */
export function useSync({ marca, recarregar, ativo, ocupado, carregando }: Args) {
  const [online, setOnline] = useState(true);

  // Refs para que o intervalo não seja recriado a cada render/recarga.
  const atual = useRef({ marca, recarregar, ocupado, carregando });
  atual.current = { marca, recarregar, ocupado, carregando };
  const sondando = useRef(false);

  useEffect(() => {
    if (!ativo) return;
    let vivo = true;

    const checar = async () => {
      const { marca, ocupado, carregando } = atual.current;
      // Sem marca ainda, mutação em voo, recarga em andamento ou aba escondida:
      // sondar agora só competiria com quem já está buscando os mesmos dados.
      if (sondando.current || ocupado || carregando || marca === null) return;
      if (document.hidden) return;

      sondando.current = true;
      try {
        const r = await api.revisaoEstado();
        if (!vivo) return;
        setOnline(true);
        // Relê a marca: ela pode ter mudado enquanto esta sonda estava no ar.
        if (`${r.instancia}:${r.revisao}` !== atual.current.marca) {
          void atual.current.recarregar();
        }
      } catch {
        // Silencioso de propósito: um cabo solto viraria um toast a cada 4s.
        if (vivo) setOnline(false);
      } finally {
        sondando.current = false;
      }
    };

    const id = setInterval(() => void checar(), INTERVALO_MS);
    // Voltar para a aba não espera o próximo tique: o operador olha a tela agora.
    const aoVoltar = () => {
      if (!document.hidden) void checar();
    };
    document.addEventListener("visibilitychange", aoVoltar);
    window.addEventListener("focus", aoVoltar);

    return () => {
      vivo = false;
      clearInterval(id);
      document.removeEventListener("visibilitychange", aoVoltar);
      window.removeEventListener("focus", aoVoltar);
    };
  }, [ativo]);

  return { online };
}
