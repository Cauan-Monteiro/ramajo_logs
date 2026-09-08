-- =====================================================================
-- V11 — Acoplamento de OS numa etapa.
-- Peças de 2-3 Ordens de Serviço entram na MESMA carga física para um
-- processo pontual e saem juntas dele. O passo continua sendo UMA linha
-- em `logs` — a OS dona da carga (`cargas.ordem_atual_id`) é a titular e
-- fica onde sempre esteve; as demais penduram-se aqui.
--
-- Por que não clonar o log por OS: toda contagem do sistema deriva de
-- linhas de `logs` (cargas distintas, etapas concluídas, tempo por etapa).
-- Clonar inflaria a produção em 2-3x e obrigaria um DISTINCT em cada
-- agregado. Um evento físico, uma linha, uma contagem.
-- =====================================================================

CREATE TABLE log_ordens_acopladas (
    log_id           UUID   NOT NULL REFERENCES logs (id),
    ordem_servico_id BIGINT NOT NULL REFERENCES ordens_servico (id),
    PRIMARY KEY (log_id, ordem_servico_id)
);

-- Acesso principal: "quais passos esta OS pegou carona" — o histórico da
-- OS carona é lido por este índice, não pela PK.
CREATE INDEX ix_loa_ordem ON log_ordens_acopladas (ordem_servico_id);

-- ------------------------------------------------------------- integridade
-- Mesmo espírito de trg_logs_imutavel: o service antecipa as duas recusas
-- abaixo com erro legível, mas quem garante é isto — a API não tem
-- autenticação e um POST cru chega até aqui.
CREATE OR REPLACE FUNCTION loa_protege()
RETURNS TRIGGER AS $$
DECLARE
    v_titular BIGINT;
    v_fim     TIMESTAMPTZ;
BEGIN
    -- Em DELETE só existe OLD; nas demais operações a linha julgada é NEW.
    SELECT l.ordem_servico_id, l.finalizado_em INTO v_titular, v_fim
      FROM logs l
     WHERE l.id = COALESCE(NEW.log_id, OLD.log_id);

    -- Composição do passo é histórico: enquanto aberto o operador corrige a
    -- seleção; depois de fechado, congela junto com o próprio passo.
    IF v_fim IS NOT NULL THEN
        RAISE EXCEPTION
            'log_ordens_acopladas: passo % já finalizado, composição não muda',
            COALESCE(NEW.log_id, OLD.log_id);
    END IF;

    IF TG_OP <> 'DELETE' AND NEW.ordem_servico_id = v_titular THEN
        RAISE EXCEPTION
            'log_ordens_acopladas: OS % é a titular do passo %, não pode acoplar-se a si mesma',
            NEW.ordem_servico_id, NEW.log_id;
    END IF;

    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_loa_protege
    BEFORE INSERT OR UPDATE OR DELETE ON log_ordens_acopladas
    FOR EACH ROW EXECUTE FUNCTION loa_protege();
