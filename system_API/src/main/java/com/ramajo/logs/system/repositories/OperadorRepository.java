package com.ramajo.logs.system.repositories;

import com.ramajo.logs.system.entities.Operador;
import com.ramajo.logs.system.enums.Permissao;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OperadorRepository extends JpaRepository<Operador, Long> {
    Optional<Operador> findByTagId(String tagId);

    // Quantos administradores ainda podem entrar. É o que impede a tela de
    // Ajustes de se trancar por fora: sem ADMIN ativo ninguém mais a abre.
    long countByPermissaoAndAtivoTrue(Permissao permissao);
}
