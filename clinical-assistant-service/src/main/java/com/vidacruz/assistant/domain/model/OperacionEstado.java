package com.vidacruz.assistant.domain.model;

import com.vidacruz.assistant.domain.model.enums.NivelTriaje;
import java.time.Instant;
import java.util.UUID;

/**
 * Lo que un evento de triage le hace a la vista de urgencias. Es el vocabulario del puerto de salida: el dominio dice
 * <i>que</i> hay que hacer y el adapter (Redis) decide <i>como</i> hacerlo de forma atomica.
 */
public sealed interface OperacionEstado {

	UUID pacienteId();

	/** El paciente entra a la vista en estado REGISTRADO y suma 1 a su nivel. */
	record Registrar(UUID pacienteId, NivelTriaje nivel, Instant fechaRegistro) implements OperacionEstado {
	}

	/** El paciente pasa a EN_CONSULTA; sigue activo, los contadores no cambian. */
	record PasarAConsulta(UUID pacienteId) implements OperacionEstado {
	}

	/** Marca el flag escalado (cuenta una vez en {@code escalados}); los contadores por nivel no cambian. */
	record Escalar(UUID pacienteId) implements OperacionEstado {
	}

	/** Resta 1 al nivel que el paciente tenia en la vista y lo elimina. */
	record DarDeAlta(UUID pacienteId) implements OperacionEstado {
	}

}
