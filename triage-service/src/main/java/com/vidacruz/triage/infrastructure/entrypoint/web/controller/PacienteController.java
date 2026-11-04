package com.vidacruz.triage.infrastructure.entrypoint.web.controller;

import com.vidacruz.triage.application.command.RegistrarPacienteComando;
import com.vidacruz.triage.application.port.in.CambiarEstadoPacienteUseCase;
import com.vidacruz.triage.application.port.in.ConsultarPacienteUseCase;
import com.vidacruz.triage.application.port.in.RegistrarPacienteUseCase;
import com.vidacruz.triage.application.result.PacienteConsultado;
import com.vidacruz.triage.infrastructure.entrypoint.web.dto.request.CambiarEstadoRequest;
import com.vidacruz.triage.infrastructure.entrypoint.web.dto.request.RegistrarPacienteRequest;
import com.vidacruz.triage.infrastructure.entrypoint.web.dto.response.PacienteCreadoResponse;
import com.vidacruz.triage.infrastructure.entrypoint.web.dto.response.PacienteResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Controllers anotados (no RouterFunction): casan con el @RestControllerAdvice global y con Bean Validation. */
@RestController
@RequestMapping("/triage/pacientes")
class PacienteController {

	private final RegistrarPacienteUseCase registrar;
	private final CambiarEstadoPacienteUseCase cambiarEstado;
	private final ConsultarPacienteUseCase consultar;

	PacienteController(RegistrarPacienteUseCase registrar, CambiarEstadoPacienteUseCase cambiarEstado,
			ConsultarPacienteUseCase consultar) {
		this.registrar = registrar;
		this.cambiarEstado = cambiarEstado;
		this.consultar = consultar;
	}

	@PostMapping
	Mono<ResponseEntity<PacienteCreadoResponse>> registrar(@Valid @RequestBody RegistrarPacienteRequest r) {
		var comando = new RegistrarPacienteComando(r.nombreCompleto(), r.edad(), r.frecuenciaCardiaca(),
				r.presionSistolica(), r.presionDiastolica(), r.spo2(), r.temperatura(), r.dolor(), r.gcs(),
				r.quejaPrincipal());
		return registrar.registrar(comando)
				.map(p -> ResponseEntity.created(URI.create("/triage/pacientes/" + p.id()))
						.body(PacienteCreadoResponse.desde(p)));
	}

	@PatchMapping("/{id}/estado")
	Mono<PacienteResponse> cambiarEstado(@PathVariable UUID id, @Valid @RequestBody CambiarEstadoRequest r) {
		// El tiempo en el estado nuevo recien empieza: se calcula desde la fecha del cambio, sin otra lectura.
		return cambiarEstado.cambiarEstado(id, r.estado())
				.map(p -> PacienteResponse.desde(new PacienteConsultado(p, Duration.ZERO)));
	}

	@GetMapping("/{id}")
	Mono<PacienteResponse> consultar(@PathVariable UUID id) {
		return consultar.consultar(id).map(PacienteResponse::desde);
	}

}
