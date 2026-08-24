/**
 * Espelho dos DTOs de system_API. Cada record Java tem aqui o seu type; os
 * nomes dos campos são os que o Jackson serializa, sem renomeação.
 */

export type Posicao = "OXIDACAO" | "AUTOMATICA" | "PENDURADO";
export type Etapa = "PRE_TRATAMENTO" | "TRATAMENTO" | "POS_TRATAMENTO";
export type TipoCarga = "TAMBOR" | "TRAVE" | "CESTO";
export type Permissao = "ADMIN" | "FUNCIONARIO";

/** dtos/OperadorDtos.OperadorDTO */
export interface OperadorDTO {
  id: number;
  nome: string;
  permissao: Permissao;
  ativo: boolean;
  tagId: string | null;
}

/** dtos/ClienteDtos.ClienteDTO */
export interface ClienteDTO {
  id: number;
  nome: string;
}

/** dtos/ProcessoDtos.ProcessoDTO */
export interface ProcessoDTO {
  id: number;
  descricao: string;
  etapa: Etapa;
  posicoes: Posicao[];
  tagId: string | null;
}

/** dtos/CargaDtos.CargaDTO */
export interface CargaDTO {
  id: number;
  nome: string;
  tipo: TipoCarga;
  posicao: Posicao;
  ativo: boolean;
  emUso: boolean;
  ordemAtualId: number | null;
  tagId: string | null;
}

/** dtos/OrdemDtos.OrdemResumoDTO */
export interface OrdemResumoDTO {
  id: number;
  idExterno: number | null;
  clienteNome: string;
  posicao: Posicao;
  emProcesso: boolean;
  iniciadaEm: string;
  totalLotes: number;
  lotesFinalizados: number;
}

/** dtos/OrdemDtos.LoteDTO */
export interface LoteDTO {
  id: number;
  numero: number;
  iniciadoEm: string;
  finalizadoEm: string | null;
  finalizadoPorNome: string | null;
}

/** dtos/OrdemDtos.LogDTO */
export interface LogDTO {
  id: string;
  ordemServicoId: number;
  cargaNome: string;
  processoDescricao: string;
  responsavelNome: string;
  iniciadoEm: string;
  finalizadoEm: string | null;
  cancelado: boolean;
}

/** dtos/OrdemDtos.OrdemDetalheDTO */
export interface OrdemDetalheDTO {
  id: number;
  idExterno: number | null;
  clienteId: number;
  clienteNome: string;
  posicao: Posicao;
  iniciadaEm: string;
  finalizadaEm: string | null;
  cancelada: boolean;
  emProcesso: boolean;
  iniciadaPorNome: string | null;
  finalizadaPorNome: string | null;
  cargasVinculadas: number[];
  lotes: LoteDTO[];
  /** Preenchido só na criação da OS; null nas demais rotas — de propósito. */
  logsIniciados: LogDTO[] | null;
}

/** web/ApiError */
export interface ApiErrorBody {
  codigo: string;
  mensagem: string;
  status: number;
  path: string;
  timestamp: string;
  campos: { campo: string; erro: string }[] | null;
}
