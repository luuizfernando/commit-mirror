package com.luiz.commit_mirror.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.luiz.commit_mirror.TestProperties;
import com.luiz.commit_mirror.config.AppProperties;

class UserAllowlistTest {

	private final UserAllowlist allowlist = new UserAllowlist(
			TestProperties.withTelegram(new AppProperties.Telegram("token", "secret", 42L, "http://telegram")));

	@Test
	void shouldAllowConfiguredUser() {
		assertThat(allowlist.isAllowed(42L)).isTrue();
	}

	@Test
	void shouldRejectOtherUser() {
		assertThat(allowlist.isAllowed(7L)).isFalse();
	}

}
