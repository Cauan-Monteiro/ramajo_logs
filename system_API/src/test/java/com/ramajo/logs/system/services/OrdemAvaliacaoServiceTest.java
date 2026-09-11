package com.ramajo.logs.system.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ramajo.logs.system.entities.Cliente;
import com.ramajo.logs.system.entities.ItemAvaliacao;
import com.ramajo.logs.system.entities.Operador;
import com.ramajo.logs.system.entities.OrdemAvaliacao;
import com.ramajo.logs.system.entities.OrdemServico;
import com.ramajo.logs.system.enums.Permissao;
import com.ramajo.logs.system.enums.Posicao;
import com.ramajo.logs.system.exceptions.AvaliacaoInvalidaException;
import com.ramajo.logs.system.exceptions.OperacaoRestritaException;
import com.ramajo.logs.system.exceptions.OrdemForaDeCirculacaoException;
import com.ramajo.logs.system.repositories.OperadorRepository;
import com.ramajo.logs.system.repositories.OrdemAvaliacaoRepository;
import com.ramajo.logs.system.repositories.OrdemServicoRepository;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Avaliação da inspeção final. O que estes casos vigiam:
 *
 * 1. O contrato de cada ponto: null | true | observação — `false` não passa,
 *    e observação em branco é só "avaliado".
 * 2. Uma por OS: avaliar de novo reaproveita a linha e troca o avaliador; e
 *    toda avaliação salva sai verificada.
 * 3. Fora da expedição, só ADMIN, e nunca em OS cancelada.
 */
