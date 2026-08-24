package com.ramajo.logs.system.enums;

/**
 * Setor / linha física do parque. Conjunto fixo e pequeno, por isso enum.
 * Um Processo pode ocorrer em VÁRIAS posições; uma Carga fica em UMA;
 * uma OrdemServico roda por UMA.
 *
 * Se um dia um setor precisar de estado próprio (capacidade, disponibilidade,
 * manutenção), promova este enum a entidade (tabela `posicoes`).
 */
public enum Posicao {
    OXIDACAO,
    AUTOMATICA,
    PENDURADO
}
