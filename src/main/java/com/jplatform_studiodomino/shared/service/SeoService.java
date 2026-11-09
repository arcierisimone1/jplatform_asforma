package com.jplatform_studiodomino.shared.service;

import com.jplatform_studiodomino.shared.dto.SeoMetadata;
import jakarta.servlet.http.HttpServletRequest;
import org.jsoup.Jsoup;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SeoService {

    private final String baseUrl;
    private final String defaultImagePath;
    private final int maxTitle;
    private final int maxDescription;

    public SeoService(
            @Value("${app.base-url}") String baseUrl,
            @Value("${app.seo.default-image}") String defaultImagePath,
            @Value("${app.seo.max-title:60}") int maxTitle,
            @Value("${app.seo.max-description:155}") int maxDescription) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.defaultImagePath = defaultImagePath;
        this.maxTitle = maxTitle;
        this.maxDescription = maxDescription;
    }

    /* ---------- sanificazione ---------- */

    /** Rimuove markup ed entità, normalizza gli spazi, tronca a confine di parola. */
    public String clean(String raw, int maxLength) {
        if (!StringUtils.hasText(raw)) return "";
        String text = Jsoup.parseBodyFragment(raw).text()   // toglie i tag, decodifica &amp; &egrave; ...
                .replace('\u00A0', ' ')                     // &nbsp; diventa spazio vero
                .replaceAll("\\s+", " ")
                .trim();
        if (text.length() <= maxLength) return text;
        int cut = text.lastIndexOf(' ', maxLength);
        return text.substring(0, cut > 0 ? cut : maxLength).trim() + "…";
    }

    /** Rende assoluto un path; lascia intatto ciò che è già un URL completo. */
    public String absolute(String pathOrUrl) {
        if (!StringUtils.hasText(pathOrUrl)) return baseUrl;
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) return pathOrUrl;
        return baseUrl + (pathOrUrl.startsWith("/") ? pathOrUrl : "/" + pathOrUrl);
    }

    /** URL canonico: sempre assoluto, senza query string né slash finale. */
    public String canonical(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return absolute(path);
    }

    /* ---------- costruzione ---------- */

    public SeoMetadata build(String rawTitle, String rawDescription, String rawImage,
                             String canonicalUrl, String type, String siteName,
                             boolean indexable) {

        String pageTitle = clean(rawTitle, maxTitle);
        String site = clean(siteName, 40);
        String fullTitle = (StringUtils.hasText(site)
                && !pageTitle.equalsIgnoreCase(site)
                && pageTitle.length() + site.length() + 3 <= maxTitle + 15)
                ? pageTitle + " | " + site
                : pageTitle;

        return new SeoMetadata(
                StringUtils.hasText(fullTitle) ? fullTitle : site,
                clean(rawDescription, maxDescription),
                absolute(StringUtils.hasText(rawImage) ? rawImage : defaultImagePath),
                pageTitle,
                canonicalUrl,
                StringUtils.hasText(type) ? type : "website",
                site,
                indexable);
    }

    /** Usato dalle pagine di errore, dove l'interceptor non passa. */
    public SeoMetadata fallback() {
        return new SeoMetadata(
                "Pagina non disponibile", "", absolute(defaultImagePath), "",
                baseUrl, "website", "", false);
    }

    /* ---------- lettura difensiva dal model ---------- */

    /**
     * Legge una proprietà (anche annidata, es. "sito.type") e restituisce il valore
     * SOLO se è testo o numero. Un oggetto di dominio restituisce null invece del
     * suo toString(): è questa la protezione contro i "Breadcrumb(items=[...])" nei meta.
     */
    public String prop(Object bean, String path) {
        if (bean == null) return null;
        try {
            Object value = PropertyAccessorFactory.forBeanPropertyAccess(bean)
                    .getPropertyValue(path);
            return asText(value);
        } catch (BeansException | IllegalArgumentException e) {
            return null;
        }
    }

    public String asText(Object value) {
        if (value instanceof CharSequence cs) {
            return StringUtils.hasText(cs.toString()) ? cs.toString() : null;
        }
        if (value instanceof Number n) return n.toString();
        return null;
    }

    public String firstNonBlank(String... values) {
        for (String v : values) {
            if (StringUtils.hasText(v)) return v;
        }
        return null;
    }
}
