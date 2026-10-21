package com.luiz.commit_mirror;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CommitMirrorApplication {

	public static void main(String[] args) {
		SpringApplication.run(CommitMirrorApplication.class, args);
	}

}
