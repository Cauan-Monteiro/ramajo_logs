package com.ramajo.logs.system.repositories;

import com.ramajo.logs.system.entities.Lote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LoteRepository extends JpaRepository<Lote, Long> {
    // Enquanto a OS está em processo existe exatamente um (ux_lotes_os_aberto).
    Optional<Lote> findByOrdemServicoIdAndFinalizadoEmIsNull(Long ordemServicoId);

    List<Lote> findByOrdemServicoIdOrderByNumeroAsc(Long ordemServicoId);

    // Referências a um operador; ver countByResponsavelId em LogRepository.
    long countByFinalizadoPorId(Long operadorId);

    // Para a planilha: o operador que fechou cada lote já vem junto.
    // LEFT porque o lote aberto ainda não tem quem o finalizou.
    @Query("""
            select lo from Lote lo
              left join fetch lo.finalizadoPor
             where lo.ordemServico.id = :osId
             order by lo.numero asc
            """)
    List<Lote> buscarParaRelatorio(@Param("osId") Long osId);

    // O mesmo para VÁRIAS ordens, no molde de
    // LogRepository.buscarParaRelatorioDeOrdens: alimenta o lote de detalhes da
    // Visão Geral, que precisa dos lotes de dezenas de OS de uma vez. O chamador
    // parte a lista de ids em blocos; o Postgres não aceita um `in` sem fim.
    //
    // Ordena por OS antes do número para o agrupamento do lado de lá sair numa
    // passagem só, sem reordenar cada grupo.
    @Query("""
            select lo from Lote lo
              left join fetch lo.finalizadoPor
             where lo.ordemServico.id in :ids
             order by lo.ordemServico.id asc, lo.numero asc
            """)
    List<Lote> buscarDeOrdens(@Param("ids") Collection<Long> ids);
}
