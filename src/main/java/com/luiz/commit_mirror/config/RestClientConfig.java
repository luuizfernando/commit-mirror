package com.luiz.commit_mirror.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class RestClientConfig {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

	private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

	@Bean
	RestClient telegramRestClient(AppProperties properties) {
		return restClient(properties.telegram().baseUrl());
	}

	@Bean
	RestClient githubRestClient(AppProperties properties) {
		return restClient(properties.github().baseUrl());
	}

	private static RestClient restClient(String baseUrl) {
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(CONNECT_TIMEOUT)
			.build());
		requestFactory.setReadTimeout(READ_TIMEOUT);
		return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
	}

}
