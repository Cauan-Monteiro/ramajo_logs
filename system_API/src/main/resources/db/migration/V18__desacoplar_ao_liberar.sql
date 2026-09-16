-- Encerrar etapas passa a desacoplar: soltar a carga de uma OS esvazia as
-- caronas dela (OrdemServicoService.soltarCarga). Com isso vale o invariante
-- "carga sem titular não carrega carona" — o mesmo que trg_coa_protege (V12)
-- já exigia de todo INSERT, e que a regra antiga violava em repouso.
--
-- As linhas órfãs que a regra antiga deixou não têm dono nem saída: a carga
-- está no pool, o × do detalhe da OS já não a alcança pelo lado da titular, e
-- a próxima OS a pegar a carga herdaria caronas que nunca escolheu.
DELETE FROM carga_ordens_acopladas coa
      USING cargas c
      WHERE c.id = coa.carga_id
        AND c.ordem_atual_id IS NULL;
