package com.vidacruz.assistant.domain.service;

import com.vidacruz.assistant.domain.model.CitaProtocolo;
import com.vidacruz.assistant.domain.model.FragmentoRecuperado;
import com.vidacruz.assistant.domain.model.RespuestaGenerada;
import com.vidacruz.assistant.domain.model.ValidacionRespuesta;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Barrera 3 de RN-05, en el dominio y no en el prompt: lo que dice el modelo se TRATA COMO NO CONFIABLE.
 * <ul>
 * <li>Solo cuentan las citas que corresponden a un fragmento recuperado en ESTA consulta (protocolo + seccion,
 * comparados sin tildes ni mayusculas). Una cita a un protocolo inventado, a una seccion que no se recupero o a un
 * protocolo real que no estaba en el contexto se descarta.</li>
 * <li>Las fuentes se construyen con los nombres canonicos del fragmento recuperado, nunca con el texto del modelo.</li>
 * <li>Si el modelo dice {@code respondida = false}, la respuesta esta vacia o no queda ninguna cita valida, la
 * respuesta no es valida (y la consulta termina en «sin protocolo», aunque el modelo haya escrito contenido clinico).</li>
 * </ul>
 */
public class ValidadorRespuesta {

	public ValidacionRespuesta validar(RespuestaGenerada generada, List<FragmentoRecuperado> recuperados) {
		Map<String, CitaProtocolo> disponibles = new LinkedHashMap<>();
		for (FragmentoRecuperado r : recuperados) {
			CitaProtocolo canonica = new CitaProtocolo(r.fragmento().protocolo(), r.fragmento().seccion());
			disponibles.put(clave(canonica), canonica);
		}
		Map<String, CitaProtocolo> validas = new LinkedHashMap<>();
		int descartadas = 0;
		for (CitaProtocolo propuesta : generada.citas()) {
			CitaProtocolo canonica = propuesta == null ? null : disponibles.get(clave(propuesta));
			if (canonica == null) {
				descartadas++;
			}
			else {
				validas.putIfAbsent(clave(canonica), canonica);
			}
		}
		int propuestas = generada.citas().size();
		List<CitaProtocolo> citasValidas = List.copyOf(validas.values());
		if (!generada.respondida()) {
			return new ValidacionRespuesta(false, null, citasValidas, propuestas, descartadas,
					"el modelo indico que el contexto no responde la pregunta");
		}
		if (generada.respuesta() == null || generada.respuesta().isBlank()) {
			return new ValidacionRespuesta(false, null, citasValidas, propuestas, descartadas, "respuesta vacia");
		}
		if (citasValidas.isEmpty()) {
			return new ValidacionRespuesta(false, null, citasValidas, propuestas, descartadas,
					"ninguna cita corresponde a un fragmento recuperado");
		}
		return new ValidacionRespuesta(true, generada.respuesta().strip(), citasValidas, propuestas, descartadas, "ok");
	}

	private static String clave(CitaProtocolo cita) {
		return Textos.normalizar(cita.protocolo()) + "|" + Textos.normalizar(cita.seccion());
	}

}
