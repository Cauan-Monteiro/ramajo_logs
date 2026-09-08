-- =====================================================================
-- V10 — Desidrogenização: cadastro, temperatura do forno e aplicação na OS.
--
-- Etapa de alívio de hidrogênio que roda DEPOIS do tratamento, em algumas OSs
-- e não em outras. Já existia desenhada na tela (o botão "Desidrogenizar" da
-- Inspeção Final, desabilitado por não haver API); esta migration é o modelo
-- que faltava.
--
-- Três tabelas porque são três ciclos de vida distintos: o catálogo (o que a
-- fábrica sabe fazer), a temperatura (parâmetro do forno, um só para todas) e
-- a aplicação (o que aconteceu numa OS, e quando).
-- =====================================================================

-- ------------------------------------------------------------- catálogo
-- `duracao_min` em MINUTOS inteiros, não interval: é assim que o operador
-- pensa ("2 horas" = 120), é o que o formulário digita, e é o que a coluna
-- gerada de ordem_desidrogenizacoes consome via make_interval.
CREATE TABLE desidrogenizacoes (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nome        VARCHAR(80) NOT NULL,
    duracao_min INT         NOT NULL,
    observacao  TEXT,
    -- Soft-delete, como em Processo (V8) e Carga: arquivar tira o cadastro das
    -- listas de escolha sem apagar a linha que o histórico referencia.
    ativo       BOOLEAN     NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_desidro_duracao CHECK (duracao_min > 0 AND duracao_min <= 10080)
);

-- Nome único entre os ATIVOS (índice parcial, mesmo truque de ux_os_id_externo):
-- dois cadastros vivos com o mesmo nome deixariam a tela ambígua, mas um nome
-- arquivado não pode barrar o recadastro do mesmo processo.
CREATE UNIQUE INDEX ux_desidro_nome ON desidrogenizacoes (LOWER(nome)) WHERE ativo;

-- --------------------------------------------------------- temperatura
-- Igual para TODA desidrogenização — é parâmetro do forno, não do cadastro.
-- Por isso não é coluna de `desidrogenizacoes`: lá, N linhas poderiam divergir.
-- Linha única garantida pelo CHECK na PK, no mesmo espírito da PK-enum de
-- posicao_processo_inicial (V7): quem impede a segunda configuração
-- concorrente é o banco, não o service.
CREATE TABLE config_desidrogenizacao (
    id          SMALLINT PRIMARY KEY,
    temperatura NUMERIC(5,1) NOT NULL,
    CONSTRAINT ck_cfg_desidro_linha_unica CHECK (id = 1),
    CONSTRAINT ck_cfg_desidro_temp CHECK (temperatura > 0 AND temperatura <= 999)
);

-- Piso para o banco nascer utilizável (a linha 1 tem que existir, o service
-- só faz UPDATE nela). O valor de verdade o ADMIN ajusta pela tela.
INSERT INTO config_desidrogenizacao (id, temperatura) VALUES (1, 200.0)
ON CONFLICT (id) DO NOTHING;

-- ----------------------------------------------------------- aplicação
-- Filha da OS, no formato de `lotes` (V2): carimbo de início vindo do relógio
-- do POSTGRES, não do processo Java — imune a relógio dessincronizado.
--
-- `duracao_min` e `temperatura` são SNAPSHOT, copiados no momento da
-- aplicação. Editar o cadastro ou a temperatura do forno amanhã não pode
-- reescrever o que já aconteceu ontem.
CREATE TABLE ordem_desidrogenizacoes (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ordem_servico_id    BIGINT       NOT NULL REFERENCES ordens_servico (id),
    desidrogenizacao_id BIGINT       NOT NULL REFERENCES desidrogenizacoes (id),
    aplicada_por_id     BIGINT       REFERENCES operadores (id),
    duracao_min         INT          NOT NULL,
    temperatura         NUMERIC(5,1) NOT NULL,
    iniciada_em         TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    -- O fim é o início + a duração, preenchido pelo BANCO (trigger abaixo).
    -- Tem que ser no banco: `iniciada_em` só existe depois do INSERT
    -- (clock_timestamp), então o service não teria o que somar antes de gravar.
    -- NOT NULL vale mesmo com o valor vindo da trigger — constraint é checada
    -- DEPOIS dos BEFORE triggers.
    finalizada_em       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_odesidro_duracao CHECK (duracao_min > 0 AND duracao_min <= 10080),
    CONSTRAINT ck_odesidro_temp    CHECK (temperatura > 0 AND temperatura <= 999),
    -- Espelha ck_lotes_janela: a janela nunca fecha antes de abrir.
    CONSTRAINT ck_odesidro_janela  CHECK (finalizada_em >= iniciada_em)
);

-- Trigger, e não coluna GENERATED: `timestamptz + interval` é STABLE, não
-- IMMUTABLE (somar interval depende do TimeZone da sessão), e o Postgres recusa
-- expressão não-imutável em GENERATED ALWAYS. A trigger não tem essa
-- restrição, e o efeito é o mesmo — o valor é calculado uma vez, na gravação,
-- e fica congelado.
CREATE OR REPLACE FUNCTION odesidro_calcula_fim()
RETURNS TRIGGER AS $$
BEGIN
    NEW.finalizada_em := NEW.iniciada_em + make_interval(mins => NEW.duracao_min);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Também no UPDATE das duas colunas de origem: se um dia alguém corrigir o
-- horário de início, o término acompanha em vez de ficar mentindo.
CREATE TRIGGER trg_odesidro_fim
    BEFORE INSERT OR UPDATE OF iniciada_em, duracao_min ON ordem_desidrogenizacoes
    FOR EACH ROW EXECUTE FUNCTION odesidro_calcula_fim();

-- Serve à listagem do detalhe da OS, que é sempre "as desta OS, em ordem".
CREATE INDEX ix_odesidro_os ON ordem_desidrogenizacoes (ordem_servico_id, iniciada_em);
