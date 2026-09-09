-- =====================================================================
-- V12 — O acoplamento sobe do passo para a CARGA.
--
-- A V11 pendurava as OS caronas no log (`log_ordens_acopladas`), e a
-- composição morria junto com o passo. Só que as peças da carona não
-- saem do tanque quando a etapa fecha: continuam lá, e a etapa seguinte
-- é dos mesmos donos. Marcar de novo a cada passo era trabalho que só
-- existia por causa do modelo.
--
-- Aqui a composição dura o que a carga durar na OS: é declarada uma vez
-- (na criação da OS ou ao vincular a carga) e cada passo novo daquela
-- carga nasce com ela. `log_ordens_acopladas` CONTINUA a ser escrita e
-- continua a ser a fonte de todo relatório — muda quem a preenche, não
-- o que ela significa. Um evento físico, uma linha, uma contagem.
-- =====================================================================

CREATE TABLE carga_ordens_acopladas (
    carga_id         BIGINT NOT NULL REFERENCES cargas (id),
    ordem_servico_id BIGINT NOT NULL REFERENCES ordens_servico (id),
    PRIMARY KEY (carga_id, ordem_servico_id)
);

-- Acesso principal: "em que carga esta OS pega carona" — a pergunta do
-- detalhe da OS e da recusa "peças estão numa carga só". Não é a PK.
CREATE INDEX ix_coa_ordem ON carga_ordens_acopladas (ordem_servico_id);

-- ------------------------------------------------------------- integridade
-- Mesmo espírito de trg_loa_protege: o service antecipa as duas recusas
-- abaixo com erro legível, mas quem garante é isto — a API não tem
-- autenticação e um POST cru chega até aqui.
CREATE OR REPLACE FUNCTION coa_protege()
RETURNS TRIGGER AS $$
DECLARE
    v_titular BIGINT;
BEGIN
    SELECT c.ordem_atual_id INTO v_titular
      FROM cargas c
     WHERE c.id = NEW.carga_id;

    -- Carona pressupõe alguém a dar boleia: carga livre não tem titular,
    -- e a linha ficaria órfã à espera do próximo vínculo.
    IF v_titular IS NULL THEN
        RAISE EXCEPTION
            'carga_ordens_acopladas: carga % não está vinculada a OS nenhuma',
            NEW.carga_id;
    END IF;

    IF NEW.ordem_servico_id = v_titular THEN
        RAISE EXCEPTION
            'carga_ordens_acopladas: OS % é a titular da carga %, não pode acoplar-se a si mesma',
            NEW.ordem_servico_id, NEW.carga_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Sem DELETE: desacoplar é operação normal (o × do detalhe da OS) e a
-- liberação da carga limpa a composição inteira. Ao contrário do passo,
-- a carga não é histórico — é estado.
CREATE TRIGGER trg_coa_protege
    BEFORE INSERT OR UPDATE ON carga_ordens_acopladas
    FOR EACH ROW EXECUTE FUNCTION coa_protege();

-- ------------------------------------------------------------- migração
-- O que está dentro dos tanques AGORA não pode ser esquecido no deploy:
-- todo passo aberto com carona vira composição da carga que o executa.
-- Passo fechado fica onde está — é histórico, e a carga já o largou.
-- O join a `cargas` não é decoração: é a trigger acima escrita como
-- filtro. Uma linha que ela recusaria derrubaria a migration inteira.
INSERT INTO carga_ordens_acopladas (carga_id, ordem_servico_id)
SELECT l.carga_id, loa.ordem_servico_id
  FROM log_ordens_acopladas loa
  JOIN logs l   ON l.id = loa.log_id
  JOIN cargas c ON c.id = l.carga_id
 WHERE l.finalizado_em IS NULL
   AND NOT l.cancelado
   AND c.ordem_atual_id IS NOT NULL
   AND c.ordem_atual_id <> loa.ordem_servico_id
ON CONFLICT DO NOTHING;
