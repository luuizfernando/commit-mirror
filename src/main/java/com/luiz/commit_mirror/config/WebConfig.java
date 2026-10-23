package com.luiz.commit_mirror.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.luiz.commit_mirror.controller.WebhookSecretInterceptor;

@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

	private final WebhookSecretInterceptor webhookSecretInterceptor;

	public WebConfig(WebhookSecretInterceptor webhookSecretInterceptor) {
		this.webhookSecretInterceptor = webhookSecretInterceptor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(webhookSecretInterceptor).addPathPatterns("/telegram/webhook");
	}

}
