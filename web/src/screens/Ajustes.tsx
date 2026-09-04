import { useState } from "react";
import * as api from "../api/endpoints";
import type { OperadorDTO, Posicao, ProcessoDTO } from "../api/types";
import { Corners } from "../components/Blueprint";
import { Modal, Vazio } from "../components/Modal";
import { SEL_PICK, SEL_SEG, dotStyle } from "../domain/derive";
import { ETAPAS, posLabel, POSICOES } from "../domain/format";
import type { AppData } from "../state/useAppData";
import type { Ctx } from "../modals/tipos";
import { AjustesCatalogo } from "./AjustesCatalogo";
import { AjustesOperadores } from "./AjustesOperadores";
import { RegistrarCargas } from "./RegistrarCargas";

/** O que o diálogo de confirmação precisa saber: para onde e para qual. */
type Troca = { pos: Posicao; processo: ProcessoDTO };

type Sub = "inicial" | "catalogo" | "operadores" | "cargas";

const SUBS: { key: Sub; label: string }[] = [
  { key: "inicial", label: "Processo inicial" },
  { key: "catalogo", label: "Catálogo" },
  { key: "operadores", label: "Operadores" },
  { key: "cargas", label: "Registrar cargas" },
];

/**
 * Ajustes de configuração, em quatro frentes: o processo inicial de cada setor
 * (em que processo toda carga entra ao ser vinculada a uma OS daquela posição),
 * o catálogo de processos em si — cadastrar, editar e arquivar —, o cadastro
 * de operadores e o cadastro de cargas.
 *
 * Tela de ADMIN (App.tsx só a monta com isAdmin), mas o gate é de conveniência:
 * a API não tem autenticação, então quem sabe a rota chama o PUT direto. Mesma
 * situação de Relatórios.
 *
 * Não usa o Ctx dos modais: aquele é montado pelo Dashboard e carrega uma
 * `posicao` — a da aba —, e esta tela configura as três de uma vez. Recebe
 * `agir`/`ocupado` soltos, como os sub-componentes de Relatórios fazem.
 */
export function Ajustes({
  data, operador, posicaoAtual, agir, ocupado, isMobile,
}: {
  data: AppData;
  /** O operador do turno — a sub-aba de operadores usa para não removê-lo. */
  operador: OperadorDTO;
  /** Posição pré-selecionada no formulário da sub-aba de cargas. */
  posicaoAtual: Posicao;
  agir: Ctx["agir"];
  ocupado: boolean;
  isMobile: boolean;
}) {
  const [sub, setSub] = useState<Sub>("inicial");

  return (
    <div className="cargas-body">
      {/* Mesmo seletor segmentado da Auditoria (.aud-seg + .seg-b). */}
      <div className="aud-seg" style={{ marginLeft: 0, flex: "none" }}>
        {SUBS.map((s) => (
          <button
            key={s.key}
            className="seg-b"
            style={sub === s.key ? SEL_SEG : undefined}
            onClick={() => setSub(s.key)}
          >
            {s.label}
          </button>
        ))}
      </div>

      {sub === "inicial" ? (
        <ProcessoInicialPainel data={data} agir={agir} ocupado={ocupado} />
      ) : sub === "catalogo" ? (
        <AjustesCatalogo data={data} agir={agir} ocupado={ocupado} />
      ) : sub === "cargas" ? (
        <RegistrarCargas
          data={data}
          posicaoAtual={posicaoAtual}
          agir={agir}
          ocupado={ocupado}
          isMobile={isMobile}
        />
      ) : (
        <AjustesOperadores
          data={data}
          operador={operador}
          agir={agir}
          ocupado={ocupado}
        />
      )}
    </div>
  );
}

