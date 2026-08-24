import { useEffect, useState } from "react";
import * as api from "../api/endpoints";
import type { OperadorDTO } from "../api/types";
import { Bp } from "../components/Blueprint";
import { IconLogo, IconScan } from "../components/Icons";
import { SEL_PICK } from "../domain/derive";
import { iniciais } from "../domain/format";

/**
 * Início de turno. A API não tem autenticação: identificar-se é escolher o
 * operador (ou encostar o crachá), e é esse id que assina todas as ações.
 */
export function Login({
  onEntrar, onErro,
}: {
  onEntrar: (op: OperadorDTO) => void;
  onErro: (e: unknown) => void;
}) {
  const [operadores, setOperadores] = useState<OperadorDTO[]>([]);
  const [sel, setSel] = useState<number | null>(null);
  /** `null` = campo do crachá fechado; string = aberto, com o que já foi lido. */
  const [cracha, setCracha] = useState<string | null>(null);

  useEffect(() => {
    api.listarOperadores()
      .then((lista) => setOperadores(lista.filter((o) => o.ativo)))
      .catch(onErro);
  }, [onErro]);

  /**
   * O crachá entra por um campo desta página, e não por `window.prompt`: num
   * tablet o prompt é uma caixa do sistema que tapa o ecrã e bloqueia tudo,
   * e o leitor RFID escreve como um teclado — acaba a leitura com Enter, que
   * aqui submete o formulário directamente.
   */
  async function lerCracha(bruto: string) {
    const tag = bruto.trim();
    if (!tag) return;
    try {
      const op = await api.operadorPorTag(tag);
      if (!op) {
        onErro(new Error(`Nenhum operador com a tag "${tag}".`));
        return;
      }
      if (!op.ativo) {
        onErro(new Error(`O operador ${op.nome} está inativo.`));
        return;
      }
      setCracha(null);
      onEntrar(op);
    } catch (e) {
      onErro(e);
    }
  }

  const escolhido = operadores.find((o) => o.id === sel);

  return (
    <div className="tab" style={{ justifyContent: "center", alignItems: "center", background: "#1d2d3d" }}>
      <Bp className="login-card" style={{ background: "#f2f2f3" }}>
        <div style={{ display: "flex", alignItems: "center", gap: 11, marginBottom: 6 }}>
          <IconLogo size={26} color="#5980a6" />
          <span style={{ font: "600 24px 'Barlow Condensed'", letterSpacing: ".02em" }}>
            RAMAJO · TRATAMENTO
          </span>
        </div>
        <div className="os-tv" style={{ marginBottom: 24, fontSize: 15 }}>
          Início de turno — identifique-se para operar.
        </div>

        {cracha === null ? (
          <button
            className="scan-b"
            style={{ width: "100%", justifyContent: "center", height: 52, fontSize: 15, marginBottom: 20 }}
            onClick={() => setCracha("")}
          >
            <IconScan size={20} />
            Ler crachá RFID
          </button>
        ) : (
          <form
            style={{ marginBottom: 20 }}
            onSubmit={(e) => {
              e.preventDefault();
              void lerCracha(cracha);
            }}
          >
            <span className="lbl">Encoste o crachá ou digite a tag</span>
            <input
              className="inp"
              autoFocus
              value={cracha}
              placeholder="Tag RFID"
              onChange={(e) => setCracha(e.target.value)}
              onKeyDown={(e) => e.key === "Escape" && setCracha(null)}
            />
            <div style={{ display: "flex", gap: 9, marginTop: 10 }}>
              <button type="button" className="btn2" style={{ flex: 1 }} onClick={() => setCracha(null)}>
                Cancelar
              </button>
              <button type="submit" className="btn2 btn2-p" style={{ flex: 1 }} disabled={!cracha.trim()}>
                Entrar
              </button>
            </div>
          </form>
        )}

        <span className="lbl">ou selecione o operador</span>
        <div style={{ display: "flex", flexDirection: "column", gap: 9, maxHeight: 260, overflow: "auto" }}>
          {operadores.map((o) => (
            <button
              key={o.id}
              className="pick"
              style={sel === o.id ? SEL_PICK : undefined}
              onClick={() => setSel(o.id)}
            >
              <span className="opav" style={{ borderColor: "rgba(89,128,166,.5)" }}>
                {iniciais(o.nome)}
              </span>
              <span style={{ flex: 1, fontWeight: 600 }}>{o.nome}</span>
              <span className="role-t" style={{ borderColor: "rgba(89,128,166,.5)", color: "#416180" }}>
                {o.permissao}
              </span>
            </button>
          ))}
          {operadores.length === 0 && (
            <span className="os-tv">Nenhum operador ativo cadastrado na API.</span>
          )}
        </div>

        <button
          className="big-cta"
          style={{ width: "100%", height: 56, fontSize: 22, marginTop: 22 }}
          disabled={!escolhido}
          onClick={() => escolhido && onEntrar(escolhido)}
        >
          Entrar no turno →
        </button>
      </Bp>
    </div>
  );
}
