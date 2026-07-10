package com.serasa.balancas.transporttransaction;

import com.serasa.balancas.branch.Branch;
import com.serasa.balancas.graintype.GrainType;
import com.serasa.balancas.truck.Truck;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

@Entity
public class TransportTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne
    private Truck truck;

    @NotNull
    @ManyToOne
    private GrainType grainType;

    @NotNull
    @ManyToOne
    private Branch branch;

    @NotNull
    @Enumerated(EnumType.STRING)
    private TransactionStatus status;

    @NotNull
    private LocalDateTime startDate;

    private LocalDateTime endDate;

    private Double grossWeightKg;

    private Double netWeightKg;

    private Double loadCost;

    public TransportTransaction() {
    }

    public TransportTransaction(Truck truck, GrainType grainType, Branch branch) {
        this.truck = truck;
        this.grainType = grainType;
        this.branch = branch;
        this.status = TransactionStatus.IN_TRANSIT;
        this.startDate = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Truck getTruck() {
        return truck;
    }

    public void setTruck(Truck truck) {
        this.truck = truck;
    }

    public GrainType getGrainType() {
        return grainType;
    }

    public void setGrainType(GrainType grainType) {
        this.grainType = grainType;
    }

    public Branch getBranch() {
        return branch;
    }

    public void setBranch(Branch branch) {
        this.branch = branch;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public LocalDateTime getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDateTime startDate) {
        this.startDate = startDate;
    }

    public LocalDateTime getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDateTime endDate) {
        this.endDate = endDate;
    }

    public Double getGrossWeightKg() {
        return grossWeightKg;
    }

    public void setGrossWeightKg(Double grossWeightKg) {
        this.grossWeightKg = grossWeightKg;
    }

    public Double getNetWeightKg() {
        return netWeightKg;
    }

    public void setNetWeightKg(Double netWeightKg) {
        this.netWeightKg = netWeightKg;
    }

    public Double getLoadCost() {
        return loadCost;
    }

    public void setLoadCost(Double loadCost) {
        this.loadCost = loadCost;
    }
}
