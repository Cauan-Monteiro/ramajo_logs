import { useEffect, useState } from "react";
import * as api from "../api/endpoints";
import type { AvaliacaoDTO, OperadorDTO, OrdemDetalheDTO, OrdemResumoDTO } from "../api/types";
import { Corners } from "../components/Blueprint";
import {
  FormAvaliacao, formDe, paraInput, type FormAvaliacaoState,
} from "../components/FormAvaliacao";
import { diaHora } from "../domain/format";
import type { AppData } from "../state/useAppData";
import type { Ctx } from "../modals/tipos";
import { CartaoOrdem } from "./AjustesCorrigirOS";

/**
 * Avaliação da inspeção final feita pelo ADMIN, fora da expedição.
 *
 * Para a OS que saiu sem avaliar, ou cuja avaliação precisa ser refeita — e
 * também para a que ainda está em produção. Cancelada, não: não vai sair.
 *
 * Mesmo fluxo de Corrigir OS: digita-se o Nº e a OS aparece. Salvar substitui
 * a avaliação que houver, sem histórico, e o ADMIN passa a ser o avaliador.
 */
export function AjustesAvaliarOS({
  data, operador, agir, ocupado,
}: {
  data: AppData;
  operador: OperadorDTO;
  agir: Ctx["agir"];
  ocupado: boolean;
}) {
  const [numero, setNumero] = useState("");
  const [detalhe, setDetalhe] = useState<OrdemDetalheDTO | null>(null);
  const [gravada, setGravada] = useState<AvaliacaoDTO | null>(null);
  const [form, setForm] = useState<FormAvaliacaoState | null>(null);

  const n = numero.trim();
  const ordem: OrdemResumoDTO | null = n
    ? data.ordens.find((o) => o.idExterno !== null && String(o.idExterno) === n) ?? null
    : null;

  // Trocou de OS: o que estava na tela era de outra.
  useEffect(() => {
    setDetalhe(null);
    setGravada(null);
    setForm(null);
  }, [ordem?.id]);

  // O resumo não diz se a OS foi cancelada; o detalhe sim.
  useEffect(() => {
    if (!ordem) return;
    let vivo = true;
    api.buscarOrdem(ordem.id).then((d) => vivo && setDetalhe(d)).catch(() => {});
    api.avaliacaoOrdem(ordem.id)
      .then((a) => {
        if (!vivo) return;
        setGravada(a);
        setForm(formDe(a));
      })
      .catch(() => vivo && setForm(formDe()));
    return () => {
      vivo = false;
    };
    // Só ao trocar de OS: uma recarga por sincronização não pode apagar o que
    // o ADMIN está a preencher. Depois de salvar, `depois` atualiza `gravada`.
  }, [ordem?.id]);

  function salvar() {
    if (!ordem || !form) return;
    agir({
      fazer: async () => {
        const a = await api.salvarAvaliacao(ordem.id, operador.id, paraInput(form));
        setGravada(a);
        setForm(formDe(a));
      },
      ok: `Avaliação da OS #${ordem.idExterno} salva.`,
    });
  }

  return (
    <>
      <div className="reg-h" style={{ fontSize: 13 }}>
        Avaliar OS
        <span className="ct">Inspeção final · OS em produção ou expedida</span>
      </div>

      <div className="os-tv" style={{ marginTop: -8 }}>
        Para a OS que foi <b>expedida sem avaliação</b>, ou cuja avaliação precisa ser refeita.
        Salvar substitui a avaliação atual e registra você como avaliador.
      </div>

      <div
        style={{
          flex: 1, minHeight: 0, overflow: "auto",
          display: "flex", flexDirection: "column", gap: 18,
        }}
      >
        <div className="bp" style={{ padding: "20px 22px", flex: "none" }}>
          <Corners />
          <span className="lbl">Nº da ordem de serviço</span>
          <div style={{ maxWidth: 300 }}>
            <input
              className="inp"
              inputMode="numeric"
              placeholder="ex: 42"
              value={numero}
              onChange={(e) => setNumero(e.target.value.replace(/\D/g, ""))}
            />
          </div>
          {n && !ordem && (
            <div className="os-tv" style={{ marginTop: 10 }}>
              Nenhuma OS com o Nº {n}.
            </div>
          )}
        </div>

        {ordem && <CartaoOrdem ordem={ordem} detalhe={detalhe} data={data} />}

        {ordem && detalhe?.cancelada && (
          <div className="os-tv" style={{ fontSize: 14 }}>
            Esta OS foi cancelada — não há expedição a avaliar.
          </div>
        )}

        {ordem && detalhe && !detalhe.cancelada && form && (
          <div className="bp" style={{ padding: "20px 22px", flex: "none" }}>
            <Corners />
            <div className="grp-h">
              <span>Avaliação</span>
              <i />
            </div>

            <div className="os-tv" style={{ fontSize: 14, marginBottom: 14 }}>
              {gravada
                ? <>Avaliação atual de <b>{gravada.avaliadaPorNome}</b> ({diaHora(gravada.avaliadaEm)}).</>
                : "Esta OS ainda não tem avaliação."}
            </div>

            <FormAvaliacao form={form} onForm={setForm} />

            <div style={{ display: "flex", alignItems: "center", gap: 12, marginTop: 16, flexWrap: "wrap" }}>
              <button
                className="btn2 btn2-p"
                style={{ marginLeft: "auto" }}
                disabled={ocupado}
                onClick={salvar}
              >
                {gravada ? "Substituir avaliação" : "Salvar avaliação"}
              </button>
            </div>
          </div>
        )}
      </div>
    </>
  );
}
