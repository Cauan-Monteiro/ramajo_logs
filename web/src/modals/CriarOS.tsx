import { useEffect, useMemo, useState } from "react";
import * as api from "../api/endpoints";
import type { CargaDTO, Posicao } from "../api/types";
import { BuscaCliente } from "../components/BuscaCliente";
import { Corners } from "../components/Blueprint";
import type { Acoplamentos } from "./AcoplarCargas";
import { AcoplarCargas, paresDe } from "./AcoplarCargas";
import { Modal } from "../components/Modal";
import { OSRapidaModal } from "./OSRapida";
import { ScanField } from "../components/ScanField";
import { SEL_CHIP, SEL_SEG } from "../domain/derive";
import { POSICOES, posLabel, posLabels } from "../domain/format";
import { cargasLivres } from "../state/useAppData";
import type { Ctx } from "./tipos";

/**
 * Criar OS. O passo 1 pede o Nº e a POSIÇÃO — e são os dois juntos que decidem
 * o fluxo, porque o Nº do ERP é único por setor, não em absoluto: a ordem 42
 * pode ter peças na Oxidação e peças na Automática, e aí são duas OS.
 *
 *   Nº já aberto NESTE setor   -> "vincular cargas à OS existente";
 *   Nº só existe noutro setor  -> nova OS, aqui, com o mesmo Nº;
 *   Nº já encerrado NESTE setor-> bloqueado: o Nº não se reutiliza;
 *   Nº livre                   -> nova OS.
 *
 * A mesma regra vive no backend (OrdemServicoService.criar), que decide sozinho
 * ao receber o POST. Aqui ela existe para a tela mostrar o desfecho antes de o
 * operador confirmar. Não há rota de busca por idExterno na API — a verificação
 * corre sobre a lista já carregada, com debounce a imitar a consulta do design.
 */
