package com.ramajo.logs.system.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ramajo.logs.system.entities.Carga;
import com.ramajo.logs.system.entities.Cliente;
import com.ramajo.logs.system.entities.Log;
import com.ramajo.logs.system.entities.Operador;
import com.ramajo.logs.system.entities.OrdemServico;
import com.ramajo.logs.system.entities.Processo;
import com.ramajo.logs.system.enums.Etapa;
import com.ramajo.logs.system.enums.Permissao;
import com.ramajo.logs.system.enums.Posicao;
import com.ramajo.logs.system.enums.TipoCarga;
import com.ramajo.logs.system.exceptions.AcoplamentoInvalidoException;
import com.ramajo.logs.system.exceptions.OrdemForaDeCirculacaoException;
import com.ramajo.logs.system.exceptions.PassoJaFinalizadoException;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Acoplamento tardio: a OS entra num passo que JÁ está a correr.
 *
 * O que se testa aqui não é o CRUD do vínculo — é o efeito colateral que o
 * torna irreversível: acoplar FECHA os passos abertos da carona, e `logs` é
 * append-only, então nenhuma recusa posterior desfaz isso. Daí metade dos casos
 * abaixo verificar, além da exception, que o passo da carona não chegou a ser
 * tocado.
 */
@ExtendWith(MockitoExtension.class)
class OrdemServicoServiceAcoplamentoTest {

