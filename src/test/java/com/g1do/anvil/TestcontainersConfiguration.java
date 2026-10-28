package com.g1do.anvil;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Every Spring context in the suite runs against one Postgres that went
 * through backup, clean and point-in-time restore ({@link PitrDrill}), so the
 * full suite passing is the post-restore check (issue #34).
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	JdbcConnectionDetails restoredPostgres() {
		PitrDrill drill = RestoredDatabase.DRILL;
		return new JdbcConnectionDetails() {

			@Override
			public String getUsername() {
				return PitrDrill.USER;
			}

			@Override
			public String getPassword() {
				return PitrDrill.PASSWORD;
			}

			@Override
			public String getJdbcUrl() {
				return drill.jdbcUrl();
			}
		};
	}

	/** Restored once per JVM on first use; Ryuk removes the container at exit. */
	private static final class RestoredDatabase {

		static final PitrDrill DRILL = PitrDrill.restoredForSuite();
	}

}
