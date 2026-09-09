package com.ramajo.logs.system.repositories;

import com.ramajo.logs.system.entities.Log;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LogRepository extends JpaRepository<Log, UUID> {
    // Histórico da OS na tela: os passos que ela executou MAIS aqueles em que
    // pegou carona (peças dela na carga de outra OS — ver V11). Um passo de
    // carona vem com `ordemServico` apontando para a titular, e é assim que o
    // front o distingue de um passo próprio.
    //
    // Só a leitura de tela usa esta visão ampliada. As consultas de relatório
    // abaixo continuam na titular de propósito: é o que faz um evento físico
    // ser contado uma vez, sem DISTINCT em cada agregado.
    @Query("""
            select l from Log l
             where l.ordemServico.id = :osId
                or :osId member of l.ordensAcopladas
             order by l.iniciadoEm asc, l.id asc
            """)
    List<Log> buscarHistorico(@Param("osId") Long osId);

    // Quantos passos este operador assinou. Alimenta a recusa de exclusão
    // definitiva em OperadorService.excluir — responsavel_id é NOT NULL, então
    // apagar a linha do operador levaria o passo junto (ou estouraria a FK).
    long countByResponsavelId(Long operadorId);

    // O passo em aberto da carga, se houver. ux_logs_carga_aberto garante que
    // é no máximo um, então Optional (e não List) é o tipo honesto. Devolve a
    // entidade, não um boolean, para o erro poder citar o passo que trava.
    Optional<Log> findByCargaIdAndFinalizadoEmIsNull(Long cargaId);

    // Passos ainda abertos de uma OS — usados para fechá-los junto com ela.
    List<Log> findByOrdemServicoIdAndFinalizadoEmIsNull(Long osId);

    // O passo aberto em que esta OS pega carona, se houver. As peças dela
    // estão num tanque só: acoplá-la a um segundo passo sem desacoplar do
    // primeiro é incoerência física, e é aqui que se descobre.
    //
    // Lista (e não Optional) porque nada no banco garante unicidade — o
    // ux_logs_carga_aberto protege a CARGA, não a carona. O service recusa
    // no primeiro que encontrar.
    @Query("""
            select l from Log l
             where l.finalizadoEm is null
               and :osId member of l.ordensAcopladas
            """)
    List<Log> buscarAcoplamentosAbertos(@Param("osId") Long osId);

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

    // O mesmo, para VÁRIAS ordens: alimenta a aba de etapas do relatório por
    // período e, da mesma lista, os contadores e tempos de cada OS — uma
    // consulta só, sem uma segunda passada agregando no banco.
    //
    // A ordem (OS, depois cronológica) é a da aba: sem ela as etapas de uma
    // mesma ordem sairiam intercaladas com as das outras.
    @Query("""
            select l from Log l
              join fetch l.carga
              join fetch l.processo
              join fetch l.responsavel
             where l.ordemServico.id in :ids
             order by l.ordemServico.id asc, l.iniciadoEm asc, l.id asc
            """)
    List<Log> buscarParaRelatorioDeOrdens(@Param("ids") Collection<Long> ids);

    // ------------------------------------------------------- etapas acopladas
    // As duas consultas acima ficam na OS TITULAR de propósito — é o que faz um
    // evento físico ser contado uma vez. As duas abaixo trazem o outro lado: os
    // passos em que a OS pegou carona na carga de outra. Servem para LISTAR
    // ("por onde passaram as peças desta OS"), nunca para contar.

    // O fetch da OS titular e do cliente não é opcional: quem lê a linha precisa
    // dizer a que ordem ela pertence, e a titular pode estar fora do recorte do
    // relatório — sem o fetch seria um SELECT por linha.
    @Query("""
            select l from Log l
              join fetch l.carga
              join fetch l.processo
              join fetch l.responsavel
              join fetch l.ordemServico o
              join fetch o.cliente
             where :osId member of l.ordensAcopladas
             order by l.iniciadoEm asc, l.id asc
            """)
    List<Log> buscarAcopladasParaRelatorio(@Param("osId") Long osId);

    // O mesmo para várias OS. Filtra por `exists` e não por um join na coleção:
    // o join restringiria a própria coleção que precisamos ler inteira depois,
    // para saber a QUAIS das OS do recorte cada passo se acopla.
    @Query("""
            select l from Log l
              join fetch l.carga
              join fetch l.processo
              join fetch l.responsavel
              join fetch l.ordemServico o
              join fetch o.cliente
             where exists (select a from Log l2 join l2.ordensAcopladas a
                            where l2 = l and a in :ids)
             order by l.iniciadoEm asc, l.id asc
            """)
    List<Log> buscarAcopladasDeOrdens(@Param("ids") Collection<Long> ids);
}
