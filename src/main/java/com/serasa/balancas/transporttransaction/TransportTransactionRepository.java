package com.serasa.balancas.transporttransaction;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TransportTransactionRepository extends JpaRepository<TransportTransaction, Long> {

    TransportTransaction findByTruck_LicensePlateAndStatusNot(String licensePlate, TransactionStatus completedStatus);
}
