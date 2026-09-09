import { useMemo, useState } from "react";
import type { CargaDTO, OrdemResumoDTO, Posicao } from "../api/types";
import { SEL_CHIP } from "../domain/derive";
import { osNum, posLabel } from "../domain/format";
import type { Ctx } from "./tipos";

/** Quantas candidatas listar antes de exigir busca — a fábrica inteira não cabe na tela. */
const MAX_VISIVEIS = 40;

/** O que o operador declara: para cada carga, as OS que pegam carona nela. */
export type Acoplamentos = Record<number, number[]>;

/** Achata o mapa nos pares que a API espera no corpo de POST /api/ordens. */
export const paresDe = (a: Acoplamentos) =>
  Object.entries(a).flatMap(([cargaId, osIds]) =>
    osIds.map((ordemServicoId) => ({ cargaId: Number(cargaId), ordemServicoId })));

/** Quantas OS foram marcadas ao todo — o resumo do botão recolhido. */
export const totalDe = (a: Acoplamentos) =>
  Object.values(a).reduce((n, ids) => n + ids.length, 0);

/**
 * "Quais outras OS vão neste mesmo tanque."
 *
 * Peças de 2-3 ordens entram na MESMA carga e passam juntas pelos processos. O
 * passo é registado UMA vez, na OS dona da carga (a titular), e as demais
 * penduram-se nele — é isso que impede um evento físico de contar como dois ou
 * três na produção.
 *
 * O vínculo é declarado aqui, na CARGA, e não a cada etapa: as peças da carona
 * não saem do tanque quando o passo fecha, então toda etapa seguinte daquela
 * carga já nasce acoplada. Vive fora do modal porque os dois caminhos de
 * vínculo precisam dele — criar OS (`CriarOS.tsx`) e vincular cargas a uma OS
 * já aberta (`DetalheOS.tsx`).
 *
 * Recolhido por omissão: a esmagadora maioria das cargas não acopla, e quem
 * não acopla não deve ver a lista.
 */
