package com.ramajo.logs.system.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ramajo.logs.system.entities.Carga;
import com.ramajo.logs.system.entities.Cliente;
import com.ramajo.logs.system.entities.Lote;
import com.ramajo.logs.system.entities.Operador;
import com.ramajo.logs.system.entities.OrdemServico;
import com.ramajo.logs.system.enums.Permissao;
import com.ramajo.logs.system.enums.Posicao;
import com.ramajo.logs.system.enums.TipoCarga;
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
import com.ramajo.logs.system.services.OrdemServicoService.Reabertura;
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
 * 3. As cargas que a expedição soltou voltam como SUGESTÃO, nunca como
 *    vínculo: `ordemAtual` continua nula em todas, e o snapshot que as
 *    guardava é consumido. Quem revincula é o operador, no modal.
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
    @Mock private OrdemAvaliacaoService avaliacaoService;

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

        Lote novo = service.reabrir(1L, 10L).lote();

        assertThat(novo.getNumero()).isEqualTo((short) 3);
        assertThat(novo.isFinalizado()).isFalse();
        assertThat(novo.getOrdemServico()).isSameAs(os);

        assertThat(os.isEmProcesso()).isTrue();
        assertThat(os.isFinalizada()).isFalse();
        assertThat(os.getFinalizadaPor()).isNull();

        // O preço da reabertura para na OS: o histórico de lotes não é tocado.
        assertThat(primeiro.getFinalizadoEm()).isEqualTo(T1);
        assertThat(segundo.getFinalizadoEm()).isEqualTo(T1);

        // A avaliação era da expedição desfeita.
        verify(avaliacaoService).descartar(1L);
    }

    /* -- a sugestão de cargas ---------------------------------------------- */

    /**
     * O caso que motivou o snapshot: sem ele o operador reabre a OS e fica
     * diante de todas as cargas livres do setor sem saber quais eram as dela.
     * O que a reabertura NÃO faz é revincular — repare no `ordemAtual` nulo.
     */
    @Test
    void sugereAsCargasQueAExpedicaoSoltouSemAsRevincular() throws Exception {
        OrdemServico os = ordemExpedida(1L);
        set(os, "lotes", new ArrayList<>(List.of(lote(os, (short) 1, T1))));

        Carga t01 = carga(7L, "T-01", Posicao.OXIDACAO);
        Carga t02 = carga(8L, "T-02", Posicao.OXIDACAO);
        os.getCargasExpedidas().addAll(List.of(7L, 8L));

        when(osRepo.findById(1L)).thenReturn(Optional.of(os));
        when(operadorRepo.findById(10L)).thenReturn(Optional.of(operador()));
        when(cargaRepo.findById(7L)).thenReturn(Optional.of(t01));
        when(cargaRepo.findById(8L)).thenReturn(Optional.of(t02));
        when(loteRepo.findByOrdemServicoIdOrderByNumeroAsc(1L))
                .thenReturn(List.of(lote(os, (short) 1, T1)));
        when(loteRepo.save(any(Lote.class))).thenAnswer(inv -> inv.getArgument(0));

        Reabertura r = service.reabrir(1L, 10L);

        assertThat(r.cargasSugeridas()).extracting(Carga::getNome)
                .containsExactly("T-01", "T-02");

        // Sugestão não é vínculo: nenhuma delas entrou na OS, e nenhum passo
        // foi aberto. Quem faz isso é vincularCarga, quando o operador
        // confirma no modal.
        assertThat(t01.getOrdemAtual()).isNull();
        assertThat(t02.getOrdemAtual()).isNull();
        verify(logRepo, never()).save(any());

        // Consumido: reabrir de novo, sem ter expedido no meio, não reoferece
        // o que o operador já viu (e talvez tenha descartado).
        assertThat(os.getCargasExpedidas()).isEmpty();
    }

    /**
     * Entre a expedição e a reabertura o chão de fábrica andou. Nada disto é
     * erro de quem reabre: a carga sai da sugestão em silêncio, como
     * acopladasVigentes() faz com a carona caduca.
     */
    @Test
    void descartaDaSugestaoACargaQueCaducou() throws Exception {
        OrdemServico os = ordemExpedida(1L);
        set(os, "lotes", new ArrayList<>(List.of(lote(os, (short) 1, T1))));

        Carga livre = carga(7L, "T-01", Posicao.OXIDACAO);

        Carga tomada = carga(8L, "T-02", Posicao.OXIDACAO);
        tomada.setOrdemAtual(ordem(2L));          // outra OS pegou-a

        Carga sucateada = carga(9L, "T-03", Posicao.OXIDACAO);
        sucateada.setAtivo(false);

        Carga mudouDeSetor = carga(11L, "T-04", Posicao.AUTOMATICA);

        os.getCargasExpedidas().addAll(List.of(7L, 8L, 9L, 11L, 12L));

        when(osRepo.findById(1L)).thenReturn(Optional.of(os));
        when(operadorRepo.findById(10L)).thenReturn(Optional.of(operador()));
        when(cargaRepo.findById(7L)).thenReturn(Optional.of(livre));
        when(cargaRepo.findById(8L)).thenReturn(Optional.of(tomada));
        when(cargaRepo.findById(9L)).thenReturn(Optional.of(sucateada));
        when(cargaRepo.findById(11L)).thenReturn(Optional.of(mudouDeSetor));
        when(cargaRepo.findById(12L)).thenReturn(Optional.empty());   // apagada
        when(loteRepo.findByOrdemServicoIdOrderByNumeroAsc(1L))
                .thenReturn(List.of(lote(os, (short) 1, T1)));
        when(loteRepo.save(any(Lote.class))).thenAnswer(inv -> inv.getArgument(0));

        Reabertura r = service.reabrir(1L, 10L);

        // Nenhuma exceção: sobra a que ainda pode voltar, e a OS reabre na mesma.
        assertThat(r.cargasSugeridas()).extracting(Carga::getNome).containsExactly("T-01");
        assertThat(os.isEmProcesso()).isTrue();
    }

    /** OS sem snapshot (expedida antes da V13, ou já reaberta) reabre vazia. */
    @Test
    void semSnapshotNaoSugereNada() throws Exception {
        OrdemServico os = ordemExpedida(1L);
        set(os, "lotes", new ArrayList<>(List.of(lote(os, (short) 1, T1))));

        when(osRepo.findById(1L)).thenReturn(Optional.of(os));
        when(operadorRepo.findById(10L)).thenReturn(Optional.of(operador()));
        when(loteRepo.findByOrdemServicoIdOrderByNumeroAsc(1L))
                .thenReturn(List.of(lote(os, (short) 1, T1)));
        when(loteRepo.save(any(Lote.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.reabrir(1L, 10L).cargasSugeridas()).isEmpty();
        verify(cargaRepo, never()).findById(any());
    }

    /**
     * O snapshot é um retrato da última expedição, não um acumulado: uma OS
     * expedida duas vezes com cargas diferentes só sugere as da segunda.
     */
    @Test
    void expedicaoNovaReescreveOSnapshotEmVezDeAcumular() throws Exception {
        OrdemServico os = ordem(1L);
        set(os, "lotes", new ArrayList<>(List.of(lote(os, (short) 1, null))));
        os.getCargasExpedidas().add(7L);          // sobra de uma expedição anterior

        Carga t02 = carga(8L, "T-02", Posicao.OXIDACAO);
        t02.setOrdemAtual(os);
        set(os, "cargas", new ArrayList<>(List.of(t02)));

        when(osRepo.findById(1L)).thenReturn(Optional.of(os));
        when(operadorRepo.findById(10L)).thenReturn(Optional.of(operador()));
        when(logRepo.findByOrdemServicoIdAndFinalizadoEmIsNull(1L)).thenReturn(List.of());
        when(cargaRepo.buscarAcoplamentosDe(1L)).thenReturn(List.of());
        when(loteRepo.findByOrdemServicoIdAndFinalizadoEmIsNull(1L)).thenReturn(Optional.empty());

        service.finalizar(1L, 10L);

        assertThat(os.getCargasExpedidas()).containsExactly(8L);
        assertThat(t02.getOrdemAtual()).isNull();
    }

    /** Cancelada não reabre, então não há sugestão a guardar. */
    @Test
    void cancelamentoNaoGravaSnapshot() throws Exception {
        OrdemServico os = ordem(1L);

        Carga t01 = carga(7L, "T-01", Posicao.OXIDACAO);
        t01.setOrdemAtual(os);
        set(os, "cargas", new ArrayList<>(List.of(t01)));

        when(osRepo.findById(1L)).thenReturn(Optional.of(os));
        when(operadorRepo.findById(10L)).thenReturn(Optional.of(operador()));
        when(logRepo.findByOrdemServicoIdAndFinalizadoEmIsNull(1L)).thenReturn(List.of());
        when(cargaRepo.buscarAcoplamentosDe(1L)).thenReturn(List.of());

        service.cancelar(1L, 10L);

        assertThat(os.getCargasExpedidas()).isEmpty();
        assertThat(t01.getOrdemAtual()).isNull();
    }

    /* -- os gates ---------------------------------------------------------- */

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
        // Reabertura recusada não apaga a avaliação.
        verify(avaliacaoService, never()).descartar(any());
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

    private Carga carga(Long id, String nome, Posicao posicao) throws Exception {
        Carga c = new Carga(nome, TipoCarga.TAMBOR, posicao);
        set(c, "id", id);
        return c;
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
