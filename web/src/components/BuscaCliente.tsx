import { useMemo, useState } from "react";
import type { ClienteDTO } from "../api/types";
import { SEL_PICK } from "../domain/derive";

/**
 * Escolha de cliente por ID ou nome. Partilhada pela criação da OS e pela
 * correção do ADMIN — as duas escolhem entre os mesmos clientes, da mesma
 * forma.
 *
 * O ranking põe o ID exato primeiro: o operador costuma digitar o código que
 * está no papel da OS, e um "12" não pode ficar atrás de "120" e "312".
 */
export function BuscaCliente({
  clientes, selecionado, onEscolher, maxHeight = 220,
}: {
  clientes: ClienteDTO[];
  selecionado: number | null;
  onEscolher: (id: number) => void;
  maxHeight?: number;
}) {
  const [busca, setBusca] = useState("");

  const lista = useMemo(() => {
    const q = busca.trim().toLowerCase();
    return clientes
      .map((c) => {
        const id = String(c.id);
        const nome = c.nome.toLowerCase();
        let rank = -1;
        if (!q) rank = 2;
        else if (id === q) rank = 0;
        else if (id.startsWith(q)) rank = 1;
        else if (id.includes(q)) rank = 2;
        else if (nome.includes(q)) rank = 3;
        return { c, rank };
      })
      .filter((x) => x.rank >= 0)
      .sort((a, b) => a.rank - b.rank || a.c.id - b.c.id)
      .map((x) => x.c);
  }, [busca, clientes]);

  return (
    <>
      <div style={{ display: "flex", gap: 10, marginBottom: 12 }}>
        <input
          className="inp"
          placeholder="Buscar por ID ou nome..."
          value={busca}
          onChange={(e) => setBusca(e.target.value)}
          style={{ flex: 1 }}
        />
      </div>
      <div style={{ display: "flex", flexDirection: "column", gap: 8, maxHeight, overflow: "auto" }}>
        {lista.map((c) => (
          <button
            key={c.id}
            className="pick"
            style={selecionado === c.id ? SEL_PICK : undefined}
            onClick={() => onEscolher(c.id)}
          >
            <span style={{ font: "600 13px 'Barlow Condensed'", color: "#5980a6", minWidth: 48 }}>
              #{c.id}
            </span>
            {c.nome}
          </button>
        ))}
        {lista.length === 0 && <span className="os-tv">Nenhum cliente encontrado.</span>}
      </div>
    </>
  );
}
