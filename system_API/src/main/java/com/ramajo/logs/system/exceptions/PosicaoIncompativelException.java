package com.ramajo.logs.system.exceptions;

import com.ramajo.logs.system.enums.Posicao;

/**
 * A posição (setor) não bate entre os participantes do trabalho: uma carga de
 * outro setor sendo vinculada à OS, ou um processo que não roda no setor da
 * OS. Requisição semanticamente inválida -> HTTP 422.
 */
public class PosicaoIncompativelException extends DominioException {

    /** Vínculo de carga: a carga roda em outro setor. */
    public PosicaoIncompativelException(Long cargaId, Posicao cargaPosicao,
                                        Long osId, Posicao osPosicao) {
        super("POSICAO_INCOMPATIVEL",
                "Carga " + cargaId + " é da posição " + cargaPosicao
                        + " e a OS " + osId + " roda em " + osPosicao + ".");
    }

    /** Abertura de passo: o processo existe, mas não naquele setor. */
    public PosicaoIncompativelException(Long processoId, String descricao, Posicao osPosicao) {
        super("POSICAO_INCOMPATIVEL",
                "Processo " + processoId + " (" + descricao + ") não é executado em "
                        + osPosicao + ".");
    }

    /**
     * Cadastro incompleto: o processo não tem posição nenhuma, então não roda
     * em setor algum. Código próprio para o front distinguir isto de "setor
     * errado" — a ação corretiva é outra (PUT /api/processos/{id}/posicoes).
     */
    public PosicaoIncompativelException(Long processoId, String descricao) {
        super("PROCESSO_SEM_POSICAO",
                "Processo " + processoId + " (" + descricao + ") não tem posições"
                        + " cadastradas; configure-as antes de usá-lo.");
    }
}
