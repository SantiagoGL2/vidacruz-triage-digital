package com.vidacruz.triage.infrastructure.config;

import com.vidacruz.triage.infrastructure.config.security.TriageSecurityProperties;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metadatos globales de OpenAPI y esquema de seguridad: la API key en el header X-API-Key se aplica a todos los
 * endpoints, asi el boton Authorize de Swagger UI la envia en cada peticion. Las anotaciones por endpoint viven en
 * los propios controllers.
 */
@Configuration
class OpenApiConfig {

	static final String ESQUEMA_API_KEY = "ApiKeyAuth";

	@Bean
	OpenAPI triageOpenApi(TriageSecurityProperties seguridad) {
		return new OpenAPI()
				.info(new Info()
						.title("VidaCruz Triage Digital - triage-service")
						.version("1.0.0")
						.description("""
								Registro y clasificación de pacientes de urgencias por reglas objetivas (RN-01), SLA por \
								nivel (RN-02), escalamiento automático idempotente (RF-03 / RN-03), auditoría inmutable \
								(RN-04), reporte de operación por hora con SQL avanzado (RF-05) y alertas en tiempo real \
								(RF-06). Publica los cambios de estado en Kafka (`triaje.eventos`) con Transactional Outbox. \
								Los errores siguen RFC 7807 (`application/problem+json`)."""))
				.externalDocs(new ExternalDocumentation()
						.description("README del proyecto")
						.url("https://github.com/SantiagoGL2/vidacruz-triage-digital#readme"))
				.components(new Components().addSecuritySchemes(ESQUEMA_API_KEY, new SecurityScheme()
						.type(SecurityScheme.Type.APIKEY)
						.in(SecurityScheme.In.HEADER)
						.name(seguridad.apiKeyHeader())
						.description("API key del servicio (variable TRIAGE_API_KEY), enviada en el header "
								+ seguridad.apiKeyHeader() + ".")))
				.addSecurityItem(new SecurityRequirement().addList(ESQUEMA_API_KEY));
	}

}
