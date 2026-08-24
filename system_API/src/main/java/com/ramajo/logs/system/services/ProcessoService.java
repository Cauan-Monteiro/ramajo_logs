package com.ramajo.logs.system.services;


import java.util.List;
import java.util.Set;

import com.ramajo.logs.system.entities.Processo;
import com.ramajo.logs.system.enums.Etapa;
import com.ramajo.logs.system.enums.Posicao;
import com.ramajo.logs.system.exceptions.RecursoNaoEncontradoException;
import com.ramajo.logs.system.repositories.ProcessoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CRUD de Processo e gestão do conjunto de posições (setores) onde ele ocorre. */
@Service
public class ProcessoService {

    private final ProcessoRepository processoRepo;

    public ProcessoService(ProcessoRepository processoRepo) {
        this.processoRepo = processoRepo;
    }

    @Transactional
    public Processo criar(String descricao, Etapa etapa, String tagId) {
        Processo processo = new Processo(descricao, etapa);
        processo.setTagId(tagId);
        return processoRepo.save(processo);
    }

    @Transactional
    public Processo atualizar(Long id, String descricao, Etapa etapa, String tagId) {
        Processo processo = buscar(id);
        processo.setDescricao(descricao);
        processo.setEtapa(etapa);
        processo.setTagId(tagId);
        return processo; // dirty checking
    }

    /**
     * Substitui as posições do processo pelo conjunto informado. Como a coleção
     * é gerenciada, o clear+addAll sincroniza a tabela processo_posicoes no commit.
     */
    @Transactional
    public Processo definirPosicoes(Long id, Set<Posicao> posicoes) {
        Processo processo = buscar(id);
        processo.getPosicoes().clear();
        processo.getPosicoes().addAll(posicoes);
        return processo;
    }

    @Transactional(readOnly = true)
    public Processo buscar(Long id) {
        return processoRepo.findById(id)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Id", id));
    }

    @Transactional(readOnly = true)
    public List<Processo> listar() {
        return processoRepo.findAll();
    }
}
