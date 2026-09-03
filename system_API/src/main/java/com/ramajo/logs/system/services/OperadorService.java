package com.ramajo.logs.system.services;


import java.util.List;
import java.util.Optional;

import com.ramajo.logs.system.entities.Operador;
import com.ramajo.logs.system.enums.Permissao;
import com.ramajo.logs.system.exceptions.OperadorEmUsoException;
import com.ramajo.logs.system.exceptions.RecursoNaoEncontradoException;
import com.ramajo.logs.system.repositories.LogRepository;
import com.ramajo.logs.system.repositories.LoteRepository;
import com.ramajo.logs.system.repositories.OperadorRepository;
import com.ramajo.logs.system.repositories.OrdemServicoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CRUD de Operador e identificação por crachá/RFID. */
@Service
public class OperadorService {

    private final OperadorRepository operadorRepo;
    // Só para contar referências antes de um delete definitivo; ver excluir().
    private final LogRepository logRepo;
    private final OrdemServicoRepository ordemRepo;
    private final LoteRepository loteRepo;

    public OperadorService(OperadorRepository operadorRepo,
                           LogRepository logRepo,
                           OrdemServicoRepository ordemRepo,
                           LoteRepository loteRepo) {
        this.operadorRepo = operadorRepo;
        this.logRepo = logRepo;
        this.ordemRepo = ordemRepo;
        this.loteRepo = loteRepo;
    }

    @Transactional
    public Operador criar(String nome, Permissao permissao, String tagId) {
        return operadorRepo.save(new Operador(nome, permissao, tagId));
    }

    @Transactional
    public Operador atualizar(Long id, String nome, Permissao permissao, String tagId) {
        Operador operador = buscar(id);
        // Rebaixar o último admin tranca a aba de Ajustes por fora, tal como
        // desativá-lo — mesma recusa.
        if (permissao != Permissao.ADMIN) {
            exigirOutroAdmin(operador);
        }
        operador.setNome(nome);
        operador.setPermissao(permissao);
        operador.setTagId(tagId);
        return operador; // dirty checking
    }

    /** Soft-delete: preserva a autoria de logs e OSs já registrados. */
    @Transactional
    public void desativar(Long id) {
        Operador operador = buscar(id);
        if (!operador.isAtivo()) {
            return; // idempotente
        }
        exigirOutroAdmin(operador);
        operador.setAtivo(false);
    }

    @Transactional
    public Operador reativar(Long id) {
        Operador operador = buscar(id);
        operador.setAtivo(true);
        return operador;
    }

    /**
     * Hard delete: apaga a linha. Só passa para quem NUNCA assinou nada — o
     * cadastro errado, o operador que saiu antes de bater o primeiro passo.
     *
     * Com histórico, recusa (409): `logs.responsavel_id` é NOT NULL e a tabela é
     * append-only por trigger, então apagar o operador significaria apagar os
     * passos que ele documentou. Para esses o caminho é desativar().
     */
    @Transactional
    public void excluir(Long id) {
        Operador operador = buscar(id);

        long passos = logRepo.countByResponsavelId(id);
        long ordens = ordemRepo.countByIniciadaPorIdOrFinalizadaPorId(id, id);
        long lotes = loteRepo.countByFinalizadoPorId(id);
        if (passos + ordens + lotes > 0) {
            throw new OperadorEmUsoException(id, operador.getNome(), passos, ordens, lotes);
        }

        exigirOutroAdmin(operador);
        operadorRepo.delete(operador);
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

    /**
     * Recusa a operação se ela deixaria o sistema sem nenhum ADMIN ativo — quem
     * não é admin ativo hoje não entra nessa conta, então só barra o último.
     */
    private void exigirOutroAdmin(Operador operador) {
        if (operador.getPermissao() != Permissao.ADMIN || !operador.isAtivo()) {
            return;
        }
        if (operadorRepo.countByPermissaoAndAtivoTrue(Permissao.ADMIN) <= 1) {
            throw new OperadorEmUsoException(operador.getId(), operador.getNome());
        }
    }
}
