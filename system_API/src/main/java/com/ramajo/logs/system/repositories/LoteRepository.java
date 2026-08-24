package com.ramajo.logs.system.repositories;

import com.ramajo.logs.system.entities.Lote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LoteRepository extends JpaRepository<Lote, Long> {
    // Enquanto a OS está em processo existe exatamente um (ux_lotes_os_aberto).
    Optional<Lote> findByOrdemServicoIdAndFinalizadoEmIsNull(Long ordemServicoId);

    List<Lote> findByOrdemServicoIdOrderByNumeroAsc(Long ordemServicoId);
}
