package com.ramajo.logs.system.repositories;

import com.ramajo.logs.system.entities.OrdemServico;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrdemServicoRepository extends JpaRepository<OrdemServico, Long> {
    List<OrdemServico> findByEmProcessoTrue();
    Optional<OrdemServico> findByIdExterno(Long idExterno);
}
