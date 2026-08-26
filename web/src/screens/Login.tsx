import { useEffect, useState } from "react";
import * as api from "../api/endpoints";
import type { OperadorDTO } from "../api/types";
import { Bp } from "../components/Blueprint";
import { IconLogo } from "../components/Icons";
import { ScanField } from "../components/ScanField";
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

  useEffect(() => {
    api.listarOperadores()
      .then((lista) => setOperadores(lista.filter((o) => o.ativo)))
      .catch(onErro);
  }, [onErro]);

  /** O campo em si é o `ScanField`, partilhado com os outros pontos de leitura. */
  async function lerCracha(tag: string) {
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

        <div className="login-scanwrap">
          <ScanField
            rotulo="Ler crachá RFID"
            titulo="Encoste o crachá ou digite a tag"
            placeholder="Tag RFID"
            onLer={(tag) => void lerCracha(tag)}
            botaoClass="scan-b login-scan"
          />
        </div>

        <span className="lbl">ou selecione o operador</span>
        <div className="login-lista">
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
          className="big-cta login-cta"
          disabled={!escolhido}
          onClick={() => escolhido && onEntrar(escolhido)}
        >
          Entrar no turno →
        </button>
      </Bp>
    </div>
  );
}
