package com.ramajo.logs.system.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ramajo.logs.system.entities.Cliente;
import com.ramajo.logs.system.entities.Lote;
import com.ramajo.logs.system.entities.Operador;
import com.ramajo.logs.system.entities.OrdemServico;
import com.ramajo.logs.system.enums.Permissao;
import com.ramajo.logs.system.enums.Posicao;
import com.ramajo.logs.system.exceptions.OperadorInativoException;
import com.ramajo.logs.system.exceptions.ReaberturaInvalidaException;
import com.ramajo.logs.system.exceptions.RecursoNaoEncontradoException;
import com.ramajo.logs.system.repositories.CargaRepository;
import com.ramajo.logs.system.repositories.ClienteRepository;
import com.ramajo.logs.system.repositories.LogRepository;
import com.ramajo.logs.system.repositories.LoteRepository;
import com.ramajo.logs.system.repositories.OperadorRepository;
import com.ramajo.logs.system.repositories.OrdemServicoRepository;
import com.ramajo.logs.system.repositories.ProcessoInicialRepository;
import com.ramajo.logs.system.repositories.ProcessoRepository;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Reabertura da OS expedida — o único ponto do sistema em que o fluxo de lote
 * anda para trás, e por isso o que estes casos vigiam não é o retorno, é o que
 * ficou para trás intacto:
 *
 * 1. Os lotes anteriores NÃO são reabertos. Eles descrevem produção que de
 *    facto terminou; reabrir abre o número seguinte.
 * 2. Só OS finalizada reabre. Em produção não há expedição a desfazer, e
 *    cancelada é um fim — ressuscitá-la apagaria a diferença entre abortar e
 *    concluir.
 */
@ExtendWith(MockitoExtension.class)
class OrdemServicoServiceReaberturaTest {

    private static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-01T17:00:00Z");

    @Mock private OrdemServicoRepository osRepo;
    @Mock private ClienteRepository clienteRepo;
    @Mock private OperadorRepository operadorRepo;
    @Mock private CargaRepository cargaRepo;
    @Mock private ProcessoRepository processoRepo;
    @Mock private LogRepository logRepo;
    @Mock private LoteRepository loteRepo;
    @Mock private ProcessoInicialRepository processoInicialRepo;

    @InjectMocks private OrdemServicoService service;

    @Test
    void reabreOsExpedidaNumLoteNovoDeixandoOsAnterioresIntactos() throws Exception {
        OrdemServico os = ordemExpedida(1L);
        Lote primeiro = lote(os, (short) 1, T1);
        Lote segundo = lote(os, (short) 2, T1);
        set(os, "lotes", new ArrayList<>(List.of(primeiro, segundo)));

        when(osRepo.findById(1L)).thenReturn(Optional.of(os));
        when(operadorRepo.findById(10L)).thenReturn(Optional.of(operador()));
        when(loteRepo.findByOrdemServicoIdOrderByNumeroAsc(1L))
                .thenReturn(List.of(primeiro, segundo));
        when(loteRepo.save(any(Lote.class))).thenAnswer(inv -> inv.getArgument(0));

        Lote novo = service.reabrir(1L, 10L);

        assertThat(novo.getNumero()).isEqualTo((short) 3);
        assertThat(novo.isFinalizado()).isFalse();
        assertThat(novo.getOrdemServico()).isSameAs(os);

        assertThat(os.isEmProcesso()).isTrue();
        assertThat(os.isFinalizada()).isFalse();
        assertThat(os.getFinalizadaPor()).isNull();

        // O preço da reabertura para na OS: o histórico de lotes não é tocado.
        assertThat(primeiro.getFinalizadoEm()).isEqualTo(T1);
        assertThat(segundo.getFinalizadoEm()).isEqualTo(T1);
    }

    @Test
    void recusaOsEmProducao() throws Exception {
        OrdemServico os = ordem(1L);
        when(osRepo.findById(1L)).thenReturn(Optional.of(os));

        assertThatThrownBy(() -> service.reabrir(1L, 10L))
                .isInstanceOf(ReaberturaInvalidaException.class)
                .hasMessageContaining("não está finalizada");

        verify(loteRepo, never()).save(any());
    }

    @Test
    void recusaOsCancelada() throws Exception {
        OrdemServico os = ordem(1L);
        os.setCancelada(true);
        os.setEmProcesso(false);
        when(osRepo.findById(1L)).thenReturn(Optional.of(os));

        assertThatThrownBy(() -> service.reabrir(1L, 10L))
                .isInstanceOf(ReaberturaInvalidaException.class)
                .hasMessageContaining("cancelada");

        verify(loteRepo, never()).save(any());
    }

    /** Estado que não deveria existir; se existisse, o INSERT bateria no índice. */
    @Test
    void recusaQuandoJaHaLoteAberto() throws Exception {
        OrdemServico os = ordemExpedida(1L);
        set(os, "lotes", new ArrayList<>(List.of(lote(os, (short) 1, null))));
        when(osRepo.findById(1L)).thenReturn(Optional.of(os));

        assertThatThrownBy(() -> service.reabrir(1L, 10L))
                .isInstanceOf(ReaberturaInvalidaException.class);

        verify(loteRepo, never()).save(any());
    }

    @Test
    void recusaOperadorInativo() throws Exception {
        OrdemServico os = ordemExpedida(1L);
        set(os, "lotes", new ArrayList<>(List.of(lote(os, (short) 1, T1))));
        Operador demitido = operador();
        demitido.setAtivo(false);

        when(osRepo.findById(1L)).thenReturn(Optional.of(os));
        when(operadorRepo.findById(10L)).thenReturn(Optional.of(demitido));

        assertThatThrownBy(() -> service.reabrir(1L, 10L))
                .isInstanceOf(OperadorInativoException.class);

        // A OS não pode ficar meio reaberta: a validação vem antes das mutações.
        assertThat(os.isFinalizada()).isTrue();
        assertThat(os.isEmProcesso()).isFalse();
        verify(loteRepo, never()).save(any());
    }

    @Test
    void recusaOsInexistente() {
        when(osRepo.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reabrir(99L, 10L))
                .isInstanceOf(RecursoNaoEncontradoException.class);
    }

    /* -- fixtures ---------------------------------------------------------- */

    private OrdemServico ordem(Long id) throws Exception {
        OrdemServico os = new OrdemServico(id, new Cliente(1L, "ACME LTDA"), Posicao.OXIDACAO);
        set(os, "id", id);
        set(os, "iniciadaEm", T0);
        return os;
    }

    private OrdemServico ordemExpedida(Long id) throws Exception {
        OrdemServico os = ordem(id);
        os.setFinalizadaEm(T1);
        os.setFinalizadaPor(operador());
        os.setEmProcesso(false);
        return os;
    }

    private Lote lote(OrdemServico os, Short numero, Instant finalizadoEm) throws Exception {
        Lote l = new Lote(os, numero);
        set(l, "id", (long) numero);
        set(l, "iniciadoEm", T0);
        l.setFinalizadoEm(finalizadoEm);
        return l;
    }

    private Operador operador() {
        return new Operador("João", Permissao.FUNCIONARIO, "T1");
    }

    /** Os carimbos e ids são gerados pelo banco; no teste eles entram por reflexão. */
    private void set(Object alvo, String campo, Object valor) throws Exception {
        Field f = alvo.getClass().getDeclaredField(campo);
        f.setAccessible(true);
        f.set(alvo, valor);
    }
}
