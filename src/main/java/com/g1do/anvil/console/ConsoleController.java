package com.g1do.anvil.console;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the operator console page (issue #77).
 *
 * <p>The console is a TypeScript single-page app built with React and Vite from
 * {@code frontend/}; its committed production bundle lives in
 * {@code src/main/resources/static/console/}. It has no server-side state and
 * sends {@code X-Tenant-Id} on every API request, so it has exactly the API's
 * tenant scoping. {@code task frontend:check} rebuilds it and fails when the
 * committed bundle is stale.
 */
@Controller
public class ConsoleController {

    /** The built bundle uses relative asset URLs; keep the base path at /console/. */
    @GetMapping("/console")
    public String consoleWithoutSlash() {
        return "redirect:/console/";
    }

    @GetMapping("/console/")
    public String console() {
        return "forward:/console/index.html";
    }
}
