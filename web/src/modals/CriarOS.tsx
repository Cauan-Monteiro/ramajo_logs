import { useEffect, useMemo, useState } from "react";
import * as api from "../api/endpoints";
import type { CargaDTO, Posicao } from "../api/types";
import { Corners } from "../components/Blueprint";
import { Modal } from "../components/Modal";
import { ScanField } from "../components/ScanField";
import { SEL_CHIP, SEL_PICK, SEL_SEG } from "../domain/derive";
import { POSICOES, posLabel } from "../domain/format";
import { cargasLivres } from "../state/useAppData";
import type { Ctx } from "./tipos";

/**
 * Criar OS. Passo 1 pede o Nº: se ele já existe numa OS aberta, o fluxo vira
 * "vincular cargas à OS existente"; se é novo, pede posição e cliente e segue
 * para o passo 2. Não há rota de busca por idExterno na API — a verificação
 * corre sobre a lista já carregada, com debounce a imitar a consulta do design.
 */
export function CriarOSModal({ ctx }: { ctx: Ctx }) {
  const [passo, setPasso] = useState<1 | 2>(1);
  const [externo, setExterno] = useState("");
  const [verificado, setVerificado] = useState("");
  const [verificando, setVerificando] = useState(false);
  const [posicao, setPosicao] = useState<Posicao>(ctx.posicao);
  const [clienteId, setClienteId] = useState<number | null>(null);
  const [buscaCliente, setBuscaCliente] = useState("");
  const [sel, setSel] = useState<string[]>([]);

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

  const existente = useMemo(() => {
    if (!verificado) return null;
    return (
      ctx.data.ordens.find(
        (o) => o.idExterno !== null && String(o.idExterno) === verificado && o.emProcesso,
      ) ?? null
    );
  }, [verificado, ctx.data.ordens]);

  const nova = verificado.length > 0 && !existente;
  const posAlvo: Posicao = existente ? existente.posicao : posicao;
  const livres = cargasLivres(ctx.data, posAlvo);

  // Trocar de posição invalida as cargas escolhidas (são da posição anterior).
  useEffect(() => {
    setSel([]);
  }, [posAlvo]);

  const alternar = (nome: string) =>
    setSel((s) => (s.includes(nome) ? s.filter((n) => n !== nome) : [...s, nome]));

  function lerCarga(tag: string) {
    ctx.agir({
      fazer: async () => {
        const c = await api.cargaPorTag(tag);
        if (!c) throw new Error(`Nenhuma carga com a tag "${tag}".`);
        if (!c.ativo) throw new Error(`A carga ${c.nome} está inativa.`);
        if (c.ordemAtualId !== null) throw new Error(`A carga ${c.nome} já está vinculada a uma OS.`);
        if (c.posicao !== posAlvo) {
          throw new Error(
            `A carga ${c.nome} está em ${posLabel(c.posicao)}, não em ${posLabel(posAlvo)}.`,
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
      // já abre o passo inicial daquela carga.
      fazer: () =>
        Promise.all(ids.map((id) => api.vincularCarga(existente.id, id, ctx.operador.id))),
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
        }),
      ok: `OS criada com ${ids.length} carga(s).`,
      depois: ctx.fechar,
    });
  }

  const clientes = useMemo(() => {
    const q = buscaCliente.trim().toLowerCase();
    return ctx.data.clientes
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
  }, [buscaCliente, ctx.data.clientes]);

  const chips = (
    <div style={{ display: "flex", flexWrap: "wrap", gap: 10 }}>
      {livres.map((c) => (
        <button
          key={c.id}
          className="cgtog"
          style={sel.includes(c.nome) ? SEL_CHIP : undefined}
          onClick={() => alternar(c.nome)}
        >
          {c.nome}
          <span className="tp">{c.tipo}</span>
        </button>
      ))}
      {livres.length === 0 && (
        <span className="os-tv">
          Sem cargas livres em {posLabel(posAlvo)}. Cadastre em “Ajustes › Registrar cargas”.
        </span>
      )}
    </div>
  );

  /* ── passo 2 ──────────────────────────────────────────────────────────── */
  if (passo === 2) {
    return (
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
              className="btn2 btn2-p"
              style={{ marginLeft: "auto" }}
              disabled={sel.length === 0 || ctx.ocupado}
              onClick={criar}
            >
              Abrir OS
            </button>
          </>
        }
      >
        <div
          className="bp"
          style={{ padding: "14px 16px", marginBottom: 18, display: "flex", gap: 22 }}
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
          {sel.length} carga(s) selecionada(s) · é obrigatório vincular ao menos uma
        </div>
      </Modal>
    );
  }

  /* ── passo 1 ──────────────────────────────────────────────────────────── */
  return (
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
              className="btn2 btn2-p"
              style={{ marginLeft: "auto" }}
              disabled={sel.length === 0 || ctx.ocupado}
              onClick={vincularAExistente}
            >
              Vincular à #{existente.idExterno ?? existente.id}
            </button>
          ) : (
            <button
              className="btn2 btn2-p"
              style={{ marginLeft: "auto" }}
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
          Se o Nº já existir, você vincula novas cargas à OS. Se for um Nº novo, cadastramos uma
          nova OS.
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
                <div className="os-tv">Posição</div>
                <div className="os-cli" style={{ fontSize: 17 }}>
                  {posLabel(existente.posicao)}
                </div>
              </div>
            </div>
          </div>
          <div className="scanhd">
            <span className="lbl">
              Cargas livres em {posLabel(existente.posicao)} para vincular
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
        </>
      )}

      {nova && (
        <>
          <div
            style={{
              display: "inline-flex",
              alignItems: "center",
              gap: 8,
              margin: "8px 0 18px",
              padding: "7px 12px",
              border: "1px solid rgba(58,143,77,.5)",
              background: "#f1f8f2",
            }}
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#2f6b3c" strokeWidth="1.7">
              <path d="M20 6 9 17l-5-5" />
            </svg>
            <span style={{ font: "600 14px 'Barlow'", color: "#2f6b3c" }}>Nº livre — nova OS</span>
          </div>

          <span className="lbl">2 · Posição / setor da OS</span>
          <div style={{ display: "flex", gap: 8, marginBottom: 20 }}>
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

          <span className="lbl">3 · Cliente</span>
          <div style={{ display: "flex", gap: 10, marginBottom: 12 }}>
            <input
              className="inp"
              placeholder="Buscar por ID ou nome..."
              value={buscaCliente}
              onChange={(e) => setBuscaCliente(e.target.value)}
              style={{ flex: 1 }}
            />
          </div>
          <div
            style={{ display: "flex", flexDirection: "column", gap: 8, maxHeight: 220, overflow: "auto" }}
          >
            {clientes.map((c) => (
              <button
                key={c.id}
                className="pick"
                style={clienteId === c.id ? SEL_PICK : undefined}
                onClick={() => setClienteId(c.id)}
              >
                <span
                  style={{ font: "600 13px 'Barlow Condensed'", color: "#5980a6", minWidth: 48 }}
                >
                  #{c.id}
                </span>
                {c.nome}
              </button>
            ))}
            {clientes.length === 0 && <span className="os-tv">Nenhum cliente encontrado.</span>}
          </div>
        </>
      )}
    </Modal>
  );
}
