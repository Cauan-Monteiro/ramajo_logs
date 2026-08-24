package com.ramajo.logs.system.services;


import java.util.List;
import java.util.Optional;

import com.ramajo.logs.system.entities.Operador;
import com.ramajo.logs.system.enums.Permissao;
import com.ramajo.logs.system.exceptions.RecursoNaoEncontradoException;
import com.ramajo.logs.system.repositories.OperadorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CRUD de Operador e identificação por crachá/RFID. */
@Service
public class OperadorService {

    private final OperadorRepository operadorRepo;

    public OperadorService(OperadorRepository operadorRepo) {
        this.operadorRepo = operadorRepo;
    }

    @Transactional
    public Operador criar(String nome, Permissao permissao, String tagId) {
        return operadorRepo.save(new Operador(nome, permissao, tagId));
    }

    @Transactional
    public Operador atualizar(Long id, String nome, Permissao permissao, String tagId) {
        Operador operador = buscar(id);
        operador.setNome(nome);
        operador.setPermissao(permissao);
        operador.setTagId(tagId);
        return operador; // dirty checking
    }

    /** Soft-delete: preserva a autoria de logs e OSs já registrados. */
    @Transactional
    public void desativar(Long id) {
        buscar(id).setAtivo(false);
    }

    @Transactional(readOnly = true)
    public Operador buscar(Long id) {
        return operadorRepo.findById(id)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Id", id));
    }

    @Transactional(readOnly = true)
    public List<Operador> listar() {
        return operadorRepo.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<Operador> buscarPorTag(String tagId) {
        return operadorRepo.findByTagId(tagId);
    }
}
