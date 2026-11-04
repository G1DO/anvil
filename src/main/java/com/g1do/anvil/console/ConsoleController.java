package com.g1do.anvil.console;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the operator console page (issue #77).
 *
 * <p>The console is static HTML, CSS and JavaScript in
 * {@code src/main/resources/static/console/}. It has no server-side state and
 * sends {@code X-Tenant-Id} on every API request, so it has exactly the API's
 * tenant scoping.
 */
@Controller
public class ConsoleController {

    /** The page's assets are relative; keep the base path at /console/. */
    @GetMapping("/console")
    public String consoleWithoutSlash() {
        return "redirect:/console/";
    }

    @GetMapping("/console/")
    public String console() {
        return "forward:/console/index.html";
    }
}
