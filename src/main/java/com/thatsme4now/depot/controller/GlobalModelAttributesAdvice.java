package com.thatsme4now.depot.controller;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Stellt Model-Attribute bereit, die auf allen Seiten verfügbar sein sollen —
 * aktuell nur "appVersion" für den Navbar-Titel (siehe fragments/navbar.html).
 *
 * Die Version wird aus dem JAR-Manifest gelesen (Implementation-Version),
 * welches der Spring-Boot-Gradle-Plugin beim `bootJar`-Task automatisch aus
 * build.gradle's `version = '...'` befüllt — keine manuelle Duplizierung
 * nötig. Einschränkung: beim direkten Start aus der IDE (Eclipse/Gradle
 * "Run", kein gepacktes JAR) ist kein Manifest vorhanden, dann bleibt der
 * Wert leer und der Navbar-Titel zeigt keine Versionsnummer an — betrifft
 * nur die lokale Entwicklung, nicht den gepackten/produktiven Betrieb.
 */
@ControllerAdvice
public class GlobalModelAttributesAdvice {

    @ModelAttribute("appVersion")
    public String appVersion() {
        String v = getClass().getPackage().getImplementationVersion();
        return v != null ? v : "1.4";
    }
}
