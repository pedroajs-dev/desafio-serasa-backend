package com.serasa.balancas.report;

import com.serasa.balancas.graintype.GrainType;
import com.serasa.balancas.graintype.GrainTypeRepository;
import com.serasa.balancas.margin.MarginService;
import com.serasa.balancas.transporttransaction.TransportTransactionRepository;
import com.serasa.balancas.weighingrecord.WeighingRecordRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final TransportTransactionRepository transportTransactionRepository;
    private final WeighingRecordRepository weighingRecordRepository;
    private final GrainTypeRepository grainTypeRepository;
    private final MarginService marginService;
    private final ReportProperties reportProperties;

    public ReportController(TransportTransactionRepository transportTransactionRepository,
            WeighingRecordRepository weighingRecordRepository, GrainTypeRepository grainTypeRepository,
            MarginService marginService, ReportProperties reportProperties) {
        this.transportTransactionRepository = transportTransactionRepository;
        this.weighingRecordRepository = weighingRecordRepository;
        this.grainTypeRepository = grainTypeRepository;
        this.marginService = marginService;
        this.reportProperties = reportProperties;
    }

    @GetMapping("/cost-by-grain")
    public List<CostByGrainResponse> costByGrain(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return transportTransactionRepository.sumLoadCostByGrainType(from, to);
    }

    @GetMapping("/scale-ranking")
    public List<ScaleRankingResponse> scaleRanking() {
        return weighingRecordRepository.countByScaleOrderedDesc();
    }

    @GetMapping("/avg-weighing-duration")
    public List<AvgWeighingDurationResponse> avgWeighingDuration() {
        List<Object[]> rows = transportTransactionRepository.findCompletedDurationsByBranch();

        Map<Long, String> branchNames = new LinkedHashMap<>();
        Map<Long, List<Long>> durationsByBranch = new LinkedHashMap<>();

        for (Object[] row : rows) {
            Long branchId = (Long) row[0];
            String branchName = (String) row[1];
            LocalDateTime startDate = (LocalDateTime) row[2];
            LocalDateTime endDate = (LocalDateTime) row[3];

            if (startDate == null || endDate == null) {
                continue;
            }

            branchNames.put(branchId, branchName);
            durationsByBranch.computeIfAbsent(branchId, id -> new ArrayList<>())
                    .add(Duration.between(startDate, endDate).getSeconds());
        }

        List<AvgWeighingDurationResponse> result = new ArrayList<>();
        for (Map.Entry<Long, List<Long>> entry : durationsByBranch.entrySet()) {
            double avg = entry.getValue().stream().mapToLong(Long::longValue).average().orElse(0.0);
            result.add(new AvgWeighingDurationResponse(entry.getKey(), branchNames.get(entry.getKey()), avg));
        }
        return result;
    }

    @GetMapping("/avg-margin-by-grain")
    public List<AvgMarginByGrainResponse> avgMarginByGrain() {
        return grainTypeRepository.findAll().stream()
                .map(grainType -> new AvgMarginByGrainResponse(
                        grainType.getId(), grainType.getName(), marginService.calculateMargin(grainType)))
                .toList();
    }

    @GetMapping("/scarcity-alerts")
    public List<ScarcityAlertResponse> scarcityAlerts() {
        List<ScarcityAlertResponse> alerts = new ArrayList<>();
        for (GrainType grainType : grainTypeRepository.findAll()) {
            BigDecimal margin = marginService.calculateMargin(grainType);
            if (margin.doubleValue() >= reportProperties.scarcityThreshold()) {
                alerts.add(new ScarcityAlertResponse(grainType.getId(), grainType.getName(), margin,
                        grainType.getCurrentStock(), grainType.getMaxReferenceStock()));
            }
        }
        return alerts;
    }
}
