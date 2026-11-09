package com.jplatform_studiodomino.shared.dto;

public record SeoMetadata(
        String title,
        String description,
        String image,
        String imageAlt,
        String canonicalUrl,
        String type,
        String siteName,
        boolean indexable
) {}
