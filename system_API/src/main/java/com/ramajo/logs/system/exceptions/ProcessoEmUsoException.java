package com.ramajo.logs.system.exceptions;

import com.ramajo.logs.system.enums.Posicao;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Recusa de arquivamento: o processo ainda é a entrada de algum setor, ou é o
 * fallback global de app.processo-inicial-id. Arquivá-lo deixaria a criação de
 * OS daquele setor apontando para um processo que abrirLog recusa.
 *
 * Note que o histórico NÃO é motivo de recusa: o soft-delete existe justamente
 * para preservá-lo. O que se recusa é deixar uma CONFIGURAÇÃO viva apontando
 * para um processo arquivado.
 *
 * Conflito de estado -> HTTP 409.
 */
public class ProcessoEmUsoException extends DominioException {

    /** É a entrada configurada de um ou mais setores. */
    public ProcessoEmUsoException(Long processoId, String descricao, List<Posicao> posicoes) {
        super("PROCESSO_EM_USO",
                "Processo " + processoId + " (" + descricao + ") é a entrada de "
                        + posicoes.stream().map(Enum::name).collect(Collectors.joining(", "))
                        + ". Troque a entrada desse(s) setor(es) antes de arquivar.");
    }

    /** É o fallback global (app.processo-inicial-id). */
    public ProcessoEmUsoException(Long processoId, String descricao) {
        super("PROCESSO_EM_USO",
                "Processo " + processoId + " (" + descricao + ") é o processo inicial padrão"
                        + " do sistema e não pode ser arquivado.");
    }
}
