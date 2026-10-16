package com.g1do.anvil;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Service probes (Starter Kit health standard).
 *
 * <p>Proves {@code GET /healthz} needs no tenant or dependencies and
 * {@code GET /readyz} checks Postgres, both echoing a correlation id and
 * requiring no auth.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class HealthControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void healthzNeedsNoTenantAndEchoesCorrelation() throws Exception {
        var result = mockMvc.perform(get("/healthz"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("\"ok\"");
        assertThat(result.getResponse().getHeader("X-Correlation-Id")).isNotBlank();
    }

    @Test
    void readyzIsReadyWithDatabase() throws Exception {
        var result = mockMvc.perform(get("/readyz"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("\"ready\"");
        assertThat(result.getResponse().getHeader("X-Correlation-Id")).isNotBlank();
    }

    @Test
    void callerCorrelationIsPropagated() throws Exception {
        var result = mockMvc.perform(get("/healthz")
                        .header("X-Correlation-Id", "test-corr-123"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getHeader("X-Correlation-Id")).isEqualTo("test-corr-123");
    }
}
