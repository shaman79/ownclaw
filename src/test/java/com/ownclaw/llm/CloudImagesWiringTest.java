package com.ownclaw.llm;

import com.ownclaw.OwnClawApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The gateway's image store, as Spring wires it: the constructor Spring uses takes one, and exactly
 * one scanned bean is one -- the file store, which reads the copy made at upload. No test loads a
 * Spring context, so two candidates, or none, would pass the suite and stop the application at
 * startup (BeanConstructorsTest asks the same of every constructor).
 */
class CloudImagesWiringTest {

    @Test
    @DisplayName("Spring hands the gateway the file store as its image store: the one bean that is one")
    void theFileStoreIsTheOneImageStore() throws Exception {
        var scanned = new ClassPathScanningCandidateComponentProvider(true)
                .findCandidateComponents(OwnClawApplication.class.getPackageName());
        var stores = new ArrayList<String>();
        for (var definition : scanned) {
            Class<?> type = Class.forName(definition.getBeanClassName(), false, getClass().getClassLoader());
            if (CloudImages.class.isAssignableFrom(type)) stores.add(type.getName());
        }
        assertEquals(java.util.List.of("com.ownclaw.conversation.FileStorageService"), stores);
        var autowired = Arrays.stream(CloudGateway.class.getConstructors())
                .filter(c -> c.isAnnotationPresent(Autowired.class)).toList();
        assertEquals(1, autowired.size());
        assertTrue(Arrays.asList(autowired.getFirst().getParameterTypes()).contains(CloudImages.class),
                "the constructor Spring uses takes the image store: " + autowired.getFirst());
        // Mutation: drop the parameter from the Spring constructor -> the gateway sends no image in production.
    }
}
