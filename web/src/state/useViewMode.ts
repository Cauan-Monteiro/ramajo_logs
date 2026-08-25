import { useEffect, useState } from "react";

export type ViewMode = "desktop" | "mobile";

/** Abaixo disto o quadro de 1180px não cabe — um Galaxy S24 reporta 360px. */
const MQ = "(max-width: 480px)";

/**
 * Modo de visualização, decidido só pela largura da tela. A media query mora
 * aqui, no JS, e não no CSS, para que o CSS tenha um caminho único —
 * `body.is-mobile` — em vez de duplicar cada regra.
 */
export function useViewMode() {
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

  const modo: ViewMode = estreito ? "mobile" : "desktop";

  // A classe vai no body (e não no .tab) porque o próprio body muda: o
  // padding de 26px que centraliza o quadro causaria transbordo em 360px.
  useEffect(() => {
    document.body.classList.toggle("is-mobile", modo === "mobile");
    return () => document.body.classList.remove("is-mobile");
  }, [modo]);

  return { modo, isMobile: modo === "mobile" };
}
