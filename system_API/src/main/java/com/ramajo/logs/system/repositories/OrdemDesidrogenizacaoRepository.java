package com.ramajo.logs.system.repositories;

import com.ramajo.logs.system.entities.OrdemDesidrogenizacao;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface OrdemDesidrogenizacaoRepository
        extends JpaRepository<OrdemDesidrogenizacao, Long> {

    // O cadastro e o operador já vêm juntos: o DTO lê o nome dos dois, e sem o
    // fetch cada linha da lista dispararia sua própria query (N+1).
    // LEFT no operador porque aplicada_por_id é nullable.
    @Query("""
            select od from OrdemDesidrogenizacao od
              join fetch od.desidrogenizacao
              left join fetch od.aplicadaPor
             where od.ordemServico.id = :osId
             order by od.iniciadaEm asc, od.id asc
            """)
    List<OrdemDesidrogenizacao> buscarDaOrdem(@Param("osId") Long osId);

    /**
     * As desidrogenizações das OS que ainda estão em produção — a fonte do
     * indicativo do Dashboard.
     *
     * Sem `now()` aqui de propósito: o conjunto já é limitado por "OS em
     * processo", e decidir o que ainda importa é política de apresentação. Se
     * o corte morasse nesta query, o servidor filtraria pelo relógio dele o que
     * a tela pinta com o relógio do navegador, e os dois discordariam na borda.
     */
    @Query("""
            select od from OrdemDesidrogenizacao od
              join fetch od.desidrogenizacao
              join fetch od.ordemServico os
             where os.emProcesso = true and os.cancelada = false
             order by od.finalizadaEm asc
            """)
    List<OrdemDesidrogenizacao> buscarDeOrdensEmProcesso();

    /**
     * As desidrogenizações de um conjunto de OS — a fonte do relatório por
     * período, que precisa delas para centenas de ordens de uma vez.
     *
     * Os mesmos fetches do `buscarDaOrdem`, pela mesma razão: a planilha lê o
     * nome do cadastro e o do operador em cada linha. O chamador parte a lista
     * de ids em blocos; o Postgres não aceita um `in` sem fim.
     */
    @Query("""
            select od from OrdemDesidrogenizacao od
              join fetch od.desidrogenizacao
              left join fetch od.aplicadaPor
             where od.ordemServico.id in :ids
             order by od.ordemServico.id asc, od.iniciadaEm asc, od.id asc
            """)
    List<OrdemDesidrogenizacao> buscarDeOrdens(@Param("ids") Collection<Long> ids);
}
