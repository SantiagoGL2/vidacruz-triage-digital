package com.vidacruz.triage.infrastructure.drivenadapter.postgres.mapper;

import com.vidacruz.triage.domain.model.Paciente;
import com.vidacruz.triage.domain.model.SignosVitales;
import com.vidacruz.triage.domain.model.enums.EstadoPaciente;
import com.vidacruz.triage.domain.model.enums.NivelTriaje;
import com.vidacruz.triage.infrastructure.drivenadapter.postgres.entity.PacienteEntity;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Dominio <-> fila de pacientes, de forma explicita (sin reflexion ni anotaciones en el dominio). */
public final class PacienteMapper {

	private PacienteMapper() {
	}

	/** {@code version} es null en el alta; en una actualizacion, la version leida (candado optimista). */
	public static PacienteEntity aEntidad(Paciente p, Long version) {
		SignosVitales s = p.signosVitales();
		return PacienteEntity.builder()
				.id(p.id())
				.nombreCompleto(p.nombreCompleto())
				.edad(p.edad())
				.frecuenciaCardiaca(s.frecuenciaCardiaca())
				.presionSistolica(s.presionSistolica())
				.presionDiastolica(s.presionDiastolica())
				.spo2(s.spo2())
				// La columna es NUMERIC(3,1): se redondea a un decimal de forma explicita (ver docs/supuestos.md).
				.temperatura(BigDecimal.valueOf(s.temperatura()).setScale(1, RoundingMode.HALF_UP))
				.dolor(s.dolor())
				.gcs(s.gcs())
				.quejaPrincipal(p.quejaPrincipal())
				.nivel(p.nivel().numero())
				.slaMinutos(p.slaMinutos())
				.estado(p.estado().name())
				.escalado(p.escalado())
				.fechaEscalamiento(p.fechaEscalamiento())
				.fechaRegistro(p.fechaRegistro())
				.fechaPrimeraConsulta(p.fechaPrimeraConsulta())
				.fechaUltimoCambioEstado(p.fechaUltimoCambioEstado())
				.version(version)
				.build();
	}

	public static Paciente aDominio(PacienteEntity e) {
		var signos = new SignosVitales(e.getFrecuenciaCardiaca(), e.getPresionSistolica(), e.getPresionDiastolica(),
				e.getSpo2(), e.getTemperatura().doubleValue(), e.getDolor(), e.getGcs());
		return new Paciente(e.getId(), e.getNombreCompleto(), e.getEdad(), signos, e.getQuejaPrincipal(),
				NivelTriaje.desdeNumero(e.getNivel()), e.getSlaMinutos(), EstadoPaciente.valueOf(e.getEstado()),
				e.isEscalado(), e.getFechaEscalamiento(), e.getFechaRegistro(), e.getFechaPrimeraConsulta(),
				e.getFechaUltimoCambioEstado());
	}

}
