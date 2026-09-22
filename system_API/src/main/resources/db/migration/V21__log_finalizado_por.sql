-- Quem FECHA a etapa, ao lado de quem a abriu.
--
-- Até aqui o passo só registava o responsável da abertura (responsavel_id) e a
-- hora do fecho. Quem carregou no botão para fechar não ficava em lado nenhum —
-- a auditoria mostrava "Fechou etapa" sem autor, e a planilha o mesmo. O lote
-- já resolvia isto desde a V2 (finalizado_por_id); o passo passa a seguir o
-- mesmo molde.
--
-- NULLABLE de propósito: os passos fechados antes desta migration não têm autor
-- e não se inventa um. "Não registado" é a verdade sobre eles.
ALTER TABLE logs
    ADD COLUMN finalizado_por_id BIGINT REFERENCES operadores (id);

-- Autor sem fecho seria um passo assinado por alguém que não o fechou. O
-- inverso — fecho sem autor — continua legítimo: é o histórico antigo.
ALTER TABLE logs
    ADD CONSTRAINT ck_logs_finalizado_por
        CHECK (finalizado_por_id IS NULL OR finalizado_em IS NOT NULL);

-- Para a FK e para a contagem de OperadorService.excluir ("este operador
-- fechou passos"), que varre por este id.
CREATE INDEX ix_logs_finalizado_por ON logs (finalizado_por_id);

-- ------------------------------------------------- imutabilidade parcial (log)
-- Mesma função da V1, com uma regra a mais: o autor do fecho é carimbo único,
-- pela mesma razão que finalizado_em já era. Reescrever depois seria atribuir a
-- outra pessoa uma assinatura que ela não deu.
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

    IF OLD.finalizado_por_id IS NOT NULL
    AND NEW.finalizado_por_id IS DISTINCT FROM OLD.finalizado_por_id THEN
        RAISE EXCEPTION 'logs: finalizado_por_id já definido, não pode mudar (id=%)', OLD.id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
