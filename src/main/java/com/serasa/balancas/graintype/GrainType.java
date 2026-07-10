package com.serasa.balancas.graintype;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

@Entity
public class GrainType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    private String name;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    private BigDecimal purchasePricePerTon;

    @NotNull
    private Double maxReferenceStock;

    private Double currentStock = 0.0;

    public GrainType() {
    }

    public GrainType(String name, BigDecimal purchasePricePerTon, Double maxReferenceStock, Double currentStock) {
        this.name = name;
        this.purchasePricePerTon = purchasePricePerTon;
        this.maxReferenceStock = maxReferenceStock;
        this.currentStock = currentStock;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getPurchasePricePerTon() {
        return purchasePricePerTon;
    }

    public void setPurchasePricePerTon(BigDecimal purchasePricePerTon) {
        this.purchasePricePerTon = purchasePricePerTon;
    }

    public Double getMaxReferenceStock() {
        return maxReferenceStock;
    }

    public void setMaxReferenceStock(Double maxReferenceStock) {
        this.maxReferenceStock = maxReferenceStock;
    }

    public Double getCurrentStock() {
        return currentStock;
    }

    public void setCurrentStock(Double currentStock) {
        this.currentStock = currentStock;
    }
}
