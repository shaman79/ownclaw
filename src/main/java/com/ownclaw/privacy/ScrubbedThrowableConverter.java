package com.ownclaw.privacy;

import ch.qos.logback.classic.spi.IThrowableProxy;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;

/**
 * A logged exception, written as Spring Boot writes it ({@code %wEx}), with every user's secret
 * vault values taken out ({@link VaultSecrets}): an exception's message can quote the value it
 * failed on -- the JDK's refusal of an environment variable quoted the whole value.
 */
public class ScrubbedThrowableConverter extends ExtendedWhitespaceThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy tp) {
        return VaultSecrets.scrubAll(super.throwableProxyToString(tp));
    }
}
