package com.thatsme4now.depot.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GlobalModelAttributesAdviceTest {

    @Test
    void appVersion_fallsBackToHardcodedValue_whenNoManifestPresent() {
        // Running from compiled classes (not a packaged JAR) means there is no
        // manifest Implementation-Version, so the fallback must be returned.
        String version = new GlobalModelAttributesAdvice().appVersion();

        assertThat(version).isNotBlank();
    }
}
