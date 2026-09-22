package com.ramajo.logs.system.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ramajo.logs.system.entities.Cliente;
import com.ramajo.logs.system.entities.OrdemAlteracao;
import com.ramajo.logs.system.entities.Operador;
import com.ramajo.logs.system.entities.OrdemServico;
import com.ramajo.logs.system.enums.CampoAlterado;
import com.ramajo.logs.system.enums.Permissao;
import com.ramajo.logs.system.enums.Posicao;
import com.ramajo.logs.system.exceptions.OperadorInativoException;
import com.ramajo.logs.system.exceptions.OrdemForaDeCirculacaoException;
import com.ramajo.logs.system.exceptions.RecursoNaoEncontradoException;
import com.ramajo.logs.system.repositories.CargaRepository;
import com.ramajo.logs.system.repositories.ClienteRepository;
import com.ramajo.logs.system.repositories.LogRepository;
import com.ramajo.logs.system.repositories.LoteRepository;
import com.ramajo.logs.system.repositories.OperadorRepository;
import com.ramajo.logs.system.repositories.OrdemAlteracaoRepository;
import com.ramajo.logs.system.repositories.OrdemServicoRepository;
import com.ramajo.logs.system.repositories.ProcessoInicialRepository;
import com.ramajo.logs.system.repositories.ProcessoRepository;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * `adicionarPosicao`: a OS passa a rodar TAMBÉM noutro setor.
 *
 * É a rota da exceção que a V19 introduziu — as peças de uma mesma ordem
 * partidas entre dois sítios da fábrica. O que estes casos vigiam:
 *
 * 1. Ela não desfaz NADA. Nenhuma carga sai, nenhum passo é cancelado, nenhum
 *    lote é tocado — é o que a distingue da remoção (que é de ADMIN, exige
 *    motivo e desfaz trabalho) e o que a torna barata de errar e corrigir.
 * 2. Não vincula carga nenhuma: só abre a ordem para o setor novo. O vínculo
 *    continua a ser a rota de sempre.
 * 3. É de chão de fábrica — operador comum, sem gate de ADMIN — mas mesmo
 *    assim deixa rasto no histórico. Sem a linha, a remoção deste setor mais
 *    tarde apareceria como a saída de algo que nunca entrou.
 */
@ExtendWith(MockitoExtension.class)
class OrdemServicoServicePosicoesTest {

    private static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");

    @Mock private OrdemServicoRepository osRepo;
    @Mock private ClienteRepository clienteRepo;
    @Mock private OperadorRepository operadorRepo;
    @Mock private CargaRepository cargaRepo;
    @Mock private ProcessoRepository processoRepo;
    @Mock private LogRepository logRepo;
    @Mock private LoteRepository loteRepo;
    @Mock private ProcessoInicialRepository processoInicialRepo;
    @Mock private OrdemAlteracaoRepository alteracaoRepo;

    @InjectMocks private OrdemServicoService service;

    @Test
    void acrescentaOSetorSemTocarEmCargaPassoOuLote() throws Exception {
        OrdemServico os = ordem(1L, Posicao.AUTOMATICA);

        when(osRepo.findById(1L)).thenReturn(Optional.of(os));
        when(operadorRepo.findById(7L)).thenReturn(Optional.of(funcionario()));

        assertThat(service.adicionarPosicao(1L, Posicao.PENDURADO, 7L)).isSameAs(os);

        assertThat(os.getPosicoes())
                .containsExactlyInAnyOrder(Posicao.AUTOMATICA, Posicao.PENDURADO);
        // Nada do chão de fábrica foi tocado — é o ponto da rota.
        verifyNoInteractions(cargaRepo, logRepo, loteRepo, processoRepo, processoInicialRepo);
    }

    /**
     * Sem gate de ADMIN: quem descobre que as peças também estão do outro lado
     * é quem está na linha, não quem administra o sistema.
     */
    @Test
    void operadorComumPodeAcrescentar() throws Exception {
        OrdemServico os = ordem(1L, Posicao.AUTOMATICA);

        when(osRepo.findById(1L)).thenReturn(Optional.of(os));
        when(operadorRepo.findById(7L)).thenReturn(Optional.of(funcionario()));

        service.adicionarPosicao(1L, Posicao.PENDURADO, 7L);

        assertThat(os.rodaEm(Posicao.PENDURADO)).isTrue();
    }

