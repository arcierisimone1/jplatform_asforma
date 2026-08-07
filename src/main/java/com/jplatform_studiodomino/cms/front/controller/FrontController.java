package com.jplatform_studiodomino.cms.front.controller;

import com.jplatform_studiodomino.cms.entity.Content;
import com.jplatform_studiodomino.cms.entity.DatiBase;
import com.jplatform_studiodomino.cms.entity.Section;
import com.jplatform_studiodomino.cms.entity.SectionType;
import com.jplatform_studiodomino.cms.front.dto.ExtraTag;
import com.jplatform_studiodomino.cms.front.dto.FrontContentFilter;
import com.jplatform_studiodomino.cms.front.service.*;
import com.jplatform_studiodomino.cms.service.ContentService;
import com.jplatform_studiodomino.shared.config.Configurazione;
import com.jplatform_studiodomino.shared.entity.Utente;
import com.jplatform_studiodomino.shared.service.ConfigurazioneService;
import com.jplatform_studiodomino.shared.service.UtenteService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * FrontController - Entry point principale front-end pubblico
 *
 * URL supportati (tutti gestiti da dispatchSeo via /{pid}/**):
 *
 *   /front?pid=123                                           → legacy query param
 *   /front/123                                               → sezione, pagina 0
 *   /front/123/Titolo-sezione                                → sezione, pagina 0 (slug ignorato)
 *   /front/123/2                                             → sezione, pagina 2
 *   /front/123/circolari/uscita-didattica/2025-01-01/2025-12-31/2   → ricerca completa
 *   /front/123/circolari/uscita-didattica/-/-/0              → solo testo, date vuote
 *   /front/123/circolari/-/2025-01-01/-/0                    → solo data inizio
 *
 * Parsing dei segmenti dopo /{pid}/:
 *   segmenti[0] == "circolari" → ricerca:  [1]=q  [2]=dal  [3]=al  [4]=page
 *   segmenti[0] è numero      → solo pagina
 *   altrimenti                → slug SEO, ignorato (pagina 0)
 */
@Controller
@RequestMapping("/front")
@SessionAttributes("config")
@RequiredArgsConstructor
@Slf4j
public class FrontController {

    private final ConfigurazioneService configurazioneService;
    private final PortalConfigurationService portalConfigService;
    private final FrontDispatchService dispatchService;
    private final CookieNavigationService cookieService;
    private final ContentService contentService;
    private final UtenteService utenteService;

    // =====================================================================
    // ENTRY POINT LEGACY  /front?pid=123
    // =====================================================================

    @GetMapping
    public String dispatch(
            @RequestParam(required = false) String pid,
            @RequestParam(required = false) String ordinamento,
            @RequestParam(required = false) String anno,
            @RequestParam(required = false) String mese,
            @RequestParam(required = false) String stato,
            @RequestParam(required = false) String privato,
            @RequestParam(required = false) String my,
            @RequestParam(required = false) String archivio,
            @RequestParam(required = false) String sqlContenuto,
            @RequestParam(required = false) String frompid,
            @RequestParam(required = false, name = "return") String returnParam,
            @SessionAttribute(required = false) Configurazione config,
            HttpServletRequest request,
            HttpServletResponse response,
            HttpSession session,
            Model model) {

        return handle(pid, ordinamento, anno, mese, stato, privato, my, archivio,
                sqlContenuto, frompid, returnParam,
                "", "", "", 0,
                config, request, response, session, model);
    }

    @GetMapping("/{pid}/**")
    public String dispatchSeo(
            @PathVariable String pid,
            @RequestParam(required = false) String ordinamento,
            @RequestParam(required = false) String anno,
            @RequestParam(required = false) String mese,
            @RequestParam(required = false) String stato,
            @RequestParam(required = false) String privato,
            @RequestParam(required = false) String my,
            @RequestParam(required = false) String archivio,
            @RequestParam(required = false) String sqlContenuto,
            @RequestParam(required = false) String frompid,
            @RequestParam(required = false, name = "return") String returnParam,
            @SessionAttribute(required = false) Configurazione config,
            HttpServletRequest request,
            HttpServletResponse response,
            HttpSession session,
            Model model) {

        // Estrae i segmenti dopo /front/{pid}/
        // es. "/front/123/circolari/uscita/2025-01-01/2025-12-31/2"
        //      → segmenti = ["circolari", "uscita", "2025-01-01", "2025-12-31", "2"]
        String[] segmenti = extractSegments(request, pid);

        String q   = "";
        String dal = "";
        String al  = "";
        int    page = 0;

        if (segmenti.length > 0) {
            String primo = segmenti[0];

            if ("circolari".equalsIgnoreCase(primo)) {
                // /front/123/circolari/{q}/{dal}/{al}/{page}
                q   = segmenti.length > 1 ? normalizeDash(segmenti[1]) : "";
                dal = segmenti.length > 2 ? normalizeDash(segmenti[2]) : "";
                al  = segmenti.length > 3 ? normalizeDash(segmenti[3]) : "";
                page = segmenti.length > 4 ? parseIntSafe(segmenti[4]) : 0;

            } else if (primo.matches("\\d+")) {
                // /front/123/2  → solo numero di pagina
                page = parseIntSafe(primo);

            }
            // altrimenti è uno slug SEO tipo "Le_circolari" → ignoriamo, page=0
        }

        return handle(pid, ordinamento, anno, mese, stato, privato, my, archivio,
                sqlContenuto, frompid, returnParam,
                q, dal, al, page,
                config, request, response, session, model);
    }

    // =====================================================================
    // METODO CENTRALE
    // =====================================================================

    private String handle(
            String pid,
            String ordinamento,
            String anno, String mese, String stato,
            String privato, String my, String archivio,
            String sqlContenuto, String frompid, String returnParam,
            String q, String dal, String al, int page,
            Configurazione config,
            HttpServletRequest request,
            HttpServletResponse response,
            HttpSession session,
            Model model) {

        log.debug("=== FRONT DISPATCH === pid={} q='{}' dal='{}' al='{}' page={}", pid, q, dal, al, page);

        String returnView = "homePortal";

        try {
            // ===== 0. VERIFICA E INIZIALIZZA CONFIGURAZIONE =====
            if (config == null) {
                log.info("Sessione scaduta o configurazione assente, inizializzo nuova configurazione");
                config = configurazioneService.getOrCreateConfiguration(request);
                if (config == null) {
                    log.error("Impossibile inizializzare la configurazione");
                    model.addAttribute("errorMessage", "Errore di configurazione del sistema");
                    return "error/500";
                }
                model.addAttribute("config", config);
            }

            // ===== 0-bis. CAPTCHA per il form contatti (site01/fragments/jspUser/contatti2) =====
            generateCaptcha(session, model);

            // ===== 0-ter. URL corrente (sostituisce #request, non più disponibile in Thymeleaf 3.1+/Spring6) =====
            model.addAttribute("currentUrl", request.getRequestURL().toString());
            model.addAttribute("currentPath", request.getRequestURI());

            // ===== 1. INIZIALIZZA PORTALE =====
            portalConfigService.initializePortal(request, response, config);

            // ===== 2. CLICK TRACKING =====
            if (frompid != null && !frompid.isEmpty()) {
                dispatchService.trackClick(frompid);
            }

            // ===== 3. NESSUN PID → HOME =====
            if (pid == null || pid.isEmpty()) {
                model.addAttribute("config", config);
                return resolveTemplate(config, returnView);
            }

            // ===== 4. CARICA CONTENT BASE =====
            Content contentBase = dispatchService.getContentBase(pid);
            if (contentBase == null) {
                log.warn("Contenuto non trovato per pid: {}", pid);
                model.addAttribute("config", config);
                // Contenuto realmente inesistente: niente soft-404. Lo status deve essere 404
                // anche se la vista mostrata resta la home, altrimenti Google indicizza pagine
                // rimosse/inesistenti come se fossero valide (200 OK).
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return resolveTemplate(config, "homePortal");
            }

            // ===== 5. BREADCRUMB =====
            config.setBreadcrumb(dispatchService.getBreadcrumbs(pid));

            // ===== 6. COOKIE NAVIGAZIONE =====
            cookieService.updateNavigationProfile(request, response, pid, config);

            // ===== 7. VERIFICA ACCESSIBILITÀ =====
            if (!dispatchService.isPublished(contentBase, stato)
                    || !dispatchService.isInPeriodoPubblicazione(contentBase)) {
                log.warn("Contenuto non accessibile: pid={}", pid);
                model.addAttribute("config", config);
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return resolveTemplate(config, "homePortal");
            }

            // ===== 8. ROUTING =====
            if (contentBase.isSection()) {
                returnView = handleSection(
                        pid, anno, mese, stato, privato, my, archivio,
                        sqlContenuto, ordinamento,
                        q, dal, al, page,
                        contentBase, config, request, model);
            } else {
                returnView = handleDocument(pid, ordinamento, contentBase, config, request, model);
            }

            // ===== 9. OVERRIDE RETURN =====
            if (returnParam != null && !returnParam.isEmpty()) {
                returnView = returnParam;
            }

            model.addAttribute("config", config);
            return resolveTemplate(config, returnView);

        } catch (Exception e) {
            log.error("Errore in front dispatch per pid: {}", pid, e);
            model.addAttribute("config", config);
            model.addAttribute("errorMessage", "Errore durante il caricamento della pagina");
            return "error/500";
        }
    }

    // =====================================================================
    // GESTIONE SEZIONE
    // =====================================================================

    private String handleSection(
            String pid,
            String anno, String mese, String stato,
            String privato, String my, String archivio,
            String sqlContenuto, String ordinamento,
            String q, String dal, String al, int page,
            Content contentBase,
            Configurazione config,
            HttpServletRequest request,
            Model model) {

        log.debug("Gestione SEZIONE: pid={}", pid);

        try {
            Integer idSito = config.getSito().getId();

            String orderBy = dispatchService.resolveOrdinamento(ordinamento, config.getSito());

            FrontContentFilter filter = dispatchService.buildContentFilter(
                    stato, privato, my, archivio,
                    orderBy, sqlContenuto, config.getUtente());

            filter.setQ(q);
            filter.setDal(dal);
            filter.setAl(al);
            filter.setPage(page);

            Section section = dispatchService.loadSection(
                    pid, filter, config.getImagesRepositoryWeb(),
                    idSito, true, true, my);

            if (section == null) {
                log.warn("Sezione non trovata: {}", pid);
                return "homePortal";
            }

            if ("1".equals(contentBase.getRegolaextratag1())) {
                ExtraTag extraTag = dispatchService.loadExtraTagsForSection(section, config);
                section.setExtratag(extraTag);
            }

            dispatchService.buildPaginationServerSide(section, config, request, filter);

            config.setActualSection(section);
            config.setContenutiActualSection(section.getContenuti());

            model.addAttribute("breadcrumb", contentService.buildBreadcrumbForSection(
                    section, config.getSito().getId().toString()));
            model.addAttribute("section", section);
            model.addAttribute("contents", section.getContenuti());

            // ===== TEAM AZIENDALE (solo per la sezione "L'azienda", id 312) =====
            // Mostra gli Amministratori del gestionale come "il nostro team",
            // riusando gli stessi dati/foto già gestiti in /admin/amministratori.
            // NB: id diverso da quello del sito Formazione (dove "L'azienda" è 313
            // dopo la fusione con "Sede") perché qui è un database CMS separato.
            if (section.getId() != null && section.getId() == 312) {
                try {
                    List<Utente> team = utenteService.getAllUtenti().stream()
                            .sorted((a, b) -> {
                                String ca = a.getCognome() != null ? a.getCognome() : "";
                                String cb = b.getCognome() != null ? b.getCognome() : "";
                                return ca.compareToIgnoreCase(cb);
                            })
                            .toList();
                    model.addAttribute("team", team);
                } catch (Exception e) {
                    log.warn("Errore caricamento team amministratori per sezione azienda", e);
                }
            }

            model.addAttribute("ricercaQ",      q   != null ? q   : "");
            model.addAttribute("ricercaDal",     dal != null ? dal : "");
            model.addAttribute("ricercaAl",      al  != null ? al  : "");
            model.addAttribute("paginaCorrente", section.getPaginaCorrente());
            model.addAttribute("totalePagine",   section.getTotalePagine());

            return "sectionDetail";

        } catch (Exception e) {
            log.error("Errore caricamento sezione: {}", pid, e);
            return "homePortal";
        }
    }

    // =====================================================================
    // GESTIONE DOCUMENTO
    // =====================================================================

    private String handleDocument(
            String pid, String ordinamento,
            Content contentBase, Configurazione config,
            HttpServletRequest request, Model model) {

        log.debug("Gestione DOCUMENTO: pid={}", pid);

        try {
            DatiBase document = dispatchService.loadDocument(pid, config.getImagesRepositoryWeb());
            if (document == null) {
                log.warn("Documento non trovato: {}", pid);
                return "homePortal";
            }

            Section parentSection = loadParentSection(contentBase, ordinamento, config);

            if (parentSection != null) {
                config.setActualSection(parentSection);
                document.setSection(parentSection);
            } else {
                if (document.getIdType() != null && !document.getIdType().isEmpty()) {
                    try {
                        SectionType sectionType = contentService.getSectionTypeById(
                                Integer.parseInt(document.getIdType()));
                        if (sectionType != null) {
                            Section fakeSection = new Section();
                            fakeSection.setSectionType(sectionType);
                            document.setSection(fakeSection);
                        }
                    } catch (Exception e) {
                        log.warn("Errore caricamento SectionType per documento {}: {}", pid, e.getMessage());
                    }
                }
            }

            loadDocumentExtraTag(document, contentBase, parentSection, config);

            config.setActualDocument(document);
            model.addAttribute("post", document);
            model.addAttribute("content", document);
            if (parentSection != null) model.addAttribute("parentSection", parentSection);
            model.addAttribute("breadcrumb", contentService.buildBreadcrumbForContent(
                    document, config.getSito().getId().toString()));

            return "documentDetail";

        } catch (Exception e) {
            log.error("Errore caricamento documento: {}", pid, e);
            return "homePortal";
        }
    }

    private Section loadParentSection(Content contentBase, String ordinamento, Configurazione config) {
        Integer idRoot = contentBase.getIdRoot();
        if (idRoot == null || idRoot == -1) return null;
        try {
            String orderBy = dispatchService.resolveOrdinamento(ordinamento, config.getSito());
            FrontContentFilter emptyFilter = FrontContentFilter.builder().ordinamento(orderBy).build();
            return dispatchService.loadSection(
                    idRoot.toString(), emptyFilter,
                    config.getImagesRepositoryWeb(),
                    config.getSito().getId(), true, true, null);
        } catch (Exception e) {
            log.warn("Errore caricamento sezione parent: {}", idRoot, e);
            return null;
        }
    }

    private void loadDocumentExtraTag(DatiBase document, Content contentBase,
                                      Section parentSection, Configurazione config) {
        try {
            if ("1".equals(contentBase.getRegolaextratag1())) {
                document.setExtratag(dispatchService.loadExtraTagsForContent(document, config));
            } else if (parentSection != null && "1".equals(parentSection.getRegolaExtraTag1())) {
                document.setExtratag(dispatchService.loadExtraTagsForSection(parentSection, config));
            }
        } catch (Exception e) {
            log.warn("Errore caricamento ExtraTag per documento: {}", document.getId(), e);
        }
    }

    // =====================================================================
    // UTILITY
    // =====================================================================

    /**
     * Estrae i segmenti del path dopo /front/{pid}/
     *
     * es. request URI = "/front/123/circolari/uscita/2025-01-01/2025-12-31/2"
     *     pid         = "123"
     *     → ["circolari", "uscita", "2025-01-01", "2025-12-31", "2"]
     */
    private String[] extractSegments(HttpServletRequest request, String pid) {
        String uri = request.getRequestURI();
        // Rimuove context path se presente
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty()) {
            uri = uri.substring(contextPath.length());
        }
        // Prefisso da rimuovere: "/front/{pid}/"
        String prefix = "/front/" + pid + "/";
        if (uri.length() <= prefix.length()) return new String[0];
        String rest = uri.substring(prefix.length());
        if (rest.isEmpty()) return new String[0];
        return rest.split("/");
    }

    /**
     * Converte "-" (placeholder per campo vuoto nell'URL) in stringa vuota.
     */
    private String normalizeDash(String value) {
        if (value == null || value.equals("-")) return "";
        return value;
    }

    private int parseIntSafe(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { return 0; }
    }

    /**
     * Genera un semplice captcha aritmetico (es. "3 + 5 = ?") per il form contatti.
     * Il risultato atteso viene salvato in sessione e verificato da ContattoController.
     */
    private void generateCaptcha(HttpSession session, Model model) {

        Integer a = (Integer) session.getAttribute("captchaA");
        Integer b = (Integer) session.getAttribute("captchaB");
        Object sum = session.getAttribute("captchaSum");
        if (a == null || b == null || sum == null) {
            java.util.Random random = new java.util.Random();
            a = 1 + random.nextInt(9);
            b = 1 + random.nextInt(9);
            session.setAttribute("captchaSum", a + b);
            session.setAttribute("captchaA", a);
            session.setAttribute("captchaB", b);
        }
        model.addAttribute("captchaA", a);
        model.addAttribute("captchaB", b);
    }

    private String resolveTemplate(Configurazione config, String viewName) {
        return config.getPublicTemplateFolder() + "/front/" + viewName;
    }
}