/** O processo em que cada setor abre o primeiro passo da carga. */
function ProcessoInicialPainel({
  data, agir, ocupado,
}: {
  data: AppData;
  agir: Ctx["agir"];
  ocupado: boolean;
}) {
  const [troca, setTroca] = useState<Troca | null>(null);

  const vigenteDe = (pos: Posicao) =>
    data.processosIniciais.find((pi) => pi.posicao === pos) ?? null;

  return (
    <>
      <div className="reg-h" style={{ fontSize: 13 }}>
        Processo inicial por posição
        <span className="ct">{data.processosIniciais.length} de {POSICOES.length} configuradas</span>
      </div>

      <div className="os-tv" style={{ marginTop: -8 }}>
        O processo em que <b>toda carga entra</b> ao ser vinculada a uma OS do
        setor — na criação da ordem e em todo vínculo posterior. Só aparecem
        processos habilitados naquela posição.
      </div>

      {/* Os três cartões rolam juntos: .cargas-body é uma coluna flex de altura
          fixa, então o scroll tem que morar aqui e não no body da página. */}
      <div
        style={{
          flex: 1, minHeight: 0, overflow: "auto",
          display: "flex", flexDirection: "column", gap: 18,
        }}
      >
        {POSICOES.map((p) => {
          const vigente = vigenteDe(p.key);

          // Espelha as guardas do ProcessoInicialService: processo que não roda
          // no setor, ou arquivado, nem aparece. Os 422 do servidor ficam como
          // rede de segurança.
          const permitidos = data.processos.filter(
            (pr) => pr.ativo && pr.posicoes.includes(p.key),
          );
          const grupos = ETAPAS.map((g) => ({
            ...g,
            itens: permitidos.filter((pr) => pr.etapa === g.key),
          })).filter((g) => g.itens.length > 0);

          return (
            <div key={p.key} className="bp" style={{ padding: "20px 22px", flex: "none" }}>
              <Corners />

              <div className="grp-h">
                <span>{p.label}</span>
                <i />
              </div>

              <div style={{ marginBottom: 18 }}>
                <span className="lbl">Entrada atual</span>
                {vigente ? (
                  <div style={{ font: "600 20px 'Barlow Condensed'" }}>
                    {vigente.processoDescricao}
                  </div>
                ) : (
                  <div className="os-tv" style={{ fontSize: 14 }}>
                    Não configurado — a API usa o processo padrão (fallback).
                  </div>
                )}
              </div>

              <span className="lbl">Trocar para</span>
              {grupos.length === 0 ? (
                <Vazio>Nenhum processo habilitado para {p.label}.</Vazio>
              ) : (
                <div
                  style={{
                    display: "flex", flexDirection: "column", gap: 16, marginTop: 8,
                  }}
                >
                  {grupos.map((g) => (
                    <div key={g.key}>
                      <div
                        style={{
                          font: "600 11px 'Barlow Condensed'",
                          letterSpacing: ".12em",
                          textTransform: "uppercase",
                          color: "rgba(29,31,32,.55)",
                          display: "flex",
                          alignItems: "center",
                          gap: 8,
                          marginBottom: 9,
                        }}
                      >
                        <span className="turn-dot" style={dotStyle(g.key)} />
                        {g.label}
                      </div>
                      <div style={{ display: "flex", flexWrap: "wrap", gap: 10 }}>
                        {g.itens.map((pr) => {
                          const atual = vigente?.processoId === pr.id;
                          return (
                            <button
                              key={pr.id}
                              className="pick"
                              style={{ flex: "none", ...(atual ? SEL_PICK : {}) }}
                              // O vigente não abre diálogo: trocar por ele
                              // mesmo não muda nada.
                              disabled={atual || ocupado}
                              title={atual ? "Já é a entrada deste setor." : undefined}
                              onClick={() => setTroca({ pos: p.key, processo: pr })}
                            >
                              <span className="turn-dot" style={dotStyle(pr.etapa)} />
                              {pr.descricao}
                            </button>
                          );
                        })}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          );
        })}
      </div>

      {troca && (
        <ConfirmarTrocaModal
          troca={troca}
          vigente={vigenteDe(troca.pos)?.processoDescricao ?? null}
          ocupado={ocupado}
          agir={agir}
          onFechar={() => setTroca(null)}
        />
      )}
    </>
  );
}

function ConfirmarTrocaModal({
  troca, vigente, ocupado, agir, onFechar,
}: {
  troca: Troca;
  vigente: string | null;
  ocupado: boolean;
  agir: Ctx["agir"];
  onFechar: () => void;
}) {
  const label = posLabel(troca.pos);

  return (
    <Modal
      kicker={`POSIÇÃO ${label.toUpperCase()}`}
      titulo="Trocar o processo inicial"
      onClose={onFechar}
      footer={
        <>
          <button className="btn2" onClick={onFechar}>
            Cancelar
          </button>
          <button
            className="btn2 btn2-p"
            style={{ marginLeft: "auto" }}
            disabled={ocupado}
            onClick={() =>
              agir({
                fazer: () => api.definirProcessoInicial(troca.pos, troca.processo.id),
                ok: `Entrada de ${label} agora é ${troca.processo.descricao}.`,
                depois: onFechar,
              })
            }
          >
            Confirmar troca
          </button>
        </>
      }
    >
      <div className="bp" style={{ padding: "18px 20px" }}>
        <Corners />
        <div className="os-tv">Entrada de {label}</div>
        <div
          style={{
            display: "flex", alignItems: "center", flexWrap: "wrap",
            gap: 12, marginTop: 8,
          }}
        >
          <span style={{ font: "600 18px 'Barlow Condensed'", opacity: 0.55 }}>
            {vigente ?? "Não configurado"}
          </span>
          <span style={{ font: "600 18px 'Barlow Condensed'", opacity: 0.4 }}>→</span>
          <span style={{ font: "600 20px 'Barlow Condensed'", color: "#416180" }}>
            <span className="turn-dot" style={{ ...dotStyle(troca.processo.etapa), marginRight: 8 }} />
            {troca.processo.descricao}
          </span>
        </div>
      </div>

      <div className="os-tv" style={{ fontSize: 14, marginTop: 16 }}>
        Toda carga vinculada a uma OS de {label} passará a abrir o primeiro passo
        neste processo. As OS já em andamento <b>não mudam</b> — o histórico de
        etapas é preservado.
      </div>
    </Modal>
  );
}
