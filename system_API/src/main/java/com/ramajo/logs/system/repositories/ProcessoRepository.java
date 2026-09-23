package com.ramajo.logs.system.repositories;

import com.ramajo.logs.system.entities.Processo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ProcessoRepository extends JpaRepository<Processo, Long> {
    Optional<Processo> findByTagId(String tagId);

    /**
     * Caminhos de LEITURA: `posicoes` é @ElementCollection LAZY e o DTO sempre
     * a copia, então sem o fetch cada processo listado custa um SELECT extra.
     * Os caminhos de escrita continuam no findById — lá a coleção é tocada
     * dentro da transação de qualquer forma.
     */
    @Query("select p from Processo p left join fetch p.posicoes")
    List<Processo> buscarTodosComPosicoes();

    @Query("select p from Processo p left join fetch p.posicoes where p.id = :id")
    Optional<Processo> buscarComPosicoes(Long id);
}
