package com.interviewflashcards.common.config;

import jakarta.servlet.MultipartConfigElement;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MultipartConfiguration {
    @Bean
    MultipartConfigElement multipartConfigElement(MultipartProperties multipart, AppProperties properties) {
        // Tomcat resolves relative upload locations beneath its work directory. Use the same
        // absolute, configured temp root for multipart spooling and streamed validation.
        multipart.setLocation(Path.of(properties.storage().temporaryRoot()).toAbsolutePath().normalize().toString());
        return multipart.createMultipartConfig();
    }
}
