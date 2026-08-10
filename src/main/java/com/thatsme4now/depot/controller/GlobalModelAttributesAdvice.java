package com.thatsme4now.depot.controller;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Supplies model attributes available on every page — currently just
 * {@code appVersion}, used by the navbar title (see fragments/navbar.html).
 */
@ControllerAdvice
public class GlobalModelAttributesAdvice {

    /**
     * Reads the app version from the JAR manifest (populated by the
     * Spring Boot Gradle plugin from build.gradle's {@code version}).
     * Falls back to a hardcoded value when run unpacked from the IDE,
     * where no manifest is present.
     */
    @ModelAttribute("appVersion")
    public String appVersion() {
        String v = getClass().getPackage().getImplementationVersion();
        return v != null ? v : "1.4";
    }
}
