import type { OperadorDTO, Posicao } from "../api/types";
import type { AppData } from "../state/useAppData";

/** Qual diálogo está aberto. Espelha os `m.type` do design. */
export type ModalState =
  | { tipo: "cad" }
  | { tipo: "passoLote" }
  | { tipo: "encerrarLote" }
  | { tipo: "inspecao" }
  | { tipo: "buscar" }
  | { tipo: "processos" }
  | { tipo: "livres" }
  | { tipo: "det"; osId: number }
  | { tipo: "vinc"; osId: number }
  | { tipo: "passo"; osId: number }
  | { tipo: "exp"; osId: number }
  | { tipo: "expParcial"; osId: number }
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
  expedirParcial:
    "A expedição parcial é feita na Inspeção final, sobre OS que já não têm " +
    "cargas vinculadas — é o único caminho para o 2º lote. Para liberar " +
    "algumas cargas desta OS sem encerrar o lote, use o hub “Encerrar etapas” " +
    "da home.",
  reativarCarga:
    "DELETE /api/cargas/{id} só desativa; não há rota de reativação na API.",
  desidrogenizar: "Etapa ainda não modelada na API.",
} as const;
