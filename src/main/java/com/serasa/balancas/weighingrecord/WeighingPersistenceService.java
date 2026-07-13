package com.serasa.balancas.weighingrecord;

import com.serasa.balancas.graintype.GrainType;
import com.serasa.balancas.graintype.GrainTypeRepository;
import com.serasa.balancas.scale.Scale;
import com.serasa.balancas.scale.ScaleRepository;
import com.serasa.balancas.stabilization.StabilizationResult;
import com.serasa.balancas.stabilization.WeighingPersistencePort;
import com.serasa.balancas.transporttransaction.TransactionStatus;
import com.serasa.balancas.transporttransaction.TransportTransaction;
import com.serasa.balancas.transporttransaction.TransportTransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WeighingPersistenceService implements WeighingPersistencePort {

    private static final Logger log = LoggerFactory.getLogger(WeighingPersistenceService.class);

    private final TransportTransactionRepository transportTransactionRepository;
    private final WeighingRecordRepository weighingRecordRepository;
    private final ScaleRepository scaleRepository;
    private final GrainTypeRepository grainTypeRepository;
    private final double maxPayloadMultiplier;

    public WeighingPersistenceService(TransportTransactionRepository transportTransactionRepository,
            WeighingRecordRepository weighingRecordRepository, ScaleRepository scaleRepository,
            GrainTypeRepository grainTypeRepository,
            @Value("${anomaly-detection.max-payload-multiplier}") double maxPayloadMultiplier) {
        this.transportTransactionRepository = transportTransactionRepository;
        this.weighingRecordRepository = weighingRecordRepository;
        this.scaleRepository = scaleRepository;
        this.grainTypeRepository = grainTypeRepository;
        this.maxPayloadMultiplier = maxPayloadMultiplier;
    }

    @Override
    @Transactional
    public void persist(StabilizationResult result) {
        TransportTransaction transaction = transportTransactionRepository
                .findByTruck_LicensePlateAndStatusNotIn(result.plate(), TransactionStatus.TERMINAL);

        if (transaction == null) {
            log.warn("No open transaction found for plate={} (scaleId={}) — dropping stabilized reading of {}kg",
                    result.plate(), result.scaleId(), result.stabilizedWeightKg());
            return;
        }

        Optional<Scale> scale = scaleRepository.findById(result.scaleId());
        if (scale.isEmpty()) {
            log.warn("No Scale found for scaleId={} — dropping stabilized reading for plate={}",
                    result.scaleId(), result.plate());
            return;
        }

        Double tareValue = transaction.getTruck().getTare();
        if (tareValue == null) {
            log.warn("Null tare for truck plate={} (scaleId={}) — dropping stabilized reading",
                    result.plate(), result.scaleId());
            return;
        }

        if (transaction.getGrainType().getPurchasePricePerTon() == null) {
            log.warn("Null purchasePricePerTon for grainType={} (plate={}, scaleId={}) — dropping stabilized reading",
                    transaction.getGrainType().getId(), result.plate(), result.scaleId());
            return;
        }

        double grossWeightKg = result.stabilizedWeightKg();
        double tare = tareValue;
        double netWeightKg = grossWeightKg - tare;

        if (netWeightKg <= 0) {
            log.warn("Non-positive netWeightKg for plate={} (scaleId={}): grossWeightKg={}, tare={}, "
                            + "netWeightKg={} — dropping stabilized reading",
                    result.plate(), result.scaleId(), grossWeightKg, tare, netWeightKg);
            return;
        }

        double maxPlausibleGrossWeightKg = tare * (1 + maxPayloadMultiplier);
        if (grossWeightKg > maxPlausibleGrossWeightKg) {
            log.warn("Anomaly detected: grossWeightKg exceeds plausible capacity for plate={} (scaleId={}): "
                            + "grossWeightKg={}, threshold={} (tare={}, maxPayloadMultiplier={})",
                    result.plate(), result.scaleId(), grossWeightKg, maxPlausibleGrossWeightKg, tare,
                    maxPayloadMultiplier);
        }

        double loadCost = (netWeightKg / 1000.0) * transaction.getGrainType().getPurchasePricePerTon().doubleValue();

        // grossWeightKg inherits floating-point residue from averaging raw sensor readings
        // (StabilizationResult), and netWeightKg/loadCost compound that residue further.
        // Round to 2 decimal places (kg / currency precision) before persisting, using the
        // same BigDecimal.setScale(2, HALF_UP) pattern as MarginService to avoid reintroducing
        // drift at the rounding step itself.
        double roundedGrossWeightKg = round2(grossWeightKg);
        double roundedNetWeightKg = round2(netWeightKg);
        double roundedLoadCost = round2(loadCost);

        WeighingRecord record = new WeighingRecord(
                transaction.getTruck(),
                scale.get(),
                transaction.getGrainType(),
                transaction,
                roundedGrossWeightKg,
                tare,
                roundedNetWeightKg,
                roundedLoadCost,
                LocalDateTime.now());
        weighingRecordRepository.save(record);

        transaction.setGrossWeightKg(roundedGrossWeightKg);
        transaction.setNetWeightKg(roundedNetWeightKg);
        transaction.setLoadCost(roundedLoadCost);
        transaction.setStatus(TransactionStatus.COMPLETED);
        transaction.setEndDate(LocalDateTime.now());
        transportTransactionRepository.save(transaction);

        // Real grain physically arrived at the dock: increase the available stock ("quantidade
        // disponível na doca") by the same net weight persisted above, so the inversely-proportional
        // sale margin (MarginService) reflects deliveries over the life of the running instance.
        // Only reached on the completion path, after every guard — CANCELLED / dropped readings never
        // get here, and the increment shares this method's @Transactional boundary.
        GrainType grainType = transaction.getGrainType();
        grainType.setCurrentStock(grainType.getCurrentStock() + roundedNetWeightKg);
        grainTypeRepository.save(grainType);
    }

    private static double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
