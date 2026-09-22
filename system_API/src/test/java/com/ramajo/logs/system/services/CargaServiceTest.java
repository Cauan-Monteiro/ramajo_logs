package com.ramajo.logs.system.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.ramajo.logs.system.entities.Carga;
import com.ramajo.logs.system.entities.Cliente;
import com.ramajo.logs.system.entities.OrdemServico;
import com.ramajo.logs.system.enums.Posicao;
import com.ramajo.logs.system.enums.TipoCarga;
import com.ramajo.logs.system.exceptions.CargaEmUsoException;
import com.ramajo.logs.system.repositories.CargaRepository;
import java.lang.reflect.Field;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Cadastro de cargas. O que estes casos vigiam é uma coisa só: o SETOR de uma
 * carga vinculada não muda.
 *
 * A posição da carga é onde o trabalho dela acontece — é ela que escolhe o
 * processo inicial no vínculo e que autoriza o processo de cada passo
 * (OrdemServicoService.abrirLog). Trocá-la com a carga em uso deixaria um passo
 * aberto a apontar para um processo do setor antigo, sem que validação nenhuma
 * voltasse a correr para o apanhar.
 *
 * Os outros campos são rótulo e mudam sempre — inclusive com a carga em uso,
 * que é o caso de corrigir um nome mal digitado sem parar a produção.
 */
@ExtendWith(MockitoExtension.class)
class CargaServiceTest {

    @Mock private CargaRepository cargaRepo;

    @InjectMocks private CargaService service;

    @Test
    void recusaTrocaDeSetorDeCargaVinculada() throws Exception {
        Carga c = carga(7L, "CG-01", Posicao.PENDURADO);
        c.setOrdemAtual(ordem(12L, Posicao.PENDURADO));
        when(cargaRepo.findById(7L)).thenReturn(Optional.of(c));

        assertThatThrownBy(() ->
                service.atualizar(7L, "CG-01", TipoCarga.TAMBOR, Posicao.AUTOMATICA, null))
                .isInstanceOf(CargaEmUsoException.class)
                .hasMessageContaining("OS 12");

        // A recusa vem ANTES de qualquer mutação: nem o setor nem os campos de
        // rótulo que vinham no mesmo corpo ficam gravados.
        assertThat(c.getPosicao()).isEqualTo(Posicao.PENDURADO);
    }

    @Test
    void permiteTrocaDeSetorDeCargaLivre() throws Exception {
        Carga c = carga(7L, "CG-01", Posicao.PENDURADO);
        when(cargaRepo.findById(7L)).thenReturn(Optional.of(c));

        service.atualizar(7L, "CG-01", TipoCarga.TAMBOR, Posicao.AUTOMATICA, null);

        assertThat(c.getPosicao()).isEqualTo(Posicao.AUTOMATICA);
    }

    /**
     * O corpo desta rota traz os campos INTEIROS, como o de corrigir() — logo
     * toda edição de nome reenvia o setor atual. Recusar o valor igual
     * impediria de corrigir o nome de uma carga em produção, que é justamente
     * quando o engano se nota.
     */
    @Test
    void permiteEditarRotulosDeCargaVinculadaReenviandoOMesmoSetor() throws Exception {
        Carga c = carga(7L, "CG-1", Posicao.PENDURADO);
        c.setOrdemAtual(ordem(12L, Posicao.PENDURADO));
        when(cargaRepo.findById(7L)).thenReturn(Optional.of(c));

        service.atualizar(7L, "CG-01", TipoCarga.CESTO, Posicao.PENDURADO, "TAG-9");

        assertThat(c.getNome()).isEqualTo("CG-01");
        assertThat(c.getTipo()).isEqualTo(TipoCarga.CESTO);
        assertThat(c.getTagId()).isEqualTo("TAG-9");
        assertThat(c.getPosicao()).isEqualTo(Posicao.PENDURADO);
    }

    /* -- fixtures ---------------------------------------------------------- */

    private Carga carga(Long id, String nome, Posicao posicao) throws Exception {
        Carga c = new Carga(nome, TipoCarga.TAMBOR, posicao);
        set(c, "id", id);
        return c;
    }

    private OrdemServico ordem(Long id, Posicao posicao) throws Exception {
        OrdemServico os = new OrdemServico(100L, new Cliente(1L, "ACME LTDA"), posicao);
        set(os, "id", id);
        return os;
    }

    /** Os ids são gerados pelo banco; no teste eles entram por reflexão. */
    private void set(Object alvo, String campo, Object valor) throws Exception {
        Field f = alvo.getClass().getDeclaredField(campo);
        f.setAccessible(true);
        f.set(alvo, valor);
    }
}
