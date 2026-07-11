package com.serasa.balancas.margin;

import com.serasa.balancas.graintype.GrainType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

@Service
public class MarginService {

    private static final double MIN_MARGIN = 0.05;
    private static final double MAX_MARGIN = 0.20;

    public BigDecimal calculateMargin(GrainType grainType) {
        double maxReferenceStock = grainType.getMaxReferenceStock();
        double currentStock = grainType.getCurrentStock();

        double ratio = maxReferenceStock <= 0
                ? 1
                : Math.max(0, Math.min(1, currentStock / maxReferenceStock));

        double margin = MAX_MARGIN - (MAX_MARGIN - MIN_MARGIN) * ratio;
        return BigDecimal.valueOf(margin).setScale(4, RoundingMode.HALF_UP);
    }

    public BigDecimal calculateSalePrice(GrainType grainType) {
        return grainType.getPurchasePricePerTon().multiply(BigDecimal.ONE.add(calculateMargin(grainType)));
    }
}
