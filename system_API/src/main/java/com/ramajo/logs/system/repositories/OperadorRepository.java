package com.ramajo.logs.system.repositories;

import com.ramajo.logs.system.entities.Operador;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OperadorRepository extends JpaRepository<Operador, Long> {
    Optional<Operador> findByTagId(String tagId);
}
