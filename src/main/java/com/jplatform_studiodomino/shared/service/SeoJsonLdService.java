package com.jplatform_studiodomino.shared.service;

import com.jplatform_studiodomino.cms.entity.Section;
import com.jplatform_studiodomino.cms.front.dto.Breadcrumb;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Costruisce i blocchi JSON-LD (schema.org) per l'header delle pagine:
 * - Organization/LocalBusiness/GovernmentOrganization (dati NAP dell'ente, per Google Business)
 * - BreadcrumbList (per il breadcrumb nei risultati di ricerca Google)
 *
 * Il JSON viene costruito a mano (stessa tecnica di Breadcrumb.toJson()/escapeJson() già presente
 * nel progetto), senza dipendere da Jackson, per evitare problemi di classpath.
 *
 * I dati dell'ente vengono da application.properties (prefisso app.org.*), cosi' ogni sito
 * (formazione, servizi, ludum, polo, asforma) puo' avere i propri senza toccare il codice,
 * sovrascrivendoli via variabile d'ambiente nel proprio docker-compose.yml, come gia' si fa
 * per APP_BASE_URL / UPLOAD_PATH.
 */
@Service
public class SeoJsonLdService {

    private final String baseUrl;
    private final String orgName;
    private final String orgType;
    private final String orgLogo;
    private final String orgPhone;
    private final String street;
    private final String city;
    private final String zip;
    private final String region;
    private final String hoursDays;
    private final String hoursOpens;
    private final String hoursCloses;
    private final String hoursDays2;
    private final String hoursOpens2;
    private final String hoursCloses2;
    private final String sameAs;

    public SeoJsonLdService(
            @Value("${app.base-url}") String baseUrl,
            @Value("${app.org.name:}") String orgName,
            @Value("${app.org.type:LocalBusiness}") String orgType,
            @Value("${app.org.logo:}") String orgLogo,
            @Value("${app.org.phone:}") String orgPhone,
            @Value("${app.org.address.street:}") String street,
            @Value("${app.org.address.city:}") String city,
            @Value("${app.org.address.zip:}") String zip,
            @Value("${app.org.address.region:}") String region,
            @Value("${app.org.hours.days:}") String hoursDays,
            @Value("${app.org.hours.opens:}") String hoursOpens,
            @Value("${app.org.hours.closes:}") String hoursCloses,
            @Value("${app.org.hours.days2:}") String hoursDays2,
            @Value("${app.org.hours.opens2:}") String hoursOpens2,
            @Value("${app.org.hours.closes2:}") String hoursCloses2,
            @Value("${app.org.sameas:}") String sameAs) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.orgName = orgName;
        this.orgType = StringUtils.hasText(orgType) ? orgType : "LocalBusiness";
        this.orgLogo = orgLogo;
        this.orgPhone = orgPhone;
        this.street = street;
        this.city = city;
        this.zip = zip;
        this.region = region;
        this.hoursDays = hoursDays;
        this.hoursOpens = hoursOpens;
        this.hoursCloses = hoursCloses;
        this.hoursDays2 = hoursDays2;
        this.hoursOpens2 = hoursOpens2;
        this.hoursCloses2 = hoursCloses2;
        this.sameAs = sameAs;
    }

    /** JSON-LD Organization/LocalBusiness/GovernmentOrganization. Null se app.org.name non e' configurato. */
    public String organization() {
        if (!StringUtils.hasText(orgName)) return null;

        List<String> fields = new ArrayList<>();
        fields.add(field("@context", "https://schema.org"));
        fields.add(field("@type", orgType));
        fields.add(field("name", orgName));
        fields.add(field("url", baseUrl));
        if (StringUtils.hasText(orgLogo)) fields.add(field("image", absolute(orgLogo)));
        if (StringUtils.hasText(orgPhone)) fields.add(field("telephone", orgPhone));

        if (StringUtils.hasText(street) || StringUtils.hasText(city)) {
            List<String> address = new ArrayList<>();
            address.add(field("@type", "PostalAddress"));
            if (StringUtils.hasText(street)) address.add(field("streetAddress", street));
            if (StringUtils.hasText(city)) address.add(field("addressLocality", city));
            if (StringUtils.hasText(zip)) address.add(field("postalCode", zip));
            if (StringUtils.hasText(region)) address.add(field("addressRegion", region));
            address.add(field("addressCountry", "IT"));
            fields.add("\"address\":{" + String.join(",", address) + "}");
        }

        List<String> hours = new ArrayList<>();
        addHours(hours, hoursDays, hoursOpens, hoursCloses);
        addHours(hours, hoursDays2, hoursOpens2, hoursCloses2);
        if (!hours.isEmpty()) {
            fields.add("\"openingHoursSpecification\":[" + String.join(",", hours) + "]");
        }

        if (StringUtils.hasText(sameAs)) {
            List<String> links = new ArrayList<>();
            for (String link : sameAs.split(",")) {
                if (StringUtils.hasText(link)) links.add(quote(link.trim()));
            }
            if (!links.isEmpty()) {
                fields.add("\"sameAs\":[" + String.join(",", links) + "]");
            }
        }

        return "{" + String.join(",", fields) + "}";
    }

    /** JSON-LD BreadcrumbList per la pagina corrente. Null se il breadcrumb e' vuoto. */
    public String breadcrumbList(Breadcrumb breadcrumb) {
        if (breadcrumb == null || breadcrumb.isEmpty()) return null;

        List<String> elements = new ArrayList<>();
        int position = 1;

        if (breadcrumb.getItems() != null) {
            for (Section section : breadcrumb.getItems()) {
                if (section == null) continue;
                String label = section.getTitolo();
                String url = section.getUrlRW();
                if (!StringUtils.hasText(label)) continue;
                elements.add(breadcrumbItem(position++, label, absolute(url)));
            }
        }

        if (StringUtils.hasText(breadcrumb.getItemAttuale())) {
            elements.add(breadcrumbItem(position, breadcrumb.getItemAttuale(), absolute(breadcrumb.getUrlIdAttuale())));
        }

        if (elements.isEmpty()) return null;

        return "{" + field("@context", "https://schema.org") + ","
                + field("@type", "BreadcrumbList") + ","
                + "\"itemListElement\":[" + String.join(",", elements) + "]}";
    }

    private String breadcrumbItem(int position, String name, String url) {
        List<String> fields = new ArrayList<>();
        fields.add(field("@type", "ListItem"));
        fields.add("\"position\":" + position);
        fields.add(field("name", name));
        if (StringUtils.hasText(url)) fields.add(field("item", url));
        return "{" + String.join(",", fields) + "}";
    }

    private void addHours(List<String> hours, String days, String opens, String closes) {
        if (!StringUtils.hasText(days) || !StringUtils.hasText(opens) || !StringUtils.hasText(closes)) return;
        List<String> dayList = new ArrayList<>();
        for (String day : days.split(",")) {
            if (StringUtils.hasText(day)) dayList.add(quote(day.trim()));
        }
        if (dayList.isEmpty()) return;

        String spec = "{" + field("@type", "OpeningHoursSpecification") + ","
                + "\"dayOfWeek\":[" + String.join(",", dayList) + "],"
                + field("opens", opens.trim()) + ","
                + field("closes", closes.trim()) + "}";
        hours.add(spec);
    }

    private String absolute(String pathOrUrl) {
        if (!StringUtils.hasText(pathOrUrl)) return null;
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) return pathOrUrl;
        return baseUrl + (pathOrUrl.startsWith("/") ? pathOrUrl : "/" + pathOrUrl);
    }

    private String field(String key, String value) {
        return quote(key) + ":" + quote(value);
    }

    private String quote(String value) {
        return "\"" + escape(value) + "\"";
    }

    /** Escape JSON, blindando anche la chiusura anticipata del tag <script> (escape di "/"). */
    private String escape(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '/': sb.append("\\/"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}