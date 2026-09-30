package com.ownclaw;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every bean the application scans has a constructor Spring will use.
 * <p>
 * No test loads a Spring context, so a bean Spring cannot construct passes the whole suite and
 * stops the application at startup. A second, package-private constructor on ContainerSandbox,
 * added as a test seam, did exactly that: with two constructors and neither marked
 * {@code @Autowired}, Spring looks for a no-argument one, finds none, and the application
 * does not start. This asks Spring's own constructor resolution the same question for every
 * scanned class.
 */
class BeanConstructorsTest {

    @Test
    @DisplayName("Spring can pick a constructor for every scanned bean")
    void everyScannedBeanHasAUsableConstructor() throws Exception {
        var scanned = new ClassPathScanningCandidateComponentProvider(true)
                .findCandidateComponents(OwnClawApplication.class.getPackageName());
        var resolver = new AutowiredAnnotationBeanPostProcessor();
        var unusable = new ArrayList<String>();
        for (var definition : scanned) {
            Class<?> type = Class.forName(definition.getBeanClassName(), false,
                    getClass().getClassLoader());
            if (resolver.determineCandidateConstructors(type, definition.getBeanClassName()) != null) {
                continue;
            }
            // None chosen: Spring then instantiates through the no-argument constructor.
            try {
                type.getDeclaredConstructor();
            } catch (NoSuchMethodException e) {
                unusable.add(type.getName());
            }
        }
        assertTrue(scanned.size() > 40, "the scan found the application's beans: " + scanned.size());
        assertTrue(unusable.isEmpty(), "no constructor Spring can use: " + unusable);
    }
}
