package com.claude.reportAi.service;

import com.claude.reportAi.constant.DocumentType;
import com.claude.reportAi.constant.Language;
import com.claude.reportAi.dto.ExtractedMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class MetadataExtractionService {

    private static final int TEXT_SCAN_LIMIT = 10_000;

    private static final Map<String, String> COUNTRY_KEYWORDS = Map.ofEntries(
            Map.entry("italy", "Italy"),
            Map.entry("italia", "Italy"),
            Map.entry("uae", "UAE"),
            Map.entry("united arab emirates", "UAE"),
            Map.entry("saudi arabia", "Saudi Arabia"),
            Map.entry("qatar", "Qatar"),
            Map.entry("oman", "Oman"),
            Map.entry("iraq", "Iraq"),
            Map.entry("kuwait", "Kuwait"),
            Map.entry("algeria", "Algeria"),
            Map.entry("egypt", "Egypt")
    );

    private static final List<String> OIL_GAS_TERMS = List.of(
            "oil", "gas", "epc", "pipeline", "upstream", "downstream",
            "offshore", "onshore", "refinery", "drilling", "petrochemical"
    );

    private static final List<String> CONTRACT_TYPES = List.of(
            "epc", "framework agreement", "service agreement", "subcontract",
            "nda", "purchase order", "master service agreement"
    );

    public ExtractedMetadata extract(String filename, String text, String contentType) {
        String safeFilename = filename == null ? "" : filename;
        String sample = normalizeText(limit(text, TEXT_SCAN_LIMIT));
        String filenameNormalized = normalizeText(safeFilename);

        DocumentType documentType = detectDocumentType(filenameNormalized, sample);
        Language language = detectLanguage(sample);
        String country = detectCountry(sample, filenameNormalized);
        String sector = detectSector(sample);
        String contractType = detectContractType(sample, filenameNormalized);
        String version = detectVersion(sample, filenameNormalized);
        LocalDate documentDate = detectDate(sample);
        String clientName = detectClient(sample);
        String projectName = detectProject(sample);
        List<String> tags = buildTags(documentType, language, country, sector, contractType, clientName, projectName);

        log.info("Metadati estratti -> type={}, language={}, country={}, client={}, project={}, sector={}, contractType={}",
                documentType, language, country, clientName, projectName, sector, contractType);

        return ExtractedMetadata.builder()
                .documentType(documentType)
                .language(language)
                .country(country)
                .clientName(clientName)
                .projectName(projectName)
                .sector(sector)
                .contractType(contractType)
                .documentDate(documentDate)
                .documentVersion(version)
                .tags(tags)
                .build();
    }

    private DocumentType detectDocumentType(String filename, String text) {
        String corpus = filename + " " + text;

        if (containsAny(corpus, "nda", "non disclosure agreement", "confidentiality agreement")) {
            return DocumentType.NDA;
        }
        if (containsAny(corpus, "tender", "rfq", "rfp", "invitation to bid", "bid package")) {
            return DocumentType.TENDER;
        }
        if (containsAny(corpus, "offer", "quotation", "commercial offer", "proposal")) {
            return DocumentType.OFFER;
        }
        if (containsAny(corpus, "contract", "agreement", "terms and conditions", "general conditions")) {
            return DocumentType.CONTRACT;
        }
        if (containsAny(corpus, "technical specification", "datasheet", "scope of work", "technical proposal")) {
            return DocumentType.TECHNICAL_SPEC;
        }
        if (containsAny(corpus, "minutes", "meeting note", "project note", "memo")) {
            return DocumentType.PROJECT_NOTE;
        }
        if (containsAny(corpus, "email", "from:", "to:", "subject:")) {
            return DocumentType.EMAIL;
        }

        return DocumentType.UNKNOWN;
    }

    private Language detectLanguage(String text) {
        if (text == null || text.isBlank()) {
            return Language.UNKNOWN;
        }

        int italianScore = scoreOccurrences(text,
                " il ", " lo ", " la ", " gli ", " che ", " per ", " con ", " contratto ", " offerta ");
        int englishScore = scoreOccurrences(text,
                " the ", " and ", " with ", " contract ", " offer ", " project ", " client ", " payment ");

        if (italianScore > englishScore) {
            return Language.ITALIANO;
        }
        if (englishScore > italianScore) {
            return Language.INGLESE;
        }
        return Language.UNKNOWN;
    }

    private String detectCountry(String text, String filename) {
        String corpus = text + " " + filename;
        for (Map.Entry<String, String> entry : COUNTRY_KEYWORDS.entrySet()) {
            if (corpus.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String detectSector(String text) {
        for (String term : OIL_GAS_TERMS) {
            if (text.contains(term)) {
                return "OIL_AND_GAS";
            }
        }
        return null;
    }

    private String detectContractType(String text, String filename) {
        String corpus = text + " " + filename;
        for (String type : CONTRACT_TYPES) {
            if (corpus.contains(type)) {
                return type.toUpperCase();
            }
        }
        return null;
    }

    private String detectVersion(String text, String filename) {
        Pattern pattern = Pattern.compile("\\bv(?:er(?:sion)?)?\\s*([0-9]+(?:\\.[0-9]+)?)\\b", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(text + " " + filename);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private LocalDate detectDate(String text) {
        List<Pattern> patterns = List.of(
                Pattern.compile("\\b(20\\d{2})-(\\d{2})-(\\d{2})\\b"),
                Pattern.compile("\\b(\\d{2})/(\\d{2})/(20\\d{2})\\b"),
                Pattern.compile("\\b(\\d{2})-(\\d{2})-(20\\d{2})\\b")
        );

        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                try {
                    if (pattern.pattern().startsWith("\\b(20")) {
                        return LocalDate.of(
                                Integer.parseInt(matcher.group(1)),
                                Integer.parseInt(matcher.group(2)),
                                Integer.parseInt(matcher.group(3))
                        );
                    } else {
                        return LocalDate.of(
                                Integer.parseInt(matcher.group(3)),
                                Integer.parseInt(matcher.group(2)),
                                Integer.parseInt(matcher.group(1))
                        );
                    }
                } catch (Exception ignored) {
                    // ignora date malformate
                }
            }
        }

        return null;
    }

    private String detectClient(String text) {
        List<Pattern> patterns = List.of(
                Pattern.compile("client\\s*[:\\-]\\s*([A-Za-z0-9 .,&()\\-]{3,80})", Pattern.CASE_INSENSITIVE),
                Pattern.compile("customer\\s*[:\\-]\\s*([A-Za-z0-9 .,&()\\-]{3,80})", Pattern.CASE_INSENSITIVE),
                Pattern.compile("cliente\\s*[:\\-]\\s*([A-Za-z0-9 .,&()\\-]{3,80})", Pattern.CASE_INSENSITIVE)
        );

        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                return cleanExtractedValue(matcher.group(1));
            }
        }

        return null;
    }

    private String detectProject(String text) {
        List<Pattern> patterns = List.of(
                Pattern.compile("project\\s*[:\\-]\\s*([A-Za-z0-9 .,&()\\-]{3,100})", Pattern.CASE_INSENSITIVE),
                Pattern.compile("progetto\\s*[:\\-]\\s*([A-Za-z0-9 .,&()\\-]{3,100})", Pattern.CASE_INSENSITIVE)
        );

        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                return cleanExtractedValue(matcher.group(1));
            }
        }

        return null;
    }

    private List<String> buildTags(DocumentType documentType,
                                   Language language,
                                   String country,
                                   String sector,
                                   String contractType,
                                   String clientName,
                                   String projectName) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();

        if (documentType != null) {
            tags.add(documentType.name());
        }
        if (language != null) {
            tags.add(language.name());
        }
        if (country != null && !country.isBlank()) {
            tags.add(country);
        }
        if (sector != null && !sector.isBlank()) {
            tags.add(sector);
        }
        if (contractType != null && !contractType.isBlank()) {
            tags.add(contractType);
        }
        if (clientName != null && !clientName.isBlank()) {
            tags.add("CLIENT:" + clientName);
        }
        if (projectName != null && !projectName.isBlank()) {
            tags.add("PROJECT:" + projectName);
        }

        return new ArrayList<>(tags);
    }

    private String cleanExtractedValue(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim().replaceAll("\\s+", " ");
        if (cleaned.length() > 100) {
            cleaned = cleaned.substring(0, 100).trim();
        }
        return cleaned;
    }

    private boolean containsAny(String text, String... tokens) {
        for (String token : tokens) {
            if (text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private int scoreOccurrences(String text, String... tokens) {
        int score = 0;
        for (String token : tokens) {
            if (text.contains(token)) {
                score++;
            }
        }
        return score;
    }

    private String normalizeText(String input) {
        return input == null ? "" : (" " + input.toLowerCase(Locale.ROOT) + " ");
    }

    private String limit(String input, int maxLen) {
        if (input == null) {
            return "";
        }
        return input.length() <= maxLen ? input : input.substring(0, maxLen);
    }
}
