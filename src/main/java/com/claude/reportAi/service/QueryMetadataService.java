package com.claude.reportAi.service;

import com.claude.reportAi.constant.DocumentType;
import com.claude.reportAi.dto.QueryMetadataHints;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@Service
public class QueryMetadataService {

    public QueryMetadataHints extractHints(String query) {
        String q = normalize(query);

        Set<DocumentType> preferredDocumentTypes = new LinkedHashSet<>();
        Set<String> countries = new LinkedHashSet<>();
        Set<String> clients = new LinkedHashSet<>();
        Set<String> sectors = new LinkedHashSet<>();
        Set<String> contractTypes = new LinkedHashSet<>();
        Set<String> keywords = extractKeywords(q);

        if (containsAny(q, "contratto", "contract", "agreement", "clausola", "clause")) {
            preferredDocumentTypes.add(DocumentType.CONTRACT);
        }
        if (containsAny(q, "offerta", "offer", "proposal", "quotation")) {
            preferredDocumentTypes.add(DocumentType.OFFER);
        }
        if (containsAny(q, "tender", "gara", "rfq", "rfp", "bid")) {
            preferredDocumentTypes.add(DocumentType.TENDER);
        }
        if (containsAny(q, "nda", "non disclosure")) {
            preferredDocumentTypes.add(DocumentType.NDA);
        }
        if (containsAny(q, "specifica tecnica", "technical specification", "scope of work")) {
            preferredDocumentTypes.add(DocumentType.TECHNICAL_SPEC);
        }

        if (containsAny(q, "oil", "gas", "epc", "pipeline", "refinery", "drilling", "offshore", "onshore")) {
            sectors.add("OIL_AND_GAS");
        }

        addCountryIfPresent(q, countries, "italy", "Italy");
        addCountryIfPresent(q, countries, "italia", "Italy");
        addCountryIfPresent(q, countries, "uae", "UAE");
        addCountryIfPresent(q, countries, "united arab emirates", "UAE");
        addCountryIfPresent(q, countries, "saudi arabia", "Saudi Arabia");
        addCountryIfPresent(q, countries, "qatar", "Qatar");
        addCountryIfPresent(q, countries, "oman", "Oman");
        addCountryIfPresent(q, countries, "iraq", "Iraq");
        addCountryIfPresent(q, countries, "kuwait", "Kuwait");
        addCountryIfPresent(q, countries, "algeria", "Algeria");
        addCountryIfPresent(q, countries, "egypt", "Egypt");

        addContractTypeIfPresent(q, contractTypes, "epc", "EPC");
        addContractTypeIfPresent(q, contractTypes, "framework agreement", "FRAMEWORK AGREEMENT");
        addContractTypeIfPresent(q, contractTypes, "service agreement", "SERVICE AGREEMENT");
        addContractTypeIfPresent(q, contractTypes, "subcontract", "SUBCONTRACT");
        addContractTypeIfPresent(q, contractTypes, "nda", "NDA");

        extractNamedClient(q, clients);

        return QueryMetadataHints.builder()
                .preferredDocumentTypes(preferredDocumentTypes)
                .countries(countries)
                .clients(clients)
                .sectors(sectors)
                .contractTypes(contractTypes)
                .keywords(keywords)
                .build();
    }

    private void addCountryIfPresent(String query, Set<String> target, String token, String normalizedValue) {
        if (query.contains(token)) {
            target.add(normalizedValue);
        }
    }

    private void addContractTypeIfPresent(String query, Set<String> target, String token, String normalizedValue) {
        if (query.contains(token)) {
            target.add(normalizedValue);
        }
    }

    private void extractNamedClient(String query, Set<String> clients) {
        if (query.contains("adnoc")) {
            clients.add("ADNOC");
        }
        if (query.contains("aramco")) {
            clients.add("ARAMCO");
        }
        if (query.contains("eni")) {
            clients.add("ENI");
        }
        if (query.contains("sonatrach")) {
            clients.add("SONATRACH");
        }
    }

    private Set<String> extractKeywords(String query) {
        Set<String> keywords = new LinkedHashSet<>();
        String[] tokens = query.split("[^a-zA-Z0-9]+");
        for (String token : tokens) {
            if (token.length() >= 4) {
                keywords.add(token);
            }
        }
        return keywords;
    }

    private boolean containsAny(String query, String... tokens) {
        for (String token : tokens) {
            if (query.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String input) {
        return input == null ? "" : input.toLowerCase(Locale.ROOT);
    }
}
