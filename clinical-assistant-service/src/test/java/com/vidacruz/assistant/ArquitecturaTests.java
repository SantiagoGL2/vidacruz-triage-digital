package com.vidacruz.assistant;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Reglas de arquitectura (las mismas que triage-service, ADR 0001): la estructura por paquetes se hace cumplir con
 * ArchUnit en lugar de modulos Gradle. Si una regla falla, el cambio rompe una frontera de capa: se corrige el codigo.
 */
@AnalyzeClasses(packages = "com.vidacruz.assistant", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquitecturaTests {

	// ------------------------------------------------------------------ capas

	@ArchTest
	static final ArchRule capas = layeredArchitecture().consideringOnlyDependenciesInLayers()
			.layer("Domain").definedBy("com.vidacruz.assistant.domain..")
			.layer("Application").definedBy("com.vidacruz.assistant.application..")
			.layer("Infrastructure").definedBy("com.vidacruz.assistant.infrastructure..")
			.whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer()
			.whereLayer("Application").mayOnlyBeAccessedByLayers("Infrastructure")
			.whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Infrastructure");

	// ------------------------------------------------------------------ domain

	@ArchTest
	static final ArchRule dominioNoDependeDeApplicationNiInfrastructure = noClasses().that().resideInAPackage("..domain..")
			.should().dependOnClassesThat().resideInAnyPackage("..application..", "..infrastructure..");

	@ArchTest
	static final ArchRule dominioNoDependeDeFrameworks = noClasses().that().resideInAPackage("..domain..")
			.should().dependOnClassesThat().resideInAnyPackage(
					"org.springframework..", "io.lettuce..", "org.apache.kafka..", "jakarta..", "com.fasterxml..",
					"lombok..", "io.micrometer..")
			.as("domain/ no debe depender de Spring, Redis, Kafka, Jackson, Lombok ni Micrometer");

	/** Reactor solo en los puertos de salida: son reactivos (Mono) y el dominio los define; el resto es Java puro. */
	@ArchTest
	static final ArchRule dominioSoloUsaReactorEnPuertosDeSalida = noClasses().that().resideInAPackage("..domain..")
			.and().resideOutsideOfPackage("..domain.port.out..")
			.should().dependOnClassesThat().resideInAPackage("reactor..")
			.as("fuera de domain.port.out, el dominio no usa Reactor");

	@ArchTest
	static final ArchRule puertosDeSalidaSoloSonInterfaces = classes().that().resideInAPackage("..domain.port.out..")
			.should().beInterfaces();

	// ------------------------------------------------------------------ application

	@ArchTest
	static final ArchRule aplicacionNoDependeDeInfraestructura = noClasses().that().resideInAPackage("..application..")
			.should().dependOnClassesThat().resideInAPackage("..infrastructure..");

	@ArchTest
	static final ArchRule aplicacionNoConoceFrameworks = noClasses().that().resideInAPackage("..application..")
			.should().dependOnClassesThat().resideInAnyPackage(
					"org.springframework..", "io.lettuce..", "com.fasterxml..", "org.apache.kafka..", "jakarta..")
			.as("application/ solo conoce dominio, Reactor y puertos");

	@ArchTest
	static final ArchRule puertosDeEntradaSoloSonInterfaces = classes().that().resideInAPackage("..application.port.in..")
			.should().beInterfaces();

	@ArchTest
	static final ArchRule casosDeUsoPublicosImplementanUnPuertoDeEntrada = classes().that().resideInAPackage("..application.usecase..")
			.and().arePublic()
			.should().implement(resideInAPackage("..application.port.in.."))
			.andShould().haveSimpleNameEndingWith("UseCaseImpl");

	// ------------------------------------------------------------------ infrastructure

	@ArchTest
	static final ArchRule controllersSoloPasanPorLosPuertosDeEntrada = noClasses().that().resideInAPackage("..entrypoint.web.controller..")
			.should().dependOnClassesThat().resideInAnyPackage(
					"..infrastructure.drivenadapter..", "..domain.port.out..", "..application.usecase..")
			.as("los controllers no tocan adapters ni puertos de salida: solo application.port.in");

	/** Lo mismo para el consumidor Kafka: solo llama casos de uso por su puerto de entrada. */
	@ArchTest
	static final ArchRule consumidoresSoloPasanPorLosPuertosDeEntrada = noClasses().that().resideInAnyPackage("..entrypoint.kafka..")
			.should().dependOnClassesThat().resideInAnyPackage(
					"..infrastructure.drivenadapter..", "..domain.port.out..", "..application.usecase..")
			.as("el consumidor Kafka solo usa application.port.in");

	@ArchTest
	static final ArchRule entrypointNoConoceDrivenAdapters = noClasses().that().resideInAPackage("..infrastructure.entrypoint..")
			.should().dependOnClassesThat().resideInAPackage("..infrastructure.drivenadapter..");

	@ArchTest
	static final ArchRule drivenAdaptersNoConocenEntrypoints = noClasses().that().resideInAPackage("..infrastructure.drivenadapter..")
			.should().dependOnClassesThat().resideInAPackage("..infrastructure.entrypoint..");

	@ArchTest
	static final ArchRule losAdaptersImplementanPuertosDeSalida = classes().that().resideInAPackage("..drivenadapter..adapter..").and().areTopLevelClasses()
			.should().implement(resideInAPackage("..domain.port.out.."))
			.andShould().haveSimpleNameEndingWith("Adapter");

	// ------------------------------------------------------------------ nombres (un paquete = un tipo de cosa)

	@ArchTest
	static final ArchRule nombresDeRespuestas = classes().that().resideInAPackage("..web.dto.response..")
			.should().haveSimpleNameEndingWith("Response");

	@ArchTest
	static final ArchRule nombresDeControllers = classes().that().resideInAPackage("..web.controller")
			.should().haveSimpleNameEndingWith("Controller");

	// ------------------------------------------------------------------ documentacion OpenAPI

	/** OpenAPI/Swagger solo en la capa web (y su configuracion global): nunca en domain, application ni drivenadapter. */
	@ArchTest
	static final ArchRule openApiSoloEnLaCapaWeb = noClasses()
			.that().resideOutsideOfPackages("..infrastructure.entrypoint.web..", "..infrastructure.config..")
			.should().dependOnClassesThat().resideInAnyPackage("io.swagger..", "org.springdoc..")
			.as("las anotaciones OpenAPI solo existen en entrypoint.web (y OpenApiConfig en config)");

	// ------------------------------------------------------------------ el asistente nunca llama a triage

	/** RF-04 parte B: el estado se reconstruye SOLO con el topic. Ningun cliente HTTP saliente en el servicio. */
	@ArchTest
	static final ArchRule nadieLlamaALaApiDeTriage = noClasses()
			.should().dependOnClassesThat().resideInAnyPackage("org.springframework.web.reactive.function.client..")
			.as("el asistente no hace llamadas HTTP a triage-service: el estado sale de triaje.eventos");

}
