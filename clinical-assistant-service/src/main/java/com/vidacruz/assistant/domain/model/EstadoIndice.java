package com.vidacruz.assistant.domain.model;

import com.vidacruz.assistant.domain.model.enums.EstadoIndexacion;
import java.time.Instant;
import java.util.Objects;

/**
 * Foto inmutable del estado del indice de protocolos. Se reemplaza completa en cada cambio, asi quien la lee nunca ve una
 * mezcla de campos de dos estados distintos.
 *
 * @param estado              fase de la indexacion
 * @param fragmentosIndexados fragmentos en el indice (0 si no esta {@code LISTO})
 * @param detalleError        causa del ultimo fallo (solo en {@code FALLIDO}; {@code null} en los demas)
 * @param actualizadoEn       cuando cambio de estado; {@code null} en el estado inicial, antes de que haya ocurrido nada
 */
public record EstadoIndice(EstadoIndexacion estado, int fragmentosIndexados, String detalleError, Instant actualizadoEn) {

	public EstadoIndice {
		Objects.requireNonNull(estado, "estado");
		if (fragmentosIndexados < 0) {
			throw new IllegalArgumentException("fragmentosIndexados no puede ser negativo");
		}
		if (estado != EstadoIndexacion.FALLIDO && detalleError != null) {
			throw new IllegalArgumentException("detalleError solo aplica al estado FALLIDO");
		}
		if (estado != EstadoIndexacion.LISTO && fragmentosIndexados != 0) {
			throw new IllegalArgumentException("solo un indice LISTO tiene fragmentos indexados");
		}
	}

	/** Estado inicial: todavia no termino ninguna indexacion. */
	public static EstadoIndice indexando() {
		return new EstadoIndice(EstadoIndexacion.INDEXANDO, 0, null, null);
	}

	public static EstadoIndice listo(ResultadoSincronizacion resultado, Instant ahora) {
		return new EstadoIndice(EstadoIndexacion.LISTO, resultado.fragmentosIndexados(), null, ahora);
	}

	public static EstadoIndice fallido(String error, Instant ahora) {
		return new EstadoIndice(EstadoIndexacion.FALLIDO, 0, error, ahora);
	}

	public boolean listo() {
		return estado == EstadoIndexacion.LISTO;
	}

}
