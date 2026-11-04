package com.vidacruz.triage.infrastructure.entrypoint.web.controller;

import com.vidacruz.triage.application.command.RegistrarPacienteComando;
import com.vidacruz.triage.application.port.in.CambiarEstadoPacienteUseCase;
import com.vidacruz.triage.application.port.in.ConsultarPacienteUseCase;
import com.vidacruz.triage.application.port.in.RegistrarPacienteUseCase;
import com.vidacruz.triage.application.result.PacienteConsultado;
import com.vidacruz.triage.infrastructure.entrypoint.web.controller.doc.EjemplosApi;
import com.vidacruz.triage.infrastructure.entrypoint.web.dto.request.CambiarEstadoRequest;
import com.vidacruz.triage.infrastructure.entrypoint.web.dto.request.RegistrarPacienteRequest;
import com.vidacruz.triage.infrastructure.entrypoint.web.dto.response.PacienteCreadoResponse;
import com.vidacruz.triage.infrastructure.entrypoint.web.dto.response.PacienteResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
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
@Tag(name = "Pacientes", description = "Registro, clasificacion y flujo de estados clinicos de los pacientes de urgencias")
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

	@Operation(
			summary = "Registrar y clasificar un paciente",
			description = """
					Registra al paciente con sus signos vitales y lo **clasifica automaticamente** segun las reglas \
					objetivas de **RN-01** (criticos > urgentes > no urgentes > queja administrativa). Asigna el SLA \
					de espera de su nivel (**RN-02**: CRITICO 5, URGENTE 10, URGENTE_2 30, NO_URGENTE 60, NO_URGENTE_2 120 \
					minutos por defecto), que queda congelado en el paciente. En la misma transaccion se escribe la \
					auditoria (RN-04) y el evento `paciente_registrado` del outbox (se publica luego a Kafka).

					Los rangos validos de cada campo estan en el esquema del cuerpo (RF-01); un valor fuera de rango \
					devuelve 400 indicando el campo.""")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(
			required = true,
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = RegistrarPacienteRequest.class),
					examples = {
							@ExampleObject(name = "E1 - Dolor toracico (CRITICO)", value = EjemplosApi.REGISTRO_E1),
							@ExampleObject(name = "E5 - Renovacion de recetario (NO_URGENTE_2)", value = EjemplosApi.REGISTRO_E5)}))
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Paciente registrado y clasificado. Header `Location` con la URL del paciente.",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PacienteCreadoResponse.class),
							examples = {
									@ExampleObject(name = "E1 - CRITICO", value = EjemplosApi.REGISTRADO_E1),
									@ExampleObject(name = "E5 - NO_URGENTE_2", value = EjemplosApi.REGISTRADO_E5)})),
			@ApiResponse(responseCode = "400", description = "Campo ausente, de tipo incorrecto o fuera de rango. `errores[]` indica el campo, el valor recibido y el rango esperado.",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class),
							examples = @ExampleObject(value = EjemplosApi.ERROR_400_VALIDACION))),
			@ApiResponse(responseCode = "401", description = "Falta el header `X-API-Key` o es invalido.",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class),
							examples = @ExampleObject(value = EjemplosApi.ERROR_401))),
			@ApiResponse(responseCode = "500", description = "Error interno inesperado (el detalle solo va al log; reportar el `correlationId`).",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class),
							examples = @ExampleObject(value = EjemplosApi.ERROR_500)))})
	@PostMapping
	Mono<ResponseEntity<PacienteCreadoResponse>> registrar(@Valid @RequestBody RegistrarPacienteRequest r) {
		var comando = new RegistrarPacienteComando(r.nombreCompleto(), r.edad(), r.frecuenciaCardiaca(),
				r.presionSistolica(), r.presionDiastolica(), r.spo2(), r.temperatura(), r.dolor(), r.gcs(),
				r.quejaPrincipal());
		return registrar.registrar(comando)
				.map(p -> ResponseEntity.created(URI.create("/triage/pacientes/" + p.id()))
						.body(PacienteCreadoResponse.desde(p)));
	}

	@Operation(
			summary = "Cambiar el estado clinico de un paciente",
			description = """
					Aplica una transicion del flujo **RF-02**: `REGISTRADO -> EN_CONSULTA`, `REGISTRADO -> ALTA` \
					(alta directa) o `EN_CONSULTA -> ALTA`. Cualquier otra (por ejemplo `ALTA -> EN_CONSULTA`) devuelve \
					**409**. Cada transicion valida escribe, en una sola transaccion, el cambio, la auditoria (RN-04) y \
					el evento `paciente_en_consulta` o `paciente_alta`. Si el paciente fue modificado a la vez por otra \
					peticion (control de concurrencia optimista) tambien responde 409; basta con reintentar.""")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(
			required = true,
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = CambiarEstadoRequest.class),
					examples = {
							@ExampleObject(name = "Transicion valida (REGISTRADO -> EN_CONSULTA)", value = EjemplosApi.ESTADO_EN_CONSULTA),
							@ExampleObject(name = "Transicion invalida si el paciente ya esta en ALTA (devuelve 409)", value = EjemplosApi.ESTADO_INVALIDO)}))
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Transicion aplicada. Devuelve el paciente actualizado.",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PacienteResponse.class),
							examples = @ExampleObject(name = "Paciente en consulta", value = EjemplosApi.PACIENTE))),
			@ApiResponse(responseCode = "400", description = "`estado` ausente, desconocido o `id` que no es un UUID.",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class),
							examples = @ExampleObject(value = EjemplosApi.ERROR_400_VALIDACION))),
			@ApiResponse(responseCode = "401", description = "Falta el header `X-API-Key` o es invalido.",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class),
							examples = @ExampleObject(value = EjemplosApi.ERROR_401))),
			@ApiResponse(responseCode = "404", description = "No existe un paciente con ese id.",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class),
							examples = @ExampleObject(value = EjemplosApi.ERROR_404))),
			@ApiResponse(responseCode = "409", description = "Transicion no permitida (incluye pedir el estado en el que ya esta) o conflicto de concurrencia.",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class),
							examples = @ExampleObject(name = "ALTA -> EN_CONSULTA", value = EjemplosApi.ERROR_409_TRANSICION))),
			@ApiResponse(responseCode = "500", description = "Error interno inesperado.",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class),
							examples = @ExampleObject(value = EjemplosApi.ERROR_500)))})
	@PatchMapping("/{id}/estado")
	Mono<PacienteResponse> cambiarEstado(@PathVariable @Parameter(description = "Identificador del paciente", example = EjemplosApi.ID_PACIENTE) UUID id,
			@Valid @RequestBody CambiarEstadoRequest r) {
		// El tiempo en el estado nuevo recien empieza: se calcula desde la fecha del cambio, sin otra lectura.
		return cambiarEstado.cambiarEstado(id, r.estado())
				.map(p -> PacienteResponse.desde(new PacienteConsultado(p, Duration.ZERO)));
	}

	@Operation(
			summary = "Consultar un paciente",
			description = """
					Devuelve los datos completos del paciente (**RF-02**): signos vitales, nivel y SLA (RN-01/RN-02), \
					estado actual, el flag persistente `escalado` (RF-03/RN-03) y el **tiempo en el estado actual**.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Paciente encontrado.",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PacienteResponse.class),
							examples = @ExampleObject(value = EjemplosApi.PACIENTE))),
			@ApiResponse(responseCode = "400", description = "El `id` no es un UUID valido.",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class),
							examples = @ExampleObject(value = EjemplosApi.ERROR_400_VALIDACION))),
			@ApiResponse(responseCode = "401", description = "Falta el header `X-API-Key` o es invalido.",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class),
							examples = @ExampleObject(value = EjemplosApi.ERROR_401))),
			@ApiResponse(responseCode = "404", description = "No existe un paciente con ese id.",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class),
							examples = @ExampleObject(value = EjemplosApi.ERROR_404))),
			@ApiResponse(responseCode = "500", description = "Error interno inesperado.",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class),
							examples = @ExampleObject(value = EjemplosApi.ERROR_500)))})
	@GetMapping("/{id}")
	Mono<PacienteResponse> consultar(
			@PathVariable @Parameter(description = "Identificador del paciente", example = EjemplosApi.ID_PACIENTE) UUID id) {
		return consultar.consultar(id).map(PacienteResponse::desde);
	}

}
