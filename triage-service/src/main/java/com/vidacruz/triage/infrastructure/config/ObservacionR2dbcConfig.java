package com.vidacruz.triage.infrastructure.config;

import io.micrometer.observation.ObservationRegistry;
import io.r2dbc.proxy.ProxyConnectionFactory;
import io.r2dbc.proxy.observation.ObservationProxyExecutionListener;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcProperties;
import org.springframework.boot.r2dbc.ConnectionFactoryDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cada consulta SQL de R2DBC pasa a ser un span hijo de la peticion (o del ciclo) que la origina. Spring Boot no instrumenta
 * R2DBC por si solo: se envuelve la {@code ConnectionFactory} con r2dbc-proxy, que emite una observacion de Micrometer por
 * consulta. El texto de la consulta va en el span; los valores de los parametros no (pueden ser datos clinicos).
 */
@Configuration
class ObservacionR2dbcConfig {

	@Bean
	ConnectionFactoryDecorator observacionR2dbc(ObservationRegistry registro, R2dbcProperties propiedades) {
		return fabrica -> ProxyConnectionFactory.builder(fabrica)
				.listener(new ObservationProxyExecutionListener(registro, fabrica, propiedades.getUrl()))
				.build();
	}

}
