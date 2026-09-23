package com.ramajo.logs.system.repositories;

import com.ramajo.logs.system.entities.ProcessoInicial;
import com.ramajo.logs.system.enums.Posicao;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * A chave é a Posicao, então findById(posicao) já é a busca do dia a dia — não
 * há método derivado a declarar para o caminho normal.
 */
public interface ProcessoInicialRepository extends JpaRepository<ProcessoInicial, Posicao> {

    /**
     * A busca INVERSA: quais setores têm este processo como entrada. É o que
     * permite recusar o arquivamento nomeando os setores a reconfigurar, em vez
     * de deixar o 409 genérico da FK falar por si.
     */
    List<ProcessoInicial> findByProcessoId(Long processoId);

    /**
     * Listagem da tela de configuração. O findAll() puro deixava um SELECT de
     * processo por setor para o DTO resolver; com o mapeamento agora dentro da
     * transação, o join fetch é o que impede esse N+1 de voltar calado.
     */
    @Query("select pi from ProcessoInicial pi join fetch pi.processo")
    List<ProcessoInicial> buscarTodosComProcesso();
}
