import { useCallback, useEffect, useState } from "react";

export type ViewMode = "desktop" | "mobile";

const KEY = "ramajo.viewmode";
/** Abaixo disto o quadro de 1180px não cabe — um Galaxy S24 reporta 360px. */
const MQ = "(max-width: 480px)";

function lerFixado(): ViewMode | null {
  try {
    const v = localStorage.getItem(KEY);
    return v === "mobile" || v === "desktop" ? v : null;
  } catch {
    return null;
  }
}

/**
 * Modo de visualização. A media query mora aqui, no JS, e não no CSS: como o
 * modo pode vir da largura da tela OU de uma escolha do operador, ter as duas
 * vias em CSS obrigaria a duplicar cada regra. Assim o CSS tem um caminho só —
 * `body.is-mobile`.
 *
 * O padrão continua o desktop de 1180×820; mobile é uma opção. Clicar no botão
 * FIXA a escolha, e a partir daí a largura da tela deixa de mandar.
 */
export function useViewMode() {
  const [fixado, setFixado] = useState<ViewMode | null>(lerFixado);
  const [estreito, setEstreito] = useState(
    () => typeof window !== "undefined" && window.matchMedia(MQ).matches,
  );

  useEffect(() => {
    const mq = window.matchMedia(MQ);
    const onChange = (e: MediaQueryListEvent) => setEstreito(e.matches);
    mq.addEventListener("change", onChange);
    setEstreito(mq.matches);
    return () => mq.removeEventListener("change", onChange);
  }, []);

  const modo: ViewMode = fixado ?? (estreito ? "mobile" : "desktop");

  // A classe vai no body (e não no .tab) porque o próprio body muda: o
  // padding de 26px que centraliza o quadro causaria transbordo em 360px.
  useEffect(() => {
    document.body.classList.toggle("is-mobile", modo === "mobile");
    return () => document.body.classList.remove("is-mobile");
  }, [modo]);

  const alternar = useCallback(() => {
    setFixado((atual) => {
      const efetivo = atual ?? (window.matchMedia(MQ).matches ? "mobile" : "desktop");
      const proximo: ViewMode = efetivo === "mobile" ? "desktop" : "mobile";
      try {
        localStorage.setItem(KEY, proximo);
      } catch {
        // storage bloqueado: a escolha vale só para esta aba
      }
      return proximo;
    });
  }, []);

  return { modo, alternar, isMobile: modo === "mobile" };
}
