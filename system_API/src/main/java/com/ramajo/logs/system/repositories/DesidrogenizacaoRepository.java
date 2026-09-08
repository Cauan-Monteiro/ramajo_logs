package com.ramajo.logs.system.repositories;

import com.ramajo.logs.system.entities.Desidrogenizacao;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DesidrogenizacaoRepository extends JpaRepository<Desidrogenizacao, Long> {
    /** O que a tela oferece para aplicar. */
    List<Desidrogenizacao> findByAtivoTrueOrderByNomeAsc();

    /** Ativos e arquivados — a tela de cadastro alterna entre os dois. */
    List<Desidrogenizacao> findAllByOrderByNomeAsc();
}
