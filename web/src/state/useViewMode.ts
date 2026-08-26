import { useEffect, useState } from "react";

export type ViewMode = "desktop" | "mobile";

/**
 * Abaixo disto o quadro de 1180px não cabe e a lista de cargas precisa virar
 * cartão. O corte fica acima da largura dos telemóveis (360-430px) porque um
 * aparelho de 540-640px também lê melhor em cartão do que no layout de mesa
 * espremido. Acima daqui e até 1259px vale o nível de tablet (bloco 6 do CSS).
 */
const MQ = "(max-width: 640px)";

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
