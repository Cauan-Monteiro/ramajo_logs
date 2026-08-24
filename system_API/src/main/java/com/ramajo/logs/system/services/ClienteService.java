package com.ramajo.logs.system.services;


import java.util.List;

import com.ramajo.logs.system.entities.Cliente;
import com.ramajo.logs.system.exceptions.RecursoNaoEncontradoException;
import com.ramajo.logs.system.repositories.ClienteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cliente vem do sistema externo com id próprio, então o padrão é upsert
 * (sincronizar), não create puro: se já existe, atualiza o nome; senão, insere.
 */
@Service
public class ClienteService {

    private final ClienteRepository clienteRepo;

    public ClienteService(ClienteRepository clienteRepo) {
        this.clienteRepo = clienteRepo;
    }

    @Transactional
    public Cliente sincronizar(Long id, String nome) {
        return clienteRepo.findById(id)
                .map(existente -> {
                    existente.setNome(nome); // dirty checking
                    return existente;
                })
                .orElseGet(() -> clienteRepo.save(new Cliente(id, nome)));
    }

    @Transactional(readOnly = true)
    public Cliente buscar(Long id) {
        return clienteRepo.findById(id)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Id", id));
    }

    @Transactional(readOnly = true)
    public List<Cliente> listar() {
        return clienteRepo.findAll();
    }
}
