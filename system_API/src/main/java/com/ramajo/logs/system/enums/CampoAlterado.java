package com.ramajo.logs.system.enums;

/**
 * O que uma correção de OS pode ter mudado. `CARGAS` não é campo da OS: é o
 * efeito da troca de POSICAO (as cargas do setor antigo saem, as do novo
 * entram), registrado à parte para o histórico dizer quais foram.
 */
public enum CampoAlterado {
    ID_EXTERNO,
    CLIENTE,
    POSICAO,
    CARGAS
}
