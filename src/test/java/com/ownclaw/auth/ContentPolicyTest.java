package com.ownclaw.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContentPolicyTest {

    @Test
    @DisplayName("the page may load scripts and styles from the app only: it needs no CDN, so it works with no internet")
    void nothingFromElsewhere() {
        String csp = SecurityHeadersFilter.cspFor(null);
        assertTrue(csp.contains("script-src 'self' 'unsafe-inline';"), csp);
        assertFalse(csp.contains("https://"), "the policy lets the page load from elsewhere: " + csp);
    }
}
