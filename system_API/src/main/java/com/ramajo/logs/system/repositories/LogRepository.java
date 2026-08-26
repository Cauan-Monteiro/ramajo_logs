package com.ramajo.logs.system.repositories;

import com.ramajo.logs.system.entities.Log;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LogRepository extends JpaRepository<Log, UUID> {
    List<Log> findByOrdemServicoIdOrderByIniciadoEmAscIdAsc(Long osId);

    // O passo em aberto da carga, se houver. ux_logs_carga_aberto garante que
    // é no máximo um, então Optional (e não List) é o tipo honesto. Devolve a
    // entidade, não um boolean, para o erro poder citar o passo que trava.
    Optional<Log> findByCargaIdAndFinalizadoEmIsNull(Long cargaId);

    // Passos ainda abertos de uma OS — usados para fechá-los junto com ela.
    List<Log> findByOrdemServicoIdAndFinalizadoEmIsNull(Long osId);

    // Mesma ordem do histórico, mas com as relações LAZY já resolvidas: a
    // planilha lê carga/processo/responsável de TODOS os passos, o que daria
    // 3N queries no lazy loading. Aqui é uma só.
    @Query("""
            select l from Log l
              join fetch l.carga
              join fetch l.processo
              join fetch l.responsavel
             where l.ordemServico.id = :osId
             order by l.iniciadoEm asc, l.id asc
            """)
    List<Log> buscarParaRelatorio(@Param("osId") Long osId);
}
