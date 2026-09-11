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
  /**
   * Arquivado (false) sai das listas de escolha, mas continua vindo no GET: os
   * logs históricos são cruzados por descrição contra este catálogo (etapaDoLog
   * em domain/derive.ts). Filtrar aqui apagaria a etapa de todo passo antigo.
   */
  ativo: boolean;
}

/**
 * dtos/ProcessoInicialDtos.ProcessoInicialDTO — o processo em que toda carga
 * entra ao ser vinculada a uma OS daquele setor. Uma linha por posição; uma
 * posição sem linha cai no fallback configurado na API.
 */
export interface ProcessoInicialDTO {
  posicao: Posicao;
  processoId: number;
  processoDescricao: string;
}

/**
 * dtos/DesidrogenizacaoDtos.DesidrogenizacaoDTO — uma receita do catálogo.
 * Sem temperatura: ela é a mesma para todas (ver ConfigDesidrogenizacaoDTO).
 */
export interface DesidrogenizacaoDTO {
  id: number;
  nome: string;
  /** Em minutos inteiros — é o que o formulário digita. */
  duracaoMin: number;
  observacao: string | null;
  /** Arquivada (false) não pode mais ser aplicada; o histórico continua válido. */
  ativo: boolean;
}

/** dtos/DesidrogenizacaoDtos.ConfigDesidrogenizacaoDTO — a temperatura do forno. */
export interface ConfigDesidrogenizacaoDTO {
  temperatura: number;
}

/**
 * dtos/DesidrogenizacaoDtos.OrdemDesidrogenizacaoDTO — uma desidrogenização
 * APLICADA a uma OS. `duracaoMin` e `temperatura` são o snapshot do que rodou,
 * não o cadastro de hoje; `finalizadaEm` é início + duração, calculado pelo
 * banco no momento da aplicação.
 */
export interface OrdemDesidrogenizacaoDTO {
  id: number;
  desidrogenizacaoId: number;
  nome: string;
  duracaoMin: number;
  temperatura: number;
  iniciadaEm: string;
  finalizadaEm: string;
  aplicadaPorNome: string | null;
}

/**
 * dtos/DesidrogenizacaoDtos.DesidroEmAndamentoDTO — o que o indicativo do
 * Dashboard precisa. Vem das OS em produção, INCLUSIVE as que já passaram do
 * horário: quem decide o que ainda merece aparecer é a tela
 * (domain/desidro.ts).
 */
export interface DesidroEmAndamentoDTO {
  id: number;
  ordemServicoId: number;
  ordemIdExterno: number | null;
  posicao: Posicao;
  nome: string;
  iniciadaEm: string;
  finalizadaEm: string;
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
  /**
   * OS que pegaram carona NESTA carga: as peças delas estão no mesmo tanque
   * que as da titular (`ordemAtualId`). Vale enquanto a carga estiver
   * vinculada — não morre quando a etapa fecha, que é a diferença em relação
   * ao `ordensAcopladas` de um passo.
   */
  ordensAcopladas: number[];
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

/**
 * dtos/OrdemDtos.ReaberturaDTO — resposta de POST /api/ordens/{id}/reabrir.
 *
 * `cargasSugeridas` são as cargas que a expedição total tinha soltado e que
 * ainda estão livres no setor. É SUGESTÃO: nenhuma foi revinculada. O detalhe
 * da OS usa-as para abrir o modal de vínculo já com elas marcadas.
 */
export interface ReaberturaDTO {
  lote: LoteDTO;
  cargasSugeridas: CargaDTO[];
}

/** dtos/OrdemDtos.LogDTO */
export interface LogDTO {
  id: string;
  ordemServicoId: number;
  cargaId: number;
  cargaNome: string;
  processoDescricao: string;
  responsavelNome: string;
  iniciadoEm: string;
  finalizadoEm: string | null;
  cancelado: boolean;
  /**
   * Outras OS cujas peças estavam na MESMA carga neste passo.
   * `ordemServicoId` acima é sempre a OS TITULAR — a dona da carga. Logo, um
   * passo que aparece no histórico da OS X com `ordemServicoId !== X` é um
   * passo de carona: aconteceu de verdade com as peças dela, mas quem o
   * executou foi a carga de outra ordem.
   *
   * Só leitura: a composição é declarada na CARGA e copiada para cá quando o
   * passo abre. Um passo fechado guarda a que teve — é o histórico.
   */
  ordensAcopladas: number[];
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
  /** Etapa opcional de forno: quase sempre vazia. */
  desidrogenizacoes: OrdemDesidrogenizacaoDTO[];
  /** Preenchido só na criação da OS; null nas demais rotas — de propósito. */
  logsIniciados: LogDTO[] | null;
}

/**
 * Um ponto da avaliação da inspeção final: `null` = não avaliado, `true` =
 * avaliado sem observação, texto = avaliado com a observação daquele ponto.
 * `false` não existe — a API recusa.
 */
export type ItemAvaliacao = null | true | string;

/** Corpo de avaliação — dtos/OrdemDtos.AvaliacaoInputDTO. */
export interface AvaliacaoInput {
  visual: ItemAvaliacao;
  aderencia: ItemAvaliacao;
  embalagem: ItemAvaliacao;
  camada: ItemAvaliacao;
  observacao: string | null;
}

/** dtos/OrdemDtos.AvaliacaoDTO — GET /api/ordens/{id}/avaliacao. */
export interface AvaliacaoDTO extends AvaliacaoInput {
  /** Gravado pela API ao salvar: salvar é concluir a avaliação. */
  isVerificado: boolean;
  avaliadaPorNome: string;
  avaliadaEm: string;
}

/** enums/CampoAlterado — `CARGAS` é o efeito da troca de posição, não um campo da OS. */
export type CampoAlterado = "ID_EXTERNO" | "CLIENTE" | "POSICAO" | "CARGAS";

/**
 * dtos/OrdemDtos.OrdemAlteracaoDTO — uma linha do histórico de correções do
 * ADMIN. Os valores já vêm como texto gravado no momento ("#12 ACME",
 * "OXIDACAO", "CG-01, CG-02"), não o cadastro de hoje; null = não havia valor.
 * As linhas de uma mesma correção partilham `alteradaEm` e `motivo`.
 */
export interface OrdemAlteracaoDTO {
  id: number;
  campo: CampoAlterado;
  valorAnterior: string | null;
  valorNovo: string | null;
  motivo: string;
  alteradaPorNome: string;
  alteradaEm: string;
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

/** dtos/EstadoDtos.RevisaoDTO — marca de versão do estado do servidor. */
export interface RevisaoDTO {
  instancia: string;
  revisao: number;
}
