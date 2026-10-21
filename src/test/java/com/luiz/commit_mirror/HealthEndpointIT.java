package com.luiz.commit_mirror;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointIT {

	@Value("${local.server.port}")
	private int serverPort;

	@Value("${local.management.port}")
	private int managementPort;

	@Test
	void shouldExposeHealthEndpointAsUp() {
		String body = RestClient.create()
			.get()
			.uri("http://localhost:{port}/actuator/health", managementPort)
			.retrieve()
			.body(String.class);

		assertThat(body).contains("\"status\":\"UP\"");
	}

	@Test
	void shouldServeHealthOnSeparateManagementPort() {
		assertThat(managementPort).isNotEqualTo(serverPort);
	}

}
