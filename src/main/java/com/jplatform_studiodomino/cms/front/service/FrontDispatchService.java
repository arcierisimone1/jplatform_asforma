package com.jplatform_studiodomino.cms.front.service;

import com.jplatform_studiodomino.cms.entity.*;
import com.jplatform_studiodomino.cms.front.dto.Breadcrumb;
import com.jplatform_studiodomino.cms.front.dto.ExtraTag;
import com.jplatform_studiodomino.cms.front.dto.FrontContentFilter;
import com.jplatform_studiodomino.cms.service.AllegatoService;
import com.jplatform_studiodomino.cms.service.CommentoService;
import com.jplatform_studiodomino.cms.service.ContentService;
import com.jplatform_studiodomino.shared.config.Configurazione;
import com.jplatform_studiodomino.shared.entity.Images;
import com.jplatform_studiodomino.shared.entity.Site;
import com.jplatform_studiodomino.shared.entity.UtenteEsterno;
import com.jplatform_studiodomino.shared.service.ImagesService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class FrontDispatchService {

    private final ContentService contentService;
    private final ExtraTagService extraTagService;
    private final AllegatoService allegatoService;
    private final CommentoService commentoService;
    private final ImagesService imagesService;

    // ========================================
    // ORDINAMENTO E FILTRI
    // ========================================

    public String resolveOrdinamento(String ordinamento, Site site) {
        if (ordinamento != null && !ordinamento.isEmpty()) return ordinamento;
        String defaultOrder = site.getLibero(7);
        return (defaultOrder != null && !defaultOrder.isEmpty()) ? defaultOrder : "data desc";
    }

    public FrontContentFilter buildContentFilter(
            String stato, String privato, String my,
            String archivio, String orderBy,
            String sqlContenuto, UtenteEsterno utente) {

        return FrontContentFilter.builder()
                .stato(stato)
                .privato(privato)
                .my(my)
                .archivio(archivio)
                .ordinamento(orderBy)
                .sqlContenuto(sqlContenuto)
                .utente(utente)
                .build();
    }

    // ========================================
    // UTILITY - GALLERY
    // ========================================

    private List<Images> parseGalleryString(String galleryString) {
        if (galleryString == null || galleryString.trim().isEmpty()) return new ArrayList<>();
        return Arrays.stream(galleryString.split("[,;]"))
                .map(s -> s.trim().replace("(", "").replace(")", ""))
                .filter(s -> !s.isEmpty())
                .map(idStr -> {
                    try {
                        return imagesService.findById(Integer.parseInt(idStr)).orElse(null);
                    } catch (NumberFormatException e) {
                        log.warn("ID immagine non valido in gallery: {}", idStr);
                        return null;
                    }
                })
                .filter(img -> img != null)
                .collect(Collectors.toList());
    }

    private void popolaGallerySezione(Section section) {
        String gs = section.getGalleryString();
        if (gs != null && !gs.isEmpty()) {
            List<Images> gallery = parseGalleryString(gs);
            section.setGallery(gallery);
            section.setGalleryList(gallery);
        }
    }

    // ========================================
    // CARICAMENTO CONTENUTI
    // ========================================

    public Content getContentBase(String pid) {
        try {
            return contentService.findContentEntityById(Integer.parseInt(pid)).orElse(null);
        } catch (NumberFormatException e) {
            log.error("ID non valido: {}", pid);
            return null;
        }
    }

    public Section loadSection(
            String pid,
            FrontContentFilter filter,
            String imagesRepositoryWeb,
            Integer idSito,
            boolean loadSubsections,
            boolean trackClick,
            String my) {

        try {
            Integer sectionId = Integer.parseInt(pid);

            // 1. Sezione base
            Section section = contentService.findSectionById(sectionId)
                    .orElseThrow(() -> new IllegalArgumentException("Sezione non trovata: " + pid));

            // 2. SectionType
            if (section.getIdType() != null) {
                section.setSectionType(contentService.getSectionTypeById(section.getIdType()));
            }

            // 3. Ordinamento
            String orderByContenuti = determineContentOrdering(section, filter.getOrdinamento());
            String maxOrdine = section.getMaxOrdineContenuti();
            if (maxOrdine == null || maxOrdine.isEmpty()) maxOrdine = "0";

            LocalDate today = LocalDate.now();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd-MM-yyyy");

            // 4. Contenuti con paginazione SQL
            List<DatiBase> contenuti;
            boolean isArgomento = section.getSectionType() != null
                    && section.getSectionType().getType() != null
                    && section.getSectionType().getType().equalsIgnoreCase("Argomento");

            if (isArgomento) {
                String tag = section.getTitolo();
                contenuti = contentService.findContentsByTag(idSito.toString(), tag);
                if ((contenuti == null || contenuti.isEmpty()) && section.getLabel() != null) {
                    contenuti = contentService.findContentsByTag(idSito.toString(), section.getLabel());
                }
                if (contenuti == null) contenuti = new ArrayList<>();
                contenuti = contenuti.stream().filter(item -> {
                    if (!"1".equals(item.getS3())) return true;
                    try {
                        String s1 = item.getS1(), s2 = item.getS2();
                        if (s1 != null && !s1.isEmpty() && today.isBefore(LocalDate.parse(s1, fmt))) return false;
                        if (s2 != null && !s2.isEmpty() && today.isAfter(LocalDate.parse(s2, fmt)))  return false;
                    } catch (Exception e) { return true; }
                    return true;
                }).collect(Collectors.toList());
                section.setContenuti(contenuti);
            } else {
                int pageSize = 20;
                String v6 = section.getVarchar6();
                if (v6 != null && !v6.isEmpty()) {
                    try { pageSize = Integer.parseInt(v6.trim()); } catch (NumberFormatException ignored) {}
                }
                int page = filter.getPage() < 0 ? 0 : filter.getPage();

                String q   = filter.getQ()   != null && !filter.getQ().isEmpty()   ? filter.getQ()   : null;
                String dal = filter.getDal() != null && !filter.getDal().isEmpty() ? filter.getDal() : null;
                String al  = filter.getAl()  != null && !filter.getAl().isEmpty()  ? filter.getAl()  : null;

                LocalDate dataDal = null, dataAl = null;
                DateTimeFormatter fmtSql = DateTimeFormatter.ofPattern("yyyy-MM-dd");
                try { if (dal != null) dataDal = LocalDate.parse(dal, fmtSql); } catch (Exception ignored) {}
                try { if (al  != null) dataAl  = LocalDate.parse(al,  fmtSql); } catch (Exception ignored) {}

                org.springframework.data.domain.Page<DatiBase> contentPage =
                        contentService.findContentsBySectionPaged(
                                idSito, section.getId(), page, pageSize, q, dataDal, dataAl);

                contenuti = new ArrayList<>(contentPage.getContent());
                section.setContenuti(contenuti);
                section.setTotalePagine((int) contentPage.getTotalPages());
                section.setPaginaCorrente(page);
                section.setTotaleContenuti((int) contentPage.getTotalElements());
            }

            // 5. Filtro periodo pubblicazione
            contenuti = contenuti.stream().filter(item -> {
                if (!"1".equals(item.getS3())) return true;
                try {
                    String s1 = item.getS1(), s2 = item.getS2();
                    if (s1 != null && !s1.isEmpty() && today.isBefore(LocalDate.parse(s1, fmt))) return false;
                    if (s2 != null && !s2.isEmpty() && today.isAfter(LocalDate.parse(s2, fmt)))  return false;
                } catch (Exception e) { return true; }
                return true;
            }).collect(Collectors.toList());

            // 7. Sottosezioni
            if (loadSubsections) {
                List<Section> subsections = filterByPeriod(
                        contentService.findSubsections(idSito.toString(), section.getId().toString()),
                        today, fmt);
                subsections.forEach(this::popolaGallerySezione);
                section.setSubsection(subsections);

                if (section.getIdParent() != null && !section.getIdParent().isEmpty()
                        && !"0".equals(section.getIdParent())) {
                    List<Section> subsectionsParent = filterByPeriod(
                            contentService.findSubsections(idSito.toString(), section.getIdParent()),
                            today, fmt);
                    subsectionsParent.forEach(this::popolaGallerySezione);
                    section.setSubsectionParent(subsectionsParent);
                }
            }

            // 8. Sezione padre
            if (section.getIdParent() != null && !section.getIdParent().isEmpty()
                    && !"0".equals(section.getIdParent())) {
                try {
                    section.setSezionePadre(
                            contentService.findSectionById(Integer.parseInt(section.getIdParent())).orElse(null));
                } catch (NumberFormatException e) {
                    log.warn("ID parent non valido: {}", section.getIdParent());
                }
            }

            // 9. Allegati
            List<Allegato> allegati = allegatoService.findAllegatiByDocumento(section.getId());
            section.setAllegati(allegati);

            // 10. Gallery
            popolaGallerySezione(section);

            // 11. Commenti
            List<Commento> commenti = commentoService.getCommentiConThread(
                    section.getId().toString(), true);
            section.setCommenti(commenti);
            section.setNumeroCommenti(Long.toString(
                    commentoService.contaCommentiApprovati(section.getId().toString())));

            // 12. Click tracking
            if (trackClick) {
                contentService.incrementClick(section.getId(), idSito.toString());
                section.setClick((section.getClick() != null ? section.getClick() : 0) + 1);
            }

            // 13. Stato archivio
            section.setStatoArchivio(
                    (filter.getStato() != null && !"-1".equals(filter.getStato()))
                            ? filter.getStato() : "1");

            return section;

        } catch (NumberFormatException e) {
            log.error("ID sezione non valido: {}", pid);
            throw new IllegalArgumentException("ID sezione non valido: " + pid);
        }
    }

    // ========================================
    // FILTRO RICERCA IN MEMORIA (q, dal, al)
    // ========================================

    private List<DatiBase> applySearchFilter(List<DatiBase> contenuti, FrontContentFilter filter) {
        String q   = filter.getQ();
        String dal = filter.getDal();
        String al  = filter.getAl();

        boolean hasQ   = q   != null && !q.trim().isEmpty();
        boolean hasDal = dal != null && !dal.trim().isEmpty();
        boolean hasAl  = al  != null && !al.trim().isEmpty();

        if (!hasQ && !hasDal && !hasAl) return contenuti;

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        LocalDate dataDal = hasDal ? LocalDate.parse(dal, fmt) : null;
        LocalDate dataAl  = hasAl  ? LocalDate.parse(al,  fmt) : null;
        String qLower     = hasQ   ? q.trim().toLowerCase()    : null;

        return contenuti.stream().filter(item -> {
            if (hasQ) {
                String titolo    = item.getTitolo()    != null ? item.getTitolo().toLowerCase()    : "";
                String riassunto = item.getRiassunto() != null ? item.getRiassunto().toLowerCase() : "";
                if (!titolo.contains(qLower) && !riassunto.contains(qLower)) return false;
            }
            if (dataDal != null && item.getDataSql() != null && item.getDataSql().isBefore(dataDal)) return false;
            if (dataAl  != null && item.getDataSql() != null && item.getDataSql().isAfter(dataAl))   return false;
            return true;
        }).collect(Collectors.toList());
    }

    // ========================================
    // PAGINAZIONE LATO SERVER
    // ========================================

    public void buildPaginationServerSide(Section section, Configurazione configCore,
                                          HttpServletRequest request, FrontContentFilter filter) {
        try {
            if (section == null || section.getContenuti() == null) {
                section.setPaginaCorrente(0);
                section.setTotalePagine(1);
                configCore.setPaginationBar("");
                return;
            }

            int pageSize       = resolvePageSize(section, configCore);
            int paginaCorrente = section.getPaginaCorrente();
            int totalePagine   = section.getTotalePagine() > 0 ? section.getTotalePagine() : 1;

            configCore.setItemsPage(String.valueOf(pageSize));
            configCore.setPageNumber(String.valueOf(paginaCorrente + 1));
            configCore.setTotalPage(String.valueOf(totalePagine));

            String paginationUrl = buildSeoPageUrl(section, filter);
            configCore.setPaginationBar(
                    generatePaginationHtml(paginaCorrente, totalePagine, paginationUrl));

        } catch (Exception e) {
            log.error("Errore in buildPaginationServerSide", e);
            section.setPaginaCorrente(0);
            section.setTotalePagine(1);
            configCore.setPaginationBar("");
        }
    }

    public void buildPagination(Section section, Configurazione configCore,
                                HttpServletRequest request) {
        buildPaginationServerSide(section, configCore, request,
                FrontContentFilter.builder().build());
    }

    private String buildSeoPageUrl(Section section, FrontContentFilter filter) {
        String pid = section.getId().toString();
        String q   = filter.getQ()   != null && !filter.getQ().isEmpty()   ? filter.getQ()   : null;
        String dal = filter.getDal() != null && !filter.getDal().isEmpty() ? filter.getDal() : null;
        String al  = filter.getAl()  != null && !filter.getAl().isEmpty()  ? filter.getAl()  : null;

        if (q == null && dal == null && al == null) {
            return "/front/" + pid + "/";
        }
        return "/front/" + pid + "/circolari/"
                + (q   != null ? q   : "-") + "/"
                + (dal != null ? dal : "-") + "/"
                + (al  != null ? al  : "-") + "/";
    }

    private int resolvePageSize(Section section, Configurazione configCore) {
        // Legge varchar6 della sezione (impostabile dall'admin nel tab "Altro")
        String v6 = section.getVarchar6();
        if (v6 != null && !v6.isEmpty()) {
            try { return Integer.parseInt(v6.trim()); } catch (NumberFormatException ignored) {}
        }
        // Fallback: libero6 del sito
        String l6 = configCore.getSito().getLibero(6);
        if (l6 != null && !l6.isEmpty()) {
            try { return Integer.parseInt(l6.trim()); } catch (NumberFormatException ignored) {}
        }
        return 20;
    }

    private String generatePaginationHtml(int paginaCorrente, int totalePagine, String baseUrl) {
        if (totalePagine <= 1) return "";
        StringBuilder html = new StringBuilder("<ul class=\"pagination\">");
        if (paginaCorrente > 0) {
            html.append("<li><a href=\"").append(baseUrl).append(paginaCorrente - 1)
                    .append("\"><i class=\"fa fa-chevron-left\"></i></a></li>");
        }
        int startPage = Math.max(0, paginaCorrente - 5);
        int endPage   = Math.min(totalePagine - 1, paginaCorrente + 4);
        for (int i = startPage; i <= endPage; i++) {
            if (i == paginaCorrente) {
                html.append("<li class=\"active\"><a href=\"").append(baseUrl)
                        .append(i).append("\">").append(i + 1).append("</a></li>");
            } else {
                html.append("<li><a href=\"").append(baseUrl)
                        .append(i).append("\">").append(i + 1).append("</a></li>");
            }
        }
        if (paginaCorrente < totalePagine - 1) {
            html.append("<li><a href=\"").append(baseUrl).append(paginaCorrente + 1)
                    .append("\"><i class=\"fa fa-chevron-right\"></i></a></li>");
        }
        html.append("</ul>");
        return html.toString();
    }

    // ========================================
    // DOCUMENTO
    // ========================================

    public DatiBase loadDocument(String pid, String imagesRepositoryWeb) {
        try {
            Integer id = Integer.parseInt(pid);
            DatiBase document = contentService.findDatiBaseById(id).orElse(null);
            if (document != null) {
                document.setAllegati(allegatoService.findAllegatiByDocumento(id));
                List<Commento> commenti = commentoService.getCommentiConThread(id.toString(), true);
                document.setCommenti(commenti);
                document.setNumeroCommenti(Long.toString(
                        commentoService.contaCommentiApprovati(id.toString())));
                if (document.getGalleryString() != null && !document.getGalleryString().isEmpty()) {
                    document.setGallery(parseGalleryString(document.getGalleryString()));
                }
                // ===== RELAZIONE (campo "l3") =====
                // Sul vecchio sito Struts, un corso "a calendario" (una data specifica)
                // non aveva quasi mai un proprio testo/programma: la pagina mostrava
                // sempre il contenuto del corso "a catalogo" collegato (post.relazione).
                // Il collegamento e' gia' salvato nel campo l3 per molti corsi (era gia'
                // valorizzato in fase di migrazione, semplicemente mai letto dal sito
                // nuovo). Qui lo carichiamo, se presente e diverso dal documento stesso.
                try {
                    String l3 = document.getL3();
                    if (l3 != null && !l3.isBlank() && !"0".equals(l3.trim())) {
                        Integer relazioneId = Integer.parseInt(l3.trim());
                        if (!relazioneId.equals(id)) {
                            contentService.findDatiBaseById(relazioneId)
                                    .ifPresent(document::setRelazione);
                        }
                    }
                } catch (NumberFormatException e) {
                    log.debug("Campo l3 non numerico per documento {}: '{}'", id, document.getL3());
                }
            }
            return document;
        } catch (NumberFormatException e) {
            log.error("ID documento non valido: {}", pid);
            return null;
        }
    }

    // ========================================
    // BREADCRUMB
    // ========================================

    public Breadcrumb getBreadcrumbs(String pid) {
        try {
            Integer id = Integer.parseInt(pid);
            Section section = contentService.findSectionById(id).orElse(null);
            if (section != null) return buildSectionBreadcrumb(section);
            DatiBase base = contentService.findDatiBaseById(id).orElse(null);
            if (base != null) return buildContentBreadcrumb(base);
            return new Breadcrumb();
        } catch (NumberFormatException e) {
            log.error("ID non valido per breadcrumb: {}", pid);
            return new Breadcrumb();
        }
    }

    private Breadcrumb buildSectionBreadcrumb(Section section) {
        Breadcrumb breadcrumb = new Breadcrumb();
        breadcrumb.add("Home", "/");
        breadcrumb.add(section.getTitolo(), "/front/" + section.getId());
        return breadcrumb;
    }

    private Breadcrumb buildContentBreadcrumb(DatiBase base) {
        Breadcrumb breadcrumb = new Breadcrumb();
        breadcrumb.add("Home", "/");
        if (base.getIdRoot() != null) {
            try {
                Section section = contentService.findSectionById(
                        Integer.parseInt(base.getIdRoot())).orElse(null);
                if (section != null) {
                    breadcrumb.add(section.getTitolo(), "/front/" + section.getId());
                }
            } catch (NumberFormatException e) {
                log.warn("ID root non valido: {}", base.getIdRoot());
            }
        }
        breadcrumb.add(base.getTitolo(), "/front/" + base.getId());
        return breadcrumb;
    }

    // ========================================
    // STATO E PUBBLICAZIONE
    // ========================================

    public boolean isPublished(Content content, String statoParam) {
        if (content == null) return false;
        String stato = content.getStato();
        if ("0".equals(stato) || "4".equals(stato)) return false;
        if (statoParam != null && !statoParam.isEmpty() && !"-1".equals(statoParam))
            return statoParam.equals(stato);
        return "1".equals(stato);
    }

    public boolean isPublished(DatiBase base, String statoParam) {
        if (base == null) return false;
        String stato = base.getStato();
        if ("0".equals(stato) || "4".equals(stato)) return false;
        if (statoParam != null && !statoParam.isEmpty() && !"-1".equals(statoParam))
            return statoParam.equals(stato);
        return "1".equals(stato) || "3".equals(stato);
    }

    public boolean isPublished(Section section) {
        if (section == null) return false;
        String stato = section.getStato();
        return "1".equals(stato) || "3".equals(stato);
    }

    public boolean isInPeriodoPubblicazione(Content content) {
        if (content == null) return true;
        if (!"1".equals(content.getS3())) return true;
        LocalDate oggi = LocalDate.now();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd-MM-yyyy");
        try {
            String s1 = content.getS1(), s2 = content.getS2();
            if (s1 != null && !s1.isEmpty() && oggi.isBefore(LocalDate.parse(s1, fmt))) return false;
            if (s2 != null && !s2.isEmpty() && oggi.isAfter(LocalDate.parse(s2, fmt)))  return false;
        } catch (Exception e) { return true; }
        return true;
    }

    // ========================================
    // EXTRA TAG
    // ========================================

    public ExtraTag loadExtraTagsForSection(Section section, Configurazione configCore) {
        if (section == null) return new ExtraTag();
        return extraTagService.elaboraExtraTagSection(
                section, section.getOrdineExtraTag(), section.getMaxExtraTag(), configCore);
    }

    public ExtraTag loadExtraTagsForContent(DatiBase base, Configurazione configCore) {
        if (base == null) return new ExtraTag();
        return extraTagService.elaboraExtraTagDatiBase(
                base, base.getOrdineExtraTag(), base.getMaxExtraTag(), configCore);
    }

    // ========================================
    // TRACKING
    // ========================================

    public void trackClick(String frompid) {
        try {
            if (frompid != null && !frompid.isEmpty())
                log.debug("Tracking click from newsletter: {}", frompid);
        } catch (Exception e) {
            log.error("Errore in trackClick", e);
        }
    }

    // ========================================
    // UTILITY PRIVATE
    // ========================================

    private String determineContentOrdering(Section section, String filterOrdering) {
        if (section.getOrdineContenuti() != null && !section.getOrdineContenuti().isEmpty())
            return section.getOrdineContenuti();
        if (filterOrdering != null && !filterOrdering.isEmpty())
            return filterOrdering;
        return "data desc";
    }

    private String buildContentWhereCondition(FrontContentFilter filter, Integer sectionId) {
        StringBuilder where = new StringBuilder();

        if (filter.getStato() != null && !"-1".equals(filter.getStato())) {
            return where.append(" AND stato='").append(filter.getStato())
                    .append("' AND privato='0'").toString();
        }
        if ("true".equals(filter.getMy()) && filter.getUtente() != null) {
            return where.append(" AND stato='1'")
                    .append(" AND apertoda='").append(filter.getUtente().getId()).append("'").toString();
        }
        if ("true".equals(filter.getPrivato()) && filter.getUtente() != null) {
            return where.append(" AND stato='1' AND privato!='0'").toString();
        }

        return where.append(" AND (stato='1' OR stato='3') AND privato='0'").toString();
    }

    private List<Section> filterByPeriod(List<Section> sections, LocalDate today,
                                         DateTimeFormatter fmt) {
        return sections.stream().filter(sub -> {
            if (!"1".equals(sub.getS3())) return true;
            try {
                String s1 = sub.getS1(), s2 = sub.getS2();
                if (s1 != null && !s1.isEmpty() && today.isBefore(LocalDate.parse(s1, fmt))) return false;
                if (s2 != null && !s2.isEmpty() && today.isAfter(LocalDate.parse(s2, fmt)))  return false;
            } catch (Exception e) { return true; }
            return true;
        }).collect(Collectors.toList());
    }

    private int resolvePageSizeStatic(Section section, Configurazione configCore) {
        String v6 = section.getVarchar6();
        if (v6 != null && !v6.isEmpty()) {
            try { return Integer.parseInt(v6.trim()); } catch (NumberFormatException ignored) {}
        }
        return 20;
    }
}