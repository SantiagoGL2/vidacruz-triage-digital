package com.vidacruz.triage.infrastructure.drivenadapter.postgres.adapter;

import com.vidacruz.triage.domain.model.HoraOperacion;
import com.vidacruz.triage.domain.port.out.ReporteOperacionPort;
import com.vidacruz.triage.infrastructure.drivenadapter.postgres.mapper.ReporteOperacionMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.core.io.ClassPathResource;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * RF-05. Se usa {@link DatabaseClient} (y no un {@code @Query}) porque la consulta es larga (CTEs, window functions,
 * percentile_cont) y vive en {@code db/queries/reporte-operacion.sql}. Se lee del classpath UNA vez al arrancar, no
 * en cada peticion. Todo el calculo ocurre en PostgreSQL: aqui solo se enlazan parametros y se mapean filas.
 */
@Component
class ReporteOperacionR2dbcAdapter implements ReporteOperacionPort {

	private static final String RECURSO = "db/queries/reporte-operacion.sql";

	private final DatabaseClient db;
	private final String sql;

	ReporteOperacionR2dbcAdapter(DatabaseClient db) {
		this.db = db;
		this.sql = cargar();
	}

	@Override
	public Flux<HoraOperacion> reportePorHora(Instant desde, Instant hasta, ZoneId zona) {
		return db.sql(sql)
				.bind("desde", desde)
				.bind("hasta", hasta)
				.bind("zona", zona.getId())
				.map(fila -> ReporteOperacionMapper.aDominio(fila, zona))
				.all();
	}

	private static String cargar() {
		try (var in = new ClassPathResource(RECURSO).getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException("No se pudo cargar " + RECURSO, e);
		}
	}

}
