package com.vidacruz.triage.domain.model;

import com.vidacruz.triage.domain.exception.CampoInvalido;
import com.vidacruz.triage.domain.exception.DatoClinicoInvalidoException;
import com.vidacruz.triage.domain.exception.TransicionInvalidaException;
import com.vidacruz.triage.domain.model.enums.EstadoPaciente;
import com.vidacruz.triage.domain.model.enums.NivelTriaje;
import com.vidacruz.triage.domain.model.enums.TipoEventoClinico;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Agregado Paciente. Inmutable: cada operacion devuelve un {@link CambioClinico} con el nuevo estado del
 * agregado y los datos de auditoria. El tiempo siempre entra por parametro ({@code ahora}) para ser determinista.
 */
public record Paciente(
		UUID id,
		String nombreCompleto,
		int edad,
		SignosVitales signosVitales,
		String quejaPrincipal,
		NivelTriaje nivel,
		int slaMinutos,
		EstadoPaciente estado,
		boolean escalado,
		Instant fechaEscalamiento,
		Instant fechaRegistro,
		Instant fechaPrimeraConsulta,
		Instant fechaUltimoCambioEstado) {

	/** Coincide con VARCHAR(500) de la tabla pacientes. */
	public static final int QUEJA_MAX = 500;

	/** Constructor canonico: tambien sirve para rehidratar desde persistencia (solo verifica coherencia basica). */
	public Paciente {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(nombreCompleto, "nombreCompleto");
		Objects.requireNonNull(signosVitales, "signosVitales");
		Objects.requireNonNull(quejaPrincipal, "quejaPrincipal");
		Objects.requireNonNull(nivel, "nivel");
		Objects.requireNonNull(estado, "estado");
		Objects.requireNonNull(fechaRegistro, "fechaRegistro");
		Objects.requireNonNull(fechaUltimoCambioEstado, "fechaUltimoCambioEstado");
		if (slaMinutos <= 0) {
			throw new IllegalArgumentException("slaMinutos debe ser positivo");
		}
		if (escalado != (fechaEscalamiento != null)) {
			throw new IllegalArgumentException("escalado y fechaEscalamiento deben ser coherentes");
		}
	}

	/** Valida nombre (3-100 tras trim), edad (0-120) y queja (obligatoria, max 500). */
	public static List<CampoInvalido> validarDatosPersonales(String nombreCompleto, int edad, String quejaPrincipal) {
		List<CampoInvalido> errores = new ArrayList<>();
		int largoNombre = nombreCompleto == null ? 0 : nombreCompleto.trim().length();
		if (largoNombre < 3 || largoNombre > 100) {
			errores.add(new CampoInvalido("nombreCompleto", nombreCompleto, "3-100 caracteres"));
		}
		Validaciones.entero(errores, "edad", edad, 0, 120, " anios");
		if (quejaPrincipal == null || quejaPrincipal.isBlank()) {
			errores.add(new CampoInvalido("quejaPrincipal", quejaPrincipal, "obligatoria, no vacia"));
		} else if (quejaPrincipal.trim().length() > QUEJA_MAX) {
			errores.add(new CampoInvalido("quejaPrincipal", "(%d caracteres)".formatted(quejaPrincipal.trim().length()),
					"maximo %d caracteres".formatted(QUEJA_MAX)));
		}
		return errores;
	}

	/** Registra un paciente ya clasificado (ver RegistradorPacientes para clasificar + SLA). */
	public static CambioClinico registrar(UUID id, String nombreCompleto, int edad, SignosVitales signosVitales,
			String quejaPrincipal, NivelTriaje nivel, int slaMinutos, Instant ahora, String detalle) {
		List<CampoInvalido> errores = validarDatosPersonales(nombreCompleto, edad, quejaPrincipal);
		if (!errores.isEmpty()) {
			throw new DatoClinicoInvalidoException(errores);
		}
		Paciente paciente = new Paciente(id, nombreCompleto.trim(), edad, signosVitales, quejaPrincipal.trim(), nivel,
				slaMinutos, EstadoPaciente.REGISTRADO, false, null, ahora, null, ahora);
		return new CambioClinico(paciente, null, EstadoPaciente.REGISTRADO, TipoEventoClinico.PACIENTE_REGISTRADO,
				ahora, detalle);
	}

	/**
	 * Cambio de estado (RF-02). Lanza {@link TransicionInvalidaException} si no es valida (incluido el mismo estado).
	 * La primera entrada a consulta fija {@code fechaPrimeraConsulta} (base del reporte RF-05).
	 */
	public CambioClinico cambiarEstado(EstadoPaciente destino, Instant ahora) {
		Objects.requireNonNull(destino, "destino");
		if (!estado.puedePasarA(destino)) {
			throw new TransicionInvalidaException(estado, destino);
		}
		Instant primeraConsulta = destino == EstadoPaciente.EN_CONSULTA && fechaPrimeraConsulta == null
				? ahora : fechaPrimeraConsulta;
		Paciente actualizado = new Paciente(id, nombreCompleto, edad, signosVitales, quejaPrincipal, nivel, slaMinutos,
				destino, escalado, fechaEscalamiento, fechaRegistro, primeraConsulta, ahora);
		TipoEventoClinico tipo = destino == EstadoPaciente.EN_CONSULTA
				? TipoEventoClinico.PACIENTE_EN_CONSULTA : TipoEventoClinico.PACIENTE_ALTA;
		return new CambioClinico(actualizado, estado, destino, tipo, ahora, null);
	}

	/** Vencimiento del SLA: fechaRegistro + slaMinutos. */
	public Instant vencimientoSla() {
		return fechaRegistro.plus(Duration.ofMinutes(slaMinutos));
	}

	/** Supero el SLA si {@code ahora} es estrictamente posterior al vencimiento. */
	public boolean haSuperadoSla(Instant ahora) {
		return ahora.isAfter(vencimientoSla());
	}

	/**
	 * Escalamiento (RF-03, RN-03). Solo procede con estado REGISTRADO, sin escalar y con el SLA superado; en
	 * cualquier otro caso devuelve vacio (no es un error: el scheduler lo invoca repetidamente). El estado no
	 * cambia, solo el flag.
	 */
	public Optional<CambioClinico> escalar(Instant ahora) {
		if (estado != EstadoPaciente.REGISTRADO || escalado || !haSuperadoSla(ahora)) {
			return Optional.empty();
		}
		Paciente escalada = new Paciente(id, nombreCompleto, edad, signosVitales, quejaPrincipal, nivel, slaMinutos,
				estado, true, ahora, fechaRegistro, fechaPrimeraConsulta, fechaUltimoCambioEstado);
		String detalle = "SLA de %d min superado (esperando %d min)"
				.formatted(slaMinutos, Duration.between(fechaRegistro, ahora).toMinutes());
		return Optional.of(new CambioClinico(escalada, estado, estado, TipoEventoClinico.PACIENTE_ESCALADO, ahora, detalle));
	}

	/** Tiempo transcurrido en el estado actual (RF-02, GET). */
	public Duration tiempoEnEstadoActual(Instant ahora) {
		return Duration.between(fechaUltimoCambioEstado, ahora);
	}

}
