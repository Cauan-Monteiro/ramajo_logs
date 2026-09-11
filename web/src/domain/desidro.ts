import type { DesidroEmAndamentoDTO } from "../api/types";
import { hhmm, osNum } from "./format";

/**
 * A escala do indicativo de desidrogenização da barra do Dashboard.
 *
 * Mora aqui, e não dentro do JSX, porque é regra de domínio: "quanto falta para
 * estourar" é a pergunta que a fábrica faz, e a barra é só uma das formas de
 * respondê-la.
 *
 * O progresso é medido com o relógio do NAVEGADOR contra carimbos do servidor —
 * a mesma aproximação que `duracao(iniciadoEm, null)` já faz na tabela do
 * Dashboard. Numa janela medida em horas, a diferença é irrelevante; só não
 * vale fingir precisão de segundos.
 */

export type NivelDesidro = "tranquilo" | "atencao" | "critico";

/** Faixas pedidas: até 80% tranquilo, 80–90% atenção, acima disso crítico. */
const LIMITE_ATENCAO = 0.8;
const LIMITE_CRITICO = 0.9;

/**
 * Quanto tempo uma desidrogenização que ESTOUROU o horário continua contando.
 *
 * Sem esta janela haveria uma escolha ruim dos dois lados: some em 100% e quem
 * saiu da tela nunca fica sabendo que passou; fica para sempre e o vermelho
 * acende indefinidamente por algo que ninguém pode mais evitar.
 */
export const JANELA_ESTOURADA_MIN = 15;

/** 0 = acabou de começar · 1 = no horário de término · >1 = estourou. */
export function progresso(d: DesidroEmAndamentoDTO, agora: number): number {
  const inicio = new Date(d.iniciadaEm).getTime();
  const fim = new Date(d.finalizadaEm).getTime();
  // Duração zero não existe (ck_desidro_duracao exige > 0), mas dividir por
  // zero devolveria Infinity e pintaria a barra de vermelho por um dado ruim.
  if (!(fim > inicio)) return 0;
  return (agora - inicio) / (fim - inicio);
}

/**
 * Por quanto tempo um alerta JÁ ACESO e sem "Ciente" continua na faixa.
 *
 * Acender é outra conta: só acende o estouro que o terminal percebe dentro de
 * JANELA_ESTOURADA_MIN — um terminal que passou horas desligado não pode voltar
 * à produção a tocar por estouros que ninguém podia mais evitar. Mas o que
 * acendeu à frente de alguém só some quando alguém diz que viu, e isto é o teto
 * disso: sem ele, um terminal ligado e esquecido acenderia amanhã por uma OS
 * que ficou aberta. Um turno basta.
 */
export const JANELA_ALERTA_MIN = 12 * 60;

/** As que já passaram do horário de término há menos de `janelaMin`. */
export function estouradas(
  ds: DesidroEmAndamentoDTO[], agora: number, janelaMin = JANELA_ALERTA_MIN,
): DesidroEmAndamentoDTO[] {
  const limite = janelaMin * 60000;
  return ds.filter((d) => {
    const fim = new Date(d.finalizadaEm).getTime();
    return agora >= fim && agora - fim < limite;
  });
}

/**
 * O próximo término ainda no futuro, em ms — o instante exato em que vale a
 * pena reavaliar o alerta. `null` quando nenhuma está a correr.
 */
export function proximoFim(ds: DesidroEmAndamentoDTO[], agora: number): number | null {
  let prox: number | null = null;
  for (const d of ds) {
    const fim = new Date(d.finalizadaEm).getTime();
    if (fim > agora && (prox === null || fim < prox)) prox = fim;
  }
  return prox;
}

export function nivel(pct: number): NivelDesidro {
  if (pct >= LIMITE_CRITICO) return "critico";
  if (pct >= LIMITE_ATENCAO) return "atencao";
  return "tranquilo";
}

/**
 * As que ainda importam: rodando, ou estouradas há menos de JANELA_ESTOURADA_MIN.
 *
 * A API devolve todas as desidrogenizações das OS em produção — inclusive as de
 * dias atrás, se a OS ficou aberta. O corte é aqui.
 */
export function emCurso(
  ds: DesidroEmAndamentoDTO[], agora: number,
): DesidroEmAndamentoDTO[] {
  const limite = JANELA_ESTOURADA_MIN * 60000;
  return ds.filter((d) => {
    const fim = new Date(d.finalizadaEm).getTime();
    const inicio = new Date(d.iniciadaEm).getTime();
    // Ainda não começou não acontece hoje (o início é o instante da aplicação),
    // mas a guarda evita contar uma linha com carimbo estranho.
    return agora >= inicio && agora - fim < limite;
  });
}

/**
 * O pior nível entre as em curso — é ele que decide a cor da barra. Uma delas
 * crítica basta: o indicativo existe para dizer se alguém precisa correr.
 */
export function piorNivel(
  ds: DesidroEmAndamentoDTO[], agora: number,
): NivelDesidro {
  return ds.reduce<NivelDesidro>((pior, d) => {
    const n = nivel(progresso(d, agora));
    if (pior === "critico" || n === "critico") return "critico";
    if (pior === "atencao" || n === "atencao") return "atencao";
    return "tranquilo";
  }, "tranquilo");
}

/**
 * Tons para a .posbar, cujo fundo é o #1d2d3d escuro.
 *
 * São novos de propósito: o verde (#3a8f4d) e o vermelho (#b4472e) do resto do
 * app foram feitos para fundo claro e afundam no escuro — e amarelo o projeto
 * não tinha nenhum. Estes são os mesmos matizes, puxados para cima em
 * luminosidade até ficarem legíveis sobre a barra.
 */
export const COR_NIVEL: Record<NivelDesidro, string> = {
  tranquilo: "#5cb46f",
  atencao: "#e0a53f",
  critico: "#e0714f",
};

export const ROTULO_NIVEL: Record<NivelDesidro, string> = {
  tranquilo: "tranquilo",
  atencao: "atenção",
  critico: "crítico",
};

/**
 * O tom do indicativo quando o forno está livre.
 *
 * O indicativo passou a estar SEMPRE na barra, e não só quando há o que avisar:
 * um lugar fixo aprende-se uma vez, um elemento que aparece e desaparece muda o
 * layout da barra a meio do turno. Mas "nada a correr" não é um estado
 * tranquilo — é a ausência de estado, e por isso sai da escala de cores.
 */
export const COR_INATIVO = "rgba(255,255,255,.42)";

/** Da mais urgente para a menos — a ordem em que se olha para o forno. */
export function porUrgencia(
  ds: DesidroEmAndamentoDTO[], agora: number,
): DesidroEmAndamentoDTO[] {
  return [...ds].sort((a, b) => progresso(b, agora) - progresso(a, agora));
}

/**
 * O detalhe que vai no `title`: uma linha por desidrogenização, da mais urgente
 * para a menos. Quem estourou aparece como tal, em vez de "101%".
 */
export function detalhe(ds: DesidroEmAndamentoDTO[], agora: number): string {
  return porUrgencia(ds, agora)
    .map((d) => {
      const pct = progresso(d, agora);
      const situacao = pct >= 1
        ? "RETIRAR DO FORNO"
        : `${Math.round(pct * 100)}% · termina ${hhmm(d.finalizadaEm)}`;
      const os = osNum({ id: d.ordemServicoId, idExterno: d.ordemIdExterno });
      return `OS ${os} · ${d.nome} · ${situacao}`;
    })
    .join("\n");
}
