package com.vidacruz.triage.infrastructure.drivenadapter.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.triage.domain.model.HoraOperacion;
import com.vidacruz.triage.domain.model.enums.NivelTriaje;
import com.vidacruz.triage.domain.port.out.ReporteOperacionPort;
import com.vidacruz.triage.support.AbstractPostgresTest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * RF-05 contra PostgreSQL real con un dataset determinista (pacientes y auditoria con timestamps controlados) y
 * resultados calculados a mano. Cada test usa su propio dia de 2020: la auditoria es append-only (no se puede
 * limpiar) y asi no se mezclan datos entre tests ni con los de otras clases.
 * Zona del reporte: America/Bogota (UTC-5, sin horario de verano).
 */
class ReporteOperacionTests extends AbstractPostgresTest {

	private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
	private static final ZoneOffset UTC_MENOS_5 = ZoneOffset.ofHours(-5);

	@Autowired
	ReporteOperacionPort reporte;

	@Autowired
	ObjectMapper objectMapper;

	// ------------------------------------------------------------------ helpers

	/** Instante local de Bogota: dia de febrero de 2020, hora, minuto, segundo. */
	private static Instant t(int dia, int hora, int minuto, int segundo) {
		return OffsetDateTime.of(2020, 2, dia, hora, minuto, segundo, 0, UTC_MENOS_5).toInstant();
	}

	private static String iso(int dia, int hora) {
		return OffsetDateTime.of(2020, 2, dia, hora, 0, 0, 0, UTC_MENOS_5).toString();
	}

	/** Inserta un paciente y su auditoria. {@code consultas} son los instantes de cada entrada a EN_CONSULTA. */
	private UUID paciente(Instant registro, int nivel, String estado, Instant... consultas) {
		UUID id = UUID.randomUUID();
		Instant primera = consultas.length == 0 ? null : consultas[0];
		var insertar = db.sql("""
				INSERT INTO pacientes (id, nombre_completo, edad, frecuencia_cardiaca, presion_sistolica, presion_diastolica,
				    spo2, temperatura, dolor, gcs, queja_principal, nivel, sla_minutos, estado, escalado, fecha_registro,
				    fecha_primera_consulta, fecha_ultimo_cambio_estado)
				VALUES (:id, 'Paciente Reporte', 40, 80, 120, 80, 98, 36.5, 2, 15, 'control', :nivel, 60, :estado, false,
				    :registro, :primera, :registro)""")
				.bind("id", id).bind("nivel", (short) nivel).bind("estado", estado).bind("registro", registro);
		insertar = primera == null ? insertar.bindNull("primera", Instant.class) : insertar.bind("primera", primera);
		insertar.fetch().rowsUpdated().block();
		auditoria(id, null, "REGISTRADO", "PACIENTE_REGISTRADO", registro);
		for (Instant consulta : consultas) {
			auditoria(id, "REGISTRADO", "EN_CONSULTA", "PACIENTE_EN_CONSULTA", consulta);
		}
		if ("ALTA".equals(estado)) {
			auditoria(id, "REGISTRADO", "ALTA", "PACIENTE_ALTA", registro.plusSeconds(3600));
		}
		return id;
	}

	private void auditoria(UUID id, String anterior, String nuevo, String tipo, Instant cuando) {
		var spec = db.sql("""
				INSERT INTO auditoria_clinica (paciente_id, fecha_evento, estado_anterior, estado_nuevo, tipo_evento)
				VALUES (:id, :cuando, :anterior, :nuevo, :tipo)""")
				.bind("id", id).bind("cuando", cuando).bind("nuevo", nuevo).bind("tipo", tipo);
		spec = anterior == null ? spec.bindNull("anterior", String.class) : spec.bind("anterior", anterior);
		spec.fetch().rowsUpdated().block();
	}

	private List<HoraOperacion> reporte(Instant desde, Instant hasta) {
		return reporte.reportePorHora(desde, hasta, BOGOTA).collectList().block(Duration.ofSeconds(30));
	}

	private static List<String> nombres(JsonNode objeto) {
		List<String> nombres = new java.util.ArrayList<>();
		objeto.fieldNames().forEachRemaining(nombres::add);
		return nombres;
	}

	private JsonNode llamar(String desde, String hasta) throws Exception {
		byte[] cuerpo = api().get().uri(u -> u.path("/triage/reportes/operacion")
						.queryParam("desde", desde).queryParam("hasta", hasta).build())
				.exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
		return objectMapper.readTree(cuerpo);
	}

	// ------------------------------------------------------------------ calculo

