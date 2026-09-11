-- =====================================================================
-- V14 — Correção de OS pelo ADMIN, com histórico.
--
-- O operador que abre uma OS com Nº, cliente ou setor errado não tinha
-- como desfazer: não há rota de apagar OS. A correção passa a existir
-- (PUT /api/ordens/{id}), mas só vale junto com o registro dela — quem
-- lê o relatório tem de saber que o Nº de hoje não é o que foi digitado.
--
-- Uma linha por CAMPO alterado; as linhas de uma mesma correção partilham
-- `alterada_em` e `motivo`. Os valores são TEXTO já legível ("#12 ACME",
-- "OXIDACAO", "CG-01, CG-02"), e não FKs: o histórico descreve o que se
-- via no momento, e renomear o cliente amanhã não pode reescrevê-lo.
--
-- `CARGAS` só aparece quando a posição muda: trocar de setor solta as
-- cargas do setor antigo e vincula as do novo, e é isso que a linha
-- guarda (as de antes -> as de depois).
-- =====================================================================

CREATE TABLE ordem_alteracoes (
    id               BIGSERIAL    PRIMARY KEY,
    ordem_servico_id BIGINT       NOT NULL REFERENCES ordens_servico (id),
    campo            VARCHAR(20)  NOT NULL,
    valor_anterior   TEXT,
    valor_novo       TEXT,
    motivo           VARCHAR(500) NOT NULL,
    alterada_por_id  BIGINT       NOT NULL REFERENCES operadores (id),
    alterada_em      TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT ck_ordem_alteracoes_campo
        CHECK (campo IN ('ID_EXTERNO', 'CLIENTE', 'POSICAO', 'CARGAS'))
);

-- Acesso principal: o histórico de uma OS em ordem cronológica.
CREATE INDEX ix_ordem_alteracoes_os ON ordem_alteracoes (ordem_servico_id, alterada_em);

-- ------------------------------------------------------------- integridade
-- Histórico de correção que se corrige não é histórico. Mesmo espírito de
-- trg_logs_imutavel (V1), só que sem nada mutável: nem UPDATE nem DELETE.
CREATE OR REPLACE FUNCTION ordem_alteracoes_protege()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'ordem_alteracoes é append-only: % não permitido (id=%)',
        TG_OP, OLD.id;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ordem_alteracoes_imutavel
    BEFORE UPDATE OR DELETE ON ordem_alteracoes
    FOR EACH ROW EXECUTE FUNCTION ordem_alteracoes_protege();
