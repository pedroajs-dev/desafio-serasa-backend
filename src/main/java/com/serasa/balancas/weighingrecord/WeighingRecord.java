package com.serasa.balancas.weighingrecord;

import com.serasa.balancas.graintype.GrainType;
import com.serasa.balancas.scale.Scale;
import com.serasa.balancas.transporttransaction.TransportTransaction;
import com.serasa.balancas.truck.Truck;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

@Entity
public class WeighingRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne
    private Truck truck;

    @NotNull
    @ManyToOne
    private Scale scale;

    @NotNull
    @ManyToOne
    private GrainType grainType;

    @NotNull
    @ManyToOne
    private TransportTransaction transportTransaction;

    @NotNull
    private Double grossWeightKg;

    @NotNull
    private Double tare;

    @NotNull
    private Double netWeightKg;

    @NotNull
    private Double loadCost;

    @NotNull
    private LocalDateTime dateTime;

    public WeighingRecord() {
    }

    public WeighingRecord(Truck truck, Scale scale, GrainType grainType, TransportTransaction transportTransaction,
            Double grossWeightKg, Double tare, Double netWeightKg, Double loadCost, LocalDateTime dateTime) {
        this.truck = truck;
        this.scale = scale;
        this.grainType = grainType;
        this.transportTransaction = transportTransaction;
        this.grossWeightKg = grossWeightKg;
        this.tare = tare;
        this.netWeightKg = netWeightKg;
        this.loadCost = loadCost;
        this.dateTime = dateTime;
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

    public Scale getScale() {
        return scale;
    }

    public void setScale(Scale scale) {
        this.scale = scale;
    }

    public GrainType getGrainType() {
        return grainType;
    }

    public void setGrainType(GrainType grainType) {
        this.grainType = grainType;
    }

    public TransportTransaction getTransportTransaction() {
        return transportTransaction;
    }

    public void setTransportTransaction(TransportTransaction transportTransaction) {
        this.transportTransaction = transportTransaction;
    }

    public Double getGrossWeightKg() {
        return grossWeightKg;
    }

    public void setGrossWeightKg(Double grossWeightKg) {
        this.grossWeightKg = grossWeightKg;
    }

    public Double getTare() {
        return tare;
    }

    public void setTare(Double tare) {
        this.tare = tare;
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

    public LocalDateTime getDateTime() {
        return dateTime;
    }

    public void setDateTime(LocalDateTime dateTime) {
        this.dateTime = dateTime;
    }
}
