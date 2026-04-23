package com.claude.reportAi.service.estimate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import com.claude.reportAi.service.ReportHeaderHelper;
import com.claude.reportAi.service.estimate.WebSearchService;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class EstimateReportBuilder {

    // -------------------------------------------------------------------------
    // Palette colori O&G
    // -------------------------------------------------------------------------
    // ── Palette Light Theme (allineata al tema "Light" dell'app) ──────
    private static final String C_NAVY       = "111827";  // near-black
    private static final String C_ORANGE     = "6366F1";  // indigo accent
    private static final String C_WHITE      = "FFFFFF";
    private static final String C_LIGHT_BG   = "F9FAFB";  // surface chiara
    private static final String C_MED_BG     = "EEF2FF";  // indigo tenue
    private static final String C_DARK_TEXT  = "111827";  // near-black
    private static final String C_GRAY_TEXT  = "6B7280";  // grigio medio
    private static final String C_SEPARATOR  = "E5E7EB";  // bordo chiaro
    private static final String C_RED        = "DC2626";
    private static final String C_WARN       = "D97706";
    private static final String C_GREEN      = "059669";
    private static final String C_SUBTOTAL   = "4338CA";  // indigo scuro per totali
    private static final String C_ALTO_BG    = "FEF2F2";
    private static final String C_MEDIO_BG   = "FFFBEB";
    private static final String C_BASSO_BG   = "F0FDF4";

    private static final int CONTENT_WIDTH = 9360;

    private final ObjectMapper objectMapper;
    private final ReportHeaderHelper reportHeaderHelper;

    @Value("${app.report.logo-path:}")
    private String logoPath;

    @Value("${app.report.company-name:Azienda S.p.A.}")
    private String companyName;

    @Autowired
    public EstimateReportBuilder(ObjectMapper objectMapper, ReportHeaderHelper reportHeaderHelper) {
        this.objectMapper = objectMapper;
        this.reportHeaderHelper = reportHeaderHelper;
    }

    // -------------------------------------------------------------------------
    // Metodo principale
    // -------------------------------------------------------------------------

    public byte[] build(String reportJson, ProjectInfoExtractor.ProjectInfo info, String originalFilename,
                        Map<String, List<WebSearchService.SearchResult>> searchResults) throws Exception {
        JsonNode root = objectMapper.readTree(cleanJson(reportJson));

        try (XWPFDocument doc = new XWPFDocument()) {
            reportHeaderHelper.setupDocumentHeader(doc);
            addCoverPage(doc, root, info, originalFilename);
            addPageBreak(doc);
            addExecutiveSummary(doc, root, info);
            addPageBreak(doc);
            addCostSummaryTable(doc, root);
            addPageBreak(doc);
            addDetailedAnalysis(doc, root);
            addPageBreak(doc);
            addTaxesSection(doc, root);
            addPageBreak(doc);
            addRisksTable(doc, root);
            addPageBreak(doc);
            addTimeline(doc, root);
            if (searchResults != null && !searchResults.isEmpty()) {
                addPageBreak(doc);
                addSearchAppendix(doc, searchResults);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            log.info("DOCX preventivo generato: {} bytes", out.size());
            return out.toByteArray();
        }
    }

    // -------------------------------------------------------------------------
    // Cover Page
    // -------------------------------------------------------------------------

    private void addCoverPage(XWPFDocument doc, JsonNode root, ProjectInfoExtractor.ProjectInfo info, String originalFilename) {
        addSpacer(doc, 1);

        // Logo
        if (logoPath != null && !logoPath.isBlank()) {
            File logoFile = new File(logoPath);
            if (logoFile.exists() && logoFile.isFile()) {
                try {
                    String lp = logoPath.toLowerCase();
                    int picType = lp.endsWith(".png") ? XWPFDocument.PICTURE_TYPE_PNG : XWPFDocument.PICTURE_TYPE_JPEG;
                    String fname = logoFile.getName();
                    XWPFParagraph logoPara = doc.createParagraph();
                    logoPara.setAlignment(ParagraphAlignment.CENTER);
                    XWPFRun logoRun = logoPara.createRun();
                    try (FileInputStream fis = new FileInputStream(logoFile)) {
                        logoRun.addPicture(fis, picType, fname,
                                Units.toEMU(142), Units.toEMU(155));
                    }
                } catch (Exception e) {
                    log.warn("Impossibile inserire logo da '{}': {}", logoPath, e.getMessage());
                    addLogoFallback(doc);
                }
            } else {
                addLogoFallback(doc);
            }
        } else {
            addLogoFallback(doc);
        }

        addSpacer(doc, 2);

        // Titolo principale
        XWPFParagraph titlePara = doc.createParagraph();
        titlePara.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun titleRun = titlePara.createRun();
        titleRun.setText("REPORT DI ANALISI COSTI");
        titleRun.setBold(true);
        titleRun.setFontSize(24);
        titleRun.setColor(C_NAVY);
        titleRun.setFontFamily("Calibri");

        addSpacer(doc, 1);

        // Sottotitolo
        String nazione    = getTextSafe(root, "nazione", info.nazione() != null ? info.nazione() : "N/D");
        String tipoProj   = getTextSafe(root, "tipo_progetto", info.tipoProgetto() != null ? info.tipoProgetto() : "N/D");
        String sottotitolo = tipoProj + " – " + nazione;

        XWPFParagraph subPara = doc.createParagraph();
        subPara.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun subRun = subPara.createRun();
        subRun.setText(sottotitolo);
        subRun.setItalic(true);
        subRun.setFontSize(14);
        subRun.setColor(C_GRAY_TEXT);
        subRun.setFontFamily("Calibri");

        // Linea separatrice con bordo arancio
        XWPFParagraph sepPara = doc.createParagraph();
        CTPPr pPr = sepPara.getCTP().isSetPPr() ? sepPara.getCTP().getPPr() : sepPara.getCTP().addNewPPr();
        CTPBdr pBdr = pPr.isSetPBdr() ? pPr.getPBdr() : pPr.addNewPBdr();
        CTBorder bottom = pBdr.isSetBottom() ? pBdr.getBottom() : pBdr.addNewBottom();
        bottom.setVal(STBorder.SINGLE);
        bottom.setSz(BigInteger.valueOf(12));
        bottom.setColor(C_ORANGE);
        bottom.setSpace(BigInteger.valueOf(4));

        addSpacer(doc, 1);

        // Box dati progetto
        int rows = 0;
        if (nazione != null && !nazione.isBlank())  rows++;
        if (tipoProj != null && !tipoProj.isBlank()) rows++;
        if (info.diametroPollici() != null) rows++;
        if (info.lunghezzaKm() != null)     rows++;
        if (info.durataMesi() != null)      rows++;
        if (info.scopeLavori() != null)     rows++;
        if (rows == 0) rows = 1;

        XWPFTable dataTable = doc.createTable(rows, 2);
        setTableWidth(dataTable, CONTENT_WIDTH);

        int r = 0;
        r = addCoverDataRow(dataTable, r, "NAZIONE", nazione);
        r = addCoverDataRow(dataTable, r, "TIPO PROGETTO", tipoProj);
        if (info.diametroPollici() != null) {
            r = addCoverDataRow(dataTable, r, "DIAMETRO", info.diametroPollici() + "\"");
        }
        if (info.lunghezzaKm() != null) {
            r = addCoverDataRow(dataTable, r, "LUNGHEZZA", info.lunghezzaKm() + " km");
        }
        if (info.durataMesi() != null) {
            r = addCoverDataRow(dataTable, r, "DURATA", info.durataMesi() + " mesi");
        }
        if (info.scopeLavori() != null) {
            r = addCoverDataRow(dataTable, r, "SCOPE", info.scopeLavori());
        }

        addSpacer(doc, 4);

        // Data e revisione
        String dataIta = LocalDate.now().format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALIAN));
        XWPFParagraph datePara = doc.createParagraph();
        datePara.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun dateRun = datePara.createRun();
        dateRun.setText("Preparato per: Top Management");
        dateRun.setFontSize(11);
        dateRun.setColor(C_GRAY_TEXT);
        dateRun.setFontFamily("Calibri");
        dateRun.addBreak();
        dateRun.setText(dataIta + "   Rev.1");
        dateRun.setFontSize(11);
        dateRun.setColor(C_GRAY_TEXT);
        dateRun.setFontFamily("Calibri");

        addSpacer(doc, 1);

        // Footer band
        XWPFTable footTable = doc.createTable(1, 1);
        setTableWidth(footTable, CONTENT_WIDTH);
        XWPFTableCell footCell = footTable.getRow(0).getCell(0);
        setCellBackground(footCell, C_ORANGE);
        setCellText(footCell, companyName + " — EPC Oil & Gas", C_WHITE, 9, false);
        footCell.getParagraphs().getFirst().setAlignment(ParagraphAlignment.CENTER);
    }

    private int addCoverDataRow(XWPFTable table, int rowIdx, String label, String value) {
        if (rowIdx >= table.getNumberOfRows()) return rowIdx;
        XWPFTableRow row = table.getRow(rowIdx);
        int colW = CONTENT_WIDTH / 2;
        setCellWidth(row.getCell(0), colW);
        setCellBackground(row.getCell(0), C_MED_BG);
        setCellText(row.getCell(0), label, C_DARK_TEXT, 10, true);
        setCellWidth(row.getCell(1), colW);
        setCellBackground(row.getCell(1), C_WHITE);
        setCellText(row.getCell(1), value != null ? value : "", C_DARK_TEXT, 10, false);
        return rowIdx + 1;
    }

    private void addLogoFallback(XWPFDocument doc) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun r = p.createRun();
        r.setText("[ LOGO AZIENDALE ]");
        r.setColor(C_GRAY_TEXT);
        r.setFontFamily("Calibri");
        r.setFontSize(10);
    }

    // -------------------------------------------------------------------------
    // Executive Summary
    // -------------------------------------------------------------------------

    private void addExecutiveSummary(XWPFDocument doc, JsonNode root, ProjectInfoExtractor.ProjectInfo info) {
        addHeading1(doc, "1. EXECUTIVE SUMMARY");
        String summary = getTextSafe(root, "executive_summary", "");
        if (!summary.isBlank()) {
            addBodyText(doc, summary);
        }
    }

    // -------------------------------------------------------------------------
    // Cost Summary Table
    // -------------------------------------------------------------------------

    private void addCostSummaryTable(XWPFDocument doc, JsonNode root) {
        addHeading1(doc, "2. QUADRO ECONOMICO DI SINTESI");

        JsonNode quadro = root.path("quadro_economico");
        if (!quadro.isArray() || quadro.isEmpty()) {
            addBodyText(doc, "Dati economici non disponibili.");
        } else {
            int numRows = quadro.size() + 1;
            XWPFTable table = doc.createTable(numRows, 4);
            setTableWidth(table, CONTENT_WIDTH);
            setTableCellSpacing(table, 40);

            int[] colWidths = {4000, 2000, 800, 2560};
            String[] headers = {"VOCE", "IMPORTO EUR", "%", "NOTE"};

            XWPFTableRow headerRow = table.getRow(0);
            for (int i = 0; i < headers.length; i++) {
                XWPFTableCell cell = headerRow.getCell(i);
                setCellWidth(cell, colWidths[i]);
                setCellBackground(cell, C_NAVY);
                setCellText(cell, headers[i], C_WHITE, 10, true);
            }

            int rowIdx = 1;
            boolean alt = false;
            for (JsonNode item : quadro) {
                String voce       = getTextSafe(item, "voce", "");
                double importo    = getDoubleNode(item, "importo_usd", 0.0);
                double perc       = getDoubleNode(item, "percentuale", 0.0);
                String note       = getTextSafe(item, "note", "");

                String vUp = voce.toUpperCase();
                String bg;
                String fgColor;
                boolean bold;
                if (vUp.contains("SUBTOTALE")) {
                    bg = C_SUBTOTAL; fgColor = C_WHITE; bold = true;
                } else if (vUp.contains("VII -") || (vUp.contains("TOTALE") && !vUp.contains("SUBTOTALE"))) {
                    bg = C_SUBTOTAL; fgColor = C_WHITE; bold = true;
                } else if (vUp.contains("VIII -") || vUp.contains("PREZZO")) {
                    bg = C_ORANGE; fgColor = C_WHITE; bold = true;
                } else if (vUp.startsWith("VI.")) {
                    bg = C_MED_BG; fgColor = C_DARK_TEXT; bold = true;
                } else {
                    bg = alt ? C_LIGHT_BG : C_WHITE; fgColor = C_DARK_TEXT; bold = false;
                    alt = !alt;
                }

                XWPFTableRow row = table.getRow(rowIdx);
                setCellWidth(row.getCell(0), colWidths[0]);
                setCellBackground(row.getCell(0), bg);
                setCellText(row.getCell(0), voce, fgColor, 10, bold);

                setCellWidth(row.getCell(1), colWidths[1]);
                setCellBackground(row.getCell(1), bg);
                setCellText(row.getCell(1), "€ " + formatUsd(importo), fgColor, 10, bold);
                row.getCell(1).getParagraphs().getFirst().setAlignment(ParagraphAlignment.RIGHT);

                setCellWidth(row.getCell(2), colWidths[2]);
                setCellBackground(row.getCell(2), bg);
                setCellText(row.getCell(2), String.format("%.1f%%", perc), fgColor, 9, bold);
                row.getCell(2).getParagraphs().getFirst().setAlignment(ParagraphAlignment.CENTER);

                setCellWidth(row.getCell(3), colWidths[3]);
                setCellBackground(row.getCell(3), bg);
                setCellText(row.getCell(3), note, fgColor, 9, false);

                rowIdx++;
            }
            addTableBorders(table);
        }

        // Tasso di cambio applicato
        JsonNode cambioCur = root.path("cambio_eur_usd");
        if (!cambioCur.isMissingNode() && !cambioCur.isNull() && cambioCur.asDouble(0) > 0) {
            addSpacer(doc, 1);
            XWPFParagraph cambioP = doc.createParagraph();
            setSpacingBefore(cambioP, 80);
            XWPFRun cambioR = cambioP.createRun();
            cambioR.setText("ℹ Tasso di cambio applicato: 1 EUR = "
                    + String.format(Locale.US, "%.4f", cambioCur.asDouble()) + " USD");
            cambioR.setFontSize(9);
            cambioR.setItalic(true);
            cambioR.setColor(C_GRAY_TEXT);
            cambioR.setFontFamily("Calibri");
        }

        // KPI box
        addSpacer(doc, 1);
        addHeading2(doc, "KPI di Progetto");

        JsonNode kpi = root.path("kpi");
        if (!kpi.isMissingNode() && !kpi.isNull()) {
            int kpiRows = 0;
            if (!kpi.path("prezzo_totale_usd").isMissingNode()) kpiRows++;
            if (!kpi.path("prezzo_al_km").isNull() && !kpi.path("prezzo_al_km").isMissingNode()) kpiRows++;
            if (!kpi.path("prezzo_al_metro").isNull() && !kpi.path("prezzo_al_metro").isMissingNode()) kpiRows++;
            if (!kpi.path("prezzo_inch_metro").isNull() && !kpi.path("prezzo_inch_metro").isMissingNode()) kpiRows++;
            if (!kpi.path("personale_diretto").isMissingNode()) kpiRows++;
            if (!kpi.path("personale_indiretto").isMissingNode()) kpiRows++;
            if (!kpi.path("personale_totale").isMissingNode()) kpiRows++;
            if (!kpi.path("ore_uomo_stimate").isMissingNode()) kpiRows++;
            if (!kpi.path("durata_mesi").isMissingNode()) kpiRows++;
            if (kpiRows == 0) kpiRows = 1;

            XWPFTable kpiTable = doc.createTable(kpiRows, 2);
            setTableWidth(kpiTable, CONTENT_WIDTH / 2);
            setTableCellSpacing(kpiTable, 40);

            int kr = 0;
            kr = addKpiRow(kpiTable, kr, "Prezzo Totale", "€ " + formatUsd(getDoubleNode(kpi, "prezzo_totale_usd", 0)));
            if (!kpi.path("prezzo_al_km").isNull() && !kpi.path("prezzo_al_km").isMissingNode()) {
                kr = addKpiRow(kpiTable, kr, "Prezzo al km", "€ " + formatUsd(getDoubleNode(kpi, "prezzo_al_km", 0)));
            }
            if (!kpi.path("prezzo_al_metro").isNull() && !kpi.path("prezzo_al_metro").isMissingNode()) {
                kr = addKpiRow(kpiTable, kr, "Prezzo al metro", "€ " + formatUsd(getDoubleNode(kpi, "prezzo_al_metro", 0)));
            }
            if (!kpi.path("prezzo_inch_metro").isNull() && !kpi.path("prezzo_inch_metro").isMissingNode()) {
                kr = addKpiRow(kpiTable, kr, "Prezzo inch-metro", "€ " + formatUsd(getDoubleNode(kpi, "prezzo_inch_metro", 0)));
            }
            if (!kpi.path("personale_diretto").isMissingNode()) {
                kr = addKpiRow(kpiTable, kr, "Personale Diretto", String.valueOf(getIntNode(kpi, "personale_diretto", 0)) + " pers.");
            }
            if (!kpi.path("personale_indiretto").isMissingNode()) {
                kr = addKpiRow(kpiTable, kr, "Personale Indiretto", String.valueOf(getIntNode(kpi, "personale_indiretto", 0)) + " pers.");
            }
            if (!kpi.path("personale_totale").isMissingNode()) {
                kr = addKpiRow(kpiTable, kr, "Personale Totale", String.valueOf(getIntNode(kpi, "personale_totale", 0)) + " pers.");
            }
            if (!kpi.path("ore_uomo_stimate").isMissingNode()) {
                kr = addKpiRow(kpiTable, kr, "Ore Uomo Stimate", String.format(Locale.US, "%,.0f", getDoubleNode(kpi, "ore_uomo_stimate", 0)) + " h");
            }
            if (!kpi.path("durata_mesi").isMissingNode()) {
                kr = addKpiRow(kpiTable, kr, "Durata (mesi)", String.valueOf(getIntNode(kpi, "durata_mesi", 0)) + " mesi");
            }
            addTableBorders(kpiTable);
        }
    }

    private int addKpiRow(XWPFTable table, int rowIdx, String label, String value) {
        if (rowIdx >= table.getNumberOfRows()) return rowIdx;
        XWPFTableRow row = table.getRow(rowIdx);
        int half = CONTENT_WIDTH / 4;
        setCellWidth(row.getCell(0), half);
        setCellBackground(row.getCell(0), C_MED_BG);
        setCellText(row.getCell(0), label, C_DARK_TEXT, 10, true);
        setCellWidth(row.getCell(1), half);
        setCellBackground(row.getCell(1), C_WHITE);
        setCellText(row.getCell(1), value, C_DARK_TEXT, 10, false);
        return rowIdx + 1;
    }

    // -------------------------------------------------------------------------
    // Detailed Analysis
    // -------------------------------------------------------------------------

    private void addDetailedAnalysis(XWPFDocument doc, JsonNode root) {
        addHeading1(doc, "3. ANALISI DETTAGLIATA DEI COSTI");

        JsonNode dettaglio = root.path("analisi_dettaglio");
        if (!dettaglio.isArray() || dettaglio.isEmpty()) {
            addBodyText(doc, "Nessuna analisi dettagliata disponibile.");
            return;
        }

        for (JsonNode item : dettaglio) {
            String categoria = getTextSafe(item, "categoria", "");
            double importo   = getDoubleNode(item, "importo_usd", 0.0);
            String descr     = getTextSafe(item, "descrizione", "");

            addHeading2(doc, categoria + " — € " + formatUsd(importo));
            if (!descr.isBlank()) {
                addBodyText(doc, descr);
            }

            // Box produttività applicata (campo valorizzato per la voce Costruzione)
            String prodApplicata = getTextSafe(item, "produttivita_applicata", "");
            if (!prodApplicata.isBlank()) {
                XWPFTable prodBox = doc.createTable(1, 1);
                setTableWidth(prodBox, CONTENT_WIDTH);
                XWPFTableCell prodCell = prodBox.getRow(0).getCell(0);
                setCellBackground(prodCell, "EEF2FF");
                while (prodCell.getParagraphs().size() > 1) {
                    prodCell.removeParagraph(prodCell.getParagraphs().size() - 1);
                }
                XWPFParagraph prodLabelP = prodCell.getParagraphs().getFirst();
                for (int i = prodLabelP.getRuns().size() - 1; i >= 0; i--) prodLabelP.removeRun(i);
                XWPFRun prodLabelR = prodLabelP.createRun();
                prodLabelR.setText("Produttivita Applicata:");
                prodLabelR.setBold(true); prodLabelR.setFontSize(9); prodLabelR.setFontFamily("Calibri"); prodLabelR.setColor(C_ORANGE);
                XWPFParagraph prodValP = prodCell.addParagraph();
                XWPFRun prodValR = prodValP.createRun();
                prodValR.setText(prodApplicata);
                prodValR.setFontSize(9); prodValR.setFontFamily("Calibri"); prodValR.setColor(C_DARK_TEXT);
                addSpacer(doc, 1);
            }

            // Tabella voci principali (5 colonne: Descrizione | Quantità | C.U. | Fonte | Totale)
            JsonNode voci = item.path("voci_principali");
            if (voci.isArray() && !voci.isEmpty()) {
                int numRows = voci.size() + 1;
                XWPFTable vociTable = doc.createTable(numRows, 5);
                setTableWidth(vociTable, CONTENT_WIDTH);
                setTableCellSpacing(vociTable, 40);

                int[] colW = {2900, 1100, 1700, 1100, 2560};
                String[] heads = {"Descrizione", "Quantita", "C.U. (EUR)", "Fonte", "Totale EUR"};

                XWPFTableRow hr = vociTable.getRow(0);
                for (int i = 0; i < heads.length; i++) {
                    setCellWidth(hr.getCell(i), colW[i]);
                    setCellBackground(hr.getCell(i), C_NAVY);
                    setCellText(hr.getCell(i), heads[i], C_WHITE, 10, true);
                }
                hr.getCell(2).getParagraphs().getFirst().setAlignment(ParagraphAlignment.RIGHT);
                hr.getCell(3).getParagraphs().getFirst().setAlignment(ParagraphAlignment.CENTER);
                hr.getCell(4).getParagraphs().getFirst().setAlignment(ParagraphAlignment.RIGHT);

                int vi = 1;
                boolean alt = false;
                for (JsonNode voce : voci) {
                    String vBg = alt ? C_LIGHT_BG : C_WHITE;
                    XWPFTableRow vr = vociTable.getRow(vi);

                    setCellWidth(vr.getCell(0), colW[0]); setCellBackground(vr.getCell(0), vBg);
                    setCellText(vr.getCell(0), getTextSafe(voce, "descrizione", ""), C_DARK_TEXT, 9, false);

                    setCellWidth(vr.getCell(1), colW[1]); setCellBackground(vr.getCell(1), vBg);
                    setCellText(vr.getCell(1), getTextSafe(voce, "quantita", ""), C_DARK_TEXT, 9, false);

                    setCellWidth(vr.getCell(2), colW[2]); setCellBackground(vr.getCell(2), vBg);
                    setCellText(vr.getCell(2), getTextSafe(voce, "costo_unitario_usd", ""), C_DARK_TEXT, 9, false);
                    vr.getCell(2).getParagraphs().getFirst().setAlignment(ParagraphAlignment.RIGHT);

                    // Badge fonte dato: verde=AZIENDALE, arancio=BENCHMARK, grigio=ASSUNZIONE
                    String fonte = getTextSafe(voce, "fonte_dato", "").toUpperCase().trim();
                    String fonteBg = switch (fonte) {
                        case "AZIENDALE" -> "D1FAE5";
                        case "BENCHMARK" -> "FEF3C7";
                        default          -> "F3F4F6";
                    };
                    String fonteFg = switch (fonte) {
                        case "AZIENDALE" -> C_GREEN;
                        case "BENCHMARK" -> C_WARN;
                        default          -> C_GRAY_TEXT;
                    };
                    setCellWidth(vr.getCell(3), colW[3]); setCellBackground(vr.getCell(3), fonteBg);
                    setCellText(vr.getCell(3), fonte.isBlank() ? "N/D" : fonte, fonteFg, 8, true);
                    vr.getCell(3).getParagraphs().getFirst().setAlignment(ParagraphAlignment.CENTER);

                    setCellWidth(vr.getCell(4), colW[4]); setCellBackground(vr.getCell(4), vBg);
                    double tot = getDoubleNode(voce, "totale_usd", 0.0);
                    setCellText(vr.getCell(4), "€ " + formatUsd(tot), C_DARK_TEXT, 9, true);
                    vr.getCell(4).getParagraphs().getFirst().setAlignment(ParagraphAlignment.RIGHT);

                    vi++;
                    alt = !alt;
                }
                addTableBorders(vociTable);
            }

            // Box assunzioni
            JsonNode assunzioni = item.path("assunzioni");
            if (assunzioni.isArray() && !assunzioni.isEmpty()) {
                addSpacer(doc, 1);
                XWPFTable assBox = doc.createTable(1, 1);
                setTableWidth(assBox, CONTENT_WIDTH);
                XWPFTableCell assCell = assBox.getRow(0).getCell(0);
                setCellBackground(assCell, "FFF3E8");

                while (assCell.getParagraphs().size() > 1) {
                    assCell.removeParagraph(assCell.getParagraphs().size() - 1);
                }
                XWPFParagraph assLabelPara = assCell.getParagraphs().getFirst();
                for (int i = assLabelPara.getRuns().size() - 1; i >= 0; i--) {
                    assLabelPara.removeRun(i);
                }
                XWPFRun assLabelRun = assLabelPara.createRun();
                assLabelRun.setText("Assunzioni:");
                assLabelRun.setBold(true);
                assLabelRun.setFontSize(10);
                assLabelRun.setFontFamily("Calibri");
                assLabelRun.setColor(C_WARN);

                for (JsonNode ass : assunzioni) {
                    XWPFParagraph ap = assCell.addParagraph();
                    XWPFRun ar = ap.createRun();
                    ar.setText("• " + ass.asText());
                    ar.setFontSize(9);
                    ar.setFontFamily("Calibri");
                    ar.setColor(C_DARK_TEXT);
                }
            }

            // Rischi (elenco puntato con icona)
            JsonNode rischi = item.path("rischi");
            if (rischi.isArray() && !rischi.isEmpty()) {
                for (JsonNode risk : rischi) {
                    XWPFParagraph rp = doc.createParagraph();
                    rp.setIndentationLeft(360);
                    XWPFRun rr = rp.createRun();
                    rr.setText("\u26A0 " + risk.asText());
                    rr.setFontSize(10);
                    rr.setFontFamily("Calibri");
                    rr.setColor(C_WARN);
                }
            }

            addSpacer(doc, 1);
        }
    }

    // -------------------------------------------------------------------------
    // Taxes Section
    // -------------------------------------------------------------------------

    private void addTaxesSection(XWPFDocument doc, JsonNode root) {
        addHeading1(doc, "4. IMPOSTE E ONERI FISCALI");

        JsonNode taxes = root.path("imposte_e_oneri");
        if (taxes.isMissingNode() || taxes.isNull()) {
            addBodyText(doc, "Nessuna informazione fiscale disponibile.");
            return;
        }

        String[][] rows = {
                {"WHT (Ritenuta alla fonte)", getTextSafe(taxes, "wht_percentuale", "N/D")},
                {"IVA / VAT",                 getTextSafe(taxes, "vat_percentuale", "N/D")},
                {"Dazi doganali",             getTextSafe(taxes, "customs", "N/D")},
                {"Impatto stimato",           "€ " + formatUsd(getDoubleNode(taxes, "impatto_stimato_usd", 0))},
                {"Note",                      getTextSafe(taxes, "note", "")}
        };

        XWPFTable table = doc.createTable(rows.length, 2);
        setTableWidth(table, CONTENT_WIDTH);
        setTableCellSpacing(table, 40);
        int half = CONTENT_WIDTH / 2;

        for (int i = 0; i < rows.length; i++) {
            XWPFTableRow row = table.getRow(i);
            setCellWidth(row.getCell(0), half);
            setCellBackground(row.getCell(0), C_LIGHT_BG);
            setCellText(row.getCell(0), rows[i][0], C_DARK_TEXT, 10, true);
            setCellWidth(row.getCell(1), half);
            setCellBackground(row.getCell(1), C_WHITE);
            setCellText(row.getCell(1), rows[i][1], C_DARK_TEXT, 10, false);
        }
        addTableBorders(table);
    }

    // -------------------------------------------------------------------------
    // Risks Table
    // -------------------------------------------------------------------------

    private void addRisksTable(XWPFDocument doc, JsonNode root) {
        addHeading1(doc, "5. RISCHI PRINCIPALI");

        JsonNode risks = root.path("rischi_principali");
        if (!risks.isArray() || risks.isEmpty()) {
            addBodyText(doc, "Nessun rischio principale identificato.");
            return;
        }

        int[] colWidths = {1500, 3000, 900, 3960};
        String[] headers = {"Categoria", "Descrizione", "Impatto", "Mitigazione"};

        XWPFTable table = doc.createTable(risks.size() + 1, 4);
        setTableWidth(table, CONTENT_WIDTH);
        setTableCellSpacing(table, 40);

        XWPFTableRow hr = table.getRow(0);
        for (int i = 0; i < headers.length; i++) {
            setCellWidth(hr.getCell(i), colWidths[i]);
            setCellBackground(hr.getCell(i), C_NAVY);
            setCellText(hr.getCell(i), headers[i], C_WHITE, 10, true);
        }

        int rowIdx = 1;
        for (JsonNode risk : risks) {
            String impatto = getTextSafe(risk, "impatto", "MEDIO").toUpperCase();
            String impatBg = switch (impatto) {
                case "ALTO"  -> C_ALTO_BG;
                case "BASSO" -> C_BASSO_BG;
                default      -> C_MEDIO_BG;
            };
            String impatFg = switch (impatto) {
                case "ALTO"  -> C_RED;
                case "BASSO" -> C_GREEN;
                default      -> C_WARN;
            };
            boolean alt = (rowIdx % 2 == 0);
            String rowBg = alt ? C_LIGHT_BG : C_WHITE;

            XWPFTableRow row = table.getRow(rowIdx);
            setCellWidth(row.getCell(0), colWidths[0]);
            setCellBackground(row.getCell(0), rowBg);
            setCellText(row.getCell(0), getTextSafe(risk, "categoria", ""), C_DARK_TEXT, 9, false);

            setCellWidth(row.getCell(1), colWidths[1]);
            setCellBackground(row.getCell(1), rowBg);
            setCellText(row.getCell(1), getTextSafe(risk, "descrizione", ""), C_DARK_TEXT, 9, false);

            setCellWidth(row.getCell(2), colWidths[2]);
            setCellBackground(row.getCell(2), impatBg);
            setCellText(row.getCell(2), impatto, impatFg, 9, true);
            row.getCell(2).getParagraphs().getFirst().setAlignment(ParagraphAlignment.CENTER);

            setCellWidth(row.getCell(3), colWidths[3]);
            setCellBackground(row.getCell(3), rowBg);
            setCellText(row.getCell(3), getTextSafe(risk, "mitigazione", ""), C_DARK_TEXT, 9, false);

            rowIdx++;
        }
        addTableBorders(table);
    }

    // -------------------------------------------------------------------------
    // Timeline
    // -------------------------------------------------------------------------

    private void addTimeline(XWPFDocument doc, JsonNode root) {
        addHeading1(doc, "6. CRONOPROGRAMMA SINTETICO");

        JsonNode crono = root.path("cronoprogramma_sintetico");
        if (!crono.isArray() || crono.isEmpty()) {
            addBodyText(doc, "Cronoprogramma non disponibile.");
            return;
        }

        int[] colWidths = {3000, 2000, 4360};
        String[] headers = {"Fase", "Durata", "Note"};

        XWPFTable table = doc.createTable(crono.size() + 1, 3);
        setTableWidth(table, CONTENT_WIDTH);
        setTableCellSpacing(table, 40);

        XWPFTableRow hr = table.getRow(0);
        for (int i = 0; i < headers.length; i++) {
            setCellWidth(hr.getCell(i), colWidths[i]);
            setCellBackground(hr.getCell(i), C_NAVY);
            setCellText(hr.getCell(i), headers[i], C_WHITE, 10, true);
        }

        int rowIdx = 1;
        boolean alt = false;
        for (JsonNode fase : crono) {
            String bg = alt ? C_LIGHT_BG : C_WHITE;
            XWPFTableRow row = table.getRow(rowIdx);
            setCellWidth(row.getCell(0), colWidths[0]); setCellBackground(row.getCell(0), bg);
            setCellText(row.getCell(0), getTextSafe(fase, "fase", ""), C_DARK_TEXT, 9, false);
            setCellWidth(row.getCell(1), colWidths[1]); setCellBackground(row.getCell(1), bg);
            String durata = getTextSafe(fase, "durata", "");
            String settimane = getTextSafe(fase, "settimane", "");
            String durataText = durata + (!settimane.isBlank() ? " (" + settimane + " sett.)" : "");
            setCellText(row.getCell(1), durataText, C_DARK_TEXT, 9, false);
            setCellWidth(row.getCell(2), colWidths[2]); setCellBackground(row.getCell(2), bg);
            setCellText(row.getCell(2), getTextSafe(fase, "note", ""), C_DARK_TEXT, 9, false);
            rowIdx++;
            alt = !alt;
        }
        addTableBorders(table);

        // Note finali
        String noteFinali = getTextSafe(root, "note_finali", "");
        if (!noteFinali.isBlank()) {
            addSpacer(doc, 1);
            addHeading2(doc, "Note Finali");
            addBodyText(doc, noteFinali);
        }
    }

    // -------------------------------------------------------------------------
    // Appendice fonti web
    // -------------------------------------------------------------------------

    private void addSearchAppendix(XWPFDocument doc,
                                   Map<String, List<WebSearchService.SearchResult>> searchResults) {
        addHeading1(doc, "7. APPENDICE — FONTI E RICERCHE DI MERCATO");
        addBodyText(doc, "Riepilogo delle fonti di dati di mercato consultate per l'aggiornamento dei prezzi e delle stime.");
        addSpacer(doc, 1);

        for (Map.Entry<String, List<WebSearchService.SearchResult>> entry : searchResults.entrySet()) {
            List<WebSearchService.SearchResult> results = entry.getValue();
            if (results == null || results.isEmpty()) continue;

            addHeading2(doc, entry.getKey());

            for (int i = 0; i < Math.min(results.size(), 5); i++) {
                WebSearchService.SearchResult r = results.get(i);

                // Numero + titolo fonte
                XWPFParagraph titlePara = doc.createParagraph();
                setSpacingBefore(titlePara, 100);
                XWPFRun numRun = titlePara.createRun();
                numRun.setText("[" + (i + 1) + "]  ");
                numRun.setBold(true);
                numRun.setFontSize(10);
                numRun.setColor(C_ORANGE);
                numRun.setFontFamily("Calibri");
                XWPFRun titleRun = titlePara.createRun();
                titleRun.setText(r.title() != null ? r.title() : "");
                titleRun.setBold(true);
                titleRun.setFontSize(10);
                titleRun.setColor(C_DARK_TEXT);
                titleRun.setFontFamily("Calibri");

                // URL
                if (r.url() != null && !r.url().isBlank()) {
                    XWPFParagraph urlPara = doc.createParagraph();
                    urlPara.setIndentationLeft(400);
                    XWPFRun urlRun = urlPara.createRun();
                    urlRun.setText(r.url());
                    urlRun.setFontSize(8);
                    urlRun.setColor(C_ORANGE);
                    urlRun.setItalic(true);
                    urlRun.setFontFamily("Calibri");
                }

                // Snippet (primi 250 caratteri del contenuto)
                String content = r.content() != null ? r.content().strip() : "";
                if (!content.isBlank()) {
                    String snippet = content.length() > 250
                            ? content.substring(0, 250) + "…"
                            : content;
                    XWPFParagraph snippetPara = doc.createParagraph();
                    snippetPara.setIndentationLeft(400);
                    setSpacingAfter(snippetPara, 80);
                    XWPFRun snippetRun = snippetPara.createRun();
                    snippetRun.setText(snippet);
                    snippetRun.setFontSize(9);
                    snippetRun.setColor(C_GRAY_TEXT);
                    snippetRun.setFontFamily("Calibri");
                }
            }

            addSpacer(doc, 1);
        }
    }

    // -------------------------------------------------------------------------
    // Helper methods POI
    // -------------------------------------------------------------------------

    private void addHeading1(XWPFDocument doc, String text) {
        XWPFParagraph para = doc.createParagraph();
        setSpacingBefore(para, 400);
        setSpacingAfter(para, 160);

        CTPPr pPr = para.getCTP().isSetPPr() ? para.getCTP().getPPr() : para.getCTP().addNewPPr();
        CTPBdr pBdr = pPr.isSetPBdr() ? pPr.getPBdr() : pPr.addNewPBdr();
        CTBorder bottom = pBdr.isSetBottom() ? pBdr.getBottom() : pBdr.addNewBottom();
        bottom.setVal(STBorder.SINGLE);
        bottom.setSz(BigInteger.valueOf(6));
        bottom.setColor(C_ORANGE);
        bottom.setSpace(BigInteger.valueOf(4));

        XWPFRun run = para.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(16);
        run.setColor(C_NAVY);
        run.setFontFamily("Calibri");
    }

    private void addHeading2(XWPFDocument doc, String text) {
        XWPFParagraph para = doc.createParagraph();
        setSpacingBefore(para, 240);
        setSpacingAfter(para, 100);
        XWPFRun run = para.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(12);
        run.setColor(C_NAVY);
        run.setFontFamily("Calibri");
    }

    private void addBodyText(XWPFDocument doc, String text) {
        XWPFParagraph para = doc.createParagraph();
        para.setAlignment(ParagraphAlignment.BOTH);
        setSpacingAfter(para, 100);
        XWPFRun run = para.createRun();
        run.setText(text);
        run.setFontSize(11);
        run.setColor(C_DARK_TEXT);
        run.setFontFamily("Calibri");
    }

    private void addSpacer(XWPFDocument doc, int lines) {
        for (int i = 0; i < lines; i++) {
            doc.createParagraph();
        }
    }

    private void addPageBreak(XWPFDocument doc) {
        XWPFParagraph para = doc.createParagraph();
        para.createRun().addBreak(BreakType.PAGE);
    }

    private void setTableWidth(XWPFTable table, int widthTwips) {
        CTTblPr tblPr = table.getCTTbl().getTblPr();
        if (tblPr == null) tblPr = table.getCTTbl().addNewTblPr();
        CTTblWidth w = tblPr.isSetTblW() ? tblPr.getTblW() : tblPr.addNewTblW();
        w.setType(STTblWidth.DXA);
        w.setW(BigInteger.valueOf(widthTwips));
    }

    private void setTableCellSpacing(XWPFTable table, int spacingTwips) {
        CTTblPr tblPr = table.getCTTbl().getTblPr();
        if (tblPr == null) tblPr = table.getCTTbl().addNewTblPr();
        CTTblWidth spacing = tblPr.isSetTblCellSpacing() ? tblPr.getTblCellSpacing() : tblPr.addNewTblCellSpacing();
        spacing.setType(STTblWidth.DXA);
        spacing.setW(BigInteger.valueOf(spacingTwips));
    }

    private void setCellWidth(XWPFTableCell cell, int widthTwips) {
        CTTcPr tcPr = cell.getCTTc().isSetTcPr()
                ? cell.getCTTc().getTcPr()
                : cell.getCTTc().addNewTcPr();
        CTTblWidth w = tcPr.isSetTcW() ? tcPr.getTcW() : tcPr.addNewTcW();
        w.setType(STTblWidth.DXA);
        w.setW(BigInteger.valueOf(widthTwips));
    }

    private void setCellBackground(XWPFTableCell cell, String hexColor) {
        CTTcPr tcPr = cell.getCTTc().isSetTcPr()
                ? cell.getCTTc().getTcPr()
                : cell.getCTTc().addNewTcPr();
        CTShd shd = tcPr.isSetShd() ? tcPr.getShd() : tcPr.addNewShd();
        shd.setFill(hexColor);
        shd.setColor("auto");
        shd.setVal(STShd.CLEAR);
    }

    private void setCellText(XWPFTableCell cell, String text, String color, int fontSize, boolean bold) {
        while (cell.getParagraphs().size() > 1) {
            cell.removeParagraph(cell.getParagraphs().size() - 1);
        }
        XWPFParagraph para = cell.getParagraphs().getFirst();
        for (int i = para.getRuns().size() - 1; i >= 0; i--) {
            para.removeRun(i);
        }

        CTTcPr tcPr = cell.getCTTc().isSetTcPr()
                ? cell.getCTTc().getTcPr()
                : cell.getCTTc().addNewTcPr();
        CTTcMar mar = tcPr.isSetTcMar() ? tcPr.getTcMar() : tcPr.addNewTcMar();
        setMarginValue(mar.isSetTop()    ? mar.getTop()    : mar.addNewTop(),    60);
        setMarginValue(mar.isSetBottom() ? mar.getBottom() : mar.addNewBottom(), 60);
        setMarginValue(mar.isSetLeft()   ? mar.getLeft()   : mar.addNewLeft(),   120);
        setMarginValue(mar.isSetRight()  ? mar.getRight()  : mar.addNewRight(),  120);

        XWPFRun run = para.createRun();
        run.setText(text != null ? text : "");
        run.setColor(color);
        run.setFontSize(fontSize);
        run.setBold(bold);
        run.setFontFamily("Calibri");
    }

    private void setMarginValue(CTTblWidth w, int value) {
        w.setType(STTblWidth.DXA);
        w.setW(BigInteger.valueOf(value));
    }

    private void addTableBorders(XWPFTable table) {
        CTTblPr tblPr = table.getCTTbl().getTblPr();
        if (tblPr == null) tblPr = table.getCTTbl().addNewTblPr();
        CTTblBorders borders = tblPr.isSetTblBorders()
                ? tblPr.getTblBorders()
                : tblPr.addNewTblBorders();

        CTBorder[] allBorders = {
                borders.isSetTop()     ? borders.getTop()     : borders.addNewTop(),
                borders.isSetBottom()  ? borders.getBottom()  : borders.addNewBottom(),
                borders.isSetLeft()    ? borders.getLeft()    : borders.addNewLeft(),
                borders.isSetRight()   ? borders.getRight()   : borders.addNewRight(),
                borders.isSetInsideH() ? borders.getInsideH() : borders.addNewInsideH(),
                borders.isSetInsideV() ? borders.getInsideV() : borders.addNewInsideV()
        };
        for (CTBorder b : allBorders) {
            b.setVal(STBorder.SINGLE);
            b.setSz(BigInteger.valueOf(2));
            b.setColor(C_SEPARATOR);
        }
    }

    private void setSpacingBefore(XWPFParagraph para, int twips) {
        CTPPr pPr = para.getCTP().isSetPPr() ? para.getCTP().getPPr() : para.getCTP().addNewPPr();
        CTSpacing sp = pPr.isSetSpacing() ? pPr.getSpacing() : pPr.addNewSpacing();
        sp.setBefore(BigInteger.valueOf(twips));
    }

    private void setSpacingAfter(XWPFParagraph para, int twips) {
        CTPPr pPr = para.getCTP().isSetPPr() ? para.getCTP().getPPr() : para.getCTP().addNewPPr();
        CTSpacing sp = pPr.isSetSpacing() ? pPr.getSpacing() : pPr.addNewSpacing();
        sp.setAfter(BigInteger.valueOf(twips));
    }

    // -------------------------------------------------------------------------
    // Data accessors
    // -------------------------------------------------------------------------

    private String getTextSafe(JsonNode node, String field, String def) {
        JsonNode n = node.path(field);
        return (n.isMissingNode() || n.isNull()) ? def : n.asText(def);
    }

    private double getDoubleNode(JsonNode node, String field, double def) {
        JsonNode n = node.path(field);
        if (n.isMissingNode() || n.isNull()) return def;
        try { return n.asDouble(def); } catch (Exception e) { return def; }
    }

    private int getIntNode(JsonNode node, String field, int def) {
        JsonNode n = node.path(field);
        if (n.isMissingNode() || n.isNull()) return def;
        try { return n.asInt(def); } catch (Exception e) { return def; }
    }

    private String formatUsd(double value) {
        return String.format(Locale.US, "%,.0f", value);
    }

    // -------------------------------------------------------------------------
    // JSON helpers
    // -------------------------------------------------------------------------

    private String cleanJson(String raw) {
        if (raw == null) return "{}";
        String s = raw.strip();
        if (s.startsWith("```")) {
            s = s.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("```$", "").strip();
        }
        int start = s.indexOf('{');
        if (start < 0) return "{}";
        s = s.substring(start);
        int end = s.lastIndexOf('}');
        if (end > 0 && end == s.length() - 1) {
            return s;
        }
        return repairTruncatedJson(s);
    }

    private String repairTruncatedJson(String json) {
        StringBuilder sb = new StringBuilder(json.stripTrailing());
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == ',') {
            sb.deleteCharAt(sb.length() - 1);
        }
        java.util.Deque<Character> stack = new java.util.ArrayDeque<>();
        boolean inString = false, escaped = false;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (!inString) {
                if (c == '{') stack.push('}');
                else if (c == '[') stack.push(']');
                else if ((c == '}' || c == ']') && !stack.isEmpty()) stack.pop();
            }
        }
        if (inString) sb.append('"');
        int added = stack.size();
        while (!stack.isEmpty()) sb.append(stack.pop());
        log.warn("JSON preventivo era troncato: riparato aggiungendo {} caratteri di chiusura", added);
        return sb.toString();
    }
}
