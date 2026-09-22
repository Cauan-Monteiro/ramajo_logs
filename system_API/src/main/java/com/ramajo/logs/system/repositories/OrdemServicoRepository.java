package com.ramajo.logs.system.repositories;

import com.ramajo.logs.system.entities.OrdemServico;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrdemServicoRepository extends JpaRepository<OrdemServico, Long> {
    List<OrdemServico> findByEmProcessoTrue();

    // Lista, e não Optional: desde a V20 o Nº do ERP é único POR POSIÇÃO, então
    // o mesmo número pode devolver uma OS por setor. Quem escolhe entre elas é
    // o service, filtrando por rodaEm() — ver criar() e corrigir().
    List<OrdemServico> findAllByIdExterno(Long idExterno);

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

    /**
     * Todas as OSs para a listagem da tela, com as LAZY que o OrdemResumoDTO lê
     * já resolvidas: o cliente (que o resumo sempre mostra) e quem entregou. Sem
     * o fetch seria uma query por linha — e esta rota devolve o histórico
     * inteiro, não só as ordens em processo.
     */
    @Query("""
            select os from OrdemServico os
              join fetch os.cliente
              left join fetch os.entreguePor
            """)
    List<OrdemServico> listarParaResumo();

    /**
     * Uma OS com os @ManyToOne que o OrdemDetalheDTO lê já resolvidos. Sem isto
     * o findById puro dispara uma query por nome (cliente e os três operadores)
     * na montagem do DTO — e o detalhe é pedido uma vez por OS ao abrir a Visão
     * Geral.
     *
     * As COLEÇÕES (lotes, cargas, desidrogenizações, posições) ficam de fora de
     * propósito: são quatro, e duas delas são List — duas bags no mesmo fetch
     * levantam MultipleBagFetchException no boot. Quem as resolve é o
     * @BatchSize delas na entidade, que já as traz em bloco.
     */
    @Query("""
            select os from OrdemServico os
              join fetch os.cliente
              left join fetch os.iniciadaPor
              left join fetch os.finalizadaPor
              left join fetch os.entreguePor
             where os.id = :id
            """)
    Optional<OrdemServico> buscarParaDetalhe(@Param("id") Long id);

    /**
     * Várias OSs com quem as abriu e quem as fechou já resolvidos — o que o
     * OrdemAuditoriaDTO lê e o OrdemResumoDTO não carrega.
     *
     * Sem `cliente` no fetch de propósito: quem chama esta consulta já tem o
     * resumo de cada OS, que traz o cliente. O chamador parte a lista de ids em
     * blocos, como em PlanilhaPeriodoService.
     */
    @Query("""
            select os from OrdemServico os
              left join fetch os.iniciadaPor
              left join fetch os.finalizadaPor
             where os.id in :ids
            """)
    List<OrdemServico> buscarParaAuditoria(@Param("ids") Collection<Long> ids);
}
