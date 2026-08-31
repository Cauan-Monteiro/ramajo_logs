import { useMemo, useState, type CSSProperties } from "react";
import { Corners } from "./Blueprint";

/**
 * Ordenação partilhada entre listas. A ideia é sempre a mesma: um array de
 * colunas é a única fonte de verdade — rótulo, direção natural e como extrair
 * o valor da linha — e tanto o cabeçalho da tabela (quando existe) como o menu
 * `⇅` leem daí.
 */

export type ColunaOrd<T> = {
  chave: string;
  label: string;
  /** Direção que a coluna adopta ao ser escolhida pela primeira vez. */
  ascPadrao: boolean;
  /** `null` quando a linha não tem o dado — cai sempre para o fim da lista. */
  valor: (item: T) => string | number | null;
};

export type Ord = { chave: string; asc: boolean };

/** Empate resolvido por um critério estável da lista, para a ordem não tremer
    a cada sync. Cada lista escolhe o seu. */
export type Desempate<T> = (a: T, b: T) => number;

export function compararPor<T>(
  a: T, b: T, col: ColunaOrd<T>, asc: boolean, desempate: Desempate<T>,
): number {
  const va = col.valor(a);
  const vb = col.valor(b);
  if (va === null || vb === null) {
    if (va === vb) return desempate(a, b);
    return va === null ? 1 : -1;  // sem dado vai ao fim nos dois sentidos
  }
  const d =
    typeof va === "number" && typeof vb === "number"
      ? va - vb
      : String(va).localeCompare(String(vb), "pt-BR", { numeric: true });
  return (asc ? d : -d) || desempate(a, b);
}

/**
 * Estado da ordenação + o `sort` já ligado à coluna activa.
 *
 * `aoOrdenar` é o gancho para o que a lista precisa de repor quando a ordem
 * muda — no Dashboard, voltar à página 1; noutras listas, nada.
 */
export function useOrdenacao<T>(
  colunas: ColunaOrd<T>[],
  inicial: Ord,
  aoOrdenar?: () => void,
) {
  const [ord, setOrd] = useState<Ord>(inicial);

  /** Coluna activa outra vez inverte; coluna nova adopta a direção natural
      dela. */
  const ordenarPor = (chave: string) => {
    setOrd((o) =>
      o.chave === chave
        ? { chave, asc: !o.asc }
        : { chave, asc: colunas.find((c) => c.chave === chave)?.ascPadrao ?? true },
    );
    aoOrdenar?.();
  };

  const ordenar = useMemo(() => {
    const col = colunas.find((c) => c.chave === ord.chave) ?? colunas[0];
    return (lista: T[], desempate: Desempate<T>) =>
      lista.sort((a, b) => compararPor(a, b, col, ord.asc, desempate));
  }, [colunas, ord]);

  return { ord, ordenarPor, ordenar };
}

/**
 * Menu `⇅` de ordenação: o caminho para listas onde não há cabeçalho de tabela
 * onde clicar — o telemóvel em pé, onde `.clhead` está escondido, e as listas
 * de cartões dos modais. Mesma regra do cabeçalho — tocar na coluna activa
 * inverte, tocar noutra troca — porque as duas passam pelo mesmo `ordenarPor`.
 */
export function OrdenarMenu<T>({
  colunas, ord, ordenarPor, style,
}: {
  colunas: ColunaOrd<T>[];
  ord: Ord;
  ordenarPor: (chave: string) => void;
  style?: CSSProperties;
}) {
  const [aberto, setAberto] = useState(false);
  const atual = colunas.find((c) => c.chave === ord.chave) ?? colunas[0];
  const seta = ord.asc ? "↑" : "↓";

  if (!atual) return null;

  return (
    <div className="ordwrap" style={style}>
      <button
        type="button"
        className="ordb"
        aria-expanded={aberto}
        onClick={() => setAberto((v) => !v)}
      >
        ⇅ {atual.label} {seta}
      </button>
      {aberto && (
        <>
          {/* Fecha ao tocar fora. Um backdrop, e não um listener no document,
              porque o listener corre o risco de disparar no mesmo toque que já
              vai marcar/desmarcar a carga da linha por baixo. */}
          <div className="ordbd" onClick={() => setAberto(false)} />
          <div className="ordmenu bp">
            <Corners />
            {colunas.map((c) => (
              <button
                key={c.chave}
                type="button"
                className={`ordi ${ord.chave === c.chave ? "on" : ""}`}
                onClick={() => {
                  ordenarPor(c.chave);
                  setAberto(false);
                }}
              >
                <span>{c.label}</span>
                {ord.chave === c.chave && <i className="ordseta">{seta}</i>}
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  );
}
