package com.ownclaw.privacy;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * A log line's message with every user's secret vault values taken out ({@link VaultSecrets}):
 * {@code %m} in the log's patterns (logback-spring.xml). A line can carry what a model wrote --
 * its reasoning, a delegation's goal, the action it chose -- and a model that has read a
 * skill's output can write a password it found there; the log goes to the file, the journal and
 * the ops API's log tail.
 */
public class ScrubbedMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return VaultSecrets.scrubAll(super.convert(event));
    }
}