    /** Grava a linha de histórico, com os conjuntos na ordem canônica. */
    @Test
    void registraNoHistoricoComMotivoProprio() throws Exception {
        OrdemServico os = ordem(1L, Posicao.PENDURADO);
        Operador op = funcionario();

        when(osRepo.findById(1L)).thenReturn(Optional.of(os));
        when(operadorRepo.findById(7L)).thenReturn(Optional.of(op));

        service.adicionarPosicao(1L, Posicao.AUTOMATICA, 7L);

        ArgumentCaptor<OrdemAlteracao> captor = ArgumentCaptor.forClass(OrdemAlteracao.class);
        verify(alteracaoRepo).save(captor.capture());
        OrdemAlteracao linha = captor.getValue();

        assertThat(linha.getCampo()).isEqualTo(CampoAlterado.POSICAO);
        assertThat(linha.getValorAnterior()).isEqualTo("PENDURADO");
        assertThat(linha.getValorNovo()).isEqualTo("AUTOMATICA, PENDURADO");
        assertThat(linha.getAlteradaPor()).isSameAs(op);
        // Texto fixo: o histórico diz QUAL das duas origens escreveu a linha.
        assertThat(linha.getMotivo()).contains("acrescentado");
    }

    /**
     * Dois terminais no mesmo chão tocam o botão ao mesmo tempo — a segunda
     * chamada só afirma o que já é verdade. E, sobretudo, não escreve uma
     * segunda linha de histórico a dizer que nada mudou.
     */
    @Test
    void repetirEhIdempotenteENaoDuplicaOHistorico() throws Exception {
        OrdemServico os = ordem(1L, Posicao.AUTOMATICA);
        os.getPosicoes().add(Posicao.PENDURADO);

        when(osRepo.findById(1L)).thenReturn(Optional.of(os));
        when(operadorRepo.findById(7L)).thenReturn(Optional.of(funcionario()));

        assertThat(service.adicionarPosicao(1L, Posicao.PENDURADO, 7L)).isSameAs(os);

        assertThat(os.getPosicoes())
                .containsExactlyInAnyOrder(Posicao.AUTOMATICA, Posicao.PENDURADO);
        verify(alteracaoRepo, never()).save(any());
    }

    @Test
    void recusaOrdemForaDeCirculacao() throws Exception {
        OrdemServico os = ordem(1L, Posicao.AUTOMATICA);
        os.setFinalizadaEm(T0);

        when(osRepo.findById(1L)).thenReturn(Optional.of(os));

        assertThatThrownBy(() -> service.adicionarPosicao(1L, Posicao.PENDURADO, 7L))
                .isInstanceOf(OrdemForaDeCirculacaoException.class);

        assertThat(os.getPosicoes()).containsExactly(Posicao.AUTOMATICA);
        verify(alteracaoRepo, never()).save(any());
    }

    @Test
    void recusaOperadorInativo() throws Exception {
        OrdemServico os = ordem(1L, Posicao.AUTOMATICA);
        Operador inativo = funcionario();
        inativo.setAtivo(false);

        when(osRepo.findById(1L)).thenReturn(Optional.of(os));
        when(operadorRepo.findById(7L)).thenReturn(Optional.of(inativo));

        assertThatThrownBy(() -> service.adicionarPosicao(1L, Posicao.PENDURADO, 7L))
                .isInstanceOf(OperadorInativoException.class);

        assertThat(os.getPosicoes()).containsExactly(Posicao.AUTOMATICA);
        verify(alteracaoRepo, never()).save(any());
    }

    @Test
    void recusaOperadorInexistente() throws Exception {
        OrdemServico os = ordem(1L, Posicao.AUTOMATICA);

        when(osRepo.findById(1L)).thenReturn(Optional.of(os));
        when(operadorRepo.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.adicionarPosicao(1L, Posicao.PENDURADO, 999L))
                .isInstanceOf(RecursoNaoEncontradoException.class);

        assertThat(os.getPosicoes()).containsExactly(Posicao.AUTOMATICA);
    }

    @Test
    void recusaOrdemInexistente() {
        when(osRepo.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.adicionarPosicao(1L, Posicao.PENDURADO, 7L))
                .isInstanceOf(RecursoNaoEncontradoException.class);
    }

    /* -- fixtures ---------------------------------------------------------- */

    private OrdemServico ordem(Long id, Posicao posicao) throws Exception {
        OrdemServico os = new OrdemServico(100L, new Cliente(1L, "ACME LTDA"), posicao);
        set(os, "id", id);
        set(os, "iniciadaEm", T0);
        return os;
    }

    private Operador funcionario() {
        return new Operador("João", Permissao.FUNCIONARIO, "T1");
    }

    /** Os carimbos e ids são gerados pelo banco; no teste entram por reflexão. */
    private void set(Object alvo, String campo, Object valor) throws Exception {
        Field f = alvo.getClass().getDeclaredField(campo);
        f.setAccessible(true);
        f.set(alvo, valor);
    }
}
