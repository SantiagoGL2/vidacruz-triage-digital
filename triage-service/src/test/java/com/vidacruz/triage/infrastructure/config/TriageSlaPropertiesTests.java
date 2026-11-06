package com.vidacruz.triage.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.triage.domain.model.enums.NivelTriaje;
import com.vidacruz.triage.domain.service.PoliticaSlaEstandar;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;

/** Los SLA por defecto viven en {@link NivelTriaje}; application.yml solo los sobrescribe (variable de entorno). */
class TriageSlaPropertiesTests {

	@org.springframework.boot.context.properties.EnableConfigurationProperties(TriageSlaProperties.class)
	static class Config {
	}

	private final ApplicationContextRunner contexto = new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
			.withUserConfiguration(Config.class);

	@Test
	void losSlaPorDefectoSonLosDelEnunciado() {
		assertThat(NivelTriaje.CRITICO.slaPorDefectoMinutos()).isEqualTo(5);
		assertThat(NivelTriaje.URGENTE.slaPorDefectoMinutos()).isEqualTo(10);
		assertThat(NivelTriaje.URGENTE_2.slaPorDefectoMinutos()).isEqualTo(30);
		assertThat(NivelTriaje.NO_URGENTE.slaPorDefectoMinutos()).isEqualTo(60);
		assertThat(NivelTriaje.NO_URGENTE_2.slaPorDefectoMinutos()).isEqualTo(120);
		PoliticaSlaEstandar politica = new PoliticaSlaEstandar();
		for (NivelTriaje nivel : NivelTriaje.values()) {
			assertThat(politica.minutosPara(nivel)).isEqualTo(nivel.slaPorDefectoMinutos());
		}
	}

	@Test
	void sinConfigurarNadaLaPropiedadTomaElValorDelEnum() {
		contexto.run(ctx -> {
			TriageSlaProperties sla = ctx.getBean(TriageSlaProperties.class);
			for (NivelTriaje nivel : NivelTriaje.values()) {
				assertThat(sla.minutos().get(nivel)).as(nivel.name()).isEqualTo(nivel.slaPorDefectoMinutos());
			}
		});
	}

	@Test
	void unaVariableDeEntornoSobrescribeSoloSuNivel() {
		contexto.withPropertyValues("TRIAGE_SLA_CRITICO=1").run(ctx -> {
			TriageSlaProperties sla = ctx.getBean(TriageSlaProperties.class);
			assertThat(sla.minutos().get(NivelTriaje.CRITICO)).isEqualTo(1);
			assertThat(sla.minutos().get(NivelTriaje.URGENTE)).isEqualTo(NivelTriaje.URGENTE.slaPorDefectoMinutos());
		});
	}

	@Test
	void completaLosNivelesAusentes() {
		TriageSlaProperties sla = new TriageSlaProperties(Map.of(NivelTriaje.URGENTE, 3), true, Duration.ofSeconds(30), 100);
		assertThat(sla.minutos().get(NivelTriaje.URGENTE)).isEqualTo(3);
		assertThat(sla.minutos().get(NivelTriaje.NO_URGENTE_2)).isEqualTo(120);
		assertThat(new TriageSlaProperties(null, true, Duration.ofSeconds(30), 100).minutos()).hasSize(5);
	}

}
