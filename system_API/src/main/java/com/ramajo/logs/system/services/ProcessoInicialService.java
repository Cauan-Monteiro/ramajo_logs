package com.ramajo.logs.system.services;

import java.util.List;

import com.ramajo.logs.system.entities.Processo;
import com.ramajo.logs.system.entities.ProcessoInicial;
import com.ramajo.logs.system.enums.Posicao;
import com.ramajo.logs.system.exceptions.PosicaoIncompativelException;
import com.ramajo.logs.system.exceptions.ProcessoInativoException;
import com.ramajo.logs.system.exceptions.RecursoNaoEncontradoException;
import com.ramajo.logs.system.repositories.ProcessoInicialRepository;
import com.ramajo.logs.system.repositories.ProcessoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Configuração do processo em que cada setor abre o primeiro passo da carga. */
@Service
public class ProcessoInicialService {

    private final ProcessoInicialRepository processoInicialRepo;
    private final ProcessoRepository processoRepo;

    public ProcessoInicialService(ProcessoInicialRepository processoInicialRepo,
                                  ProcessoRepository processoRepo) {
        this.processoInicialRepo = processoInicialRepo;
        this.processoRepo = processoRepo;
    }

    /**
     * Define (ou troca) o processo inicial de um setor.
     *
     * O processo TEM que rodar naquele setor: quem abre o passo é
     * OrdemServicoService.abrirLog, que recusa processo fora da posição da OS.
     * Sem esta guarda, uma configuração errada só apareceria mais tarde — na
     * forma de toda criação de OS daquele setor falhando. Falhar aqui, no
     * cadastro, é o momento certo.
     */
    @Transactional
    public ProcessoInicial definir(Posicao posicao, Long processoId) {
        Processo processo = processoRepo.findById(processoId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Processo", processoId));

        // Arquivado não volta a ser entrada de setor por chamada direta à API:
        // seria reintroduzir pela porta dos fundos a configuração que
        // ProcessoService.arquivar() se recusa a deixar quebrada.
        if (!processo.isAtivo()) {
            throw new ProcessoInativoException(processo.getId(), processo.getDescricao());
        }
        if (processo.getPosicoes().isEmpty()) {
            throw new PosicaoIncompativelException(processo.getId(), processo.getDescricao());
        }
        if (!processo.getPosicoes().contains(posicao)) {
            throw new PosicaoIncompativelException(
                    processo.getId(), processo.getDescricao(), posicao);
        }

        // Upsert: a PK é a posição, então trocar é atualizar a linha existente.
        return processoInicialRepo.findById(posicao)
                .map(atual -> {
                    atual.setProcesso(processo); // dirty checking
                    return atual;
                })
                .orElseGet(() -> processoInicialRepo.save(new ProcessoInicial(posicao, processo)));
    }

    @Transactional(readOnly = true)
    public List<ProcessoInicial> listar() {
        return processoInicialRepo.findAll();
    }
}
