package com.ramajo.logs.system.repositories;

import com.ramajo.logs.system.entities.Carga;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CargaRepository extends JpaRepository<Carga, Long> {
    List<Carga> findByAtivoTrueAndOrdemAtualIsNull();   // disponíveis
    Optional<Carga> findByTagId(String tagId);
}
