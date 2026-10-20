package com.luiz.commit_mirror;

import org.springframework.boot.SpringApplication;

public class TestCommitMirrorApplication {

	public static void main(String[] args) {
		SpringApplication.from(CommitMirrorApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
