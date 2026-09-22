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
import com.ramajo.logs.system.entities.ProcessoInicial;
import com.ramajo.logs.system.enums.Etapa;
import com.ramajo.logs.system.enums.Permissao;
import com.ramajo.logs.system.enums.Posicao;
import com.ramajo.logs.system.enums.TipoCarga;
import com.ramajo.logs.system.exceptions.AcoplamentoInvalidoException;
import com.ramajo.logs.system.exceptions.CargaInativaException;
import com.ramajo.logs.system.exceptions.CargaNaoVinculadaException;
import com.ramajo.logs.system.exceptions.OrdemForaDeCirculacaoException;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Acoplamento por CARGA: as peças de outra OS estão no mesmo tanque.
 *
 * Duas coisas justificam a suíte, e é por elas que quase todo caso aqui olha
 * para algo além do valor de retorno:
 *
 * 1. O vínculo é da carga, não do passo — então a etapa SEGUINTE tem de nascer
 *    acoplada sem ninguém remarcar nada. É o ponto inteiro do modelo, coberto
 *    por passoNovoNaCargaHerdaAsAcopladas.
 * 2. Acoplar FECHA os passos abertos da carona, e `logs` é append-only: nenhuma
 *    recusa posterior desfaz isso. Daí metade dos casos verificar, além da
 *    exception, que o passo da carona não chegou a ser tocado.
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

    /* -- acoplar ---------------------------------------------------------- */

    @Test
    void acoplaEFechaOsPassosAbertosDaCarona() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.OXIDACAO);
        OrdemServico carona = ordem(2L, Posicao.OXIDACAO);
        Carga carga = carga(10L, titular);
        Log passoDaCarona = log(carona, carga(11L, carona), "Desengraxe");

        when(cargaRepo.findById(10L)).thenReturn(Optional.of(carga));
        when(osRepo.findById(2L)).thenReturn(Optional.of(carona));
        when(cargaRepo.buscarAcoplamentosDe(2L)).thenReturn(List.of());
        when(logRepo.findByOrdemServicoIdAndFinalizadoEmIsNull(2L))
                .thenReturn(List.of(passoDaCarona));
        when(logRepo.findByCargaIdAndFinalizadoEmIsNull(10L)).thenReturn(Optional.empty());

        operadorNoTerminal();
        assertThat(service.acoplarNaCarga(10L, 2L, 7L)).isSameAs(carga);

        assertThat(carga.getOrdensAcopladas()).containsExactly(2L);
        // As peças saíram da carga própria: o passo dela não continua correndo.
        assertThat(passoDaCarona.getFinalizadoEm()).isNotNull();
    }

    /**
     * A etapa que já está a correr na carga passa a valer para a carona sem ser
     * reaberta nem substituída: abrir um passo novo só para reescrever a
     * composição cortaria a duração real em duas e inventaria na linha do tempo
     * um passo que ninguém executou.
     */
    @Test
    void acoplarInjetaNoPassoEmCursoSemOTocar() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.OXIDACAO);
        OrdemServico carona = ordem(2L, Posicao.OXIDACAO);
        Carga carga = carga(10L, titular);
        Log emCurso = log(titular, carga, "Banho ácido");

        when(cargaRepo.findById(10L)).thenReturn(Optional.of(carga));
        when(osRepo.findById(2L)).thenReturn(Optional.of(carona));
        when(cargaRepo.buscarAcoplamentosDe(2L)).thenReturn(List.of());
        when(logRepo.findByOrdemServicoIdAndFinalizadoEmIsNull(2L)).thenReturn(List.of());
        when(logRepo.findByCargaIdAndFinalizadoEmIsNull(10L)).thenReturn(Optional.of(emCurso));

        operadorNoTerminal();
        service.acoplarNaCarga(10L, 2L, 7L);

        assertThat(emCurso.getOrdensAcopladas()).containsExactly(2L);
        assertThat(emCurso.getFinalizadoEm()).isNull();
        assertThat(emCurso.getIniciadoEm()).isEqualTo(T0);
    }

    /** Passo cancelado afirma que o processo NÃO aconteceu; carona nele é carona em nada. */
    @Test
    void acoplarNaoInjetaEmPassoCancelado() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.OXIDACAO);
        OrdemServico carona = ordem(2L, Posicao.OXIDACAO);
        Carga carga = carga(10L, titular);
        Log cancelado = log(titular, carga, "Banho ácido");
        cancelado.setCancelado(true);

        when(cargaRepo.findById(10L)).thenReturn(Optional.of(carga));
        when(osRepo.findById(2L)).thenReturn(Optional.of(carona));
        when(cargaRepo.buscarAcoplamentosDe(2L)).thenReturn(List.of());
        when(logRepo.findByOrdemServicoIdAndFinalizadoEmIsNull(2L)).thenReturn(List.of());
        when(logRepo.findByCargaIdAndFinalizadoEmIsNull(10L)).thenReturn(Optional.of(cancelado));

        operadorNoTerminal();
        service.acoplarNaCarga(10L, 2L, 7L);

        // A carga recebe a carona — o próximo passo real será dela também.
        assertThat(carga.getOrdensAcopladas()).containsExactly(2L);
        assertThat(cancelado.getOrdensAcopladas()).isEmpty();
    }

    /**
     * Dois terminais no mesmo tanque tocam o botão: a segunda chamada só afirma
     * o que já é verdade. Não pode custar um passo à carona — que a essa altura
     * já é outro, aberto depois do primeiro acoplamento.
     */
    @Test
    void repetirNaoFechaPassoNenhum() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.OXIDACAO);
        Carga carga = carga(10L, titular);
        carga.getOrdensAcopladas().add(2L);

        when(cargaRepo.findById(10L)).thenReturn(Optional.of(carga));

        operadorNoTerminal();
        assertThat(service.acoplarNaCarga(10L, 2L, 7L)).isSameAs(carga);

        assertThat(carga.getOrdensAcopladas()).containsExactly(2L);
        verify(logRepo, never()).findByOrdemServicoIdAndFinalizadoEmIsNull(any());
        verifyNoInteractions(osRepo);
    }

    /** Carona pressupõe alguém a dar boleia: carga livre não tem titular. */
    @Test
    void recusaCargaSemTitular() throws Exception {
        Carga carga = carga(10L, null);

        when(cargaRepo.findById(10L)).thenReturn(Optional.of(carga));

        operadorNoTerminal();
        assertThatThrownBy(() -> service.acoplarNaCarga(10L, 2L, 7L))
                .isInstanceOf(CargaNaoVinculadaException.class);
        assertThat(carga.getOrdensAcopladas()).isEmpty();
        verifyNoInteractions(osRepo);
    }

    @Test
    void recusaCargaInativa() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.OXIDACAO);
        Carga carga = carga(10L, titular);
        carga.setAtivo(false);

        when(cargaRepo.findById(10L)).thenReturn(Optional.of(carga));

        operadorNoTerminal();
        assertThatThrownBy(() -> service.acoplarNaCarga(10L, 2L, 7L))
                .isInstanceOf(CargaInativaException.class);
        verifyNoInteractions(osRepo);
    }

    @Test
    void recusaATitularDaPropriaCarga() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.OXIDACAO);
        Carga carga = carga(10L, titular);

        when(cargaRepo.findById(10L)).thenReturn(Optional.of(carga));

        operadorNoTerminal();
        assertThatThrownBy(() -> service.acoplarNaCarga(10L, 1L, 7L))
                .isInstanceOf(AcoplamentoInvalidoException.class)
                .extracting("codigo").isEqualTo("ACOPLAMENTO_A_SI_MESMA");
        verifyNoInteractions(osRepo);
    }

    /** Uma carga está num setor só: OS de posições diferentes não a dividem. */
    @Test
    void recusaPosicaoDiferenteSemFecharPassoDaCarona() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.OXIDACAO);
        OrdemServico carona = ordem(2L, Posicao.PENDURADO);
        Carga carga = carga(10L, titular);

        when(cargaRepo.findById(10L)).thenReturn(Optional.of(carga));
        when(osRepo.findById(2L)).thenReturn(Optional.of(carona));

        operadorNoTerminal();
        assertThatThrownBy(() -> service.acoplarNaCarga(10L, 2L, 7L))
                .isInstanceOf(AcoplamentoInvalidoException.class)
                .extracting("codigo").isEqualTo("ACOPLAMENTO_POSICAO_INCOMPATIVEL");
        assertThat(carga.getOrdensAcopladas()).isEmpty();
        verify(logRepo, never()).findByOrdemServicoIdAndFinalizadoEmIsNull(any());
    }

    @Test
    void recusaOrdemForaDeCirculacao() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.OXIDACAO);
        OrdemServico carona = ordem(2L, Posicao.OXIDACAO);
        carona.setCancelada(true);
        Carga carga = carga(10L, titular);

        when(cargaRepo.findById(10L)).thenReturn(Optional.of(carga));
        when(osRepo.findById(2L)).thenReturn(Optional.of(carona));

        operadorNoTerminal();
        assertThatThrownBy(() -> service.acoplarNaCarga(10L, 2L, 7L))
                .isInstanceOf(OrdemForaDeCirculacaoException.class);
        verify(logRepo, never()).findByOrdemServicoIdAndFinalizadoEmIsNull(any());
    }

    /** Peças num tanque só: carona em duas cargas não descreve nada real. */
    @Test
    void recusaCaronaJaAcopladaNoutraCarga() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.OXIDACAO);
        OrdemServico carona = ordem(2L, Posicao.OXIDACAO);
        OrdemServico outraTitular = ordem(3L, Posicao.OXIDACAO);
        Carga carga = carga(10L, titular);
        Carga outra = carga(11L, outraTitular);
        outra.getOrdensAcopladas().add(2L);

        when(cargaRepo.findById(10L)).thenReturn(Optional.of(carga));
        when(osRepo.findById(2L)).thenReturn(Optional.of(carona));
        when(cargaRepo.buscarAcoplamentosDe(2L)).thenReturn(List.of(outra));

        operadorNoTerminal();
        assertThatThrownBy(() -> service.acoplarNaCarga(10L, 2L, 7L))
                .isInstanceOf(AcoplamentoInvalidoException.class)
                .extracting("codigo").isEqualTo("ACOPLAMENTO_EM_OUTRA_CARGA");
        assertThat(carga.getOrdensAcopladas()).isEmpty();
        verify(logRepo, never()).findByOrdemServicoIdAndFinalizadoEmIsNull(any());
    }

    @Test
    void recusaAcimaDoTetoPorCarga() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.OXIDACAO);
        OrdemServico carona = ordem(9L, Posicao.OXIDACAO);
        Carga carga = carga(10L, titular);
        for (long id = 2L; id <= 6L; id++) {
            carga.getOrdensAcopladas().add(id);
        }

        when(cargaRepo.findById(10L)).thenReturn(Optional.of(carga));
        when(osRepo.findById(9L)).thenReturn(Optional.of(carona));

        operadorNoTerminal();
        assertThatThrownBy(() -> service.acoplarNaCarga(10L, 9L, 7L))
                .isInstanceOf(AcoplamentoInvalidoException.class)
                .extracting("codigo").isEqualTo("ACOPLAMENTO_EXCEDE_LIMITE");
        assertThat(carga.getOrdensAcopladas()).doesNotContain(9L);
    }

    @Test
    void cargaInexistenteEhRecursoNaoEncontrado() {
        when(cargaRepo.findById(99L)).thenReturn(Optional.empty());

        operadorNoTerminal();
        assertThatThrownBy(() -> service.acoplarNaCarga(99L, 2L, 7L))
                .isInstanceOf(RecursoNaoEncontradoException.class);
    }

    /* -- desacoplar ------------------------------------------------------- */

    /**
     * Sai da carga E do passo em curso: a composição de um passo aberto ainda é
     * corrigível. Os passos já fechados ficam como estão — `logs` é append-only
     * justamente para que o registro do que aconteceu não seja reescrito.
     */
    @Test
    void desacoplarSaiDaCargaEDoPassoEmCurso() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.OXIDACAO);
        Carga carga = carga(10L, titular);
        carga.getOrdensAcopladas().add(2L);
        Log emCurso = log(titular, carga, "Banho ácido");
        emCurso.getOrdensAcopladas().add(2L);

        when(cargaRepo.findById(10L)).thenReturn(Optional.of(carga));
        when(logRepo.findByCargaIdAndFinalizadoEmIsNull(10L)).thenReturn(Optional.of(emCurso));

        service.desacoplarDaCarga(10L, 2L);

        assertThat(carga.getOrdensAcopladas()).isEmpty();
        assertThat(emCurso.getOrdensAcopladas()).isEmpty();
        assertThat(emCurso.getFinalizadoEm()).isNull();
    }

    /** A carga existe e a OS também; o que não existe é o vínculo entre elas. */
    @Test
    void desacoplarSemVinculoEhRecusado() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.OXIDACAO);
        Carga carga = carga(10L, titular);

        when(cargaRepo.findById(10L)).thenReturn(Optional.of(carga));

        assertThatThrownBy(() -> service.desacoplarDaCarga(10L, 2L))
                .isInstanceOf(AcoplamentoInvalidoException.class)
                .extracting("codigo").isEqualTo("ACOPLAMENTO_INEXISTENTE");
        verify(logRepo, never()).findByCargaIdAndFinalizadoEmIsNull(any());
    }

    /* -- a composição sobrevive à etapa ----------------------------------- */

    /**
     * O caso que motiva o modelo inteiro: abrir a etapa SEGUINTE sem passar id
     * nenhum, e ela já nascer acoplada. Antes, a composição morria com o passo
     * e alguém tinha de a remarcar a cada tanque.
     */
    @Test
    void passoNovoNaCargaHerdaAsAcopladas() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.OXIDACAO);
        OrdemServico carona = ordem(2L, Posicao.OXIDACAO);
        Carga carga = carga(10L, titular);
        carga.getOrdensAcopladas().add(2L);

        Log aberto = prepararAberturaDePasso(titular, carona, carga);

        assertThat(aberto.getOrdensAcopladas()).containsExactly(2L);
    }

    /**
     * Caronas caducam sozinhas: entre a declaração e a etapa seguinte, outro
     * terminal pode ter expedido a OS. Recusar a abertura por isso pararia o
     * chão de fábrica — a linha sai da carga e ninguém precisa de saber.
     */
    @Test
    void caronaExpedidaSaiDaCargaNaProximaEtapa() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.OXIDACAO);
        OrdemServico carona = ordem(2L, Posicao.OXIDACAO);
        carona.setCancelada(true);
        Carga carga = carga(10L, titular);
        carga.getOrdensAcopladas().add(2L);

        Log aberto = prepararAberturaDePasso(titular, carona, carga);

        assertThat(aberto.getOrdensAcopladas()).isEmpty();
        assertThat(carga.getOrdensAcopladas()).isEmpty();
    }

    /**
     * Encerrar a etapa desfaz a composição: as peças da carona saem do tanque
     * junto com as da titular e a carga volta ao pool vazia. O passo que fecha
     * guarda as suas — log_ordens_acopladas é o histórico, e é append-only.
     */
    @Test
    void liberarCargaDesacoplaAsCaronas() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.OXIDACAO);
        Carga carga = carga(10L, titular);
        carga.getOrdensAcopladas().add(2L);
        Log emCurso = log(titular, carga, "Banho ácido");
        emCurso.getOrdensAcopladas().add(2L);

        Operador op = new Operador("João", Permissao.FUNCIONARIO, "T1");
        when(osRepo.findById(1L)).thenReturn(Optional.of(titular));
        when(operadorRepo.findById(7L)).thenReturn(Optional.of(op));
        when(cargaRepo.findById(10L)).thenReturn(Optional.of(carga));
        when(logRepo.findByCargaIdAndFinalizadoEmIsNull(10L)).thenReturn(Optional.of(emCurso));

        service.liberarCargas(1L, 7L, List.of(10L));

        assertThat(carga.getOrdensAcopladas()).isEmpty();
        assertThat(carga.getOrdemAtual()).isNull();
        assertThat(emCurso.getFinalizadoEm()).isNotNull();
        assertThat(emCurso.getOrdensAcopladas()).containsExactly(2L);
    }

    /**
     * O outro lado de liberarCargaDesacoplaAsCaronas: a carga volta do pool
     * vazia, então a OS que a vincular a seguir começa do zero — não herda
     * carona nenhuma da OS anterior, nem no vínculo nem no passo inicial.
     */
    @Test
    void revincularCargaLiberadaNaoHerdaCaronas() throws Exception {
        OrdemServico anterior = ordem(1L, Posicao.OXIDACAO);
        OrdemServico nova = ordem(3L, Posicao.OXIDACAO);

        Carga carga = carga(10L, anterior);
        carga.getOrdensAcopladas().add(2L);

        Processo processo = new Processo("Banho ácido", Etapa.TRATAMENTO);
        processo.getPosicoes().add(Posicao.OXIDACAO);
        Operador op = new Operador("João", Permissao.FUNCIONARIO, "T1");

        when(osRepo.findById(1L)).thenReturn(Optional.of(anterior));
        when(osRepo.findById(3L)).thenReturn(Optional.of(nova));
        when(operadorRepo.findById(7L)).thenReturn(Optional.of(op));
        when(cargaRepo.findById(10L)).thenReturn(Optional.of(carga));
        when(processoInicialRepo.findById(Posicao.OXIDACAO))
                .thenReturn(Optional.of(new ProcessoInicial(Posicao.OXIDACAO, processo)));
        when(logRepo.findByCargaIdAndFinalizadoEmIsNull(10L)).thenReturn(Optional.empty());
        when(logRepo.save(any(Log.class))).thenAnswer(i -> i.getArgument(0));

        service.liberarCargas(1L, 7L, List.of(10L));
        Log passo = service.vincularCarga(3L, 10L, 7L, List.of());

        assertThat(carga.getOrdemAtual()).isSameAs(nova);
        assertThat(carga.getOrdensAcopladas()).isEmpty();
        assertThat(passo.getOrdensAcopladas()).isEmpty();
    }

    /**
     * Expedir a CARONA tira-a da carga alheia: as peças dela saíram do tanque.
     * acopladasVigentes() já a ignoraria no próximo passo, mas até lá a linha
     * órfã apareceria no detalhe da carga como se ela continuasse lá dentro.
     */
    @Test
    void finalizarOsCaronaSaiDaCargaAlheia() throws Exception {
        OrdemServico carona = ordem(2L, Posicao.OXIDACAO);
        Carga alheia = carga(10L, ordem(1L, Posicao.OXIDACAO));
        alheia.getOrdensAcopladas().add(2L);

        Operador op = new Operador("João", Permissao.FUNCIONARIO, "T1");
        when(osRepo.findById(2L)).thenReturn(Optional.of(carona));
        when(operadorRepo.findById(7L)).thenReturn(Optional.of(op));
        when(logRepo.findByOrdemServicoIdAndFinalizadoEmIsNull(2L)).thenReturn(List.of());
        when(loteRepo.findByOrdemServicoIdAndFinalizadoEmIsNull(2L)).thenReturn(Optional.empty());
        when(cargaRepo.buscarAcoplamentosDe(2L)).thenReturn(List.of(alheia));

        service.finalizar(2L, 7L);

        assertThat(alheia.getOrdensAcopladas()).isEmpty();
        // A carga não é dela: continua vinculada à titular, que segue a produzir.
        assertThat(alheia.getOrdemAtual()).isNotNull();
    }

    /** Mesma regra de finalizarOsCaronaSaiDaCargaAlheia, pelo caminho do cancelamento. */
    @Test
    void cancelarOsCaronaSaiDaCargaAlheia() throws Exception {
        OrdemServico carona = ordem(2L, Posicao.OXIDACAO);
        Carga alheia = carga(10L, ordem(1L, Posicao.OXIDACAO));
        alheia.getOrdensAcopladas().add(2L);

        Operador op = new Operador("João", Permissao.FUNCIONARIO, "T1");
        when(osRepo.findById(2L)).thenReturn(Optional.of(carona));
        when(operadorRepo.findById(7L)).thenReturn(Optional.of(op));
        when(logRepo.findByOrdemServicoIdAndFinalizadoEmIsNull(2L)).thenReturn(List.of());
        when(cargaRepo.buscarAcoplamentosDe(2L)).thenReturn(List.of(alheia));

        service.cancelar(2L, 7L);

        assertThat(alheia.getOrdensAcopladas()).isEmpty();
        assertThat(alheia.getOrdemAtual()).isNotNull();
    }

    /**
     * Expedir a TITULAR solta as cargas dela, e soltar uma carga é desfazer a
     * composição: a carona sai do tanque junto. Sem isto a carga voltaria ao
     * pool a levar peças de uma OS que ninguém escolheu — e a próxima OS a
     * vinculá-la herdaria-as.
     */
    @Test
    void finalizarOsLimpaCaronasDasCargasProprias() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.OXIDACAO);
        Carga propria = carga(10L, titular);
        propria.getOrdensAcopladas().add(2L);
        titular.getCargas().add(propria);

        Operador op = new Operador("João", Permissao.FUNCIONARIO, "T1");
        when(osRepo.findById(1L)).thenReturn(Optional.of(titular));
        when(operadorRepo.findById(7L)).thenReturn(Optional.of(op));
        when(logRepo.findByOrdemServicoIdAndFinalizadoEmIsNull(1L)).thenReturn(List.of());
        when(loteRepo.findByOrdemServicoIdAndFinalizadoEmIsNull(1L)).thenReturn(Optional.empty());
        when(cargaRepo.buscarAcoplamentosDe(1L)).thenReturn(List.of());

        service.finalizar(1L, 7L);

        assertThat(propria.getOrdensAcopladas()).isEmpty();
        assertThat(propria.getOrdemAtual()).isNull();
        assertThat(titular.getCargasExpedidas()).containsExactly(10L);
    }

    /** Mesma regra de finalizarOsLimpaCaronasDasCargasProprias, pelo cancelamento. */
    @Test
    void cancelarOsLimpaCaronasDasCargasProprias() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.OXIDACAO);
        Carga propria = carga(10L, titular);
        propria.getOrdensAcopladas().add(2L);
        titular.getCargas().add(propria);

        Operador op = new Operador("João", Permissao.FUNCIONARIO, "T1");
        when(osRepo.findById(1L)).thenReturn(Optional.of(titular));
        when(operadorRepo.findById(7L)).thenReturn(Optional.of(op));
        when(logRepo.findByOrdemServicoIdAndFinalizadoEmIsNull(1L)).thenReturn(List.of());
        when(cargaRepo.buscarAcoplamentosDe(1L)).thenReturn(List.of());

        service.cancelar(1L, 7L);

        assertThat(propria.getOrdensAcopladas()).isEmpty();
        assertThat(propria.getOrdemAtual()).isNull();
    }


    /* -- multi-setor (V19) ------------------------------------------------ */

    /**
     * A carona roda em DOIS setores e acopla-se numa carga de um deles.
     *
     * O que vale é o setor da CARGA estar entre os dela — não os conjuntos
     * serem iguais. A titular aqui roda só na AUTOMATICA; a carona, nos dois.
     */
    @Test
    void caronaDeDoisSetoresAcoplaNaCargaDeUmDeles() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.AUTOMATICA);
        OrdemServico carona = ordem(2L, Posicao.PENDURADO);
        carona.getPosicoes().add(Posicao.AUTOMATICA);
        Carga carga = carga(10L, titular);

        when(cargaRepo.findById(10L)).thenReturn(Optional.of(carga));
        when(osRepo.findById(2L)).thenReturn(Optional.of(carona));
        when(cargaRepo.buscarAcoplamentosDe(2L)).thenReturn(List.of());
        when(logRepo.findByOrdemServicoIdAndFinalizadoEmIsNull(2L)).thenReturn(List.of());
        when(logRepo.findByCargaIdAndFinalizadoEmIsNull(10L)).thenReturn(Optional.empty());

        operadorNoTerminal();
        service.acoplarNaCarga(10L, 2L, 7L);

        assertThat(carga.getOrdensAcopladas()).containsExactly(2L);
    }

    /** A carona não roda no setor da carga: as peças dela não podem estar lá. */
    @Test
    void recusaCaronaQueNaoRodaNoSetorDaCarga() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.PENDURADO);
        OrdemServico carona = ordem(2L, Posicao.OXIDACAO);
        carona.getPosicoes().add(Posicao.AUTOMATICA);   // nenhum deles é PENDURADO
        Carga carga = carga(10L, titular);

        when(cargaRepo.findById(10L)).thenReturn(Optional.of(carga));
        when(osRepo.findById(2L)).thenReturn(Optional.of(carona));

        operadorNoTerminal();
        assertThatThrownBy(() -> service.acoplarNaCarga(10L, 2L, 7L))
                .isInstanceOf(AcoplamentoInvalidoException.class)
                .extracting("codigo").isEqualTo("ACOPLAMENTO_POSICAO_INCOMPATIVEL");
        assertThat(carga.getOrdensAcopladas()).isEmpty();
    }

    /**
     * "Peças num tanque só" passou a valer POR SETOR: uma OS de dois setores
     * tem mesmo peças em dois tanques, um de cada lado da fábrica, e recusar o
     * segundo seria recusar o facto.
     */
    @Test
    void caronaDeDoisSetoresPodeEstarNumaCargaDeCadaSetor() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.AUTOMATICA);
        OrdemServico carona = ordem(2L, Posicao.PENDURADO);
        carona.getPosicoes().add(Posicao.AUTOMATICA);

        Carga naAutomatica = carga(10L, titular);
        // já pega carona numa carga do OUTRO setor
        Carga noPendurado = carga(11L, null, Posicao.PENDURADO);

        when(cargaRepo.findById(10L)).thenReturn(Optional.of(naAutomatica));
        when(osRepo.findById(2L)).thenReturn(Optional.of(carona));
        when(cargaRepo.buscarAcoplamentosDe(2L)).thenReturn(List.of(noPendurado));
        when(logRepo.findByOrdemServicoIdAndFinalizadoEmIsNull(2L)).thenReturn(List.of());
        when(logRepo.findByCargaIdAndFinalizadoEmIsNull(10L)).thenReturn(Optional.empty());

        operadorNoTerminal();
        service.acoplarNaCarga(10L, 2L, 7L);

        assertThat(naAutomatica.getOrdensAcopladas()).containsExactly(2L);
    }

    /** Mas duas cargas do MESMO setor continuam a ser incompatíveis. */
    @Test
    void recusaCaronaJaEmOutraCargaDoMesmoSetor() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.AUTOMATICA);
        OrdemServico carona = ordem(2L, Posicao.PENDURADO);
        carona.getPosicoes().add(Posicao.AUTOMATICA);

        Carga alvo = carga(10L, titular);
        Carga outraDaAutomatica = carga(11L, null, Posicao.AUTOMATICA);

        when(cargaRepo.findById(10L)).thenReturn(Optional.of(alvo));
        when(osRepo.findById(2L)).thenReturn(Optional.of(carona));
        when(cargaRepo.buscarAcoplamentosDe(2L)).thenReturn(List.of(outraDaAutomatica));

        operadorNoTerminal();
        assertThatThrownBy(() -> service.acoplarNaCarga(10L, 2L, 7L))
                .isInstanceOf(AcoplamentoInvalidoException.class)
                .extracting("codigo").isEqualTo("ACOPLAMENTO_EM_OUTRA_CARGA");
        assertThat(alvo.getOrdensAcopladas()).isEmpty();
    }

    /**
     * Acoplar fecha os passos da carona NAQUELE setor — e só neles. O que ela
     * tem a correr do outro lado da fábrica não foi tocado por este
     * acoplamento e continua aberto.
     *
     * Sem o filtro, acoplar na automática pararia a produção do pendurado.
     */
    @Test
    void acoplarFechaSoOsPassosDoSetorDaCarga() throws Exception {
        OrdemServico titular = ordem(1L, Posicao.AUTOMATICA);
        OrdemServico carona = ordem(2L, Posicao.PENDURADO);
        carona.getPosicoes().add(Posicao.AUTOMATICA);
        Carga carga = carga(10L, titular);

        Log noMesmoSetor = log(carona, carga(20L, carona, Posicao.AUTOMATICA), "Desengraxe");
        Log noOutroSetor = log(carona, carga(21L, carona, Posicao.PENDURADO), "Banho ácido");

        when(cargaRepo.findById(10L)).thenReturn(Optional.of(carga));
        when(osRepo.findById(2L)).thenReturn(Optional.of(carona));
        when(cargaRepo.buscarAcoplamentosDe(2L)).thenReturn(List.of());
        when(logRepo.findByOrdemServicoIdAndFinalizadoEmIsNull(2L))
                .thenReturn(List.of(noMesmoSetor, noOutroSetor));
        when(logRepo.findByCargaIdAndFinalizadoEmIsNull(10L)).thenReturn(Optional.empty());

        operadorNoTerminal();
        service.acoplarNaCarga(10L, 2L, 7L);

        assertThat(noMesmoSetor.getFinalizadoEm())
                .as("as peças saíram da carga deste setor").isNotNull();
        assertThat(noOutroSetor.getFinalizadoEm())
                .as("o outro setor não foi tocado por este acoplamento").isNull();
    }

    /* -- fixtures --------------------------------------------------------- */

    /**
     * Os mocks de uma abertura de passo pelo caminho normal (iniciarLog), que
     * são muitos e iguais nos dois casos de herança. Devolve o passo aberto.
     */
    private Log prepararAberturaDePasso(OrdemServico titular, OrdemServico carona, Carga carga)
            throws Exception {
        Processo processo = new Processo("Banho ácido", Etapa.TRATAMENTO);
        // O processo tem de rodar onde a CARGA está — é dela que abrirLog lê.
        processo.getPosicoes().add(carga.getPosicao());
        Operador op = new Operador("João", Permissao.FUNCIONARIO, "T1");

        when(osRepo.findById(1L)).thenReturn(Optional.of(titular));
        when(cargaRepo.findById(10L)).thenReturn(Optional.of(carga));
        when(processoRepo.findById(5L)).thenReturn(Optional.of(processo));
        when(operadorRepo.findById(7L)).thenReturn(Optional.of(op));
        when(osRepo.findById(carona.getId())).thenReturn(Optional.of(carona));
        when(logRepo.findByCargaIdAndFinalizadoEmIsNull(10L)).thenReturn(Optional.empty());
        when(logRepo.save(any(Log.class))).thenAnswer(i -> i.getArgument(0));

        return service.iniciarLog(1L, 10L, 5L, 7L);
    }

    private OrdemServico ordem(Long id, Posicao posicao) throws Exception {
        OrdemServico os = new OrdemServico(id, new Cliente(1L, "ACME LTDA"), posicao);
        set(os, "id", id);
        set(os, "iniciadaEm", T0);
        return os;
    }

    /** Carga no primeiro setor da titular — o único, nas OS de sempre. */
    private Carga carga(Long id, OrdemServico titular) throws Exception {
        Posicao posicao = titular != null
                ? titular.getPosicoesOrdenadas().get(0)
                : Posicao.OXIDACAO;
        return carga(id, titular, posicao);
    }

    /** Carga num setor ESCOLHIDO: para a titular que roda em mais de um. */
    private Carga carga(Long id, OrdemServico titular, Posicao posicao) throws Exception {
        Carga c = new Carga("TAMBOR-" + id, TipoCarga.TAMBOR, posicao);
        set(c, "id", id);
        c.setOrdemAtual(titular);
        return c;
    }

    /**
     * Quem está no terminal ao acoplar — é em nome dele que os passos da
     * carona fecham. Sempre o 7L, como no resto da suíte.
     */
    private void operadorNoTerminal() {
        when(operadorRepo.findById(7L))
                .thenReturn(Optional.of(new Operador("João", Permissao.FUNCIONARIO, "T1")));
    }

    private Log log(OrdemServico titular, Carga carga, String processo) throws Exception {
        Log l = new Log(titular, new Operador("João", Permissao.FUNCIONARIO, "T1"), carga,
                new Processo(processo, Etapa.TRATAMENTO));
        set(l, "id", UUID.randomUUID());
        set(l, "iniciadoEm", T0);
        return l;
    }

    /** Os carimbos e ids são gerados pelo banco; no teste eles entram por reflexão. */
    private void set(Object alvo, String campo, Object valor) throws Exception {
        Field f = alvo.getClass().getDeclaredField(campo);
        f.setAccessible(true);
        f.set(alvo, valor);
    }
}
