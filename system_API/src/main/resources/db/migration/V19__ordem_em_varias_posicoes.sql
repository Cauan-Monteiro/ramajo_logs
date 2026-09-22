-- =====================================================================
-- V19 — A OS passa a rodar em UMA OU MAIS posições.
--
-- Até aqui `ordens_servico.posicao` era escalar e significava duas
-- coisas ao mesmo tempo: "onde esta OS está autorizada a rodar" e "onde
-- o trabalho dela acontece". A segunda nunca foi verdade sobre a OS —
-- é a CARGA que está fisicamente num setor, e o código já passou a ler
-- dali (ver OrdemServicoService: processoInicial, abrirLog,
-- validarAcoplada). Sobra para a OS apenas a primeira, e essa é que
-- deixa de ser singular: excepcionalmente uma mesma OS é processada em
-- dois setores ao mesmo tempo (ex.: PENDURADO + AUTOMATICA), com as
-- peças partidas entre os dois.
--
-- O que NÃO muda, e é o que mantém a exceção barata: a expedição, a
-- entrega, a avaliação e os lotes continuam a ser da OS INTEIRA. Dois
-- setores produzem em paralelo; a ordem fecha uma vez só.
-- =====================================================================

CREATE TABLE ordem_posicoes (
    ordem_servico_id BIGINT      NOT NULL REFERENCES ordens_servico (id),
    posicao          VARCHAR(20) NOT NULL,
    PRIMARY KEY (ordem_servico_id, posicao),
    CONSTRAINT ck_ordem_posicoes_posicao
        CHECK (posicao IN ('OXIDACAO', 'AUTOMATICA', 'PENDURADO'))
);

-- Acesso principal: "quais OSs rodam no setor X" — a aba do painel. A PK
-- não serve (posicao é a segunda coluna dela). A seletividade é baixa, só
-- três valores; o índice existe pelo index-only scan da lista de ids, não
-- por filtrar muito.
CREATE INDEX ix_ordem_posicoes_posicao ON ordem_posicoes (posicao, ordem_servico_id);

-- ------------------------------------------------------------- migração
-- Toda OS existente passa a ter exatamente a posição que já tinha. Sem
-- ON CONFLICT: a tabela nasce vazia e a origem é uma coluna NOT NULL, por
-- isso qualquer colisão aqui seria sinal de erro, não ruído a ignorar.
INSERT INTO ordem_posicoes (ordem_servico_id, posicao)
SELECT id, posicao FROM ordens_servico;

-- ------------------------------------------------------------- integridade
-- Uma OS sem setor nenhum não roda em lugar nenhum: é estado que o
-- domínio recusa (OrdemServicoService recusa remover a última posição),
-- e esta é a garantia real — a API não tem autenticação e um DELETE cru
-- chega até aqui.
--
-- CONSTRAINT TRIGGER DEFERRABLE, e não um trigger de linha comum: ao
-- gravar a coleção o Hibernate apaga TODAS as linhas da OS e reinsere as
-- que ficaram, então no meio da transação o conjunto fica legitimamente
-- vazio. Verificar a cada linha recusaria toda correção de posição; a
-- verificação no COMMIT vê só o estado final, que é o que a regra
-- descreve.
CREATE OR REPLACE FUNCTION ordem_posicoes_exige_uma()
RETURNS TRIGGER AS $$
DECLARE
    v_os    BIGINT := COALESCE(OLD.ordem_servico_id, NEW.ordem_servico_id);
    v_total INT;
BEGIN
    -- A própria OS pode ter sido apagada nesta transação (não acontece
    -- hoje — não há rota de apagar OS —, mas o trigger não pode explodir
    -- se um dia acontecer): sem ordem, não há regra a impor.
    IF NOT EXISTS (SELECT 1 FROM ordens_servico WHERE id = v_os) THEN
        RETURN NULL;
    END IF;

    SELECT count(*) INTO v_total
      FROM ordem_posicoes
     WHERE ordem_servico_id = v_os;

    IF v_total = 0 THEN
        RAISE EXCEPTION
            'ordem_posicoes: a OS % ficaria sem posição nenhuma', v_os;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_ordem_posicoes_exige_uma
    AFTER DELETE ON ordem_posicoes
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ordem_posicoes_exige_uma();

-- ------------------------------------------------------------- a coluna sai
-- Duas fontes da verdade para a mesma pergunta acabam por divergir — e a
-- coluna é NOT NULL, então mantê-la obrigaria todo INSERT de OS a
-- continuar a preenchê-la depois de a entidade deixar de a mapear.
-- `ck_os_posicao` (V1) cai junto com ela; explicitado por clareza.
ALTER TABLE ordens_servico DROP CONSTRAINT IF EXISTS ck_os_posicao;
ALTER TABLE ordens_servico DROP COLUMN posicao;
