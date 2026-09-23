package com.ramajo.logs.system.services;


import java.util.List;
import java.util.Optional;

import com.ramajo.logs.system.dtos.CargaDtos.CargaDTO;
import com.ramajo.logs.system.entities.Carga;
import com.ramajo.logs.system.enums.Posicao;
import com.ramajo.logs.system.enums.TipoCarga;
import com.ramajo.logs.system.exceptions.CargaEmUsoException;
import com.ramajo.logs.system.exceptions.RecursoNaoEncontradoException;
import com.ramajo.logs.system.repositories.CargaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD e consultas de Carga. O vínculo com a OS (ordemAtual) NÃO é mexido aqui:
 * quem vincula/libera é o OrdemServicoService, dono desse fluxo.
 */
@Service
public class CargaService {

    private final CargaRepository cargaRepo;

    public CargaService(CargaRepository cargaRepo) {
        this.cargaRepo = cargaRepo;
    }

    @Transactional
    public CargaDTO criar(String nome, TipoCarga tipo, Posicao posicao, String tagId) {
        Carga carga = new Carga(nome, tipo, posicao);
        carga.setTagId(tagId);
        return CargaDTO.from(cargaRepo.save(carga));
    }

    /**
     * Nome, tipo e tag mudam a qualquer momento — são rótulo. O SETOR não:
     * é ele que escolhe o processo inicial no vínculo e que autoriza o processo
     * de cada passo (OrdemServicoService.abrirLog). Trocá-lo com a carga
     * vinculada moveria o chão debaixo de um passo já aberto, que continuaria
     * a apontar para um processo do setor antigo sem que nenhuma validação
     * voltasse a correr.
     *
     * Só recusa quando o setor REALMENTE muda: reenviar o mesmo valor é o que
     * esta rota faz sempre (o corpo traz os campos inteiros, como em
     * OrdemServicoService.corrigir), e recusá-lo impediria de corrigir o nome
     * de uma carga em uso.
     */
    @Transactional
    public CargaDTO atualizar(Long id, String nome, TipoCarga tipo, Posicao posicao,
                              String tagId) {
        Carga carga = carregar(id);

        if (posicao != carga.getPosicao() && carga.getOrdemAtual() != null) {
            throw new CargaEmUsoException(
                    id, carga.getOrdemAtual().getId(), carga.getPosicao(), posicao);
        }

        carga.setNome(nome);
        carga.setTipo(tipo);
        carga.setPosicao(posicao);
        carga.setTagId(tagId);
        // gerenciada na transação: persistido no commit (dirty checking)
        return CargaDTO.from(carga);
    }

    /** Soft-delete: some do pool sem apagar o histórico de logs que gerou. */
    @Transactional
    public void desativar(Long id) {
        carregar(id).setAtivo(false);
    }

    /** Desfaz o soft-delete: a carga volta ao pool. Idempotente. */
    @Transactional
    public CargaDTO reativar(Long id) {
        Carga carga = carregar(id);
        carga.setAtivo(true);
        return CargaDTO.from(carga); // dirty checking
    }

    @Transactional(readOnly = true)
    public CargaDTO buscar(Long id) {
        return CargaDTO.from(cargaRepo.buscarComAcopladas(id)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Id", id)));
    }

    /**
     * A flag vinha do controller, que escolhia entre dois métodos e mapeava o
     * resultado. Com o mapeamento aqui dentro, a escolha vem junto.
     */
    @Transactional(readOnly = true)
    public List<CargaDTO> listar(boolean disponiveis) {
        List<Carga> cargas = disponiveis
                ? cargaRepo.buscarDisponiveisComAcopladas()
                : cargaRepo.buscarTodasComAcopladas();
        return cargas.stream().map(CargaDTO::from).toList();
    }

    @Transactional(readOnly = true)
    public Optional<CargaDTO> buscarPorTag(String tagId) {
        return cargaRepo.buscarPorTagComAcopladas(tagId).map(CargaDTO::from);
    }

    /** Carga interna dos caminhos de escrita: entidade gerenciada, sem DTO. */
    private Carga carregar(Long id) {
        return cargaRepo.findById(id)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Id", id));
    }
}
