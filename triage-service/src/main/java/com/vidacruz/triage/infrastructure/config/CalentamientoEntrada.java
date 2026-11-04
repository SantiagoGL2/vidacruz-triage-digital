package com.vidacruz.triage.infrastructure.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.triage.infrastructure.entrypoint.web.dto.request.CambiarEstadoRequest;
import com.vidacruz.triage.infrastructure.entrypoint.web.dto.request.RegistrarPacienteRequest;
import jakarta.validation.Validator;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Prepara en el arranque, en el hilo principal, lo que de otro modo se construiria de forma perezosa en el primer
 * request y bloquearia un hilo del event loop (BlockHound lo detecta):
 * <ul>
 * <li>Hibernate Validator lee sus ResourceBundle (disco) al interpolar el primer mensaje de error.</li>
 * <li>Jackson construye y cachea el deserializador de cada DTO bajo un lock interno; dos primeras peticiones
 * concurrentes se bloquearian entre si.</li>
 * </ul>
 */
@Component
class CalentamientoEntrada implements ApplicationRunner {

	private final Validator validator;
	private final ObjectMapper objectMapper;

	CalentamientoEntrada(Validator validator, ObjectMapper objectMapper) {
		this.validator = validator;
		this.objectMapper = objectMapper;
	}

	@Override
	public void run(ApplicationArguments args) throws JsonProcessingException {
		var registrar = objectMapper.readValue("{}", RegistrarPacienteRequest.class);
		var cambiar = objectMapper.readValue("{}", CambiarEstadoRequest.class);
		validator.validate(registrar);
		validator.validate(cambiar);
	}

}
