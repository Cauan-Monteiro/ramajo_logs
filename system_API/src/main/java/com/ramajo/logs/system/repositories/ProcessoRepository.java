package com.ramajo.logs.system.repositories;

import com.ramajo.logs.system.entities.Processo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProcessoRepository extends JpaRepository<Processo, Long> {
    Optional<Processo> findByTagId(String tagId);
}
