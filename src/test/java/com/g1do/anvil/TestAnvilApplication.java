package com.g1do.anvil;

import org.springframework.boot.SpringApplication;

public class TestAnvilApplication {

	public static void main(String[] args) {
		SpringApplication.from(AnvilApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
