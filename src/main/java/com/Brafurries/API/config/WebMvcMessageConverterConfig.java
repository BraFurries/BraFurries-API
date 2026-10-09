package com.Brafurries.API.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcMessageConverterConfig implements WebMvcConfigurer {

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.stream()
            .filter(MappingJackson2HttpMessageConverter.class::isInstance)
            .map(MappingJackson2HttpMessageConverter.class::cast)
            .findFirst()
            .ifPresent(this::addLegacyMediaTypes);
    }

    private void addLegacyMediaTypes(MappingJackson2HttpMessageConverter jacksonConverter) {
        List<MediaType> supportedMediaTypes = new ArrayList<>(jacksonConverter.getSupportedMediaTypes());
        addIfMissing(supportedMediaTypes, MediaType.TEXT_PLAIN);
        addIfMissing(supportedMediaTypes, MediaType.APPLICATION_OCTET_STREAM);
        jacksonConverter.setSupportedMediaTypes(supportedMediaTypes);
    }

    private void addIfMissing(List<MediaType> mediaTypes, MediaType mediaType) {
        if (!mediaTypes.contains(mediaType)) {
            mediaTypes.add(mediaType);
        }
    }
}
