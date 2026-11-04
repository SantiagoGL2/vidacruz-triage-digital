package com.vidacruz.triage;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Reglas de arquitectura (ADR 0001): la estructura por paquetes se hace cumplir con ArchUnit en lugar de modulos
 * Gradle. Si una regla falla, el cambio rompe una frontera de capa: se corrige el codigo, no la regla.
 */
@AnalyzeClasses(packages = "com.vidacruz.triage", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquitecturaTests {

	// ------------------------------------------------------------------ capas

	@ArchTest
	static final ArchRule capas = layeredArchitecture().consideringOnlyDependenciesInLayers()
			.layer("Domain").definedBy("com.vidacruz.triage.domain..")
			.layer("Application").definedBy("com.vidacruz.triage.application..")
			.layer("Infrastructure").definedBy("com.vidacruz.triage.infrastructure..")
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
					"org.springframework..", "io.r2dbc..", "org.apache.kafka..", "jakarta..", "com.fasterxml..",
					"lombok..", "org.postgresql..", "org.flywaydb..", "io.micrometer..")
			.as("domain/ no debe depender de Spring, R2DBC, Kafka, Jackson, Lombok ni Micrometer");

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
					"org.springframework..", "io.r2dbc..", "com.fasterxml..", "org.apache.kafka..", "jakarta..")
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
			.as("los controllers no tocan adapters, entities ni puertos de salida: solo application.port.in");

	/** Lo mismo para los otros puntos de entrada: consumidor Kafka y procesos de fondo solo llaman casos de uso por su puerto. */
	@ArchTest
	static final ArchRule consumidoresYProcesosSoloPasanPorLosPuertosDeEntrada = noClasses().that().resideInAnyPackage(
			"..entrypoint.kafka..", "..entrypoint.scheduler..")
			.should().dependOnClassesThat().resideInAnyPackage(
					"..infrastructure.drivenadapter..", "..domain.port.out..", "..application.usecase..")
			.as("el consumidor Kafka y los procesos de fondo solo usan application.port.in");

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

	// ------------------------------------------------------------------ persistencia

	/** Las entidades Spring Data (@Table) viven solo en drivenadapter.postgres.entity. */
	@ArchTest
	static final ArchRule tableSoloEnEntities = noClasses().that().resideOutsideOfPackage("..infrastructure.drivenadapter.postgres.entity..")
			.should().beAnnotatedWith(Table.class);

	@ArchTest
	static final ArchRule lasEntitiesSonLasUnicasConTable = classes().that().areAnnotatedWith(Table.class)
			.should().resideInAPackage("..infrastructure.drivenadapter.postgres.entity..")
			.andShould().haveSimpleNameEndingWith("Entity");

	/** Las entidades no salen de drivenadapter.postgres: el resto del codigo trabaja con el modelo de dominio. */
	@ArchTest
	static final ArchRule lasEntitiesNoSalenDePostgres = noClasses().that().resideOutsideOfPackage("..infrastructure.drivenadapter.postgres..")
			.should().dependOnClassesThat().resideInAPackage("..drivenadapter.postgres.entity..");

	/** Los repositorios Spring Data y las proyecciones son detalle de drivenadapter.postgres: solo sus adapters los usan. */
	@ArchTest
	static final ArchRule repositoriosYProyeccionesNoSalenDePostgres = noClasses()
			.that().resideOutsideOfPackage("..infrastructure.drivenadapter.postgres..")
			.should().dependOnClassesThat().resideInAnyPackage("..postgres.repository..", "..postgres.projection..");

	@ArchTest
	static final ArchRule repositoriosSonInterfaces = classes().that().resideInAPackage("..postgres.repository..")
			.should().beInterfaces().andShould().haveSimpleNameEndingWith("Repository");

	@ArchTest
	static final ArchRule sinJpa = noClasses().should().dependOnClassesThat().resideInAnyPackage(
			"jakarta.persistence..", "javax.persistence..", "org.springframework.data.jpa..")
			.as("JPA es bloqueante: solo Spring Data R2DBC");

	// ------------------------------------------------------------------ nombres (un paquete = un tipo de cosa)

	@ArchTest
	static final ArchRule nombresDeMappers = classes().that().resideInAPackage("..postgres.mapper..")
			.should().haveSimpleNameEndingWith("Mapper");

	@ArchTest
	static final ArchRule nombresDeDtos = classes().that().resideInAPackage("..web.dto.request..")
			.should().haveSimpleNameEndingWith("Request")
			.andShould().resideOutsideOfPackage("..dto.response..");

	@ArchTest
	static final ArchRule nombresDeRespuestas = classes().that().resideInAPackage("..web.dto.response..")
			.should().haveSimpleNameEndingWith("Response");

	@ArchTest
	static final ArchRule nombresDeControllers = classes().that().resideInAPackage("..web.controller..")
			.should().haveSimpleNameEndingWith("Controller");

}
