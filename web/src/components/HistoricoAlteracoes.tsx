import type { CampoAlterado, OrdemAlteracaoDTO, Posicao } from "../api/types";
import { diaHora, posLabel } from "../domain/format";

const CAMPOS: Record<CampoAlterado, string> = {
  ID_EXTERNO: "Nº da OS",
  CLIENTE: "Cliente",
  POSICAO: "Posição",
  CARGAS: "Cargas",
};

/** A posição é gravada crua (OXIDACAO); o resto já vem como texto de tela. */
function valor(campo: CampoAlterado, v: string | null): string {
  if (v === null) return campo === "CARGAS" ? "nenhuma" : "—";
  return campo === "POSICAO" ? posLabel(v as Posicao) : v;
}

/**
 * O histórico de correções do ADMIN, uma entrada por correção.
 *
 * A API devolve uma linha por CAMPO; as de uma mesma correção partilham
 * instante, autor e motivo, e é por eles que se reagrupam aqui — ler "Nº" e
 * "Cliente" como duas correções separadas faria parecer que houve dois
 * enganos.
 */
export function HistoricoAlteracoes({ alteracoes }: { alteracoes: OrdemAlteracaoDTO[] }) {
  const grupos: OrdemAlteracaoDTO[][] = [];
  for (const a of alteracoes) {
    const ultimo = grupos[grupos.length - 1];
    const par = ultimo?.[0];
    if (par && par.alteradaEm === a.alteradaEm && par.alteradaPorNome === a.alteradaPorNome
        && par.motivo === a.motivo) {
      ultimo.push(a);
    } else {
      grupos.push([a]);
    }
  }

  return (
    <div>
      {grupos.map((g) => (
        <div key={g[0].id} className="tline">
          <div style={{ flex: 1 }}>
            <div style={{ font: "600 16px 'Barlow Condensed'" }}>“{g[0].motivo}”</div>
            {g.map((a) => (
              <div key={a.id} className="os-tv" style={{ fontSize: 14 }}>
                {CAMPOS[a.campo]}: {valor(a.campo, a.valorAnterior)}
                {" → "}
                <b style={{ color: "#1d1f20", fontWeight: 600 }}>{valor(a.campo, a.valorNovo)}</b>
              </div>
            ))}
          </div>
          <span className="time" style={{ fontSize: 14 }}>
            {diaHora(g[0].alteradaEm)}
            <br />
            {g[0].alteradaPorNome}
          </span>
        </div>
      ))}
    </div>
  );
}
