package com.ramajo.logs.system.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ramajo.logs.system.entities.Cliente;
import com.ramajo.logs.system.entities.Operador;
import com.ramajo.logs.system.entities.OrdemServico;
import com.ramajo.logs.system.enums.Permissao;
import com.ramajo.logs.system.enums.Posicao;
import com.ramajo.logs.system.exceptions.AvaliacaoInvalidaException;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A avaliação vista da expedição: vai junto quando vem, não é exigida, e uma
 * avaliação inválida não deixa a OS expedir sem ela.
 */
@ExtendWith(MockitoExtension.class)
class OrdemServicoServiceAvaliacaoTest {

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
    @Mock private OrdemAvaliacaoService avaliacaoService;

    @InjectMocks private OrdemServicoService service;

    @Test
    void expedirComAvaliacaoGravaEmNomeDeQuemExpede() throws Exception {
        OrdemServico os = ordem(1L);
        Operador joao = new Operador("João", Permissao.FUNCIONARIO, "T1");
        OrdemAvaliacaoService.Entrada entrada =
                new OrdemAvaliacaoService.Entrada(true, true, "amassado", null, null);
        when(osRepo.findById(1L)).thenReturn(Optional.of(os));
        when(operadorRepo.findById(10L)).thenReturn(Optional.of(joao));

        service.finalizar(1L, 10L, entrada);

        verify(avaliacaoService).registrar(same(os), same(joao), eq(entrada));
        assertThat(os.isFinalizada()).isTrue();
    }

    @Test
    void expedirSemAvaliacaoNaoTocaNela() throws Exception {
        OrdemServico os = ordem(1L);
        when(osRepo.findById(1L)).thenReturn(Optional.of(os));
        when(operadorRepo.findById(10L))
                .thenReturn(Optional.of(new Operador("João", Permissao.FUNCIONARIO, "T1")));

        service.finalizar(1L, 10L, null);

        verify(avaliacaoService, never()).registrar(any(), any(), any());
        assertThat(os.isFinalizada()).isTrue();
    }

    @Test
    void avaliacaoInvalidaDerrubaAExpedicao() throws Exception {
        OrdemServico os = ordem(1L);
        Operador joao = new Operador("João", Permissao.FUNCIONARIO, "T1");
        OrdemAvaliacaoService.Entrada entrada =
                new OrdemAvaliacaoService.Entrada(false, null, null, null, null);
        when(osRepo.findById(1L)).thenReturn(Optional.of(os));
        when(operadorRepo.findById(10L)).thenReturn(Optional.of(joao));
        doThrow(new AvaliacaoInvalidaException("visual", "false"))
                .when(avaliacaoService).registrar(os, joao, entrada);

        assertThatThrownBy(() -> service.finalizar(1L, 10L, entrada))
                .isInstanceOf(AvaliacaoInvalidaException.class);

        assertThat(os.isFinalizada()).isFalse();
    }

    private OrdemServico ordem(Long id) throws Exception {
        OrdemServico os = new OrdemServico(100L, new Cliente(1L, "ACME LTDA"), Posicao.OXIDACAO);
        set(os, "id", id);
        set(os, "iniciadaEm", T0);
        return os;
    }

    private void set(Object alvo, String campo, Object valor) throws Exception {
        Field f = alvo.getClass().getDeclaredField(campo);
        f.setAccessible(true);
        f.set(alvo, valor);
    }
}
