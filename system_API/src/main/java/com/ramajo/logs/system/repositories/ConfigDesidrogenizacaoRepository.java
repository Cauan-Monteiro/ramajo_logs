package com.ramajo.logs.system.repositories;

import com.ramajo.logs.system.entities.ConfigDesidrogenizacao;
import org.springframework.data.jpa.repository.JpaRepository;

/** Uma linha só (id = 1, semeada pela V10) — não há create nem delete. */
public interface ConfigDesidrogenizacaoRepository
        extends JpaRepository<ConfigDesidrogenizacao, Short> {
}
