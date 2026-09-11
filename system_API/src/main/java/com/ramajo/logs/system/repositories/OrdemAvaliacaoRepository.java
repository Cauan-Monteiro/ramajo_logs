package com.ramajo.logs.system.repositories;

import com.ramajo.logs.system.entities.OrdemAvaliacao;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrdemAvaliacaoRepository extends JpaRepository<OrdemAvaliacao, Long> {

    // O avaliador já vem junto: o DTO e a planilha leem o nome dele.
    @Query("""
            select a from OrdemAvaliacao a
              join fetch a.avaliadaPor
             where a.ordemServico.id = :osId
            """)
    Optional<OrdemAvaliacao> buscarDaOrdem(@Param("osId") Long osId);

    void deleteByOrdemServicoId(Long osId);

    // Referências a um operador; ver countByAlteradaPorId em OrdemAlteracaoRepository.
    long countByAvaliadaPorId(Long operadorId);
}
