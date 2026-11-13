package com.jplatform_studiodomino.shared.config;

import com.jplatform_studiodomino.shared.config.SeoInterceptor;
import jakarta.servlet.ServletContext;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Collections;

@Configuration
public class WebConfig implements ServletContextInitializer, WebMvcConfigurer {

    @Value("${upload.path}")
    private String uploadPath;

    private final SeoInterceptor seoInterceptor;

    public WebConfig(SeoInterceptor seoInterceptor) {
        this.seoInterceptor = seoInterceptor;
    }

    @Override
    public void onStartup(ServletContext servletContext) {
        servletContext.setInitParameter("IDSITE", "1");
        servletContext.setSessionTrackingModes(
                Collections.singleton(SessionTrackingMode.COOKIE)
        );
    }

    public ServletContextInitializer sessionConfig() {
        return servletContext -> {
            SessionCookieConfig sessionCookieConfig = servletContext.getSessionCookieConfig();
            sessionCookieConfig.setHttpOnly(true);
            sessionCookieConfig.setSecure(false);
            sessionCookieConfig.setName("JPLATFORMSESSION");
        };
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/imageProfile/**")
                .addResourceLocations("file:" + uploadPath + "imageProfile/");

        registry.addResourceHandler("/cmss/cms-repository/images/**")
                .addResourceLocations("file:" + uploadPath);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(seoInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/site01/**",
                        "/css/**", "/js/**", "/images/**", "/assets/**", "/webjars/**",
                        "/imageProfile/**",
                        "/cmss/cms-repository/images/**",
                        "/api/**",
                        "/sitemap.xml", "/robots.txt");
    }
}