	/**
	 * Hora 00 (Bogota): 6 registrados; esperas de los 4 atendidos = 10, 20, 30, 40 min.
	 * promedio = 25.0; p95 = valor en la posicion 0.95 * (4 - 1) = 2.85 -> 30 + 0.85 * (40 - 30) = 38.5 (interpolado).
	 */
	@Test
	void conteosPorNivelPromedioYP95ExactosConInterpolacion() {
		paciente(t(1, 0, 0, 0), 1, "EN_CONSULTA", t(1, 0, 10, 0));    // 10 min; registro EXACTAMENTE en "desde"
		paciente(t(1, 0, 10, 0), 2, "EN_CONSULTA", t(1, 0, 30, 0));   // 20
		paciente(t(1, 0, 20, 0), 3, "EN_CONSULTA", t(1, 0, 50, 0));   // 30
		paciente(t(1, 0, 30, 0), 3, "EN_CONSULTA", t(1, 1, 10, 0));   // 40 (la consulta cae en la hora 01)
		paciente(t(1, 0, 40, 0), 5, "ALTA");                           // alta directa: sin espera
		paciente(t(1, 0, 50, 0), 4, "REGISTRADO");                     // aun esperando: sin espera

		List<HoraOperacion> horas = reporte(t(1, 0, 0, 0), t(1, 3, 0, 0));

		assertThat(horas).hasSize(3);
		HoraOperacion h0 = horas.get(0);
		assertThat(h0.hora().toOffsetDateTime()).isEqualTo(OffsetDateTime.parse(iso(1, 0)));
		assertThat(h0.totalRegistrados()).isEqualTo(6);
		assertThat(h0.porNivel()).containsEntry(NivelTriaje.CRITICO, 1L).containsEntry(NivelTriaje.URGENTE, 1L)
				.containsEntry(NivelTriaje.URGENTE_2, 2L).containsEntry(NivelTriaje.NO_URGENTE, 1L)
				.containsEntry(NivelTriaje.NO_URGENTE_2, 1L);
		assertThat(h0.pacientesAtendidos()).isEqualTo(4);          // alta directa y el que espera no cuentan
		assertThat(h0.esperaPromedioMinutos()).isEqualTo(25.0);
		assertThat(h0.esperaP95Minutos()).isEqualTo(38.5);
	}

	@Test
	void lasHorasSinPacientesApareceEnCeroConPromedioYP95Nulos() {
		paciente(t(2, 0, 15, 0), 1, "EN_CONSULTA", t(2, 0, 20, 0));
		paciente(t(2, 2, 15, 0), 2, "EN_CONSULTA", t(2, 2, 25, 0));

		List<HoraOperacion> horas = reporte(t(2, 0, 0, 0), t(2, 4, 0, 0));

		assertThat(horas).hasSize(4);
		for (int i : new int[] {1, 3}) {
			HoraOperacion vacia = horas.get(i);
			assertThat(vacia.hora().toOffsetDateTime()).isEqualTo(OffsetDateTime.parse(iso(2, i)));
			assertThat(vacia.totalRegistrados()).isZero();
			assertThat(vacia.pacientesAtendidos()).isZero();
			assertThat(vacia.porNivel().values()).containsOnly(0L).hasSize(5);
			assertThat(vacia.esperaPromedioMinutos()).isNull();
			assertThat(vacia.esperaP95Minutos()).isNull();
		}
	}

	@Test
	void sinConsultaOAltaDirectaNoAfectanElPromedioNiElP95() {
		paciente(t(3, 5, 0, 0), 1, "EN_CONSULTA", t(3, 5, 8, 0));    // 8 min
		paciente(t(3, 5, 5, 0), 2, "ALTA");
		paciente(t(3, 5, 6, 0), 3, "REGISTRADO");

		HoraOperacion h = reporte(t(3, 5, 0, 0), t(3, 6, 0, 0)).get(0);

		assertThat(h.totalRegistrados()).isEqualTo(3);
		assertThat(h.pacientesAtendidos()).isEqualTo(1);
		assertThat(h.esperaPromedioMinutos()).isEqualTo(8.0);
		assertThat(h.esperaP95Minutos()).isEqualTo(8.0);
	}

	@Test
	void seUsaLaPrimeraEntradaAConsultaDeCadaPaciente() {
		// entradas anomalas a EN_CONSULTA a los 10 y a los 50 minutos: cuenta la primera (ROW_NUMBER = 1)
		paciente(t(4, 9, 0, 0), 2, "EN_CONSULTA", t(4, 9, 10, 0), t(4, 9, 50, 0));

		HoraOperacion h = reporte(t(4, 9, 0, 0), t(4, 10, 0, 0)).get(0);

		assertThat(h.pacientesAtendidos()).isEqualTo(1);
		assertThat(h.esperaPromedioMinutos()).isEqualTo(10.0);
		assertThat(h.esperaP95Minutos()).isEqualTo(10.0);
	}

	@Test
	void lasEsperasSeRedondeanADosDecimales() {
		paciente(t(5, 7, 0, 0), 4, "EN_CONSULTA", t(5, 7, 1, 20));   // 80 s = 1.3333 min

		HoraOperacion h = reporte(t(5, 7, 0, 0), t(5, 8, 0, 0)).get(0);

		assertThat(h.esperaPromedioMinutos()).isEqualTo(1.33);
		assertThat(h.esperaP95Minutos()).isEqualTo(1.33);
	}

	@Test
	void laEsperaSeAsignaALaHoraDelRegistroNoALaDeLaConsulta() {
		paciente(t(6, 10, 50, 0), 3, "EN_CONSULTA", t(6, 11, 20, 0));   // registrado 10:50, atendido 11:20 (30 min)

		List<HoraOperacion> horas = reporte(t(6, 10, 0, 0), t(6, 12, 0, 0));

		assertThat(horas.get(0).totalRegistrados()).isEqualTo(1);
		assertThat(horas.get(0).esperaPromedioMinutos()).isEqualTo(30.0);
		assertThat(horas.get(1).totalRegistrados()).isZero();
		assertThat(horas.get(1).esperaPromedioMinutos()).isNull();
	}

	// ------------------------------------------------------------------ limites del rango y zona horaria

	@Test
	void elRegistroExactoEnDesdeEntraYElRegistroExactoEnHastaNo() {
		paciente(t(7, 0, 0, 0), 1, "REGISTRADO");          // == desde: entra
		paciente(t(7, 2, 59, 59), 2, "REGISTRADO");        // ultimo segundo: entra
		paciente(t(7, 3, 0, 0), 3, "REGISTRADO");          // == hasta: NO entra
		paciente(t(6, 23, 59, 59), 4, "REGISTRADO");       // justo antes de desde: NO entra

		List<HoraOperacion> horas = reporte(t(7, 0, 0, 0), t(7, 3, 0, 0));

		assertThat(horas).hasSize(3);
		assertThat(horas.stream().mapToLong(HoraOperacion::totalRegistrados).sum()).isEqualTo(2);
		assertThat(horas.get(0).porNivel()).containsEntry(NivelTriaje.CRITICO, 1L);
		assertThat(horas.get(2).porNivel()).containsEntry(NivelTriaje.URGENTE, 1L);
		assertThat(horas.get(2).porNivel()).containsEntry(NivelTriaje.URGENTE_2, 0L);
	}

	@Test
	void agrupaPorLaHoraDeBogotaYNoPorLaDeUtc() {
		// 23:30 en Bogota = 04:30 UTC del dia siguiente: debe caer en la hora 23 del dia 8, no en la 04 del dia 9
		paciente(t(8, 23, 30, 0), 2, "REGISTRADO");

		List<HoraOperacion> horas = reporte(t(8, 0, 0, 0), t(9, 0, 0, 0));

		assertThat(horas).hasSize(24);
		HoraOperacion h23 = horas.get(23);
		assertThat(h23.hora().toOffsetDateTime()).isEqualTo(OffsetDateTime.parse(iso(8, 23)));
		assertThat(h23.hora().getZone()).isEqualTo(BOGOTA);
		assertThat(h23.totalRegistrados()).isEqualTo(1);
		assertThat(horas.stream().mapToLong(HoraOperacion::totalRegistrados).sum()).isEqualTo(1);
	}

	@Test
	void laConsultaDevuelveUnaFilaPorHoraDelRangoSinAgregarEnMemoria() {
		// 30 pacientes en un solo dia (el 20), rango de 3 dias: la base devuelve 72 filas (una por hora), no 30 ni 3 * 30
		for (int i = 0; i < 30; i++) {
			if (i % 2 == 0) {
				paciente(t(20, 12, i, 0), 1 + i % 5, "EN_CONSULTA", t(20, 12, i, 0).plusSeconds(60L * (i + 1)));
			}
			else {
				paciente(t(20, 12, i, 0), 1 + i % 5, "REGISTRADO");
			}
		}
		Instant desde = t(20, 0, 0, 0);
		Instant hasta = t(23, 0, 0, 0);

		var filas = reporte.reportePorHora(desde, hasta, BOGOTA).collectList().block(Duration.ofSeconds(30));

		assertThat(filas).hasSize(72);
		assertThat(filas.stream().filter(h -> h.totalRegistrados() > 0)).hasSize(1);
		assertThat(filas.get(12).totalRegistrados()).isEqualTo(30);
	}

	@Test
	void laPrimeraConsultaDeLaAuditoriaCoincideConLaColumnaDelPaciente() {
		UUID id = paciente(t(11, 14, 0, 0), 2, "EN_CONSULTA", t(11, 14, 12, 0));

		Instant columna = db.sql("SELECT fecha_primera_consulta FROM pacientes WHERE id = :id").bind("id", id)
				.map(fila -> fila.get("fecha_primera_consulta", Instant.class)).one().block();
		Instant auditoria = db.sql("""
				SELECT min(fecha_evento) AS f FROM auditoria_clinica WHERE paciente_id = :id AND estado_nuevo = 'EN_CONSULTA'""")
				.bind("id", id).map(fila -> fila.get("f", Instant.class)).one().block();

		assertThat(columna).isEqualTo(auditoria);
	}

