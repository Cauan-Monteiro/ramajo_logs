package com.ramajo.logs.system.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.ramajo.logs.system.entities.Cliente;
import com.ramajo.logs.system.entities.Lote;
import com.ramajo.logs.system.entities.Operador;
import com.ramajo.logs.system.entities.OrdemServico;
import com.ramajo.logs.system.enums.Permissao;
import com.ramajo.logs.system.enums.Posicao;
import com.ramajo.logs.system.exceptions.EntregaInvalidaException;
import com.ramajo.logs.system.exceptions.OperadorInativoException;
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
 * Entrega da OS — o passo depois da expedição.
 *
 * O que estes casos vigiam é a posição dela na linha do tempo, que é o que a
 * distingue de um campo qualquer:
 *
 * 1. Só vem DEPOIS da expedição: OS em produção não tem o que entregar, e
 *    cancelada não chegou a sair.
 * 2. Acontece UMA vez. A segunda chamada é recusada em vez de reescrever o
 *    carimbo — reescrever perderia a hora e o nome de quem entregou de facto.
 * 3. A reabertura a desfaz, como desfaz a expedição que ela sucedia.
 */
@ExtendWith(MockitoExtension.class)
class OrdemServicoServiceEntregaTest {

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
    @Mock private OrdemAvaliacaoService avaliacaoService;

    @InjectMocks private OrdemServicoService service;

    @Test
    void carimbaInstanteEAutorNaOsExpedida() throws Exception {
        OrdemServico os = ordemExpedida(1L);
        Operador motorista = operador("Rita");

        when(osRepo.findById(1L)).thenReturn(Optional.of(os));
        when(operadorRepo.findById(10L)).thenReturn(Optional.of(motorista));

        service.entregar(1L, 10L);

        assertThat(os.isEntregue()).isTrue();
        assertThat(os.getEntreguePor()).isSameAs(motorista);
        // Depois da expedição: é o que ck_os_entrega exige no banco.
        assertThat(os.getEntregueEm()).isAfterOrEqualTo(os.getFinalizadaEm());

        // Entregar não mexe na expedição — são dois eventos, não um.
        assertThat(os.getFinalizadaEm()).isEqualTo(T1);
        assertThat(os.isEmProcesso()).isFalse();
    }

    /** Quem expede e quem entrega não são a mesma pessoa, e o registo diz isso. */
    @Test
    void oAutorDaEntregaNaoSubstituiODaExpedicao() throws Exception {
        OrdemServico os = ordemExpedida(1L);

        when(osRepo.findById(1L)).thenReturn(Optional.of(os));
        when(operadorRepo.findById(10L)).thenReturn(Optional.of(operador("Rita")));

        service.entregar(1L, 10L);

        assertThat(os.getFinalizadaPor().getNome()).isEqualTo("Joao");
        assertThat(os.getEntreguePor().getNome()).isEqualTo("Rita");
    }

    @Test
    void recusaOsAindaEmProducao() throws Exception {
        OrdemServico os = ordem(1L);
        when(osRepo.findById(1L)).thenReturn(Optional.of(os));

        assertThatThrownBy(() -> service.entregar(1L, 10L))
                .isInstanceOf(EntregaInvalidaException.class)
                .hasMessageContaining("expedida");

        assertThat(os.isEntregue()).isFalse();
    }

    @Test
    void recusaOsCancelada() throws Exception {
        OrdemServico os = ordemExpedida(1L);
        os.setCancelada(true);
        when(osRepo.findById(1L)).thenReturn(Optional.of(os));

        assertThatThrownBy(() -> service.entregar(1L, 10L))
                .isInstanceOf(EntregaInvalidaException.class)
                .hasMessageContaining("cancelada");

        assertThat(os.isEntregue()).isFalse();
    }

    /**
     * A segunda entrega não reescreve a primeira: o carimbo é o registo de um
     * evento, e sobrepô-lo apagaria quem o assinou.
     */
    @Test
    void recusaSegundaEntregaSemApagarAPrimeira() throws Exception {
        OrdemServico os = ordemExpedida(1L);
        Operador primeiro = operador("Rita");
        os.setEntregueEm(T1);
        os.setEntreguePor(primeiro);

        when(osRepo.findById(1L)).thenReturn(Optional.of(os));

        assertThatThrownBy(() -> service.entregar(1L, 20L))
                .isInstanceOf(EntregaInvalidaException.class)
                .hasMessageContaining("entregue");

        assertThat(os.getEntregueEm()).isEqualTo(T1);
        assertThat(os.getEntreguePor()).isSameAs(primeiro);
    }

    @Test
    void recusaOperadorInativo() throws Exception {
        OrdemServico os = ordemExpedida(1L);
        Operador demitido = operador("Rita");
        demitido.setAtivo(false);

        when(osRepo.findById(1L)).thenReturn(Optional.of(os));
        when(operadorRepo.findById(10L)).thenReturn(Optional.of(demitido));

        assertThatThrownBy(() -> service.entregar(1L, 10L))
                .isInstanceOf(OperadorInativoException.class);

        // A validação vem antes da mutação: nada de OS meio entregue.
        assertThat(os.isEntregue()).isFalse();
    }

    @Test
    void recusaOsInexistente() {
        when(osRepo.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.entregar(99L, 10L))
                .isInstanceOf(RecursoNaoEncontradoException.class);
    }

    /**
     * Reabrir desfaz a expedição, e a entrega ia com ela: a OS voltou a
     * produzir, logo as peças não estão mais com o cliente. Deixar o carimbo
     * também violaria ck_os_entrega, que o prende a `finalizada_em`.
     */
    @Test
    void reaberturaLimpaOCarimboDeEntrega() throws Exception {
        OrdemServico os = ordemExpedida(1L);
        os.setEntregueEm(T1);
        os.setEntreguePor(operador("Rita"));
        Lote primeiro = lote(os, (short) 1, T1);
        set(os, "lotes", new ArrayList<>(List.of(primeiro)));

        when(osRepo.findById(1L)).thenReturn(Optional.of(os));
        when(operadorRepo.findById(10L)).thenReturn(Optional.of(operador("Joao")));
        when(loteRepo.findByOrdemServicoIdOrderByNumeroAsc(1L)).thenReturn(List.of(primeiro));
        when(loteRepo.save(any(Lote.class))).thenAnswer(inv -> inv.getArgument(0));

        service.reabrir(1L, 10L);

        assertThat(os.isEntregue()).isFalse();
        assertThat(os.getEntreguePor()).isNull();
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
        os.setFinalizadaPor(operador("Joao"));
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

    private Operador operador(String nome) {
        return new Operador(nome, Permissao.FUNCIONARIO, null);
    }

    /** Os carimbos e ids são gerados pelo banco; no teste eles entram por reflexão. */
    private void set(Object alvo, String campo, Object valor) throws Exception {
        Field f = alvo.getClass().getDeclaredField(campo);
        f.setAccessible(true);
        f.set(alvo, valor);
    }
}
