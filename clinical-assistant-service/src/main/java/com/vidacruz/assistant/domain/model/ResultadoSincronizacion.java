package com.vidacruz.assistant.domain.model;

/** Que hizo la indexacion: fragmentos nuevos, cambiados, eliminados y sin cambios. */
public record ResultadoSincronizacion(int agregados, int actualizados, int eliminados, int sinCambios) {

	/** Fragmentos que quedan en el indice. */
	public int fragmentosIndexados() {
		return agregados + actualizados + sinCambios;
	}

}
