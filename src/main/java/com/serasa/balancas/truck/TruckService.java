package com.serasa.balancas.truck;

import com.serasa.balancas.common.exception.DuplicateResourceException;
import com.serasa.balancas.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class TruckService {

    private final TruckRepository truckRepository;

    public TruckService(TruckRepository truckRepository) {
        this.truckRepository = truckRepository;
    }

    public Truck create(Truck truck) {
        if (truckRepository.existsByLicensePlate(truck.getLicensePlate())) {
            throw new DuplicateResourceException(
                    "Truck with license plate " + truck.getLicensePlate() + " already exists");
        }
        truck.setId(null);
        return truckRepository.save(truck);
    }

    public Truck findById(Long id) {
        return truckRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Truck not found with id " + id));
    }
}
