package com.ramajo.logs.system.repositories;

import com.ramajo.logs.system.entities.OrdemAlteracao;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrdemAlteracaoRepository extends JpaRepository<OrdemAlteracao, Long> {

    // O operador já vem junto: o DTO e a planilha leem o nome dele em cada
    // linha, e sem o fetch seria uma query por alteração.
    @Query("""
            select a from OrdemAlteracao a
              join fetch a.alteradaPor
             where a.ordemServico.id = :osId
             order by a.alteradaEm asc, a.id asc
            """)
    List<OrdemAlteracao> buscarDaOrdem(@Param("osId") Long osId);

    // Referências a um operador; ver countByResponsavelId em LogRepository.
    long countByAlteradaPorId(Long operadorId);
}
