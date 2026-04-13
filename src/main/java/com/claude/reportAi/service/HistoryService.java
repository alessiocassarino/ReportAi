package com.claude.reportAi.service;

import com.claude.reportAi.dto.HistoryDTO;
import com.claude.reportAi.entities.ContractAnalysis;
import com.claude.reportAi.entities.Estimate;
import com.claude.reportAi.entities.PriceComparison;
import com.claude.reportAi.entities.VectoreUpload;
import com.claude.reportAi.repository.ContractAnalysisRepository;
import com.claude.reportAi.repository.EstimateRepository;
import com.claude.reportAi.repository.PriceComparisonRepository;
import com.claude.reportAi.repository.VectorUploadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class HistoryService {

    private final VectorUploadRepository vectorUploadRepository;
    private final ContractAnalysisRepository contractAnalysisRepository;
    private final EstimateRepository estimateRepository;
    private final PriceComparisonRepository priceComparisonRepository;

    public Page<HistoryDTO> getJobs(
            int page, int size,
            String type, String status,
            LocalDate dateFrom, LocalDate dateTo,
            String search,
            String sortBy, String sortDir) {

        LocalDateTime from = dateFrom != null ? dateFrom.atStartOfDay() : null;
        LocalDateTime to   = dateTo   != null ? dateTo.atTime(LocalTime.MAX) : null;

        List<HistoryDTO> all = new ArrayList<>();

        if (type == null || "DOCUMENTS".equalsIgnoreCase(type)) {
            vectorUploadRepository.findAll().stream()
                    .map(this::fromUploadJob)
                    .filter(dto -> matches(dto, status, from, to, search))
                    .forEach(all::add);
        }

        if (type == null || "CONTRACTS".equalsIgnoreCase(type)) {
            contractAnalysisRepository.findAll().stream()
                    .map(this::fromContractJob)
                    .filter(dto -> matches(dto, status, from, to, search))
                    .forEach(all::add);
        }

        if (type == null || "REPORTS".equalsIgnoreCase(type)) {
            estimateRepository.findAll().stream()
                    .map(this::fromReportJob)
                    .filter(dto -> matches(dto, status, from, to, search))
                    .forEach(all::add);
        }

        if (type == null || "PRICE_COMPARISONS".equalsIgnoreCase(type)) {
            priceComparisonRepository.findAll().stream()
                    .map(this::fromPriceComparisonJob)
                    .filter(dto -> matches(dto, status, from, to, search))
                    .forEach(all::add);
        }

        all.sort(buildComparator(sortBy, sortDir));

        int total   = all.size();
        int fromIdx = page * size;
        int toIdx   = Math.min(fromIdx + size, total);
        List<HistoryDTO> content = fromIdx >= total ? List.of() : all.subList(fromIdx, toIdx);

        return new PageImpl<>(content, PageRequest.of(page, size), total);
    }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    private HistoryDTO fromUploadJob(VectoreUpload j) {
        HistoryDTO dto = new HistoryDTO();
        dto.setJobId(j.getId().toString());
        dto.setType("DOCUMENTS");
        dto.setOriginalFilename(j.getOriginalFilename());
        dto.setStatus(j.getStatus().name());
        dto.setModel(null);
        dto.setCreatedAt(j.getCreatedAt());
        dto.setUpdatedAt(j.getUpdatedAt());
        dto.setErrorMessage(j.getErrorMessage());
        return dto;
    }

    private HistoryDTO fromContractJob(ContractAnalysis j) {
        HistoryDTO dto = new HistoryDTO();
        dto.setJobId(j.getId().toString());
        dto.setType("CONTRACTS");
        dto.setOriginalFilename(j.getOriginalFilename());
        dto.setStatus(j.getStatus().name());
        dto.setModel(j.getModel());
        dto.setCreatedAt(j.getCreatedAt());
        dto.setUpdatedAt(j.getUpdatedAt());
        dto.setErrorMessage(j.getErrorMessage());
        return dto;
    }

    private HistoryDTO fromReportJob(Estimate j) {
        HistoryDTO dto = new HistoryDTO();
        dto.setJobId(j.getId().toString());
        dto.setType("REPORTS");
        dto.setOriginalFilename(j.getOriginalFilename());
        dto.setStatus(j.getStatus().name());
        dto.setModel(j.getModel());
        dto.setCreatedAt(j.getCreatedAt());
        dto.setUpdatedAt(j.getUpdatedAt());
        dto.setErrorMessage(j.getErrorMessage());
        return dto;
    }

    private HistoryDTO fromPriceComparisonJob(PriceComparison j) {
        HistoryDTO dto = new HistoryDTO();
        dto.setJobId(j.getId().toString());
        dto.setType("PRICE_COMPARISONS");
        dto.setOriginalFilename(j.getOriginalFilenames());
        dto.setStatus(j.getStatus().name());
        dto.setModel(j.getModel());
        dto.setCreatedAt(j.getCreatedAt());
        dto.setUpdatedAt(j.getUpdatedAt());
        dto.setErrorMessage(j.getErrorMessage());
        return dto;
    }

    // -------------------------------------------------------------------------
    // Filtering
    // -------------------------------------------------------------------------

    private boolean matches(HistoryDTO dto, String status,
                            LocalDateTime from, LocalDateTime to, String search) {

        if (status != null && !status.equalsIgnoreCase(dto.getStatus())) {
            return false;
        }
        if (from != null && dto.getCreatedAt() != null && dto.getCreatedAt().isBefore(from)) {
            return false;
        }
        if (to != null && dto.getCreatedAt() != null && dto.getCreatedAt().isAfter(to)) {
            return false;
        }
        if (search != null && !search.isBlank()) {
            String q = search.toLowerCase();
            boolean filenameMatch = dto.getOriginalFilename() != null
                    && dto.getOriginalFilename().toLowerCase().contains(q);
            boolean jobIdMatch = dto.getJobId() != null
                    && dto.getJobId().toLowerCase().contains(q);
            if (!filenameMatch && !jobIdMatch) {
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Sorting
    // -------------------------------------------------------------------------

    private Comparator<HistoryDTO> buildComparator(String sortBy, String sortDir) {
        Comparator<HistoryDTO> comparator = switch (sortBy == null ? "createdAt" : sortBy) {
            case "originalFilename" -> Comparator.comparing(
                    dto -> dto.getOriginalFilename() != null ? dto.getOriginalFilename() : "",
                    String.CASE_INSENSITIVE_ORDER);
            case "status"           -> Comparator.comparing(
                    dto -> dto.getStatus() != null ? dto.getStatus() : "");
            case "type"             -> Comparator.comparing(
                    dto -> dto.getType() != null ? dto.getType() : "");
            case "updatedAt"        -> Comparator.comparing(
                    HistoryDTO::getUpdatedAt,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            default                 -> Comparator.comparing(
                    HistoryDTO::getCreatedAt,
                    Comparator.nullsLast(Comparator.naturalOrder()));
        };

        return "ASC".equalsIgnoreCase(sortDir) ? comparator : comparator.reversed();
    }
}
