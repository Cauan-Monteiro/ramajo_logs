import { AvaliarExpedicaoModal } from "./AvaliarExpedicao";
import { BuscarOSModal } from "./BuscarOS";
import { CargasLivresModal } from "./CargasLivres";
import { CriarOSModal } from "./CriarOS";
import { DesidroPainelModal } from "./DesidroPainel";
import { DesidrogenizarModal } from "./Desidrogenizacao";
import { CancelarModal, DetalheOSModal, ExpedirModal, PassoModal, VincularModal } from "./DetalheOS";
import { EntregarModal } from "./Entregar";
import { ExpedirParcialModal } from "./ExpedirParcial";
import { InspecaoModal } from "./Inspecao";
import { ProcessosModal } from "./Processos";
import type { Ctx, ModalState } from "./tipos";

/**
 * O despachante de modais: o `ModalState` corrente vira o diálogo aberto.
 *
 * Vive fora do Dashboard porque a navegação entre modais é uma teia, não uma
 * lista — quem abre o detalhe de uma OS expedida pode reabri-la, e a reabertura
 * emenda no modal de vínculo, de onde se chega a abrir etapa, expedir e por aí
 * fora. Uma tela que monte só o detalhe fica com o fluxo a morrer no meio, sem
 * erro nenhum: o modal seguinte simplesmente não renderiza. Montando todos de
 * uma vez, qualquer tela que abra um deles herda a teia inteira.
 *
 * Ficam de fora os dois que dependem da seleção de cargas da tabela do
 * Dashboard (`passoLote`, `encerrarLote`): são dele, e não da OS.
 */
export function Modais({ ctx, modal }: { ctx: Ctx; modal: ModalState }) {
  if (!modal) return null;

  return (
    <>
      {modal.tipo === "cad" && <CriarOSModal ctx={ctx} />}
      {modal.tipo === "inspecao" && <InspecaoModal ctx={ctx} />}
      {modal.tipo === "buscar" && <BuscarOSModal ctx={ctx} />}
      {modal.tipo === "processos" && <ProcessosModal ctx={ctx} />}
      {modal.tipo === "livres" && <CargasLivresModal ctx={ctx} />}
      {modal.tipo === "det" && <DetalheOSModal ctx={ctx} osId={modal.osId} />}
      {modal.tipo === "vinc" && (
        <VincularModal ctx={ctx} osId={modal.osId} preSel={modal.preSel} />
      )}
      {modal.tipo === "passo" && <PassoModal ctx={ctx} osId={modal.osId} />}
      {modal.tipo === "exp" && <ExpedirModal ctx={ctx} osId={modal.osId} />}
      {modal.tipo === "expParcial" && <ExpedirParcialModal ctx={ctx} osId={modal.osId} />}
      {modal.tipo === "avaliarExp" && <AvaliarExpedicaoModal ctx={ctx} osId={modal.osId} />}
      {modal.tipo === "desidro" && <DesidrogenizarModal ctx={ctx} osId={modal.osId} />}
      {modal.tipo === "desidroPainel" && <DesidroPainelModal ctx={ctx} />}
      {modal.tipo === "cancel" && <CancelarModal ctx={ctx} osId={modal.osId} />}
      {modal.tipo === "entregar" && (
        <EntregarModal ctx={ctx} osId={modal.osId} deDetalhe={modal.deDetalhe} />
      )}
    </>
  );
}
