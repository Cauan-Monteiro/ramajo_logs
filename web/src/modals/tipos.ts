import type { OperadorDTO, Posicao } from "../api/types";
import type { AppData } from "../state/useAppData";

/** Qual diálogo está aberto. Espelha os `m.type` do design. */
export type ModalState =
  | { tipo: "cad" }
  | { tipo: "passoLote" }
  | { tipo: "inspecao" }
  | { tipo: "buscar" }
  | { tipo: "processos" }
  | { tipo: "livres" }
  | { tipo: "det"; osId: number }
  | { tipo: "vinc"; osId: number }
  | { tipo: "passo"; osId: number }
  | { tipo: "exp"; osId: number }
  | { tipo: "cancel"; osId: number }
  | null;

/** Tudo o que um modal precisa do app à volta. */
export interface Ctx {
  data: AppData;
  posicao: Posicao;
  operador: OperadorDTO;
  isAdmin: boolean;
  fechar: () => void;
  abrir: (m: ModalState) => void;
  /** Executa a mutação, recarrega os dados e reporta erro/sucesso. */
  agir: (o: { fazer: () => Promise<unknown>; ok?: string; depois?: () => void }) => void;
  ocupado: boolean;
}

/**
 * Motivos pelos quais um controlo do design fica desabilitado: a API ainda
 * não expõe a operação. Ver web/README.md — "Pendências de backend".
 */
export const SEM_API = {
  desvincular:
    "A API não expõe desvincular carga fora de finalizar/finalizarLote — " +
    "ver entrega/PATCH-finalizarLote.md no projeto de design.",
  expedirParcial:
    "POST /api/ordens/{id}/lotes/finalizar aceita só operadorId: avança o lote " +
    "sem liberar carga nenhuma. Pendente do patch de finalizarLote.",
  reativarCarga:
    "DELETE /api/cargas/{id} só desativa; não há rota de reativação na API.",
  desidrogenizar: "Etapa ainda não modelada na API.",
} as const;
