package com.vidacruz.assistant.application.usecase;

import com.vidacruz.assistant.application.command.ConsultarProtocoloComando;
import com.vidacruz.assistant.application.port.in.ConsultarProtocoloUseCase;
import com.vidacruz.assistant.application.result.RespuestaConsulta;
import com.vidacruz.assistant.domain.exception.AsistenteNoDisponibleException;
import com.vidacruz.assistant.domain.exception.IndiceNoDisponibleException;
import com.vidacruz.assistant.domain.model.CitaProtocolo;
import com.vidacruz.assistant.domain.model.FragmentoRecuperado;
import com.vidacruz.assistant.domain.model.ParametrosRecuperacion;
import com.vidacruz.assistant.domain.model.SalidaGenerador;
import com.vidacruz.assistant.domain.model.ValidacionRespuesta;
import com.vidacruz.assistant.domain.port.out.BuscadorProtocolosPort;
import com.vidacruz.assistant.domain.port.out.EstadoIndicePort;
import com.vidacruz.assistant.domain.port.out.GeneradorRespuestaPort;
import com.vidacruz.assistant.domain.port.out.MetricasRagPort;
import com.vidacruz.assistant.domain.service.ValidadorRespuesta;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * RF-04 parte A con las tres barreras anti-alucinacion de RN-05:
 * <ol>
 * <li><b>Recuperacion:</b> top-K con umbral. Si ningun fragmento lo supera, «sin protocolo» y el LLM NO se invoca.</li>
 * <li><b>Generacion:</b> el modelo solo ve los fragmentos recuperados y devuelve una salida estructurada.</li>
 * <li><b>Validacion en el dominio:</b> se descartan las citas que no corresponden a lo recuperado; si el modelo no
 * respondio, la salida no se pudo interpretar o no queda ninguna cita valida, «sin protocolo». Las fuentes salen solo
 * de las citas validadas.</li>
 * </ol>
 * La respuesta de «sin protocolo» es siempre exactamente el mensaje configurado y fuentes vacias, sin contenido clinico.
 */
public class ConsultarProtocoloUseCaseImpl implements ConsultarProtocoloUseCase {

	private static final Logger log = LoggerFactory.getLogger(ConsultarProtocoloUseCaseImpl.class);

	private final BuscadorProtocolosPort buscador;
	private final GeneradorRespuestaPort generador;
	private final ValidadorRespuesta validador;
	private final EstadoIndicePort estadoIndice;
	private final MetricasRagPort metricas;
	private final ParametrosRecuperacion parametros;
	private final String mensajeSinProtocolo;

	public ConsultarProtocoloUseCaseImpl(BuscadorProtocolosPort buscador, GeneradorRespuestaPort generador,
			ValidadorRespuesta validador, EstadoIndicePort estadoIndice, MetricasRagPort metricas,
			ParametrosRecuperacion parametros, String mensajeSinProtocolo) {
		this.buscador = buscador;
		this.generador = generador;
		this.validador = validador;
		this.estadoIndice = estadoIndice;
		this.metricas = metricas;
		this.parametros = parametros;
		this.mensajeSinProtocolo = mensajeSinProtocolo;
	}

	@Override
	public Mono<RespuestaConsulta> consultar(ConsultarProtocoloComando comando) {
		return Mono.defer(() -> {
			if (!estadoIndice.listo()) {
				return Mono.<RespuestaConsulta>error(new IndiceNoDisponibleException(estadoIndice.motivo()));
			}
			return buscador.buscar(comando.pregunta(), parametros)
					.flatMap(recuperados -> recuperados.isEmpty()
							? Mono.just(sinProtocolo(comando, recuperados, false, 0, 0, "ningun fragmento supera el umbral"))
							: generar(comando, recuperados));
		}).doOnError(e -> {
			if (e instanceof AsistenteNoDisponibleException || e instanceof IndiceNoDisponibleException) {
				metricas.consulta("degradado");
				log.atWarn().addKeyValue("resultado", "degradado").addKeyValue("motivo", e.getMessage())
						.log("Consulta de protocolo en modo degradado");
			}
		});
	}

	private Mono<RespuestaConsulta> generar(ConsultarProtocoloComando comando, List<FragmentoRecuperado> recuperados) {
		return generador.generar(comando.pregunta(), recuperados).map(salida -> switch (salida) {
			case SalidaGenerador.NoParseable n -> {
				log.atWarn().addKeyValue("motivo", n.motivo()).log("La salida del modelo no se pudo interpretar");
				yield sinProtocolo(comando, recuperados, true, 0, 0, "salida no parseable: " + n.motivo());
			}
			case SalidaGenerador.Generada g -> {
				ValidacionRespuesta validacion = validador.validar(g.respuesta(), recuperados);
				if (validacion.citasDescartadas() > 0) {
					metricas.citasDescartadas(validacion.citasDescartadas());
				}
				if (!validacion.valida()) {
					yield sinProtocolo(comando, recuperados, true, validacion.citasPropuestas(),
							validacion.citasValidas().size(), validacion.motivo());
				}
				metricas.consulta("respondida");
				registrar("respondida", recuperados, true, validacion.citasPropuestas(), validacion.citasValidas().size(),
						validacion.motivo());
				log.debug("Respuesta generada ({} caracteres)", validacion.respuesta().length());
				yield new RespuestaConsulta(validacion.respuesta(), validacion.citasValidas());
			}
		});
	}

	private RespuestaConsulta sinProtocolo(ConsultarProtocoloComando comando, List<FragmentoRecuperado> recuperados,
			boolean llmInvocado, int citasPropuestas, int citasValidas, String motivo) {
		metricas.consulta("sin_protocolo");
		registrar("sin_protocolo", recuperados, llmInvocado, citasPropuestas, citasValidas, motivo);
		return new RespuestaConsulta(mensajeSinProtocolo, List.<CitaProtocolo>of());
	}

	/** Log estructurado de cada consulta. No incluye la respuesta (a INFO) ni la pregunta (solo su longitud). */
	private void registrar(String resultado, List<FragmentoRecuperado> recuperados, boolean llmInvocado,
			int citasPropuestas, int citasValidas, String motivo) {
		List<String> fragmentos = recuperados.stream()
				.map(r -> "%s/%s=%s".formatted(r.fragmento().protocolo(), r.fragmento().seccion(),
						String.format(Locale.ROOT, "%.3f", r.puntuacion())))
				.toList();
		log.atInfo()
				.addKeyValue("resultado", resultado)
				.addKeyValue("llmInvocado", llmInvocado)
				.addKeyValue("fragmentosRecuperados", fragmentos)
				.addKeyValue("citasPropuestas", citasPropuestas)
				.addKeyValue("citasValidas", citasValidas)
				.addKeyValue("motivo", motivo)
				.log("Consulta de protocolo resuelta: {}", resultado);
	}

}
