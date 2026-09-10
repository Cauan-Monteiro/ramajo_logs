-- =====================================================================
-- V13 — A expedição total passa a lembrar-se do que soltou.
--
-- `finalizar` devolve todas as cargas da OS ao pool (ordem_atual_id =
-- NULL) e não deixa registo do vínculo desfeito. Quem reabre a OS a
-- seguir fica com o modal de vínculo em branco à frente de todas as
-- cargas livres do setor, sem pista nenhuma de quais eram as dela — e o
-- sistema também não tem: `logs` diz que a carga passou por lá, não que
-- estava lá no instante da expedição.
--
-- Esta tabela é esse registo, e nada mais. É ESTADO EFÉMERO, não
-- histórico: a linha nasce na expedição total e morre na reabertura, que
-- a consome. Quem quer histórico de produção continua a ler `logs` e
-- `lotes` — nada aqui conta para relatório nem para auditoria.
--
-- Sem trigger, ao contrário de carga_ordens_acopladas (V12): ali havia
-- uma invariante física a proteger (carona sem titular), aqui a tabela é
-- uma sugestão que o operador confirma ou descarta. Sem índice além da
-- PK: o único acesso é "as cargas desta OS", que é o prefixo dela.
-- =====================================================================

CREATE TABLE os_cargas_expedidas (
    ordem_servico_id BIGINT NOT NULL REFERENCES ordens_servico (id),
    carga_id         BIGINT NOT NULL REFERENCES cargas (id),
    PRIMARY KEY (ordem_servico_id, carga_id)
);
