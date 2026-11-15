package com.chzzk;

import org.springframework.boot.SpringApplication;

public class TestChzzkApplication {

	public static void main(String[] args) {
		SpringApplication.from(ChzzkApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
