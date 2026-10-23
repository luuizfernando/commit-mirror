package com.luiz.commit_mirror;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;

/** Shares the flow context on purpose: one Spring context and one Postgres container for every IT that needs them. */
class HealthEndpointIT extends AbstractFlowIT {

	@Value("${local.management.port}")
	private int managementPort;

	private static int status(int port, String path) {
		return RestClient.create()
			.get()
			.uri("http://localhost:{port}{path}", port, path)
			.exchange((request, response) -> response.getStatusCode().value());
	}

	private static String body(int port, String path) {
		return RestClient.create().get().uri("http://localhost:{port}{path}", port, path).retrieve().body(String.class);
	}

	@Test
	void shouldReportHealthAsUp() {
		assertThat(body(managementPort, "/actuator/health")).contains("\"status\":\"UP\"");
	}

	@Test
	void shouldServeHealthOnSeparateManagementPort() {
		assertThat(managementPort).isNotEqualTo(serverPort);
	}

	@Test
	void shouldNotServeActuatorOnTheApplicationPort() {
		assertThat(status(serverPort, "/actuator/health")).isEqualTo(404);
	}

	@Test
	void shouldExposeInfoEndpoint() {
		assertThat(status(managementPort, "/actuator/info")).isEqualTo(200);
	}

	@Test
	void shouldExposeLivenessProbeAsUp() {
		assertThat(body(managementPort, "/actuator/health/liveness")).contains("\"status\":\"UP\"");
	}

	@Test
	void shouldExposeReadinessProbeAsUp() {
		assertThat(body(managementPort, "/actuator/health/readiness")).contains("\"status\":\"UP\"");
	}

	@Test
	void shouldNotExposeOtherActuatorEndpoints() {
		assertThat(status(managementPort, "/actuator/env")).isEqualTo(404);
	}

}
