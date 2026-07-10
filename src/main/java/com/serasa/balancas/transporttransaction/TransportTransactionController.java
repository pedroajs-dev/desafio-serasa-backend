package com.serasa.balancas.transporttransaction;

import com.serasa.balancas.branch.Branch;
import com.serasa.balancas.branch.BranchRepository;
import com.serasa.balancas.common.exception.BusinessException;
import com.serasa.balancas.common.exception.ResourceNotFoundException;
import com.serasa.balancas.graintype.GrainType;
import com.serasa.balancas.graintype.GrainTypeRepository;
import com.serasa.balancas.truck.Truck;
import com.serasa.balancas.truck.TruckRepository;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transactions")
public class TransportTransactionController {

    private final TransportTransactionRepository transactionRepository;
    private final TruckRepository truckRepository;
    private final GrainTypeRepository grainTypeRepository;
    private final BranchRepository branchRepository;

    public TransportTransactionController(
            TransportTransactionRepository transactionRepository,
            TruckRepository truckRepository,
            GrainTypeRepository grainTypeRepository,
            BranchRepository branchRepository) {
        this.transactionRepository = transactionRepository;
        this.truckRepository = truckRepository;
        this.grainTypeRepository = grainTypeRepository;
        this.branchRepository = branchRepository;
    }

    @PostMapping
    public ResponseEntity<TransportTransactionResponse> create(@Valid @RequestBody TransportTransactionRequest request) {
        Truck truck = truckRepository.findById(request.truckId())
                .orElseThrow(() -> new ResourceNotFoundException("Truck not found with id " + request.truckId()));
        GrainType grainType = grainTypeRepository.findById(request.grainTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("GrainType not found with id " + request.grainTypeId()));
        Branch branch = branchRepository.findById(request.branchId())
                .orElseThrow(() -> new ResourceNotFoundException("Branch not found with id " + request.branchId()));

        if (transactionRepository.findByTruck_LicensePlateAndStatusNot(truck.getLicensePlate(), TransactionStatus.COMPLETED) != null) {
            throw new BusinessException("Truck " + truck.getLicensePlate() + " already has an open transaction");
        }

        TransportTransaction transaction = new TransportTransaction(truck, grainType, branch);
        TransportTransaction saved = transactionRepository.save(transaction);
        return ResponseEntity.created(URI.create("/api/transactions/" + saved.getId()))
                .body(TransportTransactionResponse.from(saved));
    }

    @GetMapping("/{id}")
    public TransportTransactionResponse findById(@PathVariable Long id) {
        TransportTransaction transaction = transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("TransportTransaction not found with id " + id));
        return TransportTransactionResponse.from(transaction);
    }

    @PatchMapping("/{id}/status")
    public TransportTransactionResponse updateStatus(@PathVariable Long id, @Valid @RequestBody StatusUpdateRequest request) {
        TransportTransaction transaction = transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("TransportTransaction not found with id " + id));

        transaction.setStatus(request.status());
        TransportTransaction saved = transactionRepository.save(transaction);
        return TransportTransactionResponse.from(saved);
    }
}
