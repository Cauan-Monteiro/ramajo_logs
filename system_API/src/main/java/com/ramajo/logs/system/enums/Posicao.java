package com.ramajo.logs.system.enums;

/**
 * Setor / linha física do parque. Conjunto fixo e pequeno, por isso enum.
 *
 * Um Processo pode ocorrer em VÁRIAS posições; uma Carga fica em UMA; uma
 * OrdemServico é autorizada a rodar em UMA OU MAIS (V19). O trabalho concreto
 * acontece sempre na posição da CARGA — é ela que está fisicamente no setor, e
 * é dela que o service lê para escolher o processo inicial, autorizar o
 * processo de um passo e validar um acoplamento.
 *
 * Se um dia um setor precisar de estado próprio (capacidade, disponibilidade,
 * manutenção), promova este enum a entidade (tabela `posicoes`).
 */
public enum Posicao {
    OXIDACAO,
    AUTOMATICA,
    PENDURADO
}
