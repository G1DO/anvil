package com.g1do.anvil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract's breaking-change check only compares {@code openapi.yaml} with
 * its own base version, so nothing else would notice the code drifting from it.
 * This compares the two: every API operation has a handler and every handler is
 * in the spec, in both directions.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ContractRoutesTest {

    /** Routes outside the JSON API's contract: the framework's error page and the HTML console. */
    private static final Set<String> NOT_IN_THE_CONTRACT = Set.of("/error", "/console", "/console/");

    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void everyOperationHasAHandlerAndEveryHandlerIsInTheSpec() throws Exception {
        assertThat(specified()).contains("GET /api/jobs/{}", "POST /api/documents", "GET /metrics");
        assertThat(handled()).isEqualTo(specified());
    }

    private Set<String> handled() {
        Set<String> routes = new TreeSet<>();
        mappings.getHandlerMethods().keySet().forEach(info -> {
            for (String path : info.getPatternValues()) {
                if (NOT_IN_THE_CONTRACT.contains(path)) {
                    continue;
                }
                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                    routes.add(route(method.name(), path));
                }
            }
        });
        return routes;
    }

    @SuppressWarnings("unchecked")
    private Set<String> specified() throws Exception {
        Map<String, Object> spec = new Yaml().load(Files.readString(Path.of("contracts/openapi.yaml")));
        Set<String> routes = new TreeSet<>();
        ((Map<String, Map<String, Object>>) spec.get("paths")).forEach((path, operations) -> {
            for (String key : operations.keySet()) {
                if (HTTP_METHODS.contains(key)) {
                    routes.add(route(key, path));
                }
            }
        });
        return routes;
    }

    /** Path variable names may differ between the two; only their position matters. */
    private static String route(String method, String path) {
        return method.toUpperCase(Locale.ROOT) + " " + path.replaceAll("\\{[^}]*}", "{}");
    }
}
