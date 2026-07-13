package com.serasa.balancas.transporttransaction;

import java.util.EnumSet;
import java.util.Set;

public enum TransactionStatus {
    IN_TRANSIT,
    AT_DOCK,
    WEIGHING,
    COMPLETED,
    CANCELLED;

    /**
     * Terminal statuses: a transaction in one of these is closed and no longer blocks its truck
     * from opening a new transaction. COMPLETED means a real weighing ran through
     * WeighingPersistenceService.persist() (weights/cost/endDate populated); CANCELLED means the
     * transaction was administratively closed (e.g. malfunctioning scale, truck that never
     * stabilizes) without a real weighing. Single source of truth used by the open-transaction
     * guard (TransportTransactionRepository) and the manual PATCH endpoint.
     */
    public static final Set<TransactionStatus> TERMINAL = EnumSet.of(COMPLETED, CANCELLED);
}
