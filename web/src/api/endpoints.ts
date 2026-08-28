import { http, NotFoundError } from "./client";
import type {
  CargaDTO, ClienteDTO, LogDTO, LoteDTO, OperadorDTO,
  OrdemDetalheDTO, OrdemResumoDTO, Posicao, ProcessoDTO, RevisaoDTO, TipoCarga,
} from "./types";

/** Uma função por rota de system_API. Nada mais mora aqui. */

// ── operadores ──────────────────────────────────────────────────────────
export const listarOperadores = () => http.get<OperadorDTO[]>("/api/operadores");

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
export const listarProcessos = () => http.get<ProcessoDTO[]>("/api/processos");

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
 * Expedição parcial: fecha o lote corrente e abre o seguinte, liberando as
 * cargas indicadas (o passo aberto de cada uma fecha junto). A OS segue aberta.
 * `cargaIds` vazio só avança o lote.
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
