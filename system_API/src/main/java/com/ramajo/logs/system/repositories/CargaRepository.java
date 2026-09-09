package com.ramajo.logs.system.repositories;

import com.ramajo.logs.system.entities.Carga;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CargaRepository extends JpaRepository<Carga, Long> {
    List<Carga> findByAtivoTrueAndOrdemAtualIsNull();   // disponíveis
    Optional<Carga> findByTagId(String tagId);

    // As cargas em que esta OS pega carona. As peças dela estão num tanque só,
    // então acoplá-la a uma segunda carga sem a soltar da primeira é
    // incoerência física — é aqui que se descobre.
    //
    // Lista (e não Optional) porque nada no banco garante unicidade: a PK de
    // carga_ordens_acopladas protege o par, não a carona sozinha. O service
    // recusa na primeira que encontrar.
    @Query("""
            select c from Carga c
             where :osId member of c.ordensAcopladas
            """)
    List<Carga> buscarAcoplamentosDe(@Param("osId") Long osId);
}
