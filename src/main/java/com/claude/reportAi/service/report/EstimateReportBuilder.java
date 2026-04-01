package com.claude.reportAi.service.report;

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

@Component
@Slf4j
public class EstimateReportBuilder {

    // -------------------------------------------------------------------------
    // Palette colori O&G
    // -------------------------------------------------------------------------
    private static final String C_NAVY       = "0D2137";
    private static final String C_ORANGE     = "E07B2A";
    private static final String C_WHITE      = "FFFFFF";
    private static final String C_LIGHT_BG   = "F4F7FA";
    private static final String C_MED_BG     = "EBF2FA";
    private static final String C_DARK_TEXT  = "1A1A1A";
    private static final String C_GRAY_TEXT  = "4A4A4A";
    private static final String C_SEPARATOR  = "C9D5E0";
    private static final String C_RED        = "C00000";
    private static final String C_WARN       = "C55A11";
    private static final String C_GREEN      = "1A7A4A";
    private static final String C_SUBTOTAL   = "1B3A5C";
    private static final String C_ALTO_BG    = "FFE7E7";
    private static final String C_MEDIO_BG   = "FFF2CC";
    private static final String C_BASSO_BG   = "E2EFDA";

    private static final int CONTENT_WIDTH = 9360;

    private final ObjectMapper objectMapper;

    @Value("${app.report.logo-path:}")
    private String logoPath;

    @Value("${app.report.company-name:Azienda S.p.A.}")
    private String companyName;

    @Autowired
    public EstimateReportBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Metodo principale
    // -------------------------------------------------------------------------

    public byte[] build(String reportJson, ProjectInfoExtractor.ProjectInfo info, String originalFilename) throws Exception {
        JsonNode root = objectMapper.readTree(cleanJson(reportJson));

        try (XWPFDocument doc = new XWPFDocument()) {
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
        // Banda arancio confidenziale
        XWPFTable confTable = doc.createTable(1, 1);
        setTableWidth(confTable, CONTENT_WIDTH);
        XWPFTableCell confCell = confTable.getRow(0).getCell(0);
        setCellBackground(confCell, C_ORANGE);
        setCellText(confCell, "CONFIDENZIALE — USO INTERNO", C_WHITE, 11, true);
        confCell.getParagraphs().get(0).setAlignment(ParagraphAlignment.CENTER);

        addSpacer(doc, 3);

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
                                Units.toEMU(151.18), Units.toEMU(60.47));
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
        setCellBackground(footCell, C_NAVY);
        setCellText(footCell, companyName + " — EPC Oil & Gas", C_WHITE, 9, false);
        footCell.getParagraphs().get(0).setAlignment(ParagraphAlignment.CENTER);
    }

    private int addCoverDataRow(XWPFTable table, int rowIdx, String label, String value) {
        if (rowIdx >= table.getNumberOfRows()) return rowIdx;
        XWPFTableRow row = table.getRow(rowIdx);
        int colW = CONTENT_WIDTH / 2;
        setCellWidth(row.getCell(0), colW);
        setCellBackground(row.getCell(0), C_NAVY);
        setCellText(row.getCell(0), label, C_WHITE, 10, true);
        setCellWidth(row.getCell(1), colW);
        setCellBackground(row.getCell(1), C_SUBTOTAL);
        setCellText(row.getCell(1), value != null ? value : "", C_WHITE, 10, false);
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

            int[] colWidths = {4000, 2000, 800, 2560};
            String[] headers = {"VOCE", "IMPORTO USD", "%", "NOTE"};

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
                } else if (vUp.contains("TOTALE") && !vUp.contains("SUBTOTALE")) {
                    bg = C_SUBTOTAL; fgColor = C_WHITE; bold = true;
                } else if (vUp.contains("PREZZO")) {
                    bg = C_ORANGE; fgColor = C_WHITE; bold = true;
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
                setCellText(row.getCell(1), "$ " + formatUsd(importo), fgColor, 10, bold);
                row.getCell(1).getParagraphs().get(0).setAlignment(ParagraphAlignment.RIGHT);

                setCellWidth(row.getCell(2), colWidths[2]);
                setCellBackground(row.getCell(2), bg);
                setCellText(row.getCell(2), String.format("%.1f%%", perc), fgColor, 9, bold);
                row.getCell(2).getParagraphs().get(0).setAlignment(ParagraphAlignment.CENTER);

                setCellWidth(row.getCell(3), colWidths[3]);
                setCellBackground(row.getCell(3), bg);
                setCellText(row.getCell(3), note, fgColor, 9, false);

                rowIdx++;
            }
            addTableBorders(table);
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
            if (!kpi.path("personale_totale").isMissingNode()) kpiRows++;
            if (!kpi.path("durata_mesi").isMissingNode()) kpiRows++;
            if (kpiRows == 0) kpiRows = 1;