	// ------------------------------------------------------------------ endpoint

	@Test
	void elEndpointDevuelveLaForma5NivelesHorasVaciasYZonaHoraria() throws Exception {
		paciente(t(12, 8, 5, 0), 1, "EN_CONSULTA", t(12, 8, 25, 0));       // 20 min
		paciente(t(12, 8, 10, 0), 5, "ALTA");

		JsonNode json = llamar(iso(12, 8), iso(12, 10));

		assertThat(json.get("zonaHoraria").asText()).isEqualTo("America/Bogota");
		assertThat(OffsetDateTime.parse(json.get("desde").asText())).isEqualTo(OffsetDateTime.parse(iso(12, 8)));
		assertThat(json.get("horas")).hasSize(2);
		JsonNode h0 = json.get("horas").get(0);
		assertThat(h0.get("hora").asText()).isEqualTo("2020-02-12T08:00:00-05:00");
		assertThat(h0.get("totalRegistrados").asInt()).isEqualTo(2);
		assertThat(nombres(h0.get("porNivel")))
				.containsExactly("CRITICO", "URGENTE", "URGENTE_2", "NO_URGENTE", "NO_URGENTE_2");
		assertThat(h0.get("porNivel").get("CRITICO").asInt()).isEqualTo(1);
		assertThat(h0.get("porNivel").get("NO_URGENTE_2").asInt()).isEqualTo(1);
		assertThat(h0.get("pacientesAtendidos").asInt()).isEqualTo(1);
		assertThat(h0.get("esperaPromedioMinutos").asDouble()).isEqualTo(20.0);
		JsonNode h1 = json.get("horas").get(1);
		assertThat(h1.get("totalRegistrados").asInt()).isZero();
		assertThat(h1.get("esperaPromedioMinutos").isNull()).isTrue();
		assertThat(h1.get("esperaP95Minutos").isNull()).isTrue();
		assertThat(nombres(h1.get("porNivel"))).hasSize(5);
	}

	@Test
	void elEndpointExigeApiKey() {
		anonimo().get().uri("/triage/reportes/operacion?desde=" + iso(12, 8) + "&hasta=" + iso(12, 9))
				.exchange().expectStatus().isUnauthorized();
	}

	// ------------------------------------------------------------------ validaciones 400

	private void esperar400(String uri, String campo, String mensajeContiene) {
		api().get().uri(uri).exchange().expectStatus().isBadRequest()
				.expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.errores").value(hasSize(1))
				.jsonPath("$.errores[0].campo").isEqualTo(campo)
				.jsonPath("$.errores[0].mensaje").value(containsString(mensajeContiene))
				.jsonPath("$.correlationId").exists();
	}

	@Test
	void desdeObligatorio() {
		esperar400("/triage/reportes/operacion?hasta=2020-02-01T00:00:00-05:00", "desde", "obligatorio");
	}

	@Test
	void hastaObligatorio() {
		esperar400("/triage/reportes/operacion?desde=2020-02-01T00:00:00-05:00", "hasta", "obligatorio");
	}

	@Test
	void formatoInvalido() {
		esperar400("/triage/reportes/operacion?desde=ayer&hasta=2020-02-01T00:00:00-05:00", "desde", "ISO 8601");
	}

	@Test
	void fechaSinZonaNiOffsetEsInvalida() {
		esperar400("/triage/reportes/operacion?desde=2020-02-01T00:00:00&hasta=2020-02-02T00:00:00-05:00", "desde", "ISO 8601");
	}

	@Test
	void desdeIgualOMayorQueHastaEsInvalido() {
		esperar400("/triage/reportes/operacion?desde=2020-02-02T00:00:00-05:00&hasta=2020-02-02T00:00:00-05:00", "desde", "anterior");
		esperar400("/triage/reportes/operacion?desde=2020-02-03T00:00:00-05:00&hasta=2020-02-02T00:00:00-05:00", "desde", "anterior");
	}

	@Test
	void rangoMayorAlMaximoEsInvalido() {
		// 31 dias exactos se aceptan; 31 dias y una hora no
		api().get().uri("/triage/reportes/operacion?desde=2020-03-01T00:00:00-05:00&hasta=2020-04-01T00:00:00-05:00")
				.exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.horas").value(hasSize(31 * 24));
		esperar400("/triage/reportes/operacion?desde=2020-03-01T00:00:00-05:00&hasta=2020-04-01T01:00:00-05:00", "hasta", "31 dias");
	}

}
