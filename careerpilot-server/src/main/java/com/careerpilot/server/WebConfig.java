package com.careerpilot.server;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final TokenAuthInterceptor tokens;

    public WebConfig(TokenAuthInterceptor tokens) { this.tokens = tokens; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tokens).addPathPatterns("/api/**").excludePathPatterns("/api/auth/register", "/api/auth/login", "/api/health");
    }

    /** The extension runs on a chrome-extension:// origin; requests carry a bearer token, not cookies, so any origin is safe. */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins("*").allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS").allowedHeaders("*");
    }
}
