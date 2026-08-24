-- =====================================================================
-- V3 — Processo inicial ('Desengraxante(Inicio)').
-- Toda OS nasce com um passo aberto neste processo, uma linha por carga
-- vinculada. Por ser regra de negócio (e não cadastro do operador), ele é
-- semeado aqui e referenciado por configuração — app.processo-inicial-id.
-- =====================================================================

-- processos.id é GENERATED ALWAYS AS IDENTITY, então o id precisa entrar com
-- OVERRIDING SYSTEM VALUE. O 0 fica FORA da faixa da identity (que começa em
-- 1), logo nunca colide com um processo cadastrado pela tela e a sequence
-- não precisa ser ajustada.
INSERT INTO processos (id, descricao, etapa)
OVERRIDING SYSTEM VALUE
VALUES (0, 'Desengraxante(Inicio)', 'PRE_TRATAMENTO');

-- Sem tag_id: este passo é aberto pelo sistema, não pelo leitor. ux_processos_tag
-- é índice parcial (WHERE tag_id IS NOT NULL), então o NULL não conflita.

-- Roda em qualquer setor — uma OS de qualquer posição precisa deste passo.
INSERT INTO processo_posicoes (processo_id, posicao) VALUES
    (0, 'OXIDACAO'),
    (0, 'AUTOMATICA'),
    (0, 'PENDURADO');
