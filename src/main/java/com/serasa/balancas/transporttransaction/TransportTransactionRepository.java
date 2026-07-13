package com.serasa.balancas.transporttransaction;

import com.serasa.balancas.report.CostByGrainResponse;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransportTransactionRepository extends JpaRepository<TransportTransaction, Long> {

    /**
     * Finds the truck's currently-open transaction, if any — i.e. one whose status is NOT terminal.
     * Callers pass {@link TransactionStatus#TERMINAL} so both COMPLETED and CANCELLED count as closed
     * (non-blocking). Relies on the business invariant of at most one open transaction per truck.
     */
    TransportTransaction findByTruck_LicensePlateAndStatusNotIn(String licensePlate,
            Collection<TransactionStatus> statuses);

    @Query("SELECT new com.serasa.balancas.report.CostByGrainResponse(t.grainType.id, t.grainType.name, SUM(t.loadCost)) "
            + "FROM TransportTransaction t "
            + "WHERE t.status = com.serasa.balancas.transporttransaction.TransactionStatus.COMPLETED "
            + "AND (:from IS NULL OR t.startDate >= :from) "
            + "AND (:to IS NULL OR t.startDate <= :to) "
            + "GROUP BY t.grainType.id, t.grainType.name")
    List<CostByGrainResponse> sumLoadCostByGrainType(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT t.branch.id, t.branch.name, t.startDate, t.endDate "
            + "FROM TransportTransaction t "
            + "WHERE t.status = com.serasa.balancas.transporttransaction.TransactionStatus.COMPLETED")
    List<Object[]> findCompletedDurationsByBranch();
}
