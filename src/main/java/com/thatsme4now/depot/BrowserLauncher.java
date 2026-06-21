package com.thatsme4now.depot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.net.URI;

@Slf4j
@Component
public class BrowserLauncher {

    private final Environment env;

    public BrowserLauncher(Environment env) {
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void openBrowser() {
        String port = env.getProperty("server.port", "8080");
        String url  = "http://localhost:" + port + "/btc-tracking";

        // Try java.awt.Desktop first (works on most OS)
        if (Desktop.isDesktopSupported()) {
            try {
                Desktop.getDesktop().browse(new URI(url));
                log.info("Browser opened: {}", url);
                return;
            } catch (Exception e) {
                log.debug("Desktop.browse failed: {}", e.getMessage());
            }
        }

        // Fallback: OS-specific commands
        String os = System.getProperty("os.name").toLowerCase();
        try {
            if (os.contains("win")) {
                Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start", url});
            } else if (os.contains("mac")) {
                Runtime.getRuntime().exec(new String[]{"open", url});
            } else {
                // Linux
                Runtime.getRuntime().exec(new String[]{"xdg-open", url});
            }
            log.info("Browser opened via OS command: {}", url);
        } catch (Exception e) {
            log.warn("Could not open browser automatically. Please open manually: {}", url);
        }
    }
}