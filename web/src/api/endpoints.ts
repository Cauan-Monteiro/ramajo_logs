import { http, NotFoundError } from "./client";
import type {
  CargaDTO, ClienteDTO, Etapa, LogDTO, LoteDTO, OperadorDTO, Permissao,
  OrdemDetalheDTO, OrdemResumoDTO, Posicao, ProcessoDTO, ProcessoInicialDTO,
  RevisaoDTO, TipoCarga,
} from "./types";

/** Uma função por rota de system_API. Nada mais mora aqui. */

// ── operadores ──────────────────────────────────────────────────────────
/** Traz ativos E inativos: a tela de Ajustes precisa dos dois. */
export const listarOperadores = () => http.get<OperadorDTO[]>("/api/operadores");

/** O mesmo corpo serve POST e PUT — do lado do Java é um CriarOperadorDTO só. */
export const criarOperador = (dto: {
  nome: string; permissao: Permissao; tagId: string | null;
}) => http.post<OperadorDTO>("/api/operadores", dto);

export const atualizarOperador = (id: number, dto: {
  nome: string; permissao: Permissao; tagId: string | null;
}) => http.put<OperadorDTO>(`/api/operadores/${id}`, dto);

/**
 * Soft-delete ("Desativar" na tela): o operador sai do início de turno, tudo
 * que ele assinou continua legível. Recusa com 409 o último admin ativo.
 */
export const desativarOperador = (id: number) => http.del<void>(`/api/operadores/${id}`);

export const reativarOperador = (id: number) =>
  http.post<OperadorDTO>(`/api/operadores/${id}/reativar`);

/**
 * Hard delete: apaga a linha. Recusa com 409 (OPERADOR_EM_USO) quem já assinou
 * passos, ordens ou lotes — a mensagem do ApiError já vem pronta para o toast.
 */
export const excluirOperador = (id: number) =>
  http.del<void>(`/api/operadores/${id}?definitivo=true`);

export async function operadorPorTag(tagId: string): Promise<OperadorDTO | null> {
  try {
    return await http.get<OperadorDTO>(`/api/operadores/por-tag/${encodeURIComponent(tagId)}`);
  } catch (e) {
    if (e instanceof NotFoundError) return null;
    throw e;
  }
}

// ── clientes ────────────────────────────────────────────────────────────
export const listarClientes = () => http.get<ClienteDTO[]>("/api/clientes");

// ── processos ───────────────────────────────────────────────────────────
/** Traz ativos E arquivados — ver o comentário de ProcessoDTO.ativo. */
export const listarProcessos = () => http.get<ProcessoDTO[]>("/api/processos");

/** O cadastro vai inteiro num request: a API exige ao menos uma posição. */
export const criarProcesso = (dto: {
  descricao: string; etapa: Etapa; tagId: string | null; posicoes: Posicao[];
}) => http.post<ProcessoDTO>("/api/processos", dto);

export const atualizarProcesso = (id: number, dto: {
  descricao: string; etapa: Etapa; tagId: string | null; posicoes: Posicao[];
}) => http.put<ProcessoDTO>(`/api/processos/${id}`, dto);

/**
 * Soft-delete ("Arquivar" na tela): o processo sai das listas de escolha, o
 * histórico fica. Recusa com 409 o processo que ainda é a entrada de algum
 * setor — a mensagem do ApiError já vem pronta para o toast.
 */
export const arquivarProcesso = (id: number) => http.del<void>(`/api/processos/${id}`);

export const reativarProcesso = (id: number) =>
  http.post<ProcessoDTO>(`/api/processos/${id}/reativar`);

// ── processos iniciais ──────────────────────────────────────────────────
export const listarProcessosIniciais = () =>
  http.get<ProcessoInicialDTO[]>("/api/processos-iniciais");

/**
 * A posição vai CRUA na URL (OXIDACAO/AUTOMATICA/PENDURADO) — é o
 * @PathVariable Posicao do Spring, não o rótulo de tela do posLabel().
 *
 * Recusa com 422 um processo que não roda naquele setor; a mensagem do
 * ApiError já vem pronta para o toast.
 */
export const definirProcessoInicial = (posicao: Posicao, processoId: number) =>
  http.put<ProcessoInicialDTO>(`/api/processos-iniciais/${posicao}`, { processoId });

// ── cargas ──────────────────────────────────────────────────────────────
export const listarCargas = () => http.get<CargaDTO[]>("/api/cargas");
export const listarCargasDisponiveis = () =>
  http.get<CargaDTO[]>("/api/cargas?disponiveis=true");

export const criarCarga = (dto: {
  nome: string; tipo: TipoCarga; posicao: Posicao; tagId: string | null;
}) => http.post<CargaDTO>("/api/cargas", dto);

/** Soft-delete ("Sucatear" na tela): a carga sai do pool, o histórico fica. */
export const desativarCarga = (id: number) => http.del<void>(`/api/cargas/${id}`);

export async function cargaPorTag(tagId: string): Promise<CargaDTO | null> {
  try {
    return await http.get<CargaDTO>(`/api/cargas/por-tag/${encodeURIComponent(tagId)}`);
  } catch (e) {
    if (e instanceof NotFoundError) return null;
    throw e;
  }
}

// ── ordens de serviço ───────────────────────────────────────────────────
export const listarOrdens = (emProcesso = false) =>
  http.get<OrdemResumoDTO[]>(`/api/ordens?emProcesso=${emProcesso}`);

export const buscarOrdem = (id: number) => http.get<OrdemDetalheDTO>(`/api/ordens/${id}`);
export const historicoOrdem = (id: number) => http.get<LogDTO[]>(`/api/ordens/${id}/logs`);

/** Baixa a OS inteira em .xlsx; o nome do arquivo vem do Content-Disposition. */
export const planilhaOrdem = (id: number) =>
  http.baixar(`/api/ordens/${id}/planilha`, `ordem-servico-${id}.xlsx`);

export const lotesOrdem = (id: number) => http.get<LoteDTO[]>(`/api/ordens/${id}/lotes`);

export const criarOrdem = (dto: {
  clienteId: number; operadorId: number; idExterno: number | null;
  posicao: Posicao; cargaIds: number[];
}) => http.post<OrdemDetalheDTO>("/api/ordens", dto);

/** O vínculo já abre o passo inicial da carga — por isso devolve um LogDTO. */
export const vincularCarga = (osId: number, cargaId: number, operadorId: number) =>
  http.post<LogDTO>(`/api/ordens/${osId}/cargas`, { cargaId, operadorId });

/** Abre o passo; o service fecha sozinho o passo anterior da mesma carga. */
export const iniciarLog = (
  osId: number, cargaId: number, processoId: number, responsavelId: number,
) => http.post<LogDTO>(`/api/ordens/${osId}/logs`, { cargaId, processoId, responsavelId });

export const finalizarLog = (logId: string) =>
  http.patch<LogDTO>(`/api/ordens/logs/${logId}/finalizar`);

/**
 * Fecha o passo aberto de cada carga indicada e a devolve ao pool de livres.
 * A OS segue aberta e o **lote não muda** — é a rotina "encerrar etapas" da
 * home, deliberadamente separada da expedição parcial.
 */
export const liberarCargas = (osId: number, operadorId: number, cargaIds: number[]) =>
  http.post<void>(`/api/ordens/${osId}/cargas/liberar`, { operadorId, cargaIds });

/**
 * Expedição parcial: fecha o lote corrente e abre o seguinte; a OS segue
 * aberta. É o único caminho que leva uma OS ao 2º lote, e quem o usa é o botão
 * "Expedir parcial" da Inspeção Final — que age sobre OS já sem cargas, daí
 * `cargaIds` vazio. Preenchido, as cargas listadas saem da OS junto com o lote.
 */
export const finalizarLote = (osId: number, operadorId: number, cargaIds: number[]) =>
  http.post<LoteDTO>(`/api/ordens/${osId}/lotes/finalizar`, { operadorId, cargaIds });

/** Expedição total: libera as cargas restantes e encerra a OS. */
export const finalizarOrdem = (osId: number, operadorId: number) =>
  http.post<void>(`/api/ordens/${osId}/finalizar`, { operadorId });

export const cancelarOrdem = (osId: number, operadorId: number) =>
  http.post<void>(`/api/ordens/${osId}/cancelar`, { operadorId });

// ── relatórios ──────────────────────────────────────────────────────────
/**
 * Baixa em .xlsx as OSs iniciadas no intervalo, uma linha por ordem. As datas
 * vão em ISO (yyyy-MM-dd) e os dois extremos entram inteiros.
 *
 * Fora de /api/ordens de propósito: `GET /api/ordens/planilha` casaria com o
 * padrão `GET /api/ordens/{id}` do lado do Spring.
 */
export const planilhaPeriodo = (dataInicio: string, dataFim: string) =>
  http.baixar(
    `/api/relatorios/periodo/planilha?dataInicio=${dataInicio}&dataFim=${dataFim}`,
    `ordens-servico-${dataInicio}-a-${dataFim}.xlsx`,
  );

// ── estado ──────────────────────────────────────────────────────────────
/** Sonda de sincronização: resposta minúscula, chamada em loop pelos terminais. */
export const revisaoEstado = () => http.get<RevisaoDTO>("/api/estado/revisao");
