package com.serasa.balancas.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.serasa.balancas.branch.Branch;
import com.serasa.balancas.branch.BranchRepository;
import com.serasa.balancas.graintype.GrainType;
import com.serasa.balancas.graintype.GrainTypeRepository;
import com.serasa.balancas.scale.Scale;
import com.serasa.balancas.scale.ScaleRepository;
import com.serasa.balancas.transporttransaction.TransactionStatus;
import com.serasa.balancas.transporttransaction.TransportTransaction;
import com.serasa.balancas.transporttransaction.TransportTransactionRepository;
import com.serasa.balancas.truck.Truck;
import com.serasa.balancas.truck.TruckRepository;
import com.serasa.balancas.weighingrecord.WeighingRecord;
import com.serasa.balancas.weighingrecord.WeighingRecordRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private GrainTypeRepository grainTypeRepository;

    @Autowired
    private TruckRepository truckRepository;

    @Autowired
    private ScaleRepository scaleRepository;

    @Autowired
    private TransportTransactionRepository transportTransactionRepository;

    @Autowired
    private WeighingRecordRepository weighingRecordRepository;

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private Branch createBranch() {
        return branchRepository.save(new Branch("Branch-" + uniqueSuffix(), "Somewhere"));
    }

    private GrainType createGrainType() {
        return grainTypeRepository.save(
                new GrainType("Grain-" + uniqueSuffix(), BigDecimal.valueOf(100.0), 1000.0, 500.0));
    }

    private GrainType createGrainType(double currentStock, double maxReferenceStock) {
        return grainTypeRepository.save(
                new GrainType("Grain-" + uniqueSuffix(), BigDecimal.valueOf(100.0), maxReferenceStock, currentStock));
    }

    private Truck createTruck() {
        return truckRepository.save(new Truck("T" + uniqueSuffix(), 5000.0));
    }

    private Scale createScale(Branch branch) {
        Scale scale = new Scale("SCALE-" + uniqueSuffix(), branch, "key-" + uniqueSuffix());
        return scaleRepository.save(scale);
    }

    private TransportTransaction createCompletedTransaction(Truck truck, GrainType grainType, Branch branch,
            double loadCost, LocalDateTime startDate, LocalDateTime endDate) {
        TransportTransaction transaction = new TransportTransaction(truck, grainType, branch);
        transaction.setStatus(TransactionStatus.COMPLETED);
        transaction.setStartDate(startDate);
        transaction.setEndDate(endDate);
        transaction.setGrossWeightKg(10000.0);
        transaction.setNetWeightKg(9000.0);
        transaction.setLoadCost(loadCost);
        return transportTransactionRepository.save(transaction);
    }

    private void createWeighingRecord(Truck truck, Scale scale, GrainType grainType,
            TransportTransaction transaction) {
        WeighingRecord record = new WeighingRecord(truck, scale, grainType, transaction,
                10000.0, 1000.0, 9000.0, 900.0, LocalDateTime.now());
        weighingRecordRepository.save(record);
    }

    @Test
    void costByGrainReturnsTotalsGroupedByGrainType() throws Exception {
        Branch branch = createBranch();
        GrainType grainA = createGrainType();
        GrainType grainB = createGrainType();
        Truck truck = createTruck();

        createCompletedTransaction(truck, grainA, branch, 1000.0, LocalDateTime.now(), LocalDateTime.now());
        createCompletedTransaction(truck, grainA, branch, 500.0, LocalDateTime.now(), LocalDateTime.now());
        createCompletedTransaction(truck, grainB, branch, 300.0, LocalDateTime.now(), LocalDateTime.now());

        String response = mockMvc.perform(get("/api/reports/cost-by-grain"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        Double totalA = findTotalLoadCostFor(json, grainA.getId());
        Double totalB = findTotalLoadCostFor(json, grainB.getId());

        assertThat(totalA).isEqualTo(1500.0);
        assertThat(totalB).isEqualTo(300.0);
    }

    @Test
    void costByGrainRespectsDateRangeAndOmitsOutsideTransactions() throws Exception {
        Branch branch = createBranch();
        GrainType grainType = createGrainType();
        Truck truck = createTruck();

        LocalDateTime inRange = LocalDateTime.now();
        LocalDateTime outOfRange = LocalDateTime.now().minusDays(30);

        createCompletedTransaction(truck, grainType, branch, 1000.0, inRange, inRange);
        createCompletedTransaction(truck, grainType, branch, 5000.0, outOfRange, outOfRange);

        String response = mockMvc.perform(get("/api/reports/cost-by-grain")
                        .param("from", inRange.minusHours(1).toString())
                        .param("to", inRange.plusHours(1).toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        Double total = findTotalLoadCostFor(json, grainType.getId());

        assertThat(total).isEqualTo(1000.0);
    }

    @Test
    void costByGrainExcludesNonCompletedTransactions() throws Exception {
        Branch branch = createBranch();
        GrainType grainType = createGrainType();
        Truck truck = createTruck();

        TransportTransaction openTransaction = new TransportTransaction(truck, grainType, branch);
        transportTransactionRepository.save(openTransaction);

        createCompletedTransaction(truck, grainType, branch, 750.0, LocalDateTime.now(), LocalDateTime.now());

        String response = mockMvc.perform(get("/api/reports/cost-by-grain"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        Double total = findTotalLoadCostFor(json, grainType.getId());

        assertThat(total).isEqualTo(750.0);
    }

    private Double findTotalLoadCostFor(JsonNode json, Long grainTypeId) {
        for (JsonNode node : json) {
            if (node.get("grainTypeId").asLong() == grainTypeId) {
                return node.get("totalLoadCost").asDouble();
            }
        }
        return null;
    }

    @Test
    void scaleRankingOrdersScalesByWeighingCountDescending() throws Exception {
        Branch branch = createBranch();
        GrainType grainType = createGrainType();
        Truck truck = createTruck();
        Scale busyScale = createScale(branch);
        Scale quietScale = createScale(branch);

        TransportTransaction transaction = createCompletedTransaction(truck, grainType, branch, 100.0,
                LocalDateTime.now(), LocalDateTime.now());

        createWeighingRecord(truck, busyScale, grainType, transaction);
        createWeighingRecord(truck, busyScale, grainType, transaction);
        createWeighingRecord(truck, quietScale, grainType, transaction);

        String response = mockMvc.perform(get("/api/reports/scale-ranking"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        int busyIndex = -1;
        int quietIndex = -1;
        for (int i = 0; i < json.size(); i++) {
            String scaleId = json.get(i).get("scaleId").asText();
            if (scaleId.equals(busyScale.getId())) {
                busyIndex = i;
            }
            if (scaleId.equals(quietScale.getId())) {
                quietIndex = i;
            }
        }

        assertThat(busyIndex).isGreaterThanOrEqualTo(0);
        assertThat(quietIndex).isGreaterThanOrEqualTo(0);
        assertThat(busyIndex).isLessThan(quietIndex);
        assertThat(json.get(busyIndex).get("weighingCount").asLong()).isEqualTo(2);
        assertThat(json.get(quietIndex).get("weighingCount").asLong()).isEqualTo(1);
    }

    @Test
    void avgWeighingDurationComputesAverageSecondsPerBranch() throws Exception {
        Branch branch = createBranch();
        GrainType grainType = createGrainType();
        Truck truck = createTruck();

        LocalDateTime start = LocalDateTime.now();
        createCompletedTransaction(truck, grainType, branch, 100.0, start, start.plusSeconds(100));
        createCompletedTransaction(truck, grainType, branch, 100.0, start, start.plusSeconds(200));

        String response = mockMvc.perform(get("/api/reports/avg-weighing-duration"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        Double avg = null;
        for (JsonNode node : json) {
            if (node.get("branchId").asLong() == branch.getId()) {
                avg = node.get("avgDurationSeconds").asDouble();
            }
        }

        assertThat(avg).isEqualTo(150.0);
    }

    @Test
    void avgWeighingDurationExcludesNonCompletedTransactions() throws Exception {
        Branch branch = createBranch();
        GrainType grainType = createGrainType();
        Truck truck = createTruck();

        TransportTransaction openTransaction = new TransportTransaction(truck, grainType, branch);
        transportTransactionRepository.save(openTransaction);

        LocalDateTime start = LocalDateTime.now();
        createCompletedTransaction(truck, grainType, branch, 100.0, start, start.plusSeconds(60));

        String response = mockMvc.perform(get("/api/reports/avg-weighing-duration"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        Double avg = null;
        for (JsonNode node : json) {
            if (node.get("branchId").asLong() == branch.getId()) {
                avg = node.get("avgDurationSeconds").asDouble();
            }
        }

        assertThat(avg).isEqualTo(60.0);
    }

    @Test
    void avgMarginByGrainReturnsMarginPerGrainType() throws Exception {
        GrainType scarce = createGrainType(0.0, 1000.0);
        GrainType abundant = createGrainType(1000.0, 1000.0);

        String response = mockMvc.perform(get("/api/reports/avg-margin-by-grain"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        BigDecimal scarceMargin = findMarginFor(json, scarce.getId());
        BigDecimal abundantMargin = findMarginFor(json, abundant.getId());

        assertThat(scarceMargin.compareTo(BigDecimal.valueOf(0.20))).isEqualTo(0);
        assertThat(abundantMargin.compareTo(BigDecimal.valueOf(0.05))).isEqualTo(0);
    }

    @Test
    void scarcityAlertsReturnsOnlyGrainTypesAtOrAboveThreshold() throws Exception {
        GrainType scarce = createGrainType(0.0, 1000.0);
        GrainType abundant = createGrainType(1000.0, 1000.0);

        String response = mockMvc.perform(get("/api/reports/scarcity-alerts"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        boolean scarcePresent = false;
        boolean abundantPresent = false;
        for (JsonNode node : json) {
            long id = node.get("grainTypeId").asLong();
            if (id == scarce.getId()) {
                scarcePresent = true;
            }
            if (id == abundant.getId()) {
                abundantPresent = true;
            }
        }

        assertThat(scarcePresent).isTrue();
        assertThat(abundantPresent).isFalse();
    }

    @Test
    void scarcityAlertsIncludesGrainTypeAtExactThresholdBoundary() throws Exception {
        GrainType boundary = createGrainType(2.0, 15.0);

        String response = mockMvc.perform(get("/api/reports/scarcity-alerts"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        boolean boundaryPresent = false;
        for (JsonNode node : json) {
            if (node.get("grainTypeId").asLong() == boundary.getId()) {
                boundaryPresent = true;
                assertThat(node.get("margin").decimalValue().compareTo(BigDecimal.valueOf(0.18))).isEqualTo(0);
            }
        }

        assertThat(boundaryPresent).isTrue();
    }

    private BigDecimal findMarginFor(JsonNode json, Long grainTypeId) {
        for (JsonNode node : json) {
            if (node.get("grainTypeId").asLong() == grainTypeId) {
                return node.get("margin").decimalValue();
            }
        }
        return null;
    }
}