            XWPFTable kpiTable = doc.createTable(kpiRows, 2);
            setTableWidth(kpiTable, CONTENT_WIDTH / 2);

            int kr = 0;
            kr = addKpiRow(kpiTable, kr, "Prezzo Totale", "$ " + formatUsd(getDoubleNode(kpi, "prezzo_totale_usd", 0)));
            if (!kpi.path("prezzo_al_km").isNull() && !kpi.path("prezzo_al_km").isMissingNode()) {
                kr = addKpiRow(kpiTable, kr, "Prezzo al km", "$ " + formatUsd(getDoubleNode(kpi, "prezzo_al_km", 0)));
            }
            if (!kpi.path("prezzo_al_metro").isNull() && !kpi.path("prezzo_al_metro").isMissingNode()) {
                kr = addKpiRow(kpiTable, kr, "Prezzo al metro", "$ " + formatUsd(getDoubleNode(kpi, "prezzo_al_metro", 0)));
            }
            if (!kpi.path("prezzo_inch_metro").isNull() && !kpi.path("prezzo_inch_metro").isMissingNode()) {
                kr = addKpiRow(kpiTable, kr, "Prezzo inch-metro", "$ " + formatUsd(getDoubleNode(kpi, "prezzo_inch_metro", 0)));
            }
            if (!kpi.path("personale_totale").isMissingNode()) {
                kr = addKpiRow(kpiTable, kr, "Personale Totale", String.valueOf(getIntNode(kpi, "personale_totale", 0)));
            }
            if (!kpi.path("durata_mesi").isMissingNode()) {
                kr = addKpiRow(kpiTable, kr, "Durata (mesi)", String.valueOf(getIntNode(kpi, "durata_mesi", 0)));
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

            addHeading2(doc, categoria + " — $ " + formatUsd(importo));
            if (!descr.isBlank()) {
                addBodyText(doc, descr);
            }

            // Tabella voci principali
            JsonNode voci = item.path("voci_principali");
            if (voci.isArray() && !voci.isEmpty()) {
                int numRows = voci.size() + 1;
                XWPFTable vociTable = doc.createTable(numRows, 4);
                setTableWidth(vociTable, CONTENT_WIDTH);

                int[] colW = {3500, 1300, 2000, 2560};
                String[] heads = {"Descrizione", "Quantità", "C.U. (USD)", "Totale USD"};

                XWPFTableRow hr = vociTable.getRow(0);
                for (int i = 0; i < heads.length; i++) {
                    setCellWidth(hr.getCell(i), colW[i]);
                    setCellBackground(hr.getCell(i), C_NAVY);
                    setCellText(hr.getCell(i), heads[i], C_WHITE, 10, true);
                }

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
                    setCellWidth(vr.getCell(3), colW[3]); setCellBackground(vr.getCell(3), vBg);
                    double tot = getDoubleNode(voce, "totale_usd", 0.0);
                    setCellText(vr.getCell(3), "$ " + formatUsd(tot), C_DARK_TEXT, 9, true);
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
                XWPFParagraph assLabelPara = assCell.getParagraphs().get(0);
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
                {"Impatto stimato",           "$ " + formatUsd(getDoubleNode(taxes, "impatto_stimato_usd", 0))},
                {"Note",                      getTextSafe(taxes, "note", "")}
        };

        XWPFTable table = doc.createTable(rows.length, 2);
        setTableWidth(table, CONTENT_WIDTH);
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
            row.getCell(2).getParagraphs().get(0).setAlignment(ParagraphAlignment.CENTER);

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
        XWPFParagraph para = cell.getParagraphs().get(0);
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
            b.setSz(BigInteger.valueOf(4));
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
        int braces = 0, brackets = 0;
        boolean inString = false, escaped = false;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (!inString) {
                switch (c) {
                    case '{' -> braces++;
                    case '}' -> braces--;
                    case '[' -> brackets++;
                    case ']' -> brackets--;
                }
            }
        }
        if (inString) sb.append('"');
        for (int i = 0; i < Math.max(0, brackets); i++) sb.append(']');
        for (int i = 0; i < Math.max(0, braces);   i++) sb.append('}');
        log.warn("JSON preventivo era troncato: riparato aggiungendo {} ']' e {} '}'", brackets, braces);
        return sb.toString();
    }
}
