import type { AvaliacaoInput, ItemAvaliacao } from "../api/types";

/** Os quatro pontos da inspeção, na ordem em que se conferem. */
export const ITENS_AVALIACAO = [
  { key: "visual", label: "Visual" },
  { key: "aderencia", label: "Aderência" },
  { key: "embalagem", label: "Embalagem" },
  { key: "camada", label: "Camada" },
] as const;

export type ChaveItem = (typeof ITENS_AVALIACAO)[number]["key"];

/**
 * Um ponto como a tela o edita. A observação sobrevive a desmarcar: quem tira
 * a marcação por engano e a põe de volta não perde o que escreveu.
 */
type ItemForm = { marcado: boolean; observacao: string };

export type FormAvaliacaoState = {
  itens: Record<ChaveItem, ItemForm>;
  observacao: string;
};

const MAX = 500;

function itemDe(v: ItemAvaliacao | undefined): ItemForm {
  if (v === null || v === undefined) return { marcado: false, observacao: "" };
  return { marcado: true, observacao: v === true ? "" : v };
}

/** O formulário vazio, ou preenchido com uma avaliação já gravada. */
export function formDe(a?: AvaliacaoInput | null): FormAvaliacaoState {
  return {
    itens: {
      visual: itemDe(a?.visual),
      aderencia: itemDe(a?.aderencia),
      embalagem: itemDe(a?.embalagem),
      camada: itemDe(a?.camada),
    },
    observacao: a?.observacao ?? "",
  };
}

/** O que a API recebe: desmarcado → null, marcado → true ou a observação. */
export function paraInput(f: FormAvaliacaoState): AvaliacaoInput {
  const valor = (i: ItemForm): ItemAvaliacao =>
    !i.marcado ? null : i.observacao.trim() || true;
  const obs = f.observacao.trim();
  return {
    visual: valor(f.itens.visual),
    aderencia: valor(f.itens.aderencia),
    embalagem: valor(f.itens.embalagem),
    camada: valor(f.itens.camada),
    observacao: obs || null,
  };
}

/** Texto curto de um ponto gravado, para leitura. */
export function situacaoItem(v: ItemAvaliacao): string {
  return v === null ? "Não avaliado" : "Avaliado";
}

/** Quantos pontos foram avaliados, e quantos deles têm observação. */
export function contarAvaliados(a: AvaliacaoInput): { avaliados: number; observacoes: number } {
  const valores = ITENS_AVALIACAO.map(({ key }) => a[key]);
  return {
    avaliados: valores.filter((v) => v !== null).length,
    observacoes: valores.filter((v) => typeof v === "string").length,
  };
}

/**
 * Formulário controlado da avaliação da inspeção final. Serve à expedição
 * (Inspeção final → Expedir) e ao Ajustes, onde o ADMIN avalia depois.
 *
 * Cada ponto é uma marcação: marcado = avaliado, e só então abre a observação
 * própria dele, opcional. Não há "concluído" a marcar — salvar é concluir.
 */
export function FormAvaliacao({
  form, onForm,
}: {
  form: FormAvaliacaoState;
  onForm: (f: FormAvaliacaoState) => void;
}) {
  const setItem = (key: ChaveItem, item: ItemForm) =>
    onForm({ ...form, itens: { ...form.itens, [key]: item } });

  return (
    <div>
      <span className="lbl">Pontos avaliados</span>
      {ITENS_AVALIACAO.map(({ key, label }) => {
        const item = form.itens[key];
        return (
          <div key={key} style={{ marginBottom: 12 }}>
            <label
              style={{
                display: "flex", alignItems: "center", gap: 10,
                font: "600 17px 'Barlow Condensed'", cursor: "pointer",
              }}
            >
              <input
                type="checkbox"
                checked={item.marcado}
                onChange={(e) => setItem(key, { ...item, marcado: e.target.checked })}
                style={{ width: 20, height: 20 }}
              />
              {label}
            </label>
            {item.marcado && (
              <input
                className="inp"
                style={{ marginTop: 6, marginLeft: 30, width: "calc(100% - 30px)" }}
                maxLength={MAX}
                placeholder={`Observação sobre ${label.toLowerCase()} (opcional)`}
                aria-label={`Observação sobre ${label.toLowerCase()}`}
                value={item.observacao}
                onChange={(e) => setItem(key, { ...item, observacao: e.target.value })}
              />
            )}
          </div>
        );
      })}

      <span className="lbl" style={{ marginTop: 6 }}>Observação geral (opcional)</span>
      <textarea
        className="inp"
        rows={3}
        maxLength={MAX}
        value={form.observacao}
        onChange={(e) => onForm({ ...form, observacao: e.target.value })}
        style={{ resize: "vertical" }}
      />
    </div>
  );
}
