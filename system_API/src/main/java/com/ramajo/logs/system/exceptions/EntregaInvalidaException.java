package com.ramajo.logs.system.exceptions;

/**
 * Tentativa de carimbar a entrega de uma OS que não está no estado que a
 * entrega sucede.
 *
 * Entregar é o passo DEPOIS da expedição: só faz sentido sobre uma OS
 * FINALIZADA. Uma OS ainda em produção não tem o que entregar, uma cancelada
 * não chegou a sair, e uma já entregue não sai duas vezes — o carimbo descreve
 * um evento único, e reescrevê-lo apagaria a hora e o nome de quem entregou.
 *
 * Conflito de estado -> HTTP 409, como ReaberturaInvalidaException.
 */
public class EntregaInvalidaException extends DominioException {

    /** Ainda em produção: não há o que entregar. */
    public static EntregaInvalidaException naoFinalizada(Long osId) {
        return new EntregaInvalidaException("ENTREGA_OS_NAO_FINALIZADA",
                "Ordem de serviço " + osId + " não foi expedida; só OS expedida pode ser entregue.");
    }

    /** Cancelada é um fim, não uma saída. */
    public static EntregaInvalidaException cancelada(Long osId) {
        return new EntregaInvalidaException("ENTREGA_OS_CANCELADA",
                "Ordem de serviço " + osId + " foi cancelada; não há entrega a registar.");
    }

    /** O carimbo é único: reescrevê-lo perderia a assinatura da primeira. */
    public static EntregaInvalidaException jaEntregue(Long osId) {
        return new EntregaInvalidaException("ENTREGA_OS_JA_ENTREGUE",
                "Ordem de serviço " + osId + " já está marcada como entregue.");
    }

    private EntregaInvalidaException(String codigo, String mensagem) {
        super(codigo, mensagem);
    }
}
