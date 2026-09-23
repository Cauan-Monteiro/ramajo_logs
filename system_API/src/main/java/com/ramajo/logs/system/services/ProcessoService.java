package com.ramajo.logs.system.services;


import java.util.List;
import java.util.Set;

import com.ramajo.logs.system.dtos.ProcessoDtos.ProcessoDTO;
import com.ramajo.logs.system.entities.Processo;
import com.ramajo.logs.system.entities.ProcessoInicial;
import com.ramajo.logs.system.enums.Etapa;
import com.ramajo.logs.system.enums.Posicao;
import com.ramajo.logs.system.exceptions.ProcessoEmUsoException;
import com.ramajo.logs.system.exceptions.RecursoNaoEncontradoException;
import com.ramajo.logs.system.repositories.ProcessoInicialRepository;
import com.ramajo.logs.system.repositories.ProcessoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CRUD de Processo e gestão do conjunto de posições (setores) onde ele ocorre. */
@Service
public class ProcessoService {

    private final ProcessoRepository processoRepo;
    private final ProcessoInicialRepository processoInicialRepo;

    /** O fallback global de OrdemServicoService; ver arquivar(). */
    private final Long processoInicialId;

    public ProcessoService(ProcessoRepository processoRepo,
                           ProcessoInicialRepository processoInicialRepo,
                           @Value("${app.processo-inicial-id}") Long processoInicialId) {
        this.processoRepo = processoRepo;
        this.processoInicialRepo = processoInicialRepo;
        this.processoInicialId = processoInicialId;
    }

    @Transactional
    public ProcessoDTO criar(String descricao, Etapa etapa, String tagId, Set<Posicao> posicoes) {
        Processo processo = new Processo(descricao, etapa);
        processo.setTagId(tagId);
        aplicarPosicoes(processo, posicoes);
        return ProcessoDTO.from(processoRepo.save(processo));
    }

    @Transactional
    public ProcessoDTO atualizar(Long id, String descricao, Etapa etapa, String tagId,
                                 Set<Posicao> posicoes) {
        Processo processo = carregar(id);
        processo.setDescricao(descricao);
        processo.setEtapa(etapa);
        processo.setTagId(tagId);
        aplicarPosicoes(processo, posicoes);
        return ProcessoDTO.from(processo); // dirty checking
    }

    /**
     * Substitui as posições do processo pelo conjunto informado. Como a coleção
     * é gerenciada, o clear+addAll sincroniza a tabela processo_posicoes no commit.
     */
    @Transactional
    public ProcessoDTO definirPosicoes(Long id, Set<Posicao> posicoes) {
        Processo processo = carregar(id);
        aplicarPosicoes(processo, posicoes);
        return ProcessoDTO.from(processo);
    }

    /**
     * "Excluir" um processo é arquivá-lo (ver V8): a linha fica, porque cada
     * log histórico aponta para ela — e logs é append-only. O processo apenas
     * some das listas de escolha.
     *
     * As duas recusas abaixo têm o mesmo motivo: não deixar uma CONFIGURAÇÃO
     * viva apontando para um processo que abrirLog vai passar a recusar. Sem
     * elas, o efeito só apareceria depois, na forma de toda criação de OS
     * daquele setor falhando.
     */
    @Transactional
    public void arquivar(Long id) {
        Processo processo = carregar(id);
        if (!processo.isAtivo()) {
            return; // idempotente
        }

        if (id.equals(processoInicialId)) {
            throw new ProcessoEmUsoException(id, processo.getDescricao());
        }

        List<Posicao> setores = processoInicialRepo.findByProcessoId(id).stream()
                .map(ProcessoInicial::getPosicao)
                .toList();
        if (!setores.isEmpty()) {
            throw new ProcessoEmUsoException(id, processo.getDescricao(), setores);
        }

        processo.setAtivo(false);
    }

    @Transactional
    public ProcessoDTO reativar(Long id) {
        Processo processo = carregar(id);
        processo.setAtivo(true);
        return ProcessoDTO.from(processo);
    }

    @Transactional(readOnly = true)
    public ProcessoDTO buscar(Long id) {
        return ProcessoDTO.from(processoRepo.buscarComPosicoes(id)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Id", id)));
    }

    /** Carga interna dos caminhos de escrita: entidade gerenciada, sem DTO. */
    private Processo carregar(Long id) {
        return processoRepo.findById(id)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Id", id));
    }

    /**
     * Devolve ativos E arquivados de propósito: os arquivados ainda são o
     * catálogo dos logs históricos — o front cruza a descrição do log com esta
     * lista para saber a etapa. Filtrar aqui apagaria a etapa de todo passo
     * antigo. Quem oferece processo ao usuário é que filtra por `ativo`.
     */
    @Transactional(readOnly = true)
    public List<ProcessoDTO> listar() {
        return processoRepo.buscarTodosComPosicoes().stream().map(ProcessoDTO::from).toList();
    }

    private void aplicarPosicoes(Processo processo, Set<Posicao> posicoes) {
        processo.getPosicoes().clear();
        processo.getPosicoes().addAll(posicoes);
    }
}
