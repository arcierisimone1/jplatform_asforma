package com.jplatform_studiodomino.cms.admin.controller;

import com.jplatform_studiodomino.cms.entity.Allegato;
import com.jplatform_studiodomino.cms.service.AllegatoService;
import com.jplatform_studiodomino.crm.service.AnagraficaService;
import com.jplatform_studiodomino.shared.config.Configurazione;
import com.jplatform_studiodomino.shared.entity.Folder;
import com.jplatform_studiodomino.shared.entity.Images;
import com.jplatform_studiodomino.shared.entity.Utente;
import com.jplatform_studiodomino.shared.entity.UtenteEsterno;
import com.jplatform_studiodomino.shared.repository.UtenteRepository;
import com.jplatform_studiodomino.shared.service.ConfigurazioneService;
import com.jplatform_studiodomino.shared.service.FolderService;
import com.jplatform_studiodomino.shared.service.ImagesService;
import com.jplatform_studiodomino.shared.util.ViewUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/admin/filemanager")
@RequiredArgsConstructor
@Slf4j
public class FileManagerController {

    private final ConfigurazioneService configurazioneService;
    private final FolderService folderService;
    private final ImagesService imagesService;
    private final AllegatoService allegatoService;
    private final AnagraficaService anagraficaService;
    private final UtenteRepository utenteRepository;

    @Value("${upload.path}")
    private String imagesRepositoryPath;

    private static final DateTimeFormatter DF =
            DateTimeFormatter.ofPattern("dd/MM/yyyy - HH:mm");

    // =====================================================================
    // HELPER PRIVATO — carica dati comuni in model
    // =====================================================================

    private void caricaModelBase(String idFolder, Model model, Configurazione config) {
        try {
            // 1. Identificazione cartella attuale
            int idFolderInt = Integer.parseInt(idFolder);
            Folder currentFolderObj = folderService.findById(idFolderInt).orElse(new Folder());

            // 2. Caricamento contenuti della cartella selezionata (per la GRIGLIA centrale)
            List<Folder> subfolders = folderService.getSubfolders(idFolder);
            List<Images> images     = imagesService.findByFolder(idFolder);
            List<Allegato> allegati = allegatoService.findByFolder(idFolderInt);

            // 3. Caricamento Breadcrumb (percorso in alto)
            List<Folder> folderPath = folderService.getFolderPath(idFolderInt);

            // 4. Caricamento ALBERO GERARCHICO (per la SIDEBAR a sinistra)
            // Partiamo dai figli di "1" per evitare di mostrare la Root stessa o file spazzatura
            List<Folder> sidebarTree = folderService.getFolderTree("1", 0);

            // 5. Arricchimento oggetto corrente con i conteggi per i "Filtri Rapidi" (le card colorate)
            currentFolderObj.setSubfolder(subfolders);
            currentFolderObj.setNumeroFolder(String.valueOf(subfolders.size()));
            currentFolderObj.setNumeroImmagini(String.valueOf(images.size()));
            currentFolderObj.setNumeroAllegati(String.valueOf(allegati.size()));

            // 6. Invio dati al Model (Thymeleaf)
            model.addAttribute("currentFolderObj", currentFolderObj);
            model.addAttribute("subfolders",    subfolders); // Cartelle nella griglia
            model.addAttribute("images",        images);     // Immagini nella griglia
            model.addAttribute("allegati",      allegati);   // Allegati nella griglia
            model.addAttribute("folderPath",    folderPath); // Breadcrumb
            model.addAttribute("allFolders",    sidebarTree);// L'albero gerarchico per la sidebar

            model.addAttribute("currentFolder", idFolder);
            model.addAttribute("config",        config);

        } catch (Exception e) {
            log.error("Errore caricaModelBase folder={}", idFolder, e);
        }
    }

    @PostMapping("/move-element")
    @ResponseBody
    public ResponseEntity<?> moveElement(
            @RequestParam String elementId,
            @RequestParam String targetFolderId,
            @RequestParam String type) {
        try {
            if (type.equals("folder")) {
                if (elementId.equals(targetFolderId)) return ResponseEntity.badRequest().build();
                folderService.moveFolder(elementId, targetFolderId);
            } else if (type.equals("image")) {
                imagesService.moveImage(elementId, targetFolderId);
            } else {
                allegatoService.moveAllegato(elementId, targetFolderId);
            }
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(500).body(e.getMessage());
        }
    }

