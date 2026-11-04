package com.vidacruz.assistant.infrastructure.drivenadapter.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.assistant.application.port.in.IndexarProtocolosUseCase;
import com.vidacruz.assistant.domain.model.FragmentoProtocolo;
import com.vidacruz.assistant.domain.model.Protocolo;
import com.vidacruz.assistant.domain.model.ResultadoSincronizacion;
import com.vidacruz.assistant.domain.model.SeccionProtocolo;
import com.vidacruz.assistant.domain.port.out.FuenteProtocolosPort;
import com.vidacruz.assistant.domain.port.out.IndiceProtocolosPort;
import com.vidacruz.assistant.domain.service.FragmentadorProtocolos;
import com.vidacruz.assistant.support.AbstractIntegrationTest;
import com.vidacruz.assistant.support.ProveedorLlmSimulado;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ScanOptions;

/** Indexacion idempotente en Redis Stack: sin duplicados, solo se reindexa lo que cambio, se eliminan los que ya no existen. */
class IndexacionProtocolosTests extends AbstractIntegrationTest {

	@Autowired
	IndexarProtocolosUseCase indexar;

	@Autowired
	IndiceProtocolosPort indice;

	@Autowired
	FuenteProtocolosPort fuente;

	@Autowired
	FragmentadorProtocolos fragmentador;

	private Set<String> clavesDeProtocolos() {
		return redis.scan(ScanOptions.scanOptions().match("asistente:protocolo:*").count(500).build())
				.collect(Collectors.toSet()).block(Duration.ofSeconds(10));
	}

	private List<Protocolo> corpus() {
		return fuente.cargar().block(Duration.ofSeconds(10));
	}

	private ResultadoSincronizacion sincronizar(List<Protocolo> protocolos) {
		return indice.sincronizar(fragmentador.fragmentar(protocolos)).block(Duration.ofSeconds(30));
	}

	@Test
	void alArrancarElIndiceTieneUnFragmentoPorSeccionConIdsDeterministas() {
		assertThat(clavesDeProtocolos()).containsExactlyInAnyOrder(
				"asistente:protocolo:proto-001:definicion", "asistente:protocolo:proto-001:medidas-iniciales",
				"asistente:protocolo:proto-001:contraindicaciones", "asistente:protocolo:proto-002:criterios",
				"asistente:protocolo:proto-002:acciones-iniciales", "asistente:protocolo:proto-003:clasificacion",
				"asistente:protocolo:proto-003:rehidratacion");
		assertThat(estadoIndice.fragmentosIndexados()).isEqualTo(7);
	}

	@Test
	void elIndiceTieneUnPrefijoPropioDistintoAlDeLaVistaDeUrgencias() {
		vista.aplicar(java.util.UUID.randomUUID(), java.time.Instant.now(), new com.vidacruz.assistant.domain.model.OperacionEstado.Registrar(
				java.util.UUID.randomUUID(), com.vidacruz.assistant.domain.model.enums.NivelTriaje.CRITICO, java.time.Instant.now())).block();

		assertThat(clavesDeProtocolos()).allMatch(k -> k.startsWith("asistente:protocolo:") && !k.startsWith("asistente:urgencias:"));
		assertThat(clavesDeProtocolos()).hasSize(7);
	}

	@Test
	void reiniciarNoDuplicaFragmentosNiVuelveAPedirEmbeddings() {
		ProveedorLlmSimulado.reiniciar();    // limpia el registro de peticiones

		ResultadoSincronizacion r = indexar.indexar().block(Duration.ofSeconds(30));

		assertThat(r).isEqualTo(new ResultadoSincronizacion(0, 0, 0, 7));
		assertThat(clavesDeProtocolos()).hasSize(7);
		assertThat(ProveedorLlmSimulado.llamadasEmbeddings()).as("no se recalculan embeddings de lo que no cambio").isZero();
	}

	@Test
	void modificarUnProtocoloReindexaSoloEseFragmento() {
		List<Protocolo> original = corpus();
		List<Protocolo> modificado = new ArrayList<>();
		for (Protocolo p : original) {
			if (p.id().equals("PROTO-002")) {
				List<SeccionProtocolo> secciones = p.secciones().stream()
						.map(s -> s.titulo().equals("Criterios") ? new SeccionProtocolo(s.titulo(), s.texto() + " (texto de prueba nuevo)") : s)
						.toList();
				modificado.add(new Protocolo(p.id(), p.titulo(), p.version(), secciones));
			}
			else {
				modificado.add(p);
			}
		}
		try {
			ProveedorLlmSimulado.reiniciar();

			ResultadoSincronizacion r = sincronizar(modificado);

			assertThat(r).isEqualTo(new ResultadoSincronizacion(0, 1, 0, 6));
			assertThat(ProveedorLlmSimulado.textosEmbebidos()).hasSize(1).first().asString().contains("texto de prueba nuevo");
			assertThat(clavesDeProtocolos()).hasSize(7);
		}
		finally {
			sincronizar(original);
		}
		assertThat(sincronizar(original)).isEqualTo(new ResultadoSincronizacion(0, 0, 0, 7));
	}

	@Test
	void unCambioDeVersionReindexaTodasLasSeccionesDeEseProtocolo() {
		List<Protocolo> original = corpus();
		List<Protocolo> conVersionNueva = original.stream()
				.map(p -> p.id().equals("PROTO-003") ? new Protocolo(p.id(), p.titulo(), "2.0", p.secciones()) : p).toList();
		try {
			ResultadoSincronizacion r = sincronizar(conVersionNueva);

			assertThat(r).isEqualTo(new ResultadoSincronizacion(0, 2, 0, 5));
		}
		finally {
			sincronizar(original);
		}
	}

	@Test
	void unProtocoloQueYaNoExisteSeEliminaDelIndiceSinTocarLaVistaDeUrgencias() {
		List<Protocolo> original = corpus();
		redis.opsForValue().set("asistente:urgencias:marca", "intacta").block();
		try {
			ResultadoSincronizacion r = sincronizar(original.stream().filter(p -> !p.id().equals("PROTO-003")).toList());

			assertThat(r).isEqualTo(new ResultadoSincronizacion(0, 0, 2, 5));
			assertThat(clavesDeProtocolos()).hasSize(5).noneMatch(k -> k.contains("proto-003"));
			assertThat(redis.opsForValue().get("asistente:urgencias:marca").block()).isEqualTo("intacta");
		}
		finally {
			ResultadoSincronizacion restaurado = sincronizar(original);
			assertThat(restaurado).isEqualTo(new ResultadoSincronizacion(2, 0, 0, 5));
		}
		assertThat(clavesDeProtocolos()).hasSize(7);
	}

	@Test
	void unProtocoloNuevoSeAgregaAlSoltarloEnElCorpus() {
		List<Protocolo> original = corpus();
		List<Protocolo> conNuevo = new ArrayList<>(original);
		conNuevo.add(new Protocolo("PROTO-050", "Protocolo de prueba", "1.0",
				List.of(new SeccionProtocolo("Unica", "Texto de un protocolo agregado solo para esta prueba."))));
		try {
			assertThat(sincronizar(conNuevo)).isEqualTo(new ResultadoSincronizacion(1, 0, 0, 7));
			assertThat(clavesDeProtocolos()).contains("asistente:protocolo:proto-050:unica");
		}
		finally {
			assertThat(sincronizar(original)).isEqualTo(new ResultadoSincronizacion(0, 0, 1, 7));
		}
	}

	@SuppressWarnings("unused")
	private static FragmentoProtocolo sinUso() {
		return null;
	}

}
