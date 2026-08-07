package com.jplatform_studiodomino.cms.front.controller;

import com.jplatform_studiodomino.cms.entity.Section;
import com.jplatform_studiodomino.cms.service.ContentService;
import com.jplatform_studiodomino.shared.config.Configurazione;
import com.jplatform_studiodomino.shared.service.ConfigurazioneService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDate;
import java.util.List;

/**
 * Genera dinamicamente robots.txt e sitemap.xml per il sito pubblico corrente.
 * Un solo controller, condiviso da tutti i progetti (formazione, servizi, polo,
 * ludum, asforma): l'host/dominio viene letto dalla request, non hardcoded,
 * cosi' funziona automaticamente su ciascun dominio reale in produzione.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class SitemapController {

    private final ConfigurazioneService configurazioneService;
    private final ContentService contentService;

    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public ResponseEntity<String> robots(HttpServletRequest request) {
        String baseUrl = resolveBaseUrl(request);

        StringBuilder sb = new StringBuilder();
        sb.append("User-agent: *\n");
        sb.append("Allow: /\n");
        sb.append("Disallow: /admin\n");
        sb.append("Disallow: /manager\n");
        sb.append("Disallow: /login\n");
        sb.append("Disallow: /filesId\n");
        sb.append("Disallow: /ricerca\n");
        sb.append("Sitemap: ").append(baseUrl).append("/sitemap.xml\n");

        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(sb.toString());
    }

    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    public ResponseEntity<String> sitemap(HttpServletRequest request) {
        String baseUrl = resolveBaseUrl(request);

        Configurazione config = configurazioneService.getOrCreateConfiguration(request);
        String idSite = config.getSito().getId().toString();

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

        // Homepage
        appendUrl(xml, baseUrl + "/", null, "1.0");

        try {
            List<Section> menu = contentService.findPublicMenu(idSite);
            for (Section section : menu) {
                appendSectionAndChildren(xml, baseUrl, section, 0);
            }
        } catch (Exception e) {
            log.warn("Errore generazione sitemap per sito {}: {}", idSite, e.getMessage());
        }

        xml.append("</urlset>\n");

        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(xml.toString());
    }

    private void appendSectionAndChildren(StringBuilder xml, String baseUrl, Section section, int depth) {
        if (section == null || section.getId() == null) {
            return;
        }
        if (!isPublished(section.getStato())) {
            return;
        }

        String loc = baseUrl + "/" + section.getUrl();
        String priority = depth == 0 ? "0.8" : "0.6";
        appendUrl(xml, loc, section.getDataSql(), priority);

        if (section.getSubsection() != null && depth < 5) {
            for (Section child : section.getSubsection()) {
                appendSectionAndChildren(xml, baseUrl, child, depth + 1);
            }
        }
    }

    private boolean isPublished(String stato) {
        // "1" = pubblicato, "3" = pubblicato in evidenza (convenzione usata nel resto del CMS)
        return "1".equals(stato) || "3".equals(stato);
    }

    private void appendUrl(StringBuilder xml, String loc, LocalDate lastmod, String priority) {
        xml.append("  <url>\n");
        xml.append("    <loc>").append(escapeXml(loc)).append("</loc>\n");
        if (lastmod != null) {
            xml.append("    <lastmod>").append(lastmod).append("</lastmod>\n");
        }
        xml.append("    <priority>").append(priority).append("</priority>\n");
        xml.append("  </url>\n");
    }

    private String escapeXml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    /**
     * Ricostruisce lo scheme://host (senza path) rispettando eventuali header
     * di un reverse proxy davanti all'applicazione (X-Forwarded-*), cosi' la
     * sitemap/robots.txt puntano sempre al dominio pubblico reale e non a
     * localhost o all'host interno.
     */
    private String resolveBaseUrl(HttpServletRequest request) {
        String scheme = request.getHeader("X-Forwarded-Proto");
        if (scheme == null || scheme.isBlank()) {
            scheme = request.getScheme();
        }

        String host = request.getHeader("X-Forwarded-Host");
        boolean hasForwardedHost = host != null && !host.isBlank();
        if (!hasForwardedHost) {
            host = request.getServerName();
        }

        StringBuilder sb = new StringBuilder(scheme).append("://").append(host);

        if (!hasForwardedHost) {
            int port = request.getServerPort();
            boolean standardPort = ("https".equals(scheme) && port == 443)
                    || ("http".equals(scheme) && port == 80);
            if (!standardPort) {
                sb.append(":").append(port);
            }
        }

        return sb.toString();
    }
}
