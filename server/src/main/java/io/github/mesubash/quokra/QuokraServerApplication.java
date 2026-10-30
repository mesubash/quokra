package io.github.mesubash.quokra;

import io.github.mesubash.quokra.tenant.TenantSecrets;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class QuokraServerApplication {

	public static void main(String[] args) {
		if (args.length > 0 && args[0].equals("tenant-secret")) {
			tenantSecret(args);
			return;
		}
		SpringApplication.run(QuokraServerApplication.class, args);
	}

	// `tenant-secret <slug>`: makes a new secret key for the tenant, prints it once and exits.
	// Starts without the web server, so it can run next to a live instance.
	private static void tenantSecret(String[] args) {
		if (args.length != 2) {
			System.err.println("Usage: tenant-secret <tenant slug>");
			System.exit(2);
		}
		SpringApplication app = new SpringApplication(QuokraServerApplication.class);
		app.setWebApplicationType(WebApplicationType.NONE);
		try (ConfigurableApplicationContext context = app.run()) {
			String secret = context.getBean(TenantSecrets.class).rotate(args[1]);
			// Printed, not logged, so it doesn't end up in log storage.
			System.out.println("New secret key for " + args[1] + " (shown once, the old one stops working):");
			System.out.println(secret);
		}
	}

}
