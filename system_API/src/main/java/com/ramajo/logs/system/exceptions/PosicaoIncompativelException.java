package com.ramajo.logs.system.exceptions;

import com.ramajo.logs.system.enums.Posicao;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A posição (setor) não bate entre os participantes do trabalho: uma carga de
 * outro setor sendo vinculada à OS, ou um processo que não roda no setor da
 * OS. Requisição semanticamente inválida -> HTTP 422.
 */
public class PosicaoIncompativelException extends DominioException {

    /**
     * Vínculo de carga: o setor da carga não está entre os autorizados da OS.
     *
     * A lista chega já na ordem canônica (OrdemServico.getPosicoesOrdenadas) —
     * a mensagem é lida por gente, e "roda em PENDURADO, AUTOMATICA" só ajuda
     * se disser sempre a mesma coisa.
     */
    public PosicaoIncompativelException(Long cargaId, Posicao cargaPosicao,
                                        Long osId, List<Posicao> osPosicoes) {
        super("POSICAO_INCOMPATIVEL",
                "Carga " + cargaId + " é da posição " + cargaPosicao
                        + " e a OS " + osId + " roda em "
                        + osPosicoes.stream().map(Enum::name).collect(Collectors.joining(", "))
                        + ".");
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
