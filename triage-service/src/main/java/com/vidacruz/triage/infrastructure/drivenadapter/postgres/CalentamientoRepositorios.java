package com.vidacruz.triage.infrastructure.drivenadapter.postgres;

import com.vidacruz.triage.infrastructure.drivenadapter.postgres.entity.AuditoriaEntity;
import com.vidacruz.triage.infrastructure.drivenadapter.postgres.entity.OutboxEventoEntity;
import com.vidacruz.triage.infrastructure.drivenadapter.postgres.entity.PacienteEntity;
import com.vidacruz.triage.infrastructure.drivenadapter.postgres.repository.OutboxEventoRepository;
import com.vidacruz.triage.infrastructure.drivenadapter.postgres.repository.PacienteRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;
import org.springframework.stereotype.Component;

/**
 * Prepara en el arranque el mapeo de las entidades y ejecuta una vez cada consulta de los repositorios. Spring Data introspecciona de forma perezosa
 * (lee el .class de la proyeccion desde disco) la primera vez que se invoca un metodo; hecho en el primer ciclo del
 * escalamiento o del publicador, bloquearia un hilo del event loop (BlockHound lo detecta). Aqui corre en el hilo
 * principal, donde bloquear es legitimo. Las consultas no modifican datos (ids inexistentes, lote de 0).
 */
@Component
class CalentamientoRepositorios implements ApplicationRunner {

	private final PacienteRepository pacientes;
	private final OutboxEventoRepository outbox;
	private final R2dbcMappingContext mappingContext;

	CalentamientoRepositorios(PacienteRepository pacientes, OutboxEventoRepository outbox,
			R2dbcMappingContext mappingContext) {
		this.pacientes = pacientes;
		this.outbox = outbox;
		this.mappingContext = mappingContext;
	}

	@Override
	public void run(ApplicationArguments args) {
		// El contexto de mapeo registra cada entidad la primera vez que se usa, bajo un lock de lectura/escritura:
		// dos primeros usos concurrentes en el event loop podrian bloquearse. Se registran todas aqui.
		mappingContext.getRequiredPersistentEntity(PacienteEntity.class);
		mappingContext.getRequiredPersistentEntity(AuditoriaEntity.class);
		mappingContext.getRequiredPersistentEntity(OutboxEventoEntity.class);
		Instant ahora = Instant.now();
		UUID inexistente = new UUID(0, 0);
		pacientes.buscarIdsConSlaVencido(ahora, 0).collectList().block();
		pacientes.marcarEscalado(inexistente, ahora).block();
		outbox.reservarPendientes(1, 1, 1, ahora, 0).collectList().block();
		outbox.marcarPublicado(inexistente, ahora).block();
		outbox.registrarFallo(inexistente, "calentamiento", ahora).block();
		outbox.contarPendientes(1).block();
	}

}
