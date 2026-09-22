package com.ramajo.logs.system.exceptions;

import com.ramajo.logs.system.enums.Posicao;

/**
 * Tentou-se mudar o SETOR de uma carga que está vinculada a uma OS.
 *
 * O setor da carga é onde o trabalho dela acontece: é ele que escolhe o
 * processo inicial no vínculo e que autoriza o processo de cada passo. Mudá-lo
 * com a carga em uso moveria o chão debaixo de um passo já aberto — o passo
 * continuaria a apontar para um processo que não roda no setor novo, e nenhuma
 * validação voltaria a correr para o apanhar.
 *
 * Conflito de ESTADO (a carga está ocupada), não pedido malformado -> HTTP 409.
 * Mesma família de CargaIndisponivelException.
 *
 * A saída é a de sempre: liberar a carga ("Encerrar etapas") e só então
 * corrigir o cadastro dela.
 */
public class CargaEmUsoException extends DominioException {

    public CargaEmUsoException(Long cargaId, Long ordemAtualId,
                               Posicao atual, Posicao nova) {
        super("CARGA_EM_USO",
                "Carga " + cargaId + " está vinculada à OS " + ordemAtualId
                        + "; libere-a antes de mudar o setor de " + atual
                        + " para " + nova + ".");
    }
}
