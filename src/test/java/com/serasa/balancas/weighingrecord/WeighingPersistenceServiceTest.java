package com.serasa.balancas.weighingrecord;

import static org.assertj.core.api.Assertions.assertThat;

import com.serasa.balancas.branch.Branch;
import com.serasa.balancas.branch.BranchRepository;
import com.serasa.balancas.graintype.GrainType;
import com.serasa.balancas.graintype.GrainTypeRepository;
import com.serasa.balancas.stabilization.StabilizationResult;
import com.serasa.balancas.transporttransaction.TransactionStatus;
import com.serasa.balancas.transporttransaction.TransportTransaction;
import com.serasa.balancas.transporttransaction.TransportTransactionRepository;
import com.serasa.balancas.truck.Truck;
import com.serasa.balancas.truck.TruckRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class WeighingPersistenceServiceTest {

    private static final String SCALE_ID = "BAL-001";

    @Autowired
    private WeighingPersistenceService weighingPersistenceService;

    @Autowired
    private WeighingRecordRepository weighingRecordRepository;

    @Autowired
    private TransportTransactionRepository transportTransactionRepository;

    @Autowired
    private TruckRepository truckRepository;

    @Autowired
    private GrainTypeRepository grainTypeRepository;

    @Autowired
    private BranchRepository branchRepository;

    private String uniquePlate() {
        return "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    private TransportTransaction openTransaction(String plate, double tare) {
        Truck truck = truckRepository.save(new Truck(plate, tare));
        GrainType grainType = grainTypeRepository.findById(1L).orElseThrow();
        Branch branch = branchRepository.findById(1L).orElseThrow();
        return transportTransactionRepository.save(new TransportTransaction(truck, grainType, branch));
    }

    @Test
    void persistsWeighingRecordAndClosesTransactionWhenOpenTransactionExists() {
        String plate = uniquePlate();
        TransportTransaction transaction = openTransaction(plate, 8500.0);

        weighingPersistenceService.persist(StabilizationResult.of(SCALE_ID, plate, 12500.0));

        WeighingRecord record = weighingRecordRepository.findAll().stream()
                .filter(r -> r.getTransportTransaction().getId().equals(transaction.getId()))
                .findFirst()
                .orElseThrow();

        assertThat(record.getGrossWeightKg()).isEqualTo(12500.0);
        assertThat(record.getTare()).isEqualTo(8500.0);
        assertThat(record.getNetWeightKg()).isEqualTo(4000.0);
        // Soja seed: purchasePricePerTon = 180.00, loadCost = (4000/1000) * 180 = 720.0
        assertThat(record.getLoadCost()).isEqualTo(720.0);
        assertThat(record.getScale().getId()).isEqualTo(SCALE_ID);
        assertThat(record.getGrainType().getId()).isEqualTo(1L);
        assertThat(record.getDateTime()).isNotNull();

        TransportTransaction reloaded = transportTransactionRepository.findById(transaction.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(reloaded.getEndDate()).isNotNull();
        assertThat(reloaded.getGrossWeightKg()).isEqualTo(12500.0);
        assertThat(reloaded.getNetWeightKg()).isEqualTo(4000.0);
        assertThat(reloaded.getLoadCost()).isEqualTo(720.0);
    }

    @Test
    void skipsGracefullyWhenNetWeightIsNonPositive() {
        String plate = uniquePlate();
        TransportTransaction transaction = openTransaction(plate, 8500.0);
        long countBefore = weighingRecordRepository.count();

        // grossWeight (5000) < tare (8500) -> netWeightKg = -3500
        weighingPersistenceService.persist(StabilizationResult.of(SCALE_ID, plate, 5000.0));

        assertThat(weighingRecordRepository.count()).isEqualTo(countBefore);

        TransportTransaction reloaded = transportTransactionRepository.findById(transaction.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TransactionStatus.IN_TRANSIT);
        assertThat(reloaded.getEndDate()).isNull();
        assertThat(reloaded.getGrossWeightKg()).isNull();
        assertThat(reloaded.getNetWeightKg()).isNull();
        assertThat(reloaded.getLoadCost()).isNull();
    }

    @Test
    void skipsGracefullyWhenNoOpenTransactionFoundForPlate() {
        long countBefore = weighingRecordRepository.count();
        String plateWithNoTransaction = uniquePlate();

        weighingPersistenceService.persist(StabilizationResult.of(SCALE_ID, plateWithNoTransaction, 12500.0));

        assertThat(weighingRecordRepository.count()).isEqualTo(countBefore);
    }

    @Test
    void skipsGracefullyWhenNetWeightIsExactlyZero() {
        String plate = uniquePlate();
        TransportTransaction transaction = openTransaction(plate, 8500.0);
        long countBefore = weighingRecordRepository.count();

        // grossWeight (8500) == tare (8500) -> netWeightKg = 0
        weighingPersistenceService.persist(StabilizationResult.of(SCALE_ID, plate, 8500.0));

        assertThat(weighingRecordRepository.count()).isEqualTo(countBefore);

        TransportTransaction reloaded = transportTransactionRepository.findById(transaction.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TransactionStatus.IN_TRANSIT);
        assertThat(reloaded.getEndDate()).isNull();
    }

    @Test
    void skipsGracefullyWhenNoScaleFoundForScaleId() {
        String plate = uniquePlate();
        TransportTransaction transaction = openTransaction(plate, 8500.0);
        long countBefore = weighingRecordRepository.count();

        weighingPersistenceService.persist(StabilizationResult.of("NON-EXISTENT-SCALE", plate, 12500.0));

        assertThat(weighingRecordRepository.count()).isEqualTo(countBefore);

        TransportTransaction reloaded = transportTransactionRepository.findById(transaction.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TransactionStatus.IN_TRANSIT);
        assertThat(reloaded.getEndDate()).isNull();
    }
}
