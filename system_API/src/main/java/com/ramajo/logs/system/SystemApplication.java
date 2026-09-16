package com.ramajo.logs.system;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// EnableScheduling: o EstadoStream depende de dois @Scheduled (o flush dos
// avisos de revisao e o heartbeat do SSE). Sem isto eles nunca correm e os
// terminais ficam com uma conexao aberta que nunca diz nada.
@SpringBootApplication
@EnableScheduling
public class SystemApplication {

	public static void main(String[] args) {
		SpringApplication.run(SystemApplication.class, args);
	}

}