    private static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");

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
    void acoplaEFechaOsPassosAbertosDaCarona() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.OXIDACAO);
        OrdemServico carona = ordem(2L, Posicao.OXIDACAO);
        Log passo = log(titular, "Banho ácido", false);
        Log passoDaCarona = log(carona, "Desengraxe", false);

        when(logRepo.findById(passo.getId())).thenReturn(Optional.of(passo));
        when(osRepo.findById(2L)).thenReturn(Optional.of(carona));
        when(logRepo.buscarAcoplamentosAbertos(2L)).thenReturn(List.of());
        when(logRepo.findByOrdemServicoIdAndFinalizadoEmIsNull(2L))
                .thenReturn(List.of(passoDaCarona));

        Log devolvido = service.acoplar(passo.getId(), 2L);

        assertThat(devolvido).isSameAs(passo);
        assertThat(passo.getOrdensAcopladas()).containsExactly(2L);
        // As peças saíram da carga própria: o passo dela não continua correndo.
        assertThat(passoDaCarona.getFinalizadoEm()).isNotNull();
        // O passo da titular não é tocado — é o ponto todo do acoplamento
        // tardio: reabrir ou substituir cortaria a duração real em duas.
        assertThat(passo.getFinalizadoEm()).isNull();
        assertThat(passo.getIniciadoEm()).isEqualTo(T0);
    }

    /**
     * Dois terminais no mesmo tanque tocam o botão: a segunda chamada só afirma
     * o que já é verdade. Não pode custar um passo à carona — que a essa altura
     * já é outro, aberto depois do primeiro acoplamento.
     */
    @Test
    void repetirNaoFechaPassoNenhum() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.OXIDACAO);
        Log passo = log(titular, "Banho ácido", false);
        passo.getOrdensAcopladas().add(2L);

        when(logRepo.findById(passo.getId())).thenReturn(Optional.of(passo));

        assertThat(service.acoplar(passo.getId(), 2L)).isSameAs(passo);

        assertThat(passo.getOrdensAcopladas()).containsExactly(2L);
        verify(logRepo, never()).findByOrdemServicoIdAndFinalizadoEmIsNull(any());
        verifyNoInteractions(osRepo);
    }

    @Test
    void recusaPassoJaFinalizado() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.OXIDACAO);
        Log passo = log(titular, "Banho ácido", false);
        passo.setFinalizadoEm(T0.plus(30, ChronoUnit.MINUTES));

        when(logRepo.findById(passo.getId())).thenReturn(Optional.of(passo));

        assertThatThrownBy(() -> service.acoplar(passo.getId(), 2L))
                .isInstanceOf(PassoJaFinalizadoException.class);
        assertThat(passo.getOrdensAcopladas()).isEmpty();
    }

    /** Passo cancelado afirma que o processo NÃO aconteceu; carona nele é carona em nada. */
    @Test
    void recusaPassoCancelado() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.OXIDACAO);
        Log passo = log(titular, "Banho ácido", true);

        when(logRepo.findById(passo.getId())).thenReturn(Optional.of(passo));

        assertThatThrownBy(() -> service.acoplar(passo.getId(), 2L))
                .isInstanceOf(AcoplamentoInvalidoException.class)
                .extracting("codigo").isEqualTo("ACOPLAMENTO_PASSO_CANCELADO");
    }

    @Test
    void recusaATitularDoProprioPasso() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.OXIDACAO);
        Log passo = log(titular, "Banho ácido", false);

        when(logRepo.findById(passo.getId())).thenReturn(Optional.of(passo));

        assertThatThrownBy(() -> service.acoplar(passo.getId(), 1L))
                .isInstanceOf(AcoplamentoInvalidoException.class)
                .extracting("codigo").isEqualTo("ACOPLAMENTO_A_SI_MESMA");
        verifyNoInteractions(osRepo);
    }

    /** Uma carga está num setor só: OS de posições diferentes não a dividiram. */
    @Test
    void recusaPosicaoDiferenteSemFecharPassoDaCarona() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.OXIDACAO);
        OrdemServico carona = ordem(2L, Posicao.PENDURADO);
        Log passo = log(titular, "Banho ácido", false);

        when(logRepo.findById(passo.getId())).thenReturn(Optional.of(passo));
        when(osRepo.findById(2L)).thenReturn(Optional.of(carona));

        assertThatThrownBy(() -> service.acoplar(passo.getId(), 2L))
                .isInstanceOf(AcoplamentoInvalidoException.class)
                .extracting("codigo").isEqualTo("ACOPLAMENTO_POSICAO_INCOMPATIVEL");
        assertThat(passo.getOrdensAcopladas()).isEmpty();
        verify(logRepo, never()).findByOrdemServicoIdAndFinalizadoEmIsNull(any());
    }

    @Test
    void recusaOrdemForaDeCirculacao() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.OXIDACAO);
        OrdemServico carona = ordem(2L, Posicao.OXIDACAO);
        carona.setCancelada(true);
        Log passo = log(titular, "Banho ácido", false);

        when(logRepo.findById(passo.getId())).thenReturn(Optional.of(passo));
        when(osRepo.findById(2L)).thenReturn(Optional.of(carona));

        assertThatThrownBy(() -> service.acoplar(passo.getId(), 2L))
                .isInstanceOf(OrdemForaDeCirculacaoException.class);
        verify(logRepo, never()).findByOrdemServicoIdAndFinalizadoEmIsNull(any());
    }

    /** Peças num tanque só: carona em dois passos abertos não descreve nada real. */
    @Test
    void recusaCaronaJaAcopladaNoutroPassoAberto() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.OXIDACAO);
        OrdemServico carona = ordem(2L, Posicao.OXIDACAO);
        OrdemServico outraTitular = ordem(3L, Posicao.OXIDACAO);
        Log passo = log(titular, "Banho ácido", false);
        Log outroPasso = log(outraTitular, "Desengraxe", false);
        outroPasso.getOrdensAcopladas().add(2L);

        when(logRepo.findById(passo.getId())).thenReturn(Optional.of(passo));
        when(osRepo.findById(2L)).thenReturn(Optional.of(carona));
        when(logRepo.buscarAcoplamentosAbertos(2L)).thenReturn(List.of(outroPasso));

        assertThatThrownBy(() -> service.acoplar(passo.getId(), 2L))
                .isInstanceOf(AcoplamentoInvalidoException.class)
                .extracting("codigo").isEqualTo("ACOPLAMENTO_EM_OUTRO_PASSO");
        assertThat(passo.getOrdensAcopladas()).isEmpty();
        verify(logRepo, never()).findByOrdemServicoIdAndFinalizadoEmIsNull(any());
    }

    @Test
    void recusaAcimaDoTetoPorPasso() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.OXIDACAO);
        OrdemServico carona = ordem(9L, Posicao.OXIDACAO);
        Log passo = log(titular, "Banho ácido", false);
        for (long id = 2L; id <= 6L; id++) {
            passo.getOrdensAcopladas().add(id);
        }

        when(logRepo.findById(passo.getId())).thenReturn(Optional.of(passo));
        when(osRepo.findById(9L)).thenReturn(Optional.of(carona));

        assertThatThrownBy(() -> service.acoplar(passo.getId(), 9L))
                .isInstanceOf(AcoplamentoInvalidoException.class)
                .extracting("codigo").isEqualTo("ACOPLAMENTO_EXCEDE_LIMITE");
        assertThat(passo.getOrdensAcopladas()).doesNotContain(9L);
    }

    @Test
    void passoInexistenteEhRecursoNaoEncontrado() {
        UUID id = UUID.randomUUID();
        when(logRepo.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acoplar(id, 2L))
                .isInstanceOf(RecursoNaoEncontradoException.class);
    }

    /* -- fixtures --------------------------------------------------------- */

    private OrdemServico ordem(Long id, Posicao posicao) throws Exception {
        OrdemServico os = new OrdemServico(id, new Cliente(1L, "ACME LTDA"), posicao);
        set(os, "id", id);
        set(os, "iniciadaEm", T0);
        return os;
    }

    private Log log(OrdemServico titular, String processo, boolean cancelado) throws Exception {
        Carga c = new Carga("TAMBOR-0" + titular.getId(), TipoCarga.TAMBOR, titular.getPosicao());
        Log l = new Log(titular, new Operador("João", Permissao.FUNCIONARIO, "T1"), c,
                new Processo(processo, Etapa.TRATAMENTO));
        set(l, "id", UUID.randomUUID());
        set(l, "iniciadoEm", T0);
        l.setCancelado(cancelado);
        return l;
    }

    /** Os carimbos e ids são gerados pelo banco; no teste eles entram por reflexão. */
    private void set(Object alvo, String campo, Object valor) throws Exception {
        Field f = alvo.getClass().getDeclaredField(campo);
        f.setAccessible(true);
        f.set(alvo, valor);
    }
}
