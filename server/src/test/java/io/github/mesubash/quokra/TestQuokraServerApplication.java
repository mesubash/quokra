package io.github.mesubash.quokra;

import org.springframework.boot.SpringApplication;

public class TestQuokraServerApplication {

	public static void main(String[] args) {
		SpringApplication.from(QuokraServerApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
