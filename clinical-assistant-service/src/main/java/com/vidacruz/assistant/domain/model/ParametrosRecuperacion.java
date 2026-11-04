package com.vidacruz.assistant.domain.model;

/** Cuantos fragmentos traer (top-K) y la similitud minima para que un fragmento cuente como relevante. */
public record ParametrosRecuperacion(int topK, double umbralSimilitud) {
}