@ExtendWith(MockitoExtension.class)
class OrdemAvaliacaoServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");

    @Mock private OrdemAvaliacaoRepository avaliacaoRepo;
    @Mock private OrdemServicoRepository osRepo;
    @Mock private OperadorRepository operadorRepo;

    @InjectMocks private OrdemAvaliacaoService service;

    /* -- os pontos --------------------------------------------------------- */

    @Test
    void cadaPontoIdaEVoltaNoMesmoFormato() {
        assertThat(ItemAvaliacao.de("visual", null).valor()).isNull();
        assertThat(ItemAvaliacao.de("visual", true).valor()).isEqualTo(Boolean.TRUE);
        assertThat(ItemAvaliacao.de("visual", "  riscado  ").valor()).isEqualTo("riscado");
    }

    @Test
    void observacaoEmBrancoEAvaliadoSemObservacao() {
        ItemAvaliacao item = ItemAvaliacao.de("visual", "   ");
        assertThat(item.isAvaliado()).isTrue();
        assertThat(item.getObservacao()).isNull();
        assertThat(item.valor()).isEqualTo(Boolean.TRUE);
    }

    @Test
    void recusaFalseTextoLongoEOutrosTipos() {
        assertThatThrownBy(() -> ItemAvaliacao.de("camada", false))
                .isInstanceOf(AvaliacaoInvalidaException.class);
        assertThatThrownBy(() -> ItemAvaliacao.de("camada", 1))
                .isInstanceOf(AvaliacaoInvalidaException.class);
        assertThatThrownBy(() -> ItemAvaliacao.de("camada", "x".repeat(501)))
                .isInstanceOf(AvaliacaoInvalidaException.class);
    }

    /* -- gravação ---------------------------------------------------------- */

    @Test
    void primeiraAvaliacaoCriaALinhaJaVerificada() throws Exception {
        OrdemServico os = ordem(1L);
        Operador joao = funcionario();
        when(avaliacaoRepo.buscarDaOrdem(1L)).thenReturn(Optional.empty());
        when(avaliacaoRepo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        OrdemAvaliacao a = service.registrar(os, joao,
                entrada(true, "bolha pequena", null, true, "  ok  "));

        assertThat(a.getOrdemServico()).isSameAs(os);
        assertThat(a.isVerificado()).isTrue();
        assertThat(a.getAderencia().valor()).isEqualTo("bolha pequena");
        assertThat(a.getEmbalagem().valor()).isNull();
        assertThat(a.getObservacao()).isEqualTo("ok");
        assertThat(a.getAvaliadaPor()).isSameAs(joao);
    }

    @Test
    void avaliarDeNovoSubstituiNaMesmaLinha() throws Exception {
        OrdemServico os = ordem(1L);
        OrdemAvaliacao antiga = new OrdemAvaliacao(os,
                entrada("riscado", null, null, null, null).validar(), funcionario());
        Operador ana = admin();
        when(avaliacaoRepo.buscarDaOrdem(1L)).thenReturn(Optional.of(antiga));
        when(avaliacaoRepo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        OrdemAvaliacao nova = service.registrar(os, ana,
                entrada(true, true, true, true, ""));

        assertThat(nova).isSameAs(antiga);
        assertThat(nova.getVisual().valor()).isEqualTo(Boolean.TRUE);
        assertThat(nova.isVerificado()).isTrue();
        assertThat(nova.getObservacao()).isNull();
        assertThat(nova.getAvaliadaPor()).isSameAs(ana);
    }

    /* -- Ajustes (ADMIN) --------------------------------------------------- */

    @Test
    void adminAvaliaOsEmProducao() throws Exception {
        OrdemServico os = ordem(1L);
        when(osRepo.findById(1L)).thenReturn(Optional.of(os));
        when(operadorRepo.findById(10L)).thenReturn(Optional.of(admin()));
        when(avaliacaoRepo.buscarDaOrdem(1L)).thenReturn(Optional.empty());
        when(avaliacaoRepo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        OrdemAvaliacao a = service.avaliarComoAdmin(1L, 10L,
                entrada(null, null, null, null, null));

        assertThat(a.getOrdemServico()).isSameAs(os);
        assertThat(a.isVerificado()).isTrue();
    }

    @Test
    void recusaFuncionarioForaDaExpedicao() throws Exception {
        when(osRepo.findById(1L)).thenReturn(Optional.of(ordem(1L)));
        when(operadorRepo.findById(10L)).thenReturn(Optional.of(funcionario()));

        assertThatThrownBy(() -> service.avaliarComoAdmin(1L, 10L,
                entrada(true, true, true, true, null)))
                .isInstanceOf(OperacaoRestritaException.class);

        verify(avaliacaoRepo, never()).saveAndFlush(any());
    }

    @Test
    void recusaOsCancelada() throws Exception {
        OrdemServico os = ordem(1L);
        os.setCancelada(true);
        when(osRepo.findById(1L)).thenReturn(Optional.of(os));

        assertThatThrownBy(() -> service.avaliarComoAdmin(1L, 10L,
                entrada(true, true, true, true, null)))
                .isInstanceOf(OrdemForaDeCirculacaoException.class);

        verify(avaliacaoRepo, never()).saveAndFlush(any());
    }

    /* -- fixtures ---------------------------------------------------------- */

    private OrdemAvaliacaoService.Entrada entrada(Object visual, Object aderencia,
                                                  Object embalagem, Object camada, String obs) {
        return new OrdemAvaliacaoService.Entrada(visual, aderencia, embalagem, camada, obs);
    }

    private OrdemServico ordem(Long id) throws Exception {
        OrdemServico os = new OrdemServico(100L, new Cliente(1L, "ACME LTDA"), Posicao.OXIDACAO);
        set(os, "id", id);
        set(os, "iniciadaEm", T0);
        return os;
    }

    private Operador admin() {
        return new Operador("Ana", Permissao.ADMIN, "A1");
    }

    private Operador funcionario() {
        return new Operador("João", Permissao.FUNCIONARIO, "T1");
    }

    private void set(Object alvo, String campo, Object valor) throws Exception {
        Field f = alvo.getClass().getDeclaredField(campo);
        f.setAccessible(true);
        f.set(alvo, valor);
    }
}