    // =====================================================================
    // MEDIA LIBRARY — PAGINA PRINCIPALE (layout completo)
    // =====================================================================

    @GetMapping({"", "/"})
    public String mediaLibrary(HttpServletRequest request, Model model) {
        return mediaLibraryByPath("1", request, model);
    }

    @GetMapping("/{idfolder}")
    public String mediaLibraryByPath(
            @PathVariable String idfolder,
            HttpServletRequest request, Model model) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        caricaModelBase(idfolder, model, config);
        model.addAttribute("sorgente", "");
        model.addAttribute("metodo", "tutti");
        return ViewUtils.resolveProtectedTemplate("filemanager/mediaLibrary");
    }

    // =====================================================================
    // MEDIA LIBRARY — SOLO IMMAGINI (layout completo)
    // =====================================================================

    @GetMapping("/images/{idfolder}")
    public String mediaLibraryImages(
            @PathVariable String idfolder,
            HttpServletRequest request, Model model) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        try {
            int idFolderInt = Integer.parseInt(idfolder);
            List<Images> images     = imagesService.findByFolder(idfolder);
            List<Folder> subfolders = folderService.getSubfolders(idfolder);
            List<Folder> folderPath = folderService.getFolderPath(idFolderInt);
            List<Folder> allFolders = folderService.getAllFolders();

            Folder currentFolderObj = folderService.findById(idFolderInt).orElse(new Folder());
            currentFolderObj.setNumeroImmagini(String.valueOf(images.size()));

            model.addAttribute("currentFolderObj", currentFolderObj);
            model.addAttribute("subfolders",    subfolders);
            model.addAttribute("images",        images);
            model.addAttribute("folderPath",    folderPath);
            model.addAttribute("allFolders",    allFolders);
            model.addAttribute("currentFolder", idfolder);
            model.addAttribute("config",        config);
        } catch (Exception e) {
            log.error("Errore mediaLibraryImages folder={}", idfolder, e);
        }

        return ViewUtils.resolveProtectedTemplate("filemanager/mediaLibraryImages");
    }

    // =====================================================================
    // MEDIA LIBRARY — SOLO ALLEGATI (layout completo)
    // =====================================================================

    @GetMapping("/files/{idfolder}")
    public String mediaLibraryFiles(
            @PathVariable String idfolder,
            @RequestParam(value = "sorgente", defaultValue = "") String sorgente,
            @RequestParam(value = "docid", defaultValue = "0") String docId,
            HttpServletRequest request, Model model) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        try {
            int idFolderInt = Integer.parseInt(idfolder);
            List<Folder> subfolders = folderService.getSubfolders(idfolder);
            List<Allegato> allegati = allegatoService.findByFolder(idFolderInt);
            List<Folder> folderPath = folderService.getFolderPath(idFolderInt);
            List<Folder> allFolders = folderService.getAllFolders();

            Folder currentFolderObj = folderService.findById(idFolderInt).orElse(new Folder());
            currentFolderObj.setSubfolder(subfolders);
            currentFolderObj.setNumeroFolder(String.valueOf(subfolders.size()));
            currentFolderObj.setNumeroAllegati(String.valueOf(allegati.size()));

            model.addAttribute("currentFolderObj", currentFolderObj);
            model.addAttribute("subfolders",    subfolders);
            model.addAttribute("allegati",      allegati);
            model.addAttribute("folderPath",    folderPath);
            model.addAttribute("allFolders",    allFolders);
            model.addAttribute("currentFolder", idfolder);
            model.addAttribute("sorgente",      sorgente);
            model.addAttribute("docId",         docId);
            model.addAttribute("config",        config);
        } catch (Exception e) {
            log.error("Errore mediaLibraryFiles folder={}", idfolder, e);
        }

        return ViewUtils.resolveProtectedTemplate("filemanager/mediaLibraryFiles");
    }

    // =====================================================================
    // POPUP — senza layout, caricato via fetch nella modal
    // /admin/filemanager/popup/1         → tutto
    // /admin/filemanager/popup/1/immagini → solo immagini + cartelle
    // /admin/filemanager/popup/1/allegati → solo allegati + cartelle
    // =====================================================================

    @GetMapping("/popup/{idfolder}")
    public String popupTutti(
            @PathVariable String idfolder,
            HttpServletRequest request, Model model) {
        return popupConTipo(idfolder, "tutti", request, model);
    }

    @GetMapping("/popup/{idfolder}/{tipo}")
    public String popupConTipo(
            @PathVariable String idfolder,
            @PathVariable String tipo,
            HttpServletRequest request, Model model) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        caricaModelBase(idfolder, model, config);
        model.addAttribute("allFolders", folderService.getAllFolders());
        model.addAttribute("tipo", tipo);
        return ViewUtils.resolveProtectedTemplate("filemanager/mediaLibraryPopup");
    }

    // =====================================================================
    // FORM IMMAGINE — aperto nella modal
    // =====================================================================

    @GetMapping("/images/new/{idfolder}")
    public String newImage(
            @PathVariable String idfolder,
            HttpServletRequest request, Model model) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        model.addAttribute("image", new Images());
        model.addAttribute("folders", folderService.getAllFolders());
        model.addAttribute("currentFolder", idfolder);
        model.addAttribute("sorgente", "");
        model.addAttribute("config", config);
        return ViewUtils.resolveProtectedTemplate("filemanager/images");
    }

    @GetMapping("/images/{id}/edit/{idfolder}")
    public String editImage(
            @PathVariable Integer id,
            @PathVariable String idfolder,
            HttpServletRequest request, Model model) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        try {
            Images image = imagesService.findById(id).orElseThrow();
            model.addAttribute("image", image);
            model.addAttribute("folders", folderService.getAllFolders());
            model.addAttribute("currentFolder", idfolder);
            model.addAttribute("sorgente", "");
            model.addAttribute("config", config);
        } catch (Exception e) {
            log.error("Errore editImage id={}", id, e);
        }
        return ViewUtils.resolveProtectedTemplate("filemanager/images");
    }

    // =====================================================================
    // SAVE IMMAGINE
    // =====================================================================

    @PostMapping("/images/save")
    public String saveImage(
            @RequestParam(value = "id", required = false) Integer id,
            @RequestParam(value = "name", defaultValue = "") String name,
            @RequestParam(value = "didascalia", defaultValue = "") String didascalia,
            @RequestParam(value = "idfolder", defaultValue = "1") String idFolder,
            @RequestParam(value = "filer", required = false) MultipartFile filer,
            HttpServletRequest request) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        try {
            Images image;
            if (id == null || id <= 0) {
                image = imagesService.uploadImage(filer, idFolder, imagesRepositoryPath);
            } else {
                image = imagesService.findById(id).orElseThrow();
                if (filer != null && !filer.isEmpty()) {
                    imagesService.delete(id);
                    image = imagesService.uploadImage(filer, idFolder, imagesRepositoryPath);
                }
            }
            if (!name.isEmpty()) image.setName(name);
            if (!didascalia.isEmpty()) image.setDidascalia(didascalia);
            image.setL4(config.getAmministratore().getNomeCompleto());
            image.setL5(LocalDateTime.now().format(DF));
            imagesService.save(image);
        } catch (Exception e) {
            log.error("Errore saveImage", e);
        }
        return "redirect:/admin/filemanager/" + idFolder;
    }

    // =====================================================================
    // UPLOAD IMMAGINE AJAX (singola)
    // =====================================================================

    @PostMapping("/images/upload")
    @ResponseBody
    public Map<String, Object> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "idfolder", defaultValue = "1") String idFolder,
            @RequestParam(value = "nome", defaultValue = "") String nome,
            @RequestParam(value = "didascalia", defaultValue = "") String didascalia,
            HttpServletRequest request) {

        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);

        if (!config.isLogged()) {
            result.put("success", false);
            result.put("error", "Non autorizzato");
            return result;
        }

        try {
            Images image = imagesService.uploadImage(file, idFolder, imagesRepositoryPath);
            if (!nome.isEmpty()) image.setName(nome);
            if (!didascalia.isEmpty()) image.setDidascalia(didascalia);
            image.setL4(config.getAmministratore().getNomeCompleto());
            image.setL5(LocalDateTime.now().format(DF));
            imagesService.save(image);
            result.put("success", true);
            result.put("id", image.getId());
            result.put("path", image.getFullpath());
            result.put("nome", image.getName());
        } catch (Exception e) {
            log.error("Errore upload immagine", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    // =====================================================================
    // UPLOAD IMMAGINE DA TINYMCE (drag&drop / incolla dentro l'editor)
    // =====================================================================
    @PostMapping("/upload-tinymce")
    @ResponseBody
    public Map<String, Object> uploadImageTinyMce(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {

        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);

        if (!config.isLogged()) {
            result.put("error", "Non autorizzato");
            return result;
        }

        try {
            Images image = imagesService.uploadImage(file, "1", imagesRepositoryPath);
            image.setL4(config.getAmministratore().getNomeCompleto());
            image.setL5(LocalDateTime.now().format(DF));
            imagesService.save(image);
            result.put("location", config.getImagesRepositoryWeb() + image.getFullpath());
        } catch (Exception e) {
            log.error("Errore upload immagine da TinyMCE", e);
            result.put("error", e.getMessage());
        }
        return result;
    }

    // =====================================================================
    // UPLOAD IMMAGINE AJAX (multipla)
    // =====================================================================

    @PostMapping("/images/upload-multiple")
    @ResponseBody
    public Map<String, Object> uploadImageMultiple(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "idfolder", defaultValue = "1") String idFolder,
            HttpServletRequest request) {

        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);

        if (!config.isLogged()) {
            result.put("success", false);
            return result;
        }

        int ok = 0, ko = 0;
        for (MultipartFile file : files) {
            try {
                Images image = imagesService.uploadImage(file, idFolder, imagesRepositoryPath);
                image.setL4(config.getAmministratore().getNomeCompleto());
                image.setL5(LocalDateTime.now().format(DF));
                imagesService.save(image);
                ok++;
            } catch (Exception e) {
                log.error("Errore upload file: {}", file.getOriginalFilename(), e);
                ko++;
            }
        }
        result.put("success", true);
        result.put("uploaded", ok);
        result.put("errors", ko);
        return result;
    }

    // =====================================================================
    // ELIMINA IMMAGINE
    // =====================================================================

    @PostMapping("/images/{id}/delete")
    @ResponseBody
    public ResponseEntity<String> deleteImage(
            @PathVariable Integer id,
            HttpServletRequest request) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return ResponseEntity.status(401).body("KO");

        try {
            imagesService.delete(id);
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            log.error("Errore eliminazione immagine id={}", id, e);
            return ResponseEntity.internalServerError().body("KO");
        }
    }

    // =====================================================================
    // FORM ALLEGATO — aperto nella modal
    // =====================================================================

    @GetMapping("/files/new/{idfolder}")
    public String newAllegato(
            @PathVariable String idfolder,
            HttpServletRequest request, Model model) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        Allegato allegato = new Allegato();
        allegato.setIdDocAllegati("0");
        allegato.setIdFolder(Integer.parseInt(idfolder));

        model.addAttribute("allegato", allegato);
        model.addAttribute("folders", folderService.getAllFolders());
        model.addAttribute("currentFolder", idfolder);
        model.addAttribute("sorgente", "");
        model.addAttribute("config", config);
        return ViewUtils.resolveProtectedTemplate("filemanager/allegato");
    }

    @GetMapping("/files/new/{idfolder}/{docid}")
    public String newAllegato(
            @PathVariable String idfolder,
            @PathVariable(required = false) String docid,
            HttpServletRequest request, Model model) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        Allegato allegato = new Allegato();
        allegato.setIdDocAllegati(docid != null ? docid : "0");
        allegato.setIdFolder(Integer.parseInt(idfolder));

        model.addAttribute("allegato", allegato);
        model.addAttribute("folders", folderService.getAllFolders());
        model.addAttribute("currentFolder", idfolder);
        model.addAttribute("sorgente", "");
        model.addAttribute("config", config);
        return ViewUtils.resolveProtectedTemplate("filemanager/allegato");
    }

    @GetMapping("/files/{id}/edit/{idfolder}")
    public String editAllegato(
            @PathVariable Integer id,
            @PathVariable String idfolder,
            HttpServletRequest request, Model model) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        try {
            Allegato allegato = allegatoService.findById(id).orElseThrow();
            model.addAttribute("allegato", allegato);
            model.addAttribute("folders", folderService.getAllFolders());
            model.addAttribute("currentFolder", idfolder);
            model.addAttribute("sorgente", "");
            model.addAttribute("config", config);
        } catch (Exception e) {
            log.error("Errore editAllegato id={}", id, e);
        }
        return ViewUtils.resolveProtectedTemplate("filemanager/allegato");
    }

    // =====================================================================
    // UPLOAD ALLEGATO AJAX
    // =====================================================================

    @PostMapping("/files/upload")
    @ResponseBody
    public Map<String, Object> uploadAllegato(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "idfolder", defaultValue = "1") String idFolder,
            @RequestParam(value = "docid", defaultValue = "0") String docId,
            @RequestParam(value = "nome", defaultValue = "") String nome,
            @RequestParam(value = "annotazioni", defaultValue = "") String annotazioni,
            HttpServletRequest request) {

        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);

        if (!config.isLogged()) {
            result.put("success", false);
            return result;
        }

        try {
            String originalFilename = file.getOriginalFilename();
            String ext = originalFilename != null && originalFilename.contains(".")
                    ? originalFilename.substring(originalFilename.lastIndexOf(".") + 1) : "bin";
            String nomeFile = !nome.isEmpty() ? nome
                    : (originalFilename != null
                    ? originalFilename.substring(0, originalFilename.lastIndexOf(".")) : "file");

            Allegato allegato = new Allegato();
            allegato.setIdFolder(Integer.parseInt(idFolder));
            allegato.setL1(nomeFile);
            allegato.setNome(originalFilename);
            allegato.setType(ext);
            allegato.setVersion("0");
            allegato.setAnnotazioni(annotazioni.isEmpty() ? nomeFile : annotazioni);
            allegato.setIdUtente(config.getAmministratore().getId().toString());
            allegato.setApertoDa(config.getAmministratore().getNomeCompleto());
            allegato.setDataInserimento(LocalDateTime.now().format(DF));
            allegato.setDove("file");
            allegato.setIdDocAllegati(docId);

            Allegato saved = allegatoService.salvaAllegato(allegato, file);

            if (!"0".equals(docId)) {
                allegatoService.collegaAllegato(
                        Integer.parseInt(docId), saved.getId(),
                        config.getAmministratore().getNomeCompleto(),
                        config.getAmministratore().getId().toString());
            }

            result.put("success", true);
            result.put("id", saved.getId());
            result.put("l1", saved.getL1());
            result.put("type", saved.getType());
            result.put("url", saved.getUrlRW());
        } catch (Exception e) {
            log.error("Errore upload allegato", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    // =====================================================================
    // DOWNLOAD ALLEGATO
    // =====================================================================

    @GetMapping("/files/{id}/download")
    public ResponseEntity<InputStreamResource> downloadAllegato(
            @PathVariable Integer id,
            HttpServletRequest request) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return ResponseEntity.status(401).build();

        try {
            Allegato allegato = allegatoService.findById(id).orElseThrow();
            InputStream is = allegatoService.getFileInputStream(id);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=" + allegato.getL1() + "." + allegato.getType())
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(new InputStreamResource(is));
        } catch (Exception e) {
            log.error("Errore download allegato id={}", id, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // =====================================================================
    // ELIMINA ALLEGATO
    // =====================================================================

    @PostMapping("/files/{id}/delete")
    @ResponseBody
    public ResponseEntity<String> deleteAllegato(
            @PathVariable Integer id,
            HttpServletRequest request) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return ResponseEntity.status(401).body("KO");

        try {
            allegatoService.deleteAllegato(id);
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            log.error("Errore eliminazione allegato id={}", id, e);
            return ResponseEntity.internalServerError().body("KO");
        }
    }

    // =====================================================================
    // SCOLLEGA ALLEGATO
    // =====================================================================

    @PostMapping("/files/{idDocAllegati}/scollega")
    @ResponseBody
    public ResponseEntity<String> scollegaAllegato(
            @PathVariable Integer idDocAllegati,
            HttpServletRequest request) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return ResponseEntity.status(401).body("KO");

        try {
            allegatoService.scollegaAllegato(idDocAllegati);
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            log.error("Errore scollega allegato id={}", idDocAllegati, e);
            return ResponseEntity.internalServerError().body("KO");
        }
    }

    // =====================================================================
    // GESTIONE FOLDER
    // =====================================================================

    @GetMapping("/folder/new/{idfolder}")
    public String newFolderForm(
            @PathVariable String idfolder,
            HttpServletRequest request, Model model) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        Folder folder = new Folder();
        folder.setIdfolder(idfolder);
        model.addAttribute("folder", folder);
        model.addAttribute("config", config);
        return ViewUtils.resolveProtectedTemplate("filemanager/folder");
    }

    @GetMapping("/folder/{id}/edit")
    public String editFolderForm(
            @PathVariable Integer id,
            HttpServletRequest request, Model model) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        try {
            Folder folder = folderService.findById(id).orElseThrow();
            model.addAttribute("folder", folder);
            model.addAttribute("config", config);
        } catch (Exception e) {
            log.error("Errore editFolderForm id={}", id, e);
        }
        return ViewUtils.resolveProtectedTemplate("filemanager/folder");
    }

    @PostMapping("/folder/save")
    public String saveFolder(
            @RequestParam(value = "id", required = false) Integer id,
            @RequestParam("nome") String nome,
            @RequestParam(value = "idfolder", defaultValue = "1") String idFolder,
            @RequestParam(value = "idgruppo", defaultValue = "0") String idGruppo,
            HttpServletRequest request) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        try {
            if (id == null || id <= 0) {
                folderService.createFolder(nome, idFolder, idGruppo);
            } else {
                Folder folder = folderService.findById(id).orElseThrow();
                folder.setNome(nome);
                folder.setIdgruppo(idGruppo);
                folderService.save(folder);
            }
        } catch (Exception e) {
            log.error("Errore saveFolder", e);
        }
        return "redirect:/admin/filemanager/" + idFolder;
    }

    @PostMapping("/folder/{id}/delete")
    @ResponseBody
    public ResponseEntity<String> deleteFolder(
            @PathVariable Integer id,
            HttpServletRequest request) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return ResponseEntity.status(401).body("KO");

        try {
            folderService.deleteFolder(id);
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            log.error("Errore eliminazione folder id={}", id, e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @PostMapping("/folder/{id}/rename")
    @ResponseBody
    public ResponseEntity<String> renameFolder(
            @PathVariable Integer id,
            @RequestParam("nome") String nome,
            HttpServletRequest request) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return ResponseEntity.status(401).body("KO");

        try {
            folderService.renameFolder(id, nome);
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            log.error("Errore rinomina folder id={}", id, e);
            return ResponseEntity.internalServerError().body("KO");
        }
    }

    @PostMapping("/folder/{id}/move")
    @ResponseBody
    public ResponseEntity<String> moveFolder(
            @PathVariable Integer id,
            @RequestParam("newParent") String newParent,
            HttpServletRequest request) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return ResponseEntity.status(401).body("KO");

        try {
            folderService.moveFolder(id, newParent);
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            log.error("Errore spostamento folder id={}", id, e);
            return ResponseEntity.internalServerError().body("KO");
        }
    }

    // =====================================================================
    // AVATAR
    // =====================================================================

    @PostMapping("/avatar/upload")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> uploadAvatar(
            @RequestParam("file") MultipartFile file,
            @RequestParam("idUtente") String idUtente,
            HttpServletRequest request) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        Map<String, Object> result = new HashMap<>();

        if (!config.isLogged()) {
            result.put("success", false);
            return ResponseEntity.status(401).body(result);
        }

        try {
            Path avatarDir = Paths.get(imagesRepositoryPath, "imageProfile");
            Files.createDirectories(avatarDir);
            Path avatarPath = avatarDir.resolve("pfImage" + idUtente + ".jpg");
            file.transferTo(avatarPath.toFile());
            result.put("success", true);
            result.put("path", "imageProfile/pfImage" + idUtente + ".jpg");
        } catch (Exception e) {
            log.error("Errore upload avatar id={}", idUtente, e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/avatar/upload-base64")
    @ResponseBody
    public ResponseEntity<String> uploadAvatarBase64(
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return ResponseEntity.status(401).body("KO");

        try {
            String imageBase64 = body.get("image");
            String idUtente    = body.get("idUtente");
            String tipo        = body.getOrDefault("tipo", "utente"); // "utente" o "amministratore"

            // Salva su disco
            Path avatarDir = Paths.get(imagesRepositoryPath, "imageProfile");
            Files.createDirectories(avatarDir);
            Path avatarPath = avatarDir.resolve("pfImage" + idUtente + ".jpg");

            if (imageBase64 == null || imageBase64.isEmpty()) {
                // Cancella l'immagine
                Files.deleteIfExists(avatarPath);
            } else {
                byte[] bytes = java.util.Base64.getDecoder()
                        .decode(imageBase64.replaceAll("^data:image/\\w+;base64,", ""));
                Files.write(avatarPath, bytes);
            }

            // Aggiorna DB in base al tipo
            String pathRelativo = (imageBase64 != null && !imageBase64.isEmpty())
                    ? "/imageProfile/pfImage" + idUtente + ".jpg"
                    : null;
            Integer flagProfileImage = (pathRelativo != null) ? 1 : 0;

            if ("amministratore".equals(tipo)) {
                Utente utente = utenteRepository.findById(Integer.parseInt(idUtente))
                        .orElseThrow(() -> new RuntimeException("Amministratore non trovato: " + idUtente));
                utente.setImage(pathRelativo);
                utente.setProfileImage(flagProfileImage);
                Utente saved = utenteRepository.save(utente);
                log.info("Avatar DB aggiornato: id={}, profileImage={}, image={}",
                        saved.getId(), saved.getProfileImage(), saved.getImage());
            } else {
                UtenteEsterno utente = anagraficaService.findById(Integer.parseInt(idUtente));
                utente.setImage(pathRelativo);
                utente.setProfileImage(flagProfileImage);
                anagraficaService.salva(utente);
            }

            log.info("Avatar salvato per tipo={} id={} profileImage={}", tipo, idUtente, flagProfileImage);
            return ResponseEntity.ok("OK");

        } catch (Exception e) {
            log.error("Errore upload avatar base64", e);
            return ResponseEntity.internalServerError().body("KO");
        }
    }

    // =====================================================================
    // API JSON
    // =====================================================================

    @GetMapping("/api/images")
    @ResponseBody
    public List<Map<String, Object>> apiImages(
            @RequestParam(value = "idfolder", defaultValue = "1") String idFolder,
            HttpServletRequest request) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return List.of();

        return imagesService.findByFolder(idFolder).stream().map(img -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", img.getId());
            m.put("nome", img.getName());
            m.put("path", img.getFullpath());
            m.put("didascalia", img.getDidascalia());
            m.put("size", img.getFormattedSize());
            return m;
        }).toList();
    }

    @GetMapping("/api/folders")
    @ResponseBody
    public List<Map<String, Object>> apiFolders(
            @RequestParam(value = "idfolder", defaultValue = "1") String idFolder,
            HttpServletRequest request) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return List.of();

        return folderService.getSubfolders(idFolder).stream().map(f -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", f.getId());
            m.put("nome", f.getNome());
            m.put("idfolder", f.getIdfolder());
            m.put("hasSubfolders", f.hasSubfolders());
            return m;
        }).toList();
    }

    @GetMapping("/api/folders/all")
    @ResponseBody
    public List<Map<String, Object>> apiFoldersAll(HttpServletRequest request) {
        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return List.of();

        return folderService.getAllFolders().stream().map(f -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", f.getId());
            m.put("nome", f.getNome());
            m.put("idfolder", f.getIdfolder());
            return m;
        }).toList();
    }

    @PostMapping("/files/save")
    public String saveAllegato(
            @RequestParam(value = "id", required = false) Integer id,
            @RequestParam(value = "nome", defaultValue = "") String nome,
            @RequestParam(value = "annotazioni", defaultValue = "") String annotazioni,
            @RequestParam(value = "l3", defaultValue = "0") String l3,
            @RequestParam(value = "idfolder", defaultValue = "1") String idFolderRaw,
            @RequestParam(value = "docid", defaultValue = "0") String docId,
            @RequestParam(value = "tipoVersione", defaultValue = "0") String tipoVersione,
            @RequestParam(value = "file", required = false) MultipartFile file,
            HttpServletRequest request) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        // Se arrivano più valori concatenati (es: "1,1"), prendi solo il primo
        String idFolder = idFolderRaw.contains(",")
                ? idFolderRaw.split(",")[0].trim()
                : idFolderRaw.trim();

        try {
            if (id == null || id <= 0) {
                // --- NUOVO ALLEGATO ---
                if (file == null || file.isEmpty()) {
                    log.warn("Nessun file fornito per nuovo allegato");
                    return "redirect:/admin/filemanager/" + idFolder;
                }
                String originalFilename = file.getOriginalFilename();
                String ext = originalFilename != null && originalFilename.contains(".")
                        ? originalFilename.substring(originalFilename.lastIndexOf(".") + 1) : "bin";
                String nomeFile = !nome.isEmpty() ? nome
                        : (originalFilename != null
                        ? originalFilename.substring(0, originalFilename.lastIndexOf(".")) : "file");

                Allegato allegato = new Allegato();
                allegato.setIdFolder(Integer.parseInt(idFolder));
                allegato.setL1(nomeFile);
                allegato.setL3(l3);
                allegato.setNome(originalFilename);
                allegato.setType(ext);
                allegato.setVersion("0");
                allegato.setAnnotazioni(annotazioni.isEmpty() ? nomeFile : annotazioni);
                allegato.setIdUtente(config.getAmministratore().getId().toString());
                allegato.setApertoDa(config.getAmministratore().getNomeCompleto());
                allegato.setDataInserimento(LocalDateTime.now().format(DF));
                allegato.setDove("file");
                allegato.setIdDocAllegati(docId);

                Allegato saved = allegatoService.salvaAllegato(allegato, file);

                if (!"0".equals(docId)) {
                    allegatoService.collegaAllegato(
                            Integer.parseInt(docId), saved.getId(),
                            config.getAmministratore().getNomeCompleto(),
                            config.getAmministratore().getId().toString());
                }

            } else {
                // --- AGGIORNAMENTO ALLEGATO ---
                Allegato allegato = allegatoService.findById(id).orElseThrow();
                if (!nome.isEmpty()) allegato.setL1(nome);
                if (!annotazioni.isEmpty()) allegato.setAnnotazioni(annotazioni);
                allegato.setL3(l3);
                allegato.setIdFolder(Integer.parseInt(idFolder));

                boolean hasFile = file != null && !file.isEmpty();

                if (!hasFile || "0".equals(tipoVersione)) {
                    allegatoService.updateAllegato(allegato, file, "semplice");
                } else if ("1".equals(tipoVersione)) {
                    String ext = file.getOriginalFilename() != null && file.getOriginalFilename().contains(".")
                            ? file.getOriginalFilename().substring(file.getOriginalFilename().lastIndexOf(".") + 1) : "bin";
                    allegato.setNome(file.getOriginalFilename());
                    allegato.setType(ext);
                    allegatoService.updateAllegato(allegato, file, "cambia");
                } else if ("2".equals(tipoVersione)) {
                    String ext = file.getOriginalFilename() != null && file.getOriginalFilename().contains(".")
                            ? file.getOriginalFilename().substring(file.getOriginalFilename().lastIndexOf(".") + 1) : "bin";
                    allegato.setNome(file.getOriginalFilename());
                    allegato.setType(ext);
                    allegatoService.updateAllegato(allegato, file, "versione");
                }
            }

            log.info("Allegato salvato: id={}, folder={}", id, idFolder);

        } catch (Exception e) {
            log.error("Errore saveAllegato", e);
        }

        return "redirect:/admin/filemanager/" + idFolder;
    }

    @PostMapping("/images/save-ajax")
    @ResponseBody
    public Map<String, Object> saveImageAjax(
            @RequestParam(value = "id", required = false) Integer id,
            @RequestParam(value = "name", defaultValue = "") String name,
            @RequestParam(value = "didascalia", defaultValue = "") String didascalia,
            @RequestParam(value = "idfolder", defaultValue = "1") String idFolder,
            @RequestParam(value = "filer", required = false) MultipartFile filer,
            HttpServletRequest request) {

        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);

        if (!config.isLogged()) {
            result.put("success", false);
            return result;
        }

        try {
            Images image;
            if (id == null || id <= 0) {
                image = imagesService.uploadImage(filer, idFolder, imagesRepositoryPath);
            } else {
                image = imagesService.findById(id).orElseThrow();
                if (filer != null && !filer.isEmpty()) {
                    imagesService.delete(id);
                    image = imagesService.uploadImage(filer, idFolder, imagesRepositoryPath);
                }
            }
            if (!name.isEmpty()) image.setName(name);
            if (!didascalia.isEmpty()) image.setDidascalia(didascalia);
            image.setL4(config.getAmministratore().getNomeCompleto());
            image.setL5(LocalDateTime.now().format(DF));
            imagesService.save(image);

            result.put("success", true);
            result.put("id", image.getId());
            result.put("url", config.getImagesRepositoryWeb() + image.getFullpath());
            result.put("nome", image.getName());
        } catch (Exception e) {
            log.error("Errore saveImageAjax", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    // =====================================================================
// COLLEGA ALLEGATO DA MEDIA LIBRARY
// =====================================================================

    @PostMapping("/files/collega")
    @ResponseBody
    public Map<String, Object> collegaAllegatoDaLibreria(
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {

        Map<String, Object> result = new HashMap<>();
        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);

        if (!config.isLogged()) {
            result.put("success", false);
            return result;
        }

        try {
            Integer idAllegato  = Integer.parseInt(body.get("idAllegato"));
            Integer idDocumento = Integer.parseInt(body.get("idDocumento"));

            allegatoService.collegaAllegato(
                    idDocumento, idAllegato,
                    config.getAmministratore().getNomeCompleto(),
                    config.getAmministratore().getId().toString());

            result.put("success", true);
        } catch (Exception e) {
            log.error("Errore collegaAllegatoDaLibreria", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }
}

