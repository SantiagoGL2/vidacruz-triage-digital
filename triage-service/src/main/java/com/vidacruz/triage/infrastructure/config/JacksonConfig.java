package com.vidacruz.triage.infrastructure.config;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Entrada estricta: un numero o booleano en un campo de texto es un 400, no se convierte en silencio
 * (junto con las propiedades spring.jackson.* de application.yml para numericos).
 */
@Configuration
class JacksonConfig {

	@Bean
	Jackson2ObjectMapperBuilderCustomizer jacksonEstricto() {
		return builder -> builder.postConfigurer(mapper -> {
			var texto = mapper.coercionConfigFor(LogicalType.Textual);
			texto.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
			texto.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
			texto.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
		});
	}

}
