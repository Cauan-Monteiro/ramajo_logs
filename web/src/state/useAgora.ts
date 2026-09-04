import { useEffect, useState } from "react";

/**
 * Relógio de parede da tela. A régua do "agora" e a ponta das etapas abertas
 * andam sozinhas; meio minuto é fino de mais para se notar o salto e grosso o
 * suficiente para não repintar a página à toa.
 */
export function useAgora(passoMs: number): number {
  const [agora, setAgora] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setAgora(Date.now()), passoMs);
    return () => clearInterval(t);
  }, [passoMs]);
  return agora;
}
