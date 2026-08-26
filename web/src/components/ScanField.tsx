import { useState } from "react";
import { IconScan } from "./Icons";

/**
 * Leitura de etiqueta / crachá RFID.
 *
 * O campo abre na própria página, e não por `window.prompt`: num tablet ou
 * telemóvel o prompt é uma caixa do sistema que tapa o ecrã e bloqueia tudo
 * atrás dela. O leitor RFID escreve como um teclado e termina a leitura com
 * Enter — aqui isso submete o formulário directamente, sem o operador tocar
 * em nada. `Escape` fecha, para quem estiver de rato e teclado.
 */
export function ScanField({
  rotulo, titulo, placeholder, onLer, botaoClass = "scan-b", botaoStyle,
}: {
  /** Texto do botão que abre o campo. */
  rotulo: string;
  /** Rótulo acima do campo, já aberto. */
  titulo: string;
  placeholder: string;
  /** Recebe a tag lida, já sem espaços. Só é chamado com texto não vazio. */
  onLer: (tag: string) => void;
  botaoClass?: string;
  botaoStyle?: React.CSSProperties;
}) {
  /** `null` = fechado; string = aberto, com o que já foi lido. */
  const [tag, setTag] = useState<string | null>(null);

  if (tag === null) {
    return (
      <button className={botaoClass} style={botaoStyle} onClick={() => setTag("")}>
        <IconScan />
        {rotulo}
      </button>
    );
  }

  return (
    <form
      className="scan-form"
      onSubmit={(e) => {
        e.preventDefault();
        const t = tag.trim();
        if (!t) return;
        setTag(null);
        onLer(t);
      }}
    >
      <span className="lbl">{titulo}</span>
      <input
        className="inp"
        autoFocus
        value={tag}
        placeholder={placeholder}
        onChange={(e) => setTag(e.target.value)}
        onKeyDown={(e) => e.key === "Escape" && setTag(null)}
      />
      <div className="scan-acoes">
        <button type="button" className="btn2" onClick={() => setTag(null)}>
          Cancelar
        </button>
        <button type="submit" className="btn2 btn2-p" disabled={!tag.trim()}>
          Confirmar
        </button>
      </div>
    </form>
  );
}
