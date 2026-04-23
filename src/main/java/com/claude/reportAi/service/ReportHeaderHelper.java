package com.claude.reportAi.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.util.Units;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.math.BigInteger;

/**
 * Shared component that adds a professional letterhead header to all generated DOCX reports.
 * The header appears on every page except the cover (suppressed via titlePg).
 *
 * Layout: logo left + right-aligned company name and confidentiality notice,
 * separated from content by an accent-colored bottom border.
 *
 * Logo resolution order:
 *  1. Filesystem path configured via app.report.logo-path (absolute or relative)
 *  2. Classpath resource /logo.png (place file in src/main/resources/logo.png)
 *  3. Omitted silently — company name still rendered
 */
@Component
@Slf4j
public class ReportHeaderHelper {

    private static final String ACCENT_COLOR = "6366F1";  // indigo accent
    private static final String NAVY_COLOR   = "111827";  // near-black text
    private static final String GRAY_COLOR   = "6B7280";  // muted gray

    // Logo dimensions in the header (pixels at 96 DPI → EMU via Units.toEMU)
    private static final int LOGO_W_PX = 80;  // ≈ 2.1 cm wide
    private static final int LOGO_H_PX = 28;  // ≈ 0.74 cm tall

    @Value("${app.report.logo-path:}")
    private String logoPath;

    @Value("${app.report.company-name:Azienda S.p.A.}")
    private String companyName;

    /**
     * Configures the document with a letterhead header visible from page 2 onwards.
     * The first (cover) page gets an empty header so the cover stays clean.
     *
     * Must be called before any content is added to the document.
     */
    public void setupDocumentHeader(XWPFDocument doc) {
        try {
            // Activate distinct first-page header so the cover page shows nothing
            CTBody body = doc.getDocument().getBody();
            CTSectPr sectPr = body.isSetSectPr() ? body.getSectPr() : body.addNewSectPr();
            if (!sectPr.isSetTitlePg()) sectPr.addNewTitlePg();

            // Empty header on the cover page
            doc.createHeader(HeaderFooterType.FIRST);

            // Default header applied to every page after the cover
            XWPFHeader header = doc.createHeader(HeaderFooterType.DEFAULT);
            XWPFParagraph para = header.getParagraphs().isEmpty()
                    ? header.createParagraph()
                    : header.getParagraphs().get(0);
            para.setAlignment(ParagraphAlignment.LEFT);

            // ── Paragraph formatting ─────────────────────────────────
            CTPPr pPr = para.getCTP().isSetPPr() ? para.getCTP().getPPr() : para.getCTP().addNewPPr();

            // Bottom separator line in accent color
            CTPBdr bdr = pPr.isSetPBdr() ? pPr.getPBdr() : pPr.addNewPBdr();
            CTBorder btm = bdr.isSetBottom() ? bdr.getBottom() : bdr.addNewBottom();
            btm.setVal(STBorder.SINGLE);
            btm.setSz(BigInteger.valueOf(4));
            btm.setColor(ACCENT_COLOR);
            btm.setSpace(BigInteger.valueOf(4));

            // Right-aligned tab stop at the content width so the company name hugs the margin
            CTTabs tabs = pPr.isSetTabs() ? pPr.getTabs() : pPr.addNewTabs();
            CTTabStop tabStop = tabs.addNewTab();
            tabStop.setVal(STTabJc.RIGHT);
            tabStop.setPos(BigInteger.valueOf(9360));

            // ── Logo ─────────────────────────────────────────────────
            boolean logoInserted = insertLogoFromFilesystem(para)
                    || insertLogoFromClasspath(para);

            if (!logoInserted) {
                log.debug("Header: nessun logo disponibile, verrà usato solo il nome azienda");
            }

            // Tab pushes the company name to the right margin
            XWPFRun tabRun = para.createRun();
            tabRun.addTab();

            // Company name (right side)
            XWPFRun nameRun = para.createRun();
            nameRun.setText(companyName != null && !companyName.isBlank() ? companyName : "ReportAI");
            nameRun.setBold(true);
            nameRun.setFontSize(9);
            nameRun.setColor(NAVY_COLOR);
            nameRun.setFontFamily("Calibri");

            // Confidentiality notice
            XWPFRun confRun = para.createRun();
            confRun.setText("  |  DOCUMENTO RISERVATO");
            confRun.setFontSize(8);
            confRun.setColor(GRAY_COLOR);
            confRun.setFontFamily("Calibri");

        } catch (Exception e) {
            log.warn("Impossibile impostare l'intestazione del documento: {}", e.getMessage());
        }
    }

    // ── private helpers ──────────────────────────────────────────────

    private boolean insertLogoFromFilesystem(XWPFParagraph para) {
        if (logoPath == null || logoPath.isBlank()) return false;
        File logoFile = new File(logoPath);
        if (!logoFile.exists() || !logoFile.isFile()) return false;
        try {
            int picType = logoPath.toLowerCase().endsWith(".png")
                    ? XWPFDocument.PICTURE_TYPE_PNG
                    : XWPFDocument.PICTURE_TYPE_JPEG;
            XWPFRun logoRun = para.createRun();
            try (FileInputStream fis = new FileInputStream(logoFile)) {
                logoRun.addPicture(fis, picType, logoFile.getName(),
                        Units.toEMU(LOGO_W_PX), Units.toEMU(LOGO_H_PX));
            }
            log.debug("Header: logo caricato da filesystem '{}'", logoPath);
            return true;
        } catch (Exception e) {
            log.warn("Header: logo da filesystem non caricabile ({}): {}", logoPath, e.getMessage());
            return false;
        }
    }

    private boolean insertLogoFromClasspath(XWPFParagraph para) {
        try (InputStream is = getClass().getResourceAsStream("/logo.png")) {
            if (is == null) return false;
            XWPFRun logoRun = para.createRun();
            logoRun.addPicture(is, XWPFDocument.PICTURE_TYPE_PNG, "logo.png",
                    Units.toEMU(LOGO_W_PX), Units.toEMU(LOGO_H_PX));
            log.debug("Header: logo caricato da classpath /logo.png");
            return true;
        } catch (Exception e) {
            log.warn("Header: logo da classpath non caricabile: {}", e.getMessage());
            return false;
        }
    }
}
