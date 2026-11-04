package com.vidacruz.triage.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.triage.domain.Signos;
import com.vidacruz.triage.domain.model.enums.NivelTriaje;
import com.vidacruz.triage.domain.service.ClasificadorTriaje;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * La lista de quejas administrativas sale de triage.clasificacion.quejas-administrativas (application.yml trae la
 * del enunciado) y el ClasificadorTriaje se construye a partir de ella. Sin base de datos ni Docker.
 */
class ClasificacionConfigTests {

	@Configuration
	@EnableConfigurationProperties(TriageClasificacionProperties.class)
	static class Props {
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withUserConfiguration(Props.class);

	private static ClasificadorTriaje clasificador(TriageClasificacionProperties propiedades) {
		var config = new ApplicationConfig();
		return config.clasificadorTriaje(config.quejasAdministrativas(propiedades));
	}

	private static NivelTriaje nivelDe(ClasificadorTriaje clasificador, String queja) {
		return clasificador.clasificar(Signos.normales().build(), queja);
	}

	@Test
	void applicationYmlTraeLaListaDelEnunciado() {
		runner.run(ctx -> {
			var propiedades = ctx.getBean(TriageClasificacionProperties.class);
			assertThat(propiedades.quejasAdministrativas()).containsExactly(
					"documentos", "recolección de resultados", "renovación de recetario", "vacunación", "seguimiento");

			var clasificador = clasificador(propiedades);
			assertThat(nivelDe(clasificador, "Renovacion de recetario")).isEqualTo(NivelTriaje.NO_URGENTE_2);
			assertThat(nivelDe(clasificador, "Herida por cortar")).isEqualTo(NivelTriaje.NO_URGENTE);
		});
	}

	@Test
	void elClasificadorUsaLaListaConfigurada() {
		runner.withPropertyValues(
				"triage.clasificacion.quejas-administrativas[0]=certificado medico",
				"triage.clasificacion.quejas-administrativas[1]=constancia")
				.run(ctx -> {
					var propiedades = ctx.getBean(TriageClasificacionProperties.class);
					assertThat(propiedades.quejasAdministrativas()).containsExactly("certificado medico", "constancia");

					var clasificador = clasificador(propiedades);
					assertThat(nivelDe(clasificador, "Certificado médico")).isEqualTo(NivelTriaje.NO_URGENTE_2);
					assertThat(nivelDe(clasificador, "Constancia de atención")).isEqualTo(NivelTriaje.NO_URGENTE_2);
					// la lista configurada REEMPLAZA a la del enunciado
					assertThat(nivelDe(clasificador, "Renovación de recetario")).isEqualTo(NivelTriaje.NO_URGENTE);
				});
	}

	@Test
	void unaFraseVaciaImpideArrancar() {
		runner.withPropertyValues("triage.clasificacion.quejas-administrativas[0]=   ")
				.run(ctx -> assertThat(ctx).hasFailed());
	}

}
