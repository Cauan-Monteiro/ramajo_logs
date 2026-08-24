package com.ramajo.logs.system.services;


import java.util.List;
import java.util.Optional;

import com.ramajo.logs.system.entities.Carga;
import com.ramajo.logs.system.enums.Posicao;
import com.ramajo.logs.system.enums.TipoCarga;
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
    public Carga criar(String nome, TipoCarga tipo, Posicao posicao, String tagId) {
        Carga carga = new Carga(nome, tipo, posicao);
        carga.setTagId(tagId);
        return cargaRepo.save(carga);
    }

    @Transactional
    public Carga atualizar(Long id, String nome, TipoCarga tipo, Posicao posicao, String tagId) {
        Carga carga = buscar(id);
        carga.setNome(nome);
        carga.setTipo(tipo);
        carga.setPosicao(posicao);
        carga.setTagId(tagId);
        return carga; // gerenciada na transação: persistido no commit (dirty checking)
    }

    /** Soft-delete: some do pool sem apagar o histórico de logs que gerou. */
    @Transactional
    public void desativar(Long id) {
        buscar(id).setAtivo(false);
    }

    @Transactional(readOnly = true)
    public Carga buscar(Long id) {
        return cargaRepo.findById(id)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Id", id));
    }

    @Transactional(readOnly = true)
    public List<Carga> listar() {
        return cargaRepo.findAll();
    }

    @Transactional(readOnly = true)
    public List<Carga> listarDisponiveis() {
        return cargaRepo.findByAtivoTrueAndOrdemAtualIsNull();
    }

    @Transactional(readOnly = true)
    public Optional<Carga> buscarPorTag(String tagId) {
        return cargaRepo.findByTagId(tagId);
    }
}
