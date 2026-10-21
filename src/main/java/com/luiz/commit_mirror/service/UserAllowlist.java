package com.luiz.commit_mirror.service;

import org.springframework.stereotype.Service;

import com.luiz.commit_mirror.config.AppProperties;

@Service
public class UserAllowlist {

	private final long allowedUserId;

	public UserAllowlist(AppProperties properties) {
		this.allowedUserId = properties.telegram().allowedUserId();
	}

	public boolean isAllowed(long userId) {
		return allowedUserId == userId;
	}

}
