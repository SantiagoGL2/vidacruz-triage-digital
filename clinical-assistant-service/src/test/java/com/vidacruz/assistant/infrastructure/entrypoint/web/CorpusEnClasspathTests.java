package com.vidacruz.assistant.infrastructure.entrypoint.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** El corpus oficial son exactamente los tres protocolos del enunciado, uno por archivo. */
class CorpusEnClasspathTests {

	@Test
	void hayUnArchivoPorProtocoloDelEnunciado() throws IOException {
		var recursos = new PathMatchingResourcePatternResolver().getResources("classpath:protocolos/*.md");

		assertThat(recursos).extracting(r -> r.getFilename()).containsExactlyInAnyOrder("PROTO-001.md", "PROTO-002.md", "PROTO-003.md");
	}

}
