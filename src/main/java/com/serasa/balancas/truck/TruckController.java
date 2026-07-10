package com.serasa.balancas.truck;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trucks")
public class TruckController {

    private final TruckRepository truckRepository;

    public TruckController(TruckRepository truckRepository) {
        this.truckRepository = truckRepository;
    }

    @PostMapping
    public ResponseEntity<Truck> create(@Valid @RequestBody Truck truck) {
        truck.setId(null);
        Truck saved = truckRepository.save(truck);
        return ResponseEntity.created(URI.create("/api/trucks/" + saved.getId())).body(saved);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Truck> findById(@PathVariable Long id) {
        return truckRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
