package com.ownclaw.interfaces.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The upload limit FileUploadController states is the one that applies.
 * <p>
 * The controller refuses a file over {@code MAX_FILE_SIZE} with a message naming that size, but
 * the servlet container parses the multipart body before the controller runs, under
 * {@code spring.servlet.multipart}. Left at Spring's defaults -- 1 MB a file, 10 MB a request --
 * any file over 1 MB was rejected there and never reached the controller or its message. So the
 * application.yaml the app loads is read here with Spring's own loader and bound the way the
 * running app binds it, which also checks how "50MB" is converted to bytes.
 */
class MultipartLimitTest {

    @Test
    @DisplayName("application.yaml lets through exactly the file size the upload controller accepts")
    void configuredLimitIsTheControllers() throws Exception {
        var sources = new YamlPropertySourceLoader()
                .load("application.yaml", new ClassPathResource("application.yaml"));
        MultipartProperties multipart = new Binder(ConfigurationPropertySources.from(sources))
                .bind("spring.servlet.multipart", MultipartProperties.class)
                .orElseGet(MultipartProperties::new);

        assertEquals(FileUploadController.MAX_FILE_SIZE, multipart.getMaxFileSize().toBytes(),
                "spring.servlet.multipart.max-file-size");
        // The browser sends every file picked in one request, so a request limit below the file
        // limit would refuse a single file the controller accepts.
        assertEquals(FileUploadController.MAX_FILE_SIZE, multipart.getMaxRequestSize().toBytes(),
                "spring.servlet.multipart.max-request-size");
    }
}