export function AcoplarCargas({
  ctx, posicao, cargas, osIdTitular, valor, onChange,
}: {
  ctx: Ctx;
  posicao: Posicao;
  /** As cargas já escolhidas no modal. Só se acopla ao que a OS vai levar. */
  cargas: CargaDTO[];
  /** A OS dona das cargas. Não entra na lista: as peças dela já lá estão. */
  osIdTitular: number | undefined;
  valor: Acoplamentos;
  onChange: (a: Acoplamentos) => void;
}) {
  const [aberto, setAberto] = useState(false);
  const [busca, setBusca] = useState("");
  const [confirmando, setConfirmando] = useState<string | null>(null);

  const total = totalDe(valor);
  // Recolher não pode esconder uma escolha já feita: se há OS marcadas, o
  // resumo continua à vista mesmo fechado.
  const mostrar = aberto || total > 0;

  const titular = ctx.data.ordens.find((o) => o.id === osIdTitular);

  /** As já marcadas neste modal, em qualquer carga — peças estão num tanque só. */
  const marcadasAqui = useMemo(
    () => new Set(Object.values(valor).flat()),
    [valor]);

  /**
   * Candidatas: abertas, no MESMO setor (uma carga está num lugar só), nem a
   * titular nem já caronas de outra carga. A API recusa as quatro coisas de
   * qualquer forma — aqui é só para não oferecer o que vai dar erro.
   */
  const candidatas = useMemo(() => {
    const q = busca.trim().toLowerCase();
    const caronas = new Set(ctx.data.cargas.flatMap((c) => c.ordensAcopladas));
    return ctx.data.ordens
      .filter((o) =>
        o.emProcesso &&
        o.posicao === posicao &&
        o.id !== osIdTitular &&
        !caronas.has(o.id))
      .filter((o) =>
        q === "" ||
        osNum(o).toLowerCase().includes(q) ||
        o.clienteNome.toLowerCase().includes(q))
      .slice(0, MAX_VISIVEIS);
  }, [ctx.data.ordens, ctx.data.cargas, posicao, osIdTitular, busca]);

  function alternar(cargaId: number, osId: number) {
    const atual = valor[cargaId] ?? [];
    const proximo = atual.includes(osId)
      ? atual.filter((x) => x !== osId)
      : [...atual, osId];

    const copia = { ...valor };
    if (proximo.length === 0) delete copia[cargaId];
    else copia[cargaId] = proximo;
    onChange(copia);
  }

  /**
   * Dois toques para marcar, um só para desmarcar. Acoplar encerra as etapas
   * abertas da OS escolhida e isso não se desfaz — desmarcar antes de
   * confirmar o vínculo, sim.
   */
  function tocar(cargaId: number, o: OrdemResumoDTO) {
    const chave = `${cargaId}:${o.id}`;
    const marcada = (valor[cargaId] ?? []).includes(o.id);

    if (marcada || confirmando === chave) {
      setConfirmando(null);
      alternar(cargaId, o.id);
      return;
    }
    setConfirmando(chave);
  }

  const rotulo = (id: number) => {
    const o = ctx.data.ordens.find((x) => x.id === id);
    return o ? osNum(o) : `#${id}`;
  };

  return (
    <div style={{ marginTop: 22 }}>
      <button
        className="btn2"
        style={{ padding: "8px 14px", fontSize: 14 }}
        onClick={() => setAberto((a) => !a)}
      >
        {mostrar ? "−" : "+"} Acoplar outra OS a uma carga{" "}
        <span className="os-tv">
          {total > 0 ? `· ${total} marcada(s)` : "(opcional)"}
        </span>
      </button>

      {mostrar && (
        <>
          <div className="os-tv" style={{ margin: "10px 0" }}>
            Escolha a carga e marque as OS cujas peças vão dentro dela.
          </div>

          {cargas.length === 0 ? (
            <span className="os-tv">Selecione ao menos uma carga acima.</span>
          ) : (
            <>
              <input
                className="inp"
                placeholder={`Buscar OS de ${posLabel(posicao)} por nº ou cliente`}
                value={busca}
                onChange={(e) => setBusca(e.target.value)}
              />
              {cargas.map((c) => (
                <div key={c.id} className="ac-linha">
                  <span className="ac-carga">{c.nome}</span>
                  <div className="ac-chips">
                    {candidatas.map((o) => {
                      const marcada = (valor[c.id] ?? []).includes(o.id);
                      // Marcada noutra carga deste mesmo modal: as peças já
                      // têm tanque, e oferecê-la de novo seria oferecer erro.
                      if (!marcada && marcadasAqui.has(o.id)) return null;
                      const aguardando = confirmando === `${c.id}:${o.id}`;
                      return (
                        <button
                          key={o.id}
                          className="cgtog"
                          style={marcada || aguardando ? SEL_CHIP : undefined}
                          onClick={() => tocar(c.id, o)}
                          onBlur={() => setConfirmando(null)}
                        >
                          {aguardando ? `Confirmar ${osNum(o)}` : osNum(o)}
                          <span className="tp">{o.clienteNome}</span>
                        </button>
                      );
                    })}
                    {candidatas.length === 0 && (
                      <span className="os-tv">
                        {busca.trim()
                          ? "Nenhuma OS desta posição bate com a busca."
                          : `Nenhuma outra OS aberta em ${posLabel(posicao)}.`}
                      </span>
                    )}
                  </div>
                </div>
              ))}
            </>
          )}
        </>
      )}

      {/* O que vai acontecer, dito antes de acontecer: nem o conceito de
          titular, nem o fecho das etapas da carona, nem a duração do vínculo
          são adivinháveis a partir de um chip marcado. */}
      {total > 0 && (
        <div className="bp" style={{ padding: "11px 14px", marginTop: 12 }}>
          {cargas
            .filter((c) => (valor[c.id] ?? []).length > 0)
            .map((c) => (
              <div key={c.id} className="os-tv" style={{ marginBottom: 6 }}>
                Na carga <b>{c.nome}</b>: {titular ? <b>{osNum(titular)}</b> : "esta OS"}{" "}
                (dona da carga)
                {(valor[c.id] ?? []).map((id) => (
                  <span key={id}> · <b>{rotulo(id)}</b></span>
                ))}
              </div>
            ))}
          <div className="os-tv" style={{ marginTop: 8 }}>
            Acoplar <b>encerra as etapas abertas</b> das OS escolhidas — as peças
            delas saem da carga própria e entram nesta. O vínculo <b>não termina com
            a etapa</b> nem com “Encerrar etapas”: dura até ser desfeito à mão, no
            detalhe da OS.
          </div>
        </div>
      )}
    </div>
  );
}
