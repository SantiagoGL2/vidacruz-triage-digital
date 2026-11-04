package com.vidacruz.assistant.infrastructure.config;

import com.vidacruz.assistant.infrastructure.entrypoint.web.dto.request.ConsultaRequest;
import jakarta.validation.Validator;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Prepara en el arranque, en el hilo principal, lo que de otro modo se leeria de disco en el primer request y bloquearia un
 * hilo del event loop (BlockHound lo detecta): Hibernate Validator y {@code messages.properties} (los mensajes de
 * Bean Validation son claves de ese archivo) se cargan al interpolar el primer mensaje de error.
 */
@Component
class CalentamientoEntrada implements ApplicationRunner {

	private final Validator validator;

	CalentamientoEntrada(Validator validator) {
		this.validator = validator;
	}

	@Override
	public void run(ApplicationArguments args) {
		validator.validate(new ConsultaRequest(""));
	}

}
