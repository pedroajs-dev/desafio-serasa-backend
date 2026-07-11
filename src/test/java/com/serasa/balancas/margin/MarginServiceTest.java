package com.serasa.balancas.margin;

import static org.assertj.core.api.Assertions.assertThat;

import com.serasa.balancas.graintype.GrainType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MarginServiceTest {

    private final MarginService service = new MarginService();

    private GrainType grainType(double currentStock, double maxReferenceStock) {
        return new GrainType("Soy", BigDecimal.valueOf(1000), maxReferenceStock, currentStock);
    }

    @Test
    void depletedStockYieldsMaximumMargin() {
        BigDecimal margin = service.calculateMargin(grainType(0, 1000));

        assertThat(margin.compareTo(BigDecimal.valueOf(0.20))).isZero();
    }

    @Test
    void stockAtReferenceYieldsMinimumMargin() {
        BigDecimal margin = service.calculateMargin(grainType(1000, 1000));

        assertThat(margin.compareTo(BigDecimal.valueOf(0.05))).isZero();
    }

    @Test
    void stockAboveReferenceClampsToMinimumMargin() {
        BigDecimal margin = service.calculateMargin(grainType(1200, 1000));

        assertThat(margin.compareTo(BigDecimal.valueOf(0.05))).isZero();
    }

    @Test
    void midpointStockYieldsInterpolatedMargin() {
        BigDecimal margin = service.calculateMargin(grainType(500, 1000));

        assertThat(margin.compareTo(BigDecimal.valueOf(0.125))).isZero();
    }

    @Test
    void calculateSalePriceAppliesMarginToPurchasePrice() {
        GrainType grainType = grainType(500, 1000);

        BigDecimal salePrice = service.calculateSalePrice(grainType);

        assertThat(salePrice.compareTo(BigDecimal.valueOf(1125.00))).isZero();
    }
}
