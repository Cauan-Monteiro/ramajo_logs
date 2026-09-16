-- =====================================================================
-- V17 — Entrega da OS ao cliente.
--
-- A expedição tira as peças da produção; a entrega tira-as da casa. São
-- dois eventos, e até aqui só o primeiro tinha carimbo — "esta OS já
-- saiu?" não tinha onde ser respondida.
--
-- Colunas na própria OS, e não tabela à parte (como a avaliação): é um
-- par instante/autor, exactamente do feitio de finalizada_em/
-- finalizada_por_id, e não se repete — uma OS entrega-se uma vez.
--
-- Estado da expedição CORRENTE, não histórico: reabrir a OS limpa-o,
-- pelo mesmo motivo que apaga a avaliação (V15) — descrevia uma
-- produção que foi desfeita.
-- =====================================================================

ALTER TABLE ordens_servico
    ADD COLUMN entregue_em     TIMESTAMPTZ,
    ADD COLUMN entregue_por_id BIGINT REFERENCES operadores (id);

-- A entrega é o passo DEPOIS da expedição: não existe sem ela e nunca a
-- antecede. Vale mesmo na reabertura, que limpa os dois campos na mesma
-- transação em que zera finalizada_em.
ALTER TABLE ordens_servico
    ADD CONSTRAINT ck_os_entrega
        CHECK (entregue_em IS NULL
               OR (finalizada_em IS NOT NULL AND entregue_em >= finalizada_em)),
    ADD CONSTRAINT ck_os_entrega_autor
        CHECK ((entregue_em IS NULL) = (entregue_por_id IS NULL));

-- A aba de Entregas pergunta sempre a mesma coisa: "quais expedidas ainda
-- não saíram". Índice parcial porque a fila é pequena perto do histórico.
CREATE INDEX ix_ordens_aguardando_entrega ON ordens_servico (finalizada_em)
    WHERE entregue_em IS NULL AND NOT cancelada AND NOT em_processo;
