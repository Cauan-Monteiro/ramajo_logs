package com.ramajo.logs.system.repositories;

import com.ramajo.logs.system.entities.OrdemServico;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrdemServicoRepository extends JpaRepository<OrdemServico, Long> {
    List<OrdemServico> findByEmProcessoTrue();
    Optional<OrdemServico> findByIdExterno(Long idExterno);

    // Referências a um operador nas duas pontas da OS (quem abriu, quem fechou);
    // ver countByResponsavelId em LogRepository. Os dois parâmetros são o mesmo
    // id — o Spring Data exige um por termo do OR.
    long countByIniciadaPorIdOrFinalizadaPorId(Long iniciadaPorId, Long finalizadaPorId);

    // As OSs ABERTAS na janela, com as relações LAZY que o relatório lê já
    // resolvidas — sem isto seriam 3N queries. iniciadaPor/finalizadaPor são
    // opcionais, daí o left join. O fim da janela é EXCLUSIVO (ver DataHoraBr).
    @Query("""
            select os from OrdemServico os
              join fetch os.cliente
              left join fetch os.iniciadaPor
              left join fetch os.finalizadaPor
             where os.iniciadaEm >= :inicio and os.iniciadaEm < :fim
             order by os.iniciadaEm asc, os.id asc
            """)
    List<OrdemServico> buscarParaRelatorioPorPeriodo(@Param("inicio") Instant inicio,
                                                     @Param("fim") Instant fim);
}
