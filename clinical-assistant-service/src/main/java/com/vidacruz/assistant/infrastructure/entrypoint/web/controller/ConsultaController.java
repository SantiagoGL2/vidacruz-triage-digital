package com.vidacruz.assistant.infrastructure.entrypoint.web.controller;

import com.vidacruz.assistant.application.command.ConsultarProtocoloComando;
import com.vidacruz.assistant.application.port.in.ConsultarProtocoloUseCase;
import com.vidacruz.assistant.infrastructure.entrypoint.web.controller.doc.EjemplosApi;
import com.vidacruz.assistant.infrastructure.entrypoint.web.dto.request.ConsultaRequest;
import com.vidacruz.assistant.infrastructure.entrypoint.web.dto.response.ConsultaResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** RF-04 parte A: consulta de protocolos con RAG. La logica (tres barreras anti-alucinacion) vive en el caso de uso. */
@RestController
@RequestMapping("/asistente")
@Tag(name = "Consulta de protocolos", description = "Preguntas sobre los protocolos clínicos con RAG, siempre con fuente del corpus (RN-05)")
class ConsultaController {

	private final ConsultarProtocoloUseCase consultar;

	ConsultaController(ConsultarProtocoloUseCase consultar) {
		this.consultar = consultar;
	}

	@Operation(
			summary = "Consultar los protocolos clínicos",
			description = """
					**RF-04 parte A / RN-05.** Responde una pregunta usando RAG sobre el corpus de protocolos \
					(PROTO-001 crisis hipertensiva, PROTO-002 sepsis, PROTO-003 deshidratación pediátrica). \
					Tres barreras anti-alucinación: (1) búsqueda de los fragmentos más parecidos con un **umbral de similitud**: \
					si ninguno lo supera no se llama al LLM; (2) el LLM solo ve esos fragmentos y devuelve una salida \
					estructurada con sus citas; (3) el servidor **valida** las citas contra lo recuperado y descarta las que \
					no corresponden; sin ninguna cita válida, o si el modelo dice que no puede responder, la respuesta es el aviso \
					de que no existe protocolo disponible, **con HTTP 200** y `fuentes` vacío, sin contenido clínico.

					Las `fuentes` salen solo de las citas validadas. Si el proveedor de IA no está disponible (circuito abierto, \
					timeout, reintentos agotados) o el índice de protocolos aún se está construyendo, responde **503** con \
					`Retry-After`; el resto del servicio (por ejemplo `GET /asistente/estado-urgencias`) sigue funcionando. \
					La pregunta se trata como dato, no como instrucciones.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Respuesta con fuentes, o el aviso de «sin protocolo» con `fuentes` vacío.",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ConsultaResponse.class),
							examples = {
									@ExampleObject(name = "Con fuentes (crisis hipertensiva)", value = EjemplosApi.CONSULTA_RESPONDIDA),
									@ExampleObject(name = "Sin protocolo (diabetes)", value = EjemplosApi.CONSULTA_SIN_PROTOCOLO)})),
			@ApiResponse(responseCode = "400", description = "`pregunta` ausente, en blanco, de menos de 5 o más de 500 caracteres.",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class),
							examples = @ExampleObject(value = EjemplosApi.ERROR_400_CONSULTA))),
			@ApiResponse(responseCode = "401", description = EjemplosApi.DESC_401_API_KEY,
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class),
							examples = @ExampleObject(value = EjemplosApi.ERROR_401))),
			@ApiResponse(responseCode = "429", description = "Limite de consultas por API key superado (cada consulta cuesta una llamada al proveedor de IA). Header `Retry-After`.",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class),
							examples = @ExampleObject(value = EjemplosApi.ERROR_429))),
			@ApiResponse(responseCode = "500", description = EjemplosApi.DESC_500,
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class),
							examples = @ExampleObject(value = EjemplosApi.ERROR_500))),
			@ApiResponse(responseCode = "503", description = "Modo degradado: el proveedor de IA no está disponible o el índice de protocolos aún no está listo. Header `Retry-After`.",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class),
							examples = {
									@ExampleObject(name = "Proveedor de IA caído", value = EjemplosApi.ERROR_503_LLM),
									@ExampleObject(name = "Índice en construcción", value = EjemplosApi.ERROR_503_INDICE)}))})
	@PostMapping("/consulta")
	Mono<ConsultaResponse> consultar(@Valid @RequestBody ConsultaRequest request) {
		return consultar.consultar(new ConsultarProtocoloComando(request.pregunta().strip())).map(ConsultaResponse::desde);
	}

}