export function CriarOSModal({ ctx }: { ctx: Ctx }) {
  const [passo, setPasso] = useState<1 | 2>(1);
  const [externo, setExterno] = useState("");
  const [verificado, setVerificado] = useState("");
  const [verificando, setVerificando] = useState(false);
  const [posicao, setPosicao] = useState<Posicao>(ctx.posicao);
  const [clienteId, setClienteId] = useState<number | null>(null);
  const [sel, setSel] = useState<string[]>([]);
  const [acopladas, setAcopladas] = useState<Acoplamentos>({});
  /** A carga que espera a OS do cadastro rápido; null com ele fechado. */
  const [rapidaPara, setRapidaPara] = useState<number | null>(null);

  // Debounce da verificação do Nº — o design mostrava um spinner de 4 s.
  useEffect(() => {
    const v = externo.trim();
    setVerificado("");
    if (!v) {
      setVerificando(false);
      return;
    }
    setVerificando(true);
    const t = setTimeout(() => {
      setVerificado(v);
      setVerificando(false);
    }, 400);
    return () => clearTimeout(t);
  }, [externo]);

  /** Todas as OS que já usam este Nº, em qualquer setor. */
  const mesmoNumero = useMemo(() => {
    if (!verificado) return [];
    return ctx.data.ordens.filter(
      (o) => o.idExterno !== null && String(o.idExterno) === verificado,
    );
  }, [verificado, ctx.data.ordens]);

  /** A OS deste Nº NESTE setor, ainda aberta: o alvo do vínculo. */
  const existente = useMemo(
    () => mesmoNumero.find((o) => o.posicoes.includes(posicao) && o.emProcesso) ?? null,
    [mesmoNumero, posicao],
  );

  /**
   * O mesmo Nº neste setor, mas já expedido ou cancelado. Não dá para vincular
   * (a OS saiu de circulação) nem para criar: o Nº não se reutiliza. O backend
   * recusa com OS_FORA_DE_CIRCULACAO — o botão para antes.
   */
  const gasto = useMemo(
    () =>
      existente
        ? null
        : mesmoNumero.find((o) => o.posicoes.includes(posicao)) ?? null,
    [mesmoNumero, posicao, existente],
  );

  /** O mesmo Nº rodando NOUTROS setores — informativo: esta OS nasce à mesma. */
  const noutrosSetores = useMemo(
    () => mesmoNumero.filter((o) => !o.posicoes.includes(posicao)),
    [mesmoNumero, posicao],
  );

  /**
   * O cliente que o Nº já tem. Um Nº do ERP é de UMA ordem, e uma ordem é de um
   * cliente — os setores só a partem —, então todas as homônimas partilham o
   * dono e a primeira responde por todas. Havendo dono, não há o que escolher:
   * o backend recusa com OS_CLIENTE_DIVERGENTE qualquer outro.
   */
  const donoDoNumero = mesmoNumero[0] ?? null;

  const nova = verificado.length > 0 && !existente && !gasto;
  const livres = cargasLivres(ctx.data, posicao);

  // Trocar de posição invalida as cargas escolhidas (são da posição anterior)
  // e, com elas, os acoplamentos que penduravam nessas cargas.
  useEffect(() => {
    setSel([]);
    setAcopladas({});
  }, [posicao]);

  // O Nº manda no cliente: assim que aparece um dono, é ele que vai no pedido.
  // Sem dono, o campo volta a ser do operador — inclusive ao apagar o Nº.
  const donoId = donoDoNumero?.clienteId ?? null;
  useEffect(() => {
    if (donoId !== null) setClienteId(donoId);
  }, [donoId]);

  /** As cargas marcadas, resolvidas — é sobre elas que o acoplamento se declara. */
  const cargasSel = livres.filter((c) => sel.includes(c.nome));

  /** Desmarcar uma carga leva junto quem ia dentro dela: o tanque saiu da OS. */
  function alternar(carga: CargaDTO) {
    const sai = sel.includes(carga.nome);
    setSel((s) => (sai ? s.filter((n) => n !== carga.nome) : [...s, carga.nome]));
    if (sai) {
      setAcopladas(({ [carga.id]: _, ...resto }) => resto);
    }
  }

  function lerCarga(tag: string) {
    ctx.agir({
      fazer: async () => {
        const c = await api.cargaPorTag(tag);
        if (!c) throw new Error(`Nenhuma carga com a tag "${tag}".`);
        if (!c.ativo) throw new Error(`A carga ${c.nome} está inativa.`);
        if (c.ordemAtualId !== null) throw new Error(`A carga ${c.nome} já está vinculada a uma OS.`);
        if (c.posicao !== posicao) {
          throw new Error(
            `A carga ${c.nome} está em ${posLabel(c.posicao)}, não em ${posLabel(posicao)}.`,
          );
        }
        setSel((s) => (s.includes(c.nome) ? s : [...s, c.nome]));
      },
    });
  }

  const idsSelecionados = (fonte: CargaDTO[]) =>
    fonte.filter((c) => sel.includes(c.nome)).map((c) => c.id);

  function vincularAExistente() {
    if (!existente) return;
    const ids = idsSelecionados(livres);
    ctx.agir({
      // Uma chamada por carga: a API vincula uma de cada vez, e cada vínculo
      // já abre o passo inicial daquela carga — com as caronas dela, que
      // valem para todas as etapas seguintes e não só para essa.
      fazer: () =>
        Promise.all(ids.map((id) =>
          api.vincularCarga(existente.id, id, ctx.operador.id, acopladas[id] ?? []))),
      ok: `${ids.length} carga(s) vinculada(s) à OS #${existente.idExterno ?? existente.id}.`,
      depois: ctx.fechar,
    });
  }

  function criar() {
    if (clienteId === null) return;
    const ids = idsSelecionados(livres);
    ctx.agir({
      fazer: () =>
        api.criarOrdem({
          clienteId,
          operadorId: ctx.operador.id,
          idExterno: verificado ? Number(verificado) : null,
          posicao,
          cargaIds: ids,
          acoplamentos: paresDe(acopladas),
        }),
      ok: ids.length === 0
        ? "OS criada sem cargas · acople-a a uma carga no detalhe da OS."
        : `OS criada com ${ids.length} carga(s).`,
      depois: ctx.fechar,
    });
  }

  const chips = (
    <div style={{ display: "flex", flexWrap: "wrap", gap: 10 }}>
      {livres.map((c) => (
        <button
          key={c.id}
          className="cgtog"
          style={sel.includes(c.nome) ? SEL_CHIP : undefined}
          onClick={() => alternar(c)}
        >
          {c.nome}
          <span className="tp">{c.tipo}</span>
        </button>
      ))}
      {livres.length === 0 && (
        <span className="os-tv">
          Sem cargas livres em {posLabel(posicao)}. Pode abrir a OS assim mesmo e acoplá-la
          depois, ou cadastrar cargas em “Ajustes › Registrar cargas”.
        </span>
      )}
    </div>
  );

  /** O cadastro rápido, por cima do passo em que estiver — ver `OSRapida.tsx`. */
  const rapida = rapidaPara !== null && (
    <OSRapidaModal
      ctx={ctx}
      posicao={posicao}
      nosReservados={existente || !verificado ? [] : [verificado]}
      onVoltar={() => setRapidaPara(null)}
      onCriada={(osId) => {
        setAcopladas((a) => ({ ...a, [rapidaPara]: [...(a[rapidaPara] ?? []), osId] }));
        setRapidaPara(null);
      }}
    />
  );

  /* ── passo 2 ──────────────────────────────────────────────────────────── */
  if (passo === 2) {
    return (
      <>
      <Modal
        kicker="NOVA OS · CARGAS"
        titulo="Vincular cargas"
        onClose={ctx.fechar}
        footer={
          <>
            <button className="btn2" onClick={() => setPasso(1)}>
              ← Voltar
            </button>
            <button
              className="btn2 btn2-p btn2-end"
              disabled={ctx.ocupado}
              onClick={criar}
            >
              Abrir OS
            </button>
          </>
        }
      >
        <div
          className="bp"
          style={{
            padding: "14px 16px", marginBottom: 18,
            display: "flex", gap: 22, flexWrap: "wrap",
          }}
        >
          <Corners />
          <div>
            <div className="os-tv">Cliente</div>
            <div className="os-cli" style={{ fontSize: 19 }}>
              {ctx.data.clientes.find((c) => c.id === clienteId)?.nome ?? "—"}
            </div>
          </div>
          <div>
            <div className="os-tv">Posição</div>
            <div className="os-cli" style={{ fontSize: 19 }}>
              {posLabel(posicao)}
            </div>
          </div>
        </div>
        <div className="scanhd">
          <span className="lbl">3 · Vincular cargas (mesma posição)</span>
          <ScanField
            rotulo="Ler carga"
            titulo="Encoste a etiqueta ou digite a tag da carga"
            placeholder="ex: CG-0142"
            onLer={lerCarga}
          />
        </div>
        {chips}
        <div className="os-tv" style={{ marginTop: 14 }}>
          {sel.length} carga(s) selecionada(s) · opcional: a OS pode nascer sem carga
          própria e ir de carona numa carga de outra OS
        </div>
        <AcoplarCargas
          ctx={ctx}
          posicao={posicao}
          cargas={cargasSel}
          osIdTitular={undefined}
          valor={acopladas}
          onChange={setAcopladas}
          onNovaOS={setRapidaPara}
        />
      </Modal>
      {rapida}
      </>
    );
  }

  /* ── passo 1 ──────────────────────────────────────────────────────────── */
  return (
    <>
    <Modal
      kicker="NOVA OS"
      titulo="Criar Ordem de Serviço"
      onClose={ctx.fechar}
      footer={
        <>
          <button className="btn2" onClick={ctx.fechar}>
            Cancelar
          </button>
          {existente ? (
            <button
              className="btn2 btn2-p btn2-end"
              disabled={sel.length === 0 || ctx.ocupado}
              onClick={vincularAExistente}
            >
              Vincular à #{existente.idExterno ?? existente.id}
            </button>
          ) : (
            <button
              className="btn2 btn2-p btn2-end"
              disabled={!(nova && clienteId !== null)}
              onClick={() => setPasso(2)}
            >
              Próximo · cargas →
            </button>
          )}
        </>
      }
    >
      <span className="lbl">1 · Nº da ordem de serviço</span>
      <div style={{ maxWidth: 300, marginBottom: 8 }}>
        <input
          className="inp"
          inputMode="numeric"
          placeholder="ex: 42"
          value={externo}
          onChange={(e) => setExterno(e.target.value.replace(/\D/g, ""))}
        />
      </div>

      {verificando && (
        <div
          style={{
            display: "inline-flex",
            alignItems: "center",
            gap: 9,
            margin: "8px 0 18px",
            padding: "8px 13px",
            border: "1px solid rgba(89,128,166,.5)",
            background: "#eef6ff",
          }}
        >
          <span className="spin" />
          <span style={{ font: "600 14px 'Barlow'", color: "#416180" }}>
            Verificando Nº da OS...
          </span>
        </div>
      )}

      {!verificando && externo.trim().length === 0 && (
        <div className="os-tv" style={{ marginTop: 2 }}>
          Se o Nº já existir neste setor, você vincula novas cargas à OS. Se for um Nº novo —
          ou o mesmo Nº noutro setor — cadastramos uma nova OS.
        </div>
      )}

      {/*
        A posição vem ANTES do desfecho, e não dentro do ramo da OS nova: é ela,
        com o Nº, que decide entre vincular e criar. Trocar de setor aqui muda o
        que os blocos abaixo mostram.
      */}
      <span className="lbl">2 · Posição / setor da OS</span>
      <div style={{ display: "flex", gap: 8, marginBottom: 20, flexWrap: "wrap" }}>
        {POSICOES.map((p) => (
          <button
            key={p.key}
            className="seg-b"
            style={posicao === p.key ? SEL_SEG : undefined}
            onClick={() => setPosicao(p.key)}
          >
            {p.label}
          </button>
        ))}
      </div>

      {gasto && (
        <div
          style={{
            margin: "0 0 18px",
            padding: "8px 13px",
            border: "1px solid rgba(170,51,51,.5)",
            background: "#fdf1f1",
            font: "600 14px 'Barlow'",
            color: "#a33",
          }}
        >
          A OS #{gasto.idExterno} de {posLabel(posicao)} já foi{" "}
          {gasto.cancelada ? "cancelada" : "expedida"} · este Nº não volta a ser usado neste
          setor. Para mexer nela, reabra-a.
        </div>
      )}

      {nova && noutrosSetores.length > 0 && (
        <div
          style={{
            margin: "0 0 18px",
            padding: "8px 13px",
            border: "1px solid rgba(89,128,166,.5)",
            background: "#eef6ff",
            font: "600 14px 'Barlow'",
            color: "#416180",
          }}
        >
          #{verificado} já existe em{" "}
          {posLabels(noutrosSetores.flatMap((o) => o.posicoes))} ({noutrosSetores[0].clienteNome})
          · esta será uma OS nova em {posLabel(posicao)}, com o mesmo Nº e o mesmo cliente.
        </div>
      )}

      {existente && (
        <>
          <div
            className="bp"
            style={{ padding: "14px 16px", margin: "8px 0 18px", background: "#eef6ff" }}
          >
            <Corners />
            <div className="os-resumo">
              <div>
                <div className="os-tv">OS existente</div>
                <div className="os-cli" style={{ fontSize: 20 }}>
                  #{existente.idExterno ?? existente.id}
                </div>
              </div>
              <div>
                <div className="os-tv">Cliente</div>
                <div className="os-cli" style={{ fontSize: 17 }}>
                  {existente.clienteNome}
                </div>
              </div>
              <div>
                <div className="os-tv">
                  {existente.posicoes.length > 1 ? "Posições" : "Posição"}
                </div>
                <div className="os-cli" style={{ fontSize: 17 }}>
                  {posLabels(existente.posicoes)}
                </div>
              </div>
            </div>
          </div>
          <div className="scanhd">
            <span className="lbl">
              Cargas livres em {posLabel(posicao)} para vincular
            </span>
            <ScanField
              rotulo="Ler carga"
              titulo="Encoste a etiqueta ou digite a tag da carga"
              placeholder="ex: CG-0142"
              onLer={lerCarga}
            />
          </div>
          {chips}
          <div className="os-tv" style={{ marginTop: 14 }}>
            {sel.length} carga(s) selecionada(s) · serão vinculadas à #
            {existente.idExterno ?? existente.id}
          </div>
          <AcoplarCargas
            ctx={ctx}
            posicao={posicao}
            cargas={cargasSel}
            osIdTitular={existente.id}
            valor={acopladas}
            onChange={setAcopladas}
            onNovaOS={setRapidaPara}
          />
        </>
      )}

      {nova && (
        <>
          {/* O aviso azul acima já explicou o caso do Nº noutro setor. */}
          {noutrosSetores.length === 0 && (
            <div
              style={{
                display: "inline-flex",
                alignItems: "center",
                gap: 8,
                margin: "0 0 18px",
                padding: "7px 12px",
                border: "1px solid rgba(58,143,77,.5)",
                background: "#f1f8f2",
              }}
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#2f6b3c" strokeWidth="1.7">
                <path d="M20 6 9 17l-5-5" />
              </svg>
              <span style={{ font: "600 14px 'Barlow'", color: "#2f6b3c" }}>
                Nº livre em {posLabel(posicao)} — nova OS
              </span>
            </div>
          )}

          <span className="lbl">3 · Cliente</span>
          {donoDoNumero ? (
            // Não há o que escolher: o Nº já tem dono, e a OS nova é a mesma
            // ordem do ERP noutro setor. Deixar escolher seria oferecer um
            // pedido que o backend recusa com OS_CLIENTE_DIVERGENTE.
            <div
              className="bp"
              style={{ padding: "12px 15px", background: "#f6f8fa" }}
            >
              <Corners />
              <div className="os-cli" style={{ fontSize: 17 }}>
                {donoDoNumero.clienteNome}
              </div>
              <div className="os-tv" style={{ marginTop: 3 }}>
                definido pelo Nº #{verificado}
              </div>
            </div>
          ) : (
            <BuscaCliente
              clientes={ctx.data.clientes}
              selecionado={clienteId}
              onEscolher={setClienteId}
            />
          )}
        </>
      )}
    </Modal>
    {rapida}
    </>
  );
}
