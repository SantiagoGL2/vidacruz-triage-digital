package com.vidacruz.assistant.domain.model;

/** Fragmento devuelto por la busqueda y su puntuacion de similitud (0..1, mayor = mas parecido). */
public record FragmentoRecuperado(FragmentoProtocolo fragmento, double puntuacion) {
}
