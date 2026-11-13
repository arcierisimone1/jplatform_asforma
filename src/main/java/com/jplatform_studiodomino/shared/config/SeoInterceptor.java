package com.jplatform_studiodomino.shared.config;

import com.jplatform_studiodomino.shared.dto.SeoMetadata;
import com.jplatform_studiodomino.shared.service.SeoJsonLdService;
import com.jplatform_studiodomino.shared.service.SeoService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.util.Map;

@Component
public class SeoInterceptor implements HandlerInterceptor {

    private final SeoService seo;
    private final SeoJsonLdService seoJsonLd;

    public SeoInterceptor(SeoService seo, SeoJsonLdService seoJsonLd) {
        this.seo = seo;
        this.seoJsonLd = seoJsonLd;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response,
                           Object handler, ModelAndView mv) {

        if (mv == null || !mv.hasView() || !(handler instanceof HandlerMethod)) return;

        String viewName = mv.getViewName();
        if (viewName != null && (viewName.startsWith("redirect:") || viewName.startsWith("forward:"))) return;

        Map<String, Object> model = mv.getModel();

        // Se un controller ha già impostato "seo", vince lui.
        if (!(model.get("seo") instanceof SeoMetadata)) {

            Object post = model.get("post");
            Object section = model.get("section");
            Object config = model.get("config");

            String rawTitle = seo.firstNonBlank(
                    seo.prop(post, "titolo"),
                    seo.prop(section, "titolo"),
                    seo.asText(model.get("pageTitle")),
                    "Home");

            String rawDescription = seo.firstNonBlank(
                    seo.prop(post, "riassunto"),
                    seo.prop(section, "riassunto"),
                    seo.prop(config, "sito.libero2"));

            String logoPath = seo.firstNonBlank(
                    seo.prop(post, "logo"),
                    seo.prop(section, "logo"));

            String imagesRepositoryWeb = seo.prop(config, "imagesRepositoryWeb");

            String rawImage = (logoPath != null
                    && !"nofoto.jpg".equals(logoPath)
                    && imagesRepositoryWeb != null && !imagesRepositoryWeb.isBlank())
                    ? imagesRepositoryWeb + logoPath
                    : null;

            String canonical = seo.firstNonBlank(
                    seo.asText(model.get("currentUrl")),
                    seo.canonical(request));

            model.put("seo", seo.build(
                    rawTitle,
                    rawDescription,
                    rawImage,
                    seo.absolute(canonical),
                    post != null ? "article" : "website",
                    seo.prop(config, "sito.type"),
                    isIndexable(request, model)));
        }

        // JSON-LD: dati organizzazione (uguali su ogni pagina) + breadcrumb della pagina corrente.
        String orgJsonLd = seoJsonLd.organization();
        if (orgJsonLd != null) {
            model.put("orgJsonLd", orgJsonLd);
        }

        Object configObj = model.get("config");
        if (configObj instanceof Configurazione configurazione) {
            String breadcrumbJsonLd = seoJsonLd.breadcrumbList(configurazione.getBreadcrumb());
            if (breadcrumbJsonLd != null) {
                model.put("breadcrumbJsonLd", breadcrumbJsonLd);
            }
        }
    }

    /** Liste paginate oltre la prima e pagine di ricerca non vanno indicizzate. */
    private boolean isIndexable(HttpServletRequest request, Map<String, Object> model) {
        if (Boolean.TRUE.equals(model.get("noindex"))) return false;
        String page = request.getParameter("page");
        if (page != null && !page.isBlank() && !page.equals("0") && !page.equals("1")) return false;
        return request.getParameter("q") == null && request.getParameter("search") == null;
    }
}