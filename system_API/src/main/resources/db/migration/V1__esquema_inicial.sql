-- =====================================================================
-- V1 — Esquema inicial (fonte da verdade do banco).
-- Use ddl-auto=validate: o Hibernate confere o mapeamento, o Flyway cria.
-- =====================================================================

-- ---------------------------------------------------------------- clientes
-- id vem de fora (sem IDENTITY).
CREATE TABLE clientes (
    id   BIGINT       PRIMARY KEY,
    nome VARCHAR(120) NOT NULL
);

-- --------------------------------------------------------------- operadores
CREATE TABLE operadores (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nome      VARCHAR(120) NOT NULL,
    tag_id    VARCHAR(64),
    permissao VARCHAR(20)  NOT NULL,
    ativo     BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_operadores_permissao
        CHECK (permissao IN ('ADMIN', 'FUNCIONARIO'))
);
-- Único quando presente: no Postgres, NULLs são distintos, então o índice
-- parcial permite muitos operadores sem tag e proíbe tag repetida.
CREATE UNIQUE INDEX ux_operadores_tag ON operadores (tag_id) WHERE tag_id IS NOT NULL;

-- ---------------------------------------------------------------- processos
CREATE TABLE processos (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    descricao VARCHAR(200) NOT NULL,
    etapa     VARCHAR(20)  NOT NULL,
    tag_id    VARCHAR(64),
    CONSTRAINT ck_processos_etapa
        CHECK (etapa IN ('PRE_TRATAMENTO', 'TRATAMENTO', 'POS_TRATAMENTO'))
);
CREATE UNIQUE INDEX ux_processos_tag ON processos (tag_id) WHERE tag_id IS NOT NULL;

-- Posições (setores) em que cada processo pode ocorrer — N por processo.
CREATE TABLE processo_posicoes (
    processo_id BIGINT      NOT NULL REFERENCES processos (id),
    posicao     VARCHAR(20) NOT NULL,
    PRIMARY KEY (processo_id, posicao),
    CONSTRAINT ck_processo_posicoes_posicao
        CHECK (posicao IN ('OXIDACAO', 'AUTOMATICA', 'PENDURADO'))
);

-- ------------------------------------------------------------ ordens_servico
CREATE TABLE ordens_servico (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id_externo        BIGINT,
    cliente_id        BIGINT      NOT NULL REFERENCES clientes (id),
    posicao           VARCHAR(20) NOT NULL,
    iniciada_em       TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    finalizada_em     TIMESTAMPTZ,
    cancelada         BOOLEAN     NOT NULL DEFAULT FALSE,
    em_processo       BOOLEAN     NOT NULL DEFAULT TRUE,
    iniciada_por_id   BIGINT      REFERENCES operadores (id),
    finalizada_por_id BIGINT      REFERENCES operadores (id),
    CONSTRAINT ck_os_posicao
        CHECK (posicao IN ('OXIDACAO', 'AUTOMATICA', 'PENDURADO')),
    CONSTRAINT ck_os_janela
        CHECK (finalizada_em IS NULL OR finalizada_em >= iniciada_em)
);
CREATE UNIQUE INDEX ux_os_id_externo ON ordens_servico (id_externo) WHERE id_externo IS NOT NULL;
CREATE INDEX ix_os_cliente     ON ordens_servico (cliente_id);
CREATE INDEX ix_os_em_processo ON ordens_servico (em_processo) WHERE em_processo;

-- -------------------------------------------------------------------- cargas
CREATE TABLE cargas (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nome           VARCHAR(120) NOT NULL,
    tipo           VARCHAR(20)  NOT NULL,
    posicao        VARCHAR(20)  NOT NULL,
    ativo          BOOLEAN      NOT NULL DEFAULT TRUE,
    ordem_atual_id BIGINT       REFERENCES ordens_servico (id),
    tag_id         VARCHAR(64),
    CONSTRAINT ck_cargas_tipo
        CHECK (tipo IN ('TAMBOR', 'TRAVE', 'CESTO')),
    CONSTRAINT ck_cargas_posicao
        CHECK (posicao IN ('OXIDACAO', 'AUTOMATICA', 'PENDURADO'))
);
CREATE UNIQUE INDEX ux_cargas_tag         ON cargas (tag_id)         WHERE tag_id IS NOT NULL;
CREATE INDEX        ix_cargas_ordem_atual ON cargas (ordem_atual_id);

-- ---------------------------------------------------------------------- logs
CREATE TABLE logs (
    id               UUID        PRIMARY KEY,
    ordem_servico_id BIGINT      NOT NULL REFERENCES ordens_servico (id),
    responsavel_id   BIGINT      NOT NULL REFERENCES operadores (id),
    carga_id         BIGINT      NOT NULL REFERENCES cargas (id),
    processo_id      BIGINT      NOT NULL REFERENCES processos (id),
    iniciado_em      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    finalizado_em    TIMESTAMPTZ,
    cancelado        BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_logs_janela
        CHECK (finalizado_em IS NULL OR finalizado_em >= iniciado_em)
);
-- Acesso principal: histórico de uma OS em ordem cronológica.
CREATE INDEX ix_logs_os_iniciado ON logs (ordem_servico_id, iniciado_em);
CREATE INDEX ix_logs_carga       ON logs (carga_id);

-- ------------------------------------------------- imutabilidade parcial (log)
-- Congela identidade e início do passo; permite fechar o intervalo e cancelar
-- uma única vez; proíbe DELETE. O código da app pode antecipar o erro, mas a
-- garantia real mora aqui.
CREATE OR REPLACE FUNCTION logs_protege_imutaveis()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'logs é append-only: DELETE não permitido (id=%)', OLD.id;
    END IF;

    IF NEW.id               IS DISTINCT FROM OLD.id
    OR NEW.ordem_servico_id IS DISTINCT FROM OLD.ordem_servico_id
    OR NEW.responsavel_id   IS DISTINCT FROM OLD.responsavel_id
    OR NEW.carga_id         IS DISTINCT FROM OLD.carga_id
    OR NEW.processo_id      IS DISTINCT FROM OLD.processo_id
    OR NEW.iniciado_em      IS DISTINCT FROM OLD.iniciado_em THEN
        RAISE EXCEPTION 'logs: colunas imutáveis não podem ser alteradas (id=%)', OLD.id;
    END IF;

    IF OLD.finalizado_em IS NOT NULL
    AND NEW.finalizado_em IS DISTINCT FROM OLD.finalizado_em THEN
        RAISE EXCEPTION 'logs: finalizado_em já definido, não pode mudar (id=%)', OLD.id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_logs_imutavel
    BEFORE UPDATE OR DELETE ON logs
    FOR EACH ROW EXECUTE FUNCTION logs_protege_imutaveis();
