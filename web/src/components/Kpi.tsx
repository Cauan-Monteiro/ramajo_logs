import { useEffect, useRef, useState } from "react";
import { Corners } from "./Blueprint";

/** Conta de 0 até ao valor na entrada, e do valor antigo para o novo depois. */
export function useContagem(valor: number): number {
  const [n, setN] = useState(valor);
  const de = useRef(valor);
  useEffect(() => {
    const inicio = de.current;
    const t0 = performance.now();
    let raf = 0;
    const passo = (t: number) => {
      const k = Math.min(1, (t - t0) / 420);
      setN(Math.round(inicio + (valor - inicio) * (1 - (1 - k) * (1 - k))));
      if (k < 1) raf = requestAnimationFrame(passo);
      else de.current = valor;
    };
    raf = requestAnimationFrame(passo);
    return () => cancelAnimationFrame(raf);
  }, [valor]);
  return n;
}

/** Um número grande e o seu rótulo, dentro de um cartão blueprint. */
export function Kpi({ n, label }: { n: number; label: string }) {
  const v = useContagem(n);
  return (
    <div className="bp aud-kpi">
      <Corners />
      <span className="aud-kpi-n">{v}</span>
      <span className="aud-kpi-l">{label}</span>
    </div>
  );
}
