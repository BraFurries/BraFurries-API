package com.Brafurries.API;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvBuilder;
import io.github.cdimascio.dotenv.DotenvEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BraFurriesApiApplication {

	public static void main(String[] args) {
		loadDotenvIntoSystemProperties();
		SpringApplication.run(BraFurriesApiApplication.class, args);
	}

	private static void loadDotenvIntoSystemProperties() {
		Dotenv dotenv = configureDotenv()
				.ignoreIfMalformed()
				.ignoreIfMissing()
				.load();

		for (DotenvEntry entry : dotenv.entries()) {
			if (System.getProperty(entry.getKey()) == null) {
				String environmentValue = System.getenv(entry.getKey());
				String value = environmentValue != null ? environmentValue : entry.getValue();
				System.setProperty(entry.getKey(), stripWrappingQuotes(value));
			}
		}

		normalizeEnvironmentValue("SMTP_USERNAME");
		normalizeEnvironmentValue("SMTP_PASSWORD");
	}

	private static void normalizeEnvironmentValue(String key) {
		if (System.getProperty(key) == null && System.getenv(key) != null) {
			System.setProperty(key, stripWrappingQuotes(System.getenv(key)));
		}
	}

	static String stripWrappingQuotes(String value) {
		if (value == null || value.length() < 2) {
			return value;
		}
		char first = value.charAt(0);
		char last = value.charAt(value.length() - 1);
		if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
			return value.substring(1, value.length() - 1);
		}
		return value;
	}

	private static DotenvBuilder configureDotenv() {
		Path directory = findDotenvDirectory();
		DotenvBuilder builder = Dotenv.configure();
		if (directory != null) {
			builder.directory(directory.toString());
		}
		return builder;
	}

	private static Path findDotenvDirectory() {
		Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
		while (current != null) {
			if (Files.isRegularFile(current.resolve(".env"))) {
				return current;
			}
			current = current.getParent();
		}
		return null;
	}

}
