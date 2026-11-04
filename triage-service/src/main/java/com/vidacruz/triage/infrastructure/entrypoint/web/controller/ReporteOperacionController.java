package com.vidacruz.triage.infrastructure.entrypoint.web.controller;

import com.vidacruz.triage.application.command.GenerarReporteOperacionComando;
import com.vidacruz.triage.application.port.in.GenerarReporteOperacionUseCase;
import com.vidacruz.triage.infrastructure.entrypoint.web.controller.doc.EjemplosApi;
import com.vidacruz.triage.infrastructure.entrypoint.web.dto.response.ReporteOperacionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * RF-05. Los parametros se reciben como {@link OffsetDateTime} (ISO 8601 con offset): un valor sin offset o con otro
 * formato es un 400 que nombra el parametro. Las reglas del rango (desde &lt; hasta, maximo de dias) las valida el
 * caso de uso.
 */
@RestController
@RequestMapping("/triage/reportes")
@Tag(name = "Reportes", description = "Reportes de operacion calculados con SQL en PostgreSQL")
class ReporteOperacionController {

	private final GenerarReporteOperacionUseCase reporte;

	ReporteOperacionController(GenerarReporteOperacionUseCase reporte) {
		this.reporte = reporte;
	}

	@Operation(
			summary = "Reporte de operacion por hora",
			description = """
					**RF-05.** Por cada hora del rango `[desde, hasta)` devuelve: pacientes registrados, conteo por nivel \
					de triaje (siempre los 5 niveles), pacientes atendidos y el **tiempo de espera promedio y p95** \
					(registro -> primera entrada a consulta, en minutos con 2 decimales). Las horas sin pacientes aparecen en \
					cero y con promedio/p95 `null`.

					Supuestos: la espera se asigna a la hora en que el paciente **se registro**; quien pasa directo a ALTA o \
					aun espera cuenta en el total y por nivel, pero no en el promedio ni el p95. La primera entrada a \
					consulta se toma de la auditoria (RN-04). Las horas se agrupan en la zona horaria configurada \
					(`triage.reportes.zona-horaria`). Todo se calcula en una sola consulta SQL \
					(ver docs/reporte-operacion.md).

					El rango maximo es `triage.reportes.rango-maximo-dias` (31 por defecto). Si el offset lleva `+`, \
					codificalo como `%2B` en la URL.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Reporte generado: una entrada por cada hora del rango.",
					content = @Content(mediaType = "application/json",
							schema = @Schema(implementation = ReporteOperacionResponse.class),
							examples = @ExampleObject(value = EjemplosApi.REPORTE))),
			@ApiResponse(responseCode = "400", description = "Parametro ausente, con formato invalido, `desde >= hasta` o rango mayor al maximo. `errores[].campo` indica el parametro.",
					content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class),
							examples = @ExampleObject(value = EjemplosApi.ERROR_400_REPORTE))),
			@ApiResponse(responseCode = "401", description = "Falta el header `X-API-Key` o es invalido.",
					content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class),
							examples = @ExampleObject(value = EjemplosApi.ERROR_401))),
			@ApiResponse(responseCode = "500", description = "Error interno inesperado.",
					content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class),
							examples = @ExampleObject(value = EjemplosApi.ERROR_500)))})
	@GetMapping("/operacion")
	Mono<ReporteOperacionResponse> operacion(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
			@Parameter(description = "Inicio del rango (incluido). ISO 8601 con zona u offset.", required = true,
					example = "2026-11-04T00:00:00-05:00") OffsetDateTime desde,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
			@Parameter(description = "Fin del rango (excluido). ISO 8601 con zona u offset. Debe ser posterior a `desde`.", required = true,
					example = "2026-11-05T00:00:00-05:00") OffsetDateTime hasta) {
		return reporte.generar(new GenerarReporteOperacionComando(desde.toInstant(), hasta.toInstant()))
				.map(ReporteOperacionResponse::desde);
	}

}
