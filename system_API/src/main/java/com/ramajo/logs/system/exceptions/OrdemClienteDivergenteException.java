package com.ramajo.logs.system.exceptions;

import com.ramajo.logs.system.enums.Posicao;
import java.util.List;
import java.util.stream.Collectors;

/**
 * O Nº do ERP já é de uma ordem de OUTRO cliente.
 *
 * O número identifica uma ordem, e uma ordem tem um dono. A V20 permitiu que
 * ela exista como duas OS — uma por setor, com as peças partidas —, mas isso
 * são dois pedaços da MESMA ordem, não duas ordens que calharam no mesmo
 * número. Por isso a recusa não olha o setor: qualquer homônima de outro dono
 * derruba a operação, seja ela criar aqui, vincular, ou corrigir o Nº/cliente.
 *
 * Na prática dispara quando a tela concluiu "Nº livre" com uma lista
 * desatualizada e pediu o cliente ao operador. Sem ela, as cargas entrariam
 * caladas na ordem de outro cliente e a resposta confirmaria o engano.
 *
 * Conflito de estado -> HTTP 409.
 */
public class OrdemClienteDivergenteException extends DominioException {

    public OrdemClienteDivergenteException(Long idExterno, List<Posicao> posicoes,
                                           String clienteDaOrdem, String clientePedido) {
        super("OS_CLIENTE_DIVERGENTE",
                "O Nº " + idExterno + " de "
                        + posicoes.stream().map(Enum::name).collect(Collectors.joining(", "))
                        + " é do cliente " + clienteDaOrdem + ", não " + clientePedido + ".");
    }
}
