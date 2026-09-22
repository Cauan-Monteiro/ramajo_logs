package com.ramajo.logs.system.exceptions;

import com.ramajo.logs.system.enums.Posicao;
import java.util.List;
import java.util.stream.Collectors;

/**
 * O Nº do ERP já é de outra ordem de serviço NAQUELE SETOR. Desde a V20 o
 * número só é único por posição — a mesma ordem partida entre dois setores usa
 * o mesmo Nº nos dois —, e por isso a mensagem diz onde está a colisão.
 *
 * Só a correção (ADMIN) chega aqui: na criação, Nº repetido noutro setor é caso
 * legítimo e Nº repetido no mesmo setor vira vínculo, não erro. Conflito de
 * estado -> HTTP 409.
 */
public class OrdemIdExternoExistente extends DominioException {

    public OrdemIdExternoExistente(Long idExterno, List<Posicao> posicoesDaOutra) {
        super("OS_EXISTENTE",
                "O Nº " + idExterno + " já é de outra ordem de serviço em "
                        + posicoesDaOutra.stream().map(Enum::name)
                                .collect(Collectors.joining(", "))
                        + ".");
    }
}
