package com.vidacruz.triage;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Reglas hexagonales: el dominio es Java puro y las capas apuntan solo hacia adentro. */
@AnalyzeClasses(packages = "com.vidacruz.triage", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquitecturaTests {

	@ArchTest
	static final ArchRule dominioNoDependeDeFrameworks = noClasses().that().resideInAPackage("..domain..")
			.should().dependOnClassesThat().resideInAnyPackage(
					"org.springframework..", "io.r2dbc..", "org.apache.kafka..", "reactor..", "jakarta..",
					"com.fasterxml..", "lombok..", "org.postgresql..", "org.flywaydb..")
			.as("domain/ no debe depender de Spring, R2DBC, Kafka, Reactor, Jackson ni Lombok");

	@ArchTest
	static final ArchRule dominioNoDependeDeOtrasCapas = noClasses().that().resideInAPackage("..domain..")
			.should().dependOnClassesThat().resideInAnyPackage("..application..", "..infrastructure..");

	@ArchTest
	static final ArchRule aplicacionNoDependeDeInfraestructura = noClasses().that().resideInAPackage("..application..")
			.should().dependOnClassesThat().resideInAPackage("..infrastructure..").allowEmptyShould(true);

}
