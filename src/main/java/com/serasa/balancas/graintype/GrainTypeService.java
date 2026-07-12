package com.serasa.balancas.graintype;

import com.serasa.balancas.common.exception.DuplicateResourceException;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class GrainTypeService {

    private final GrainTypeRepository grainTypeRepository;

    public GrainTypeService(GrainTypeRepository grainTypeRepository) {
        this.grainTypeRepository = grainTypeRepository;
    }

    public GrainType create(GrainType grainType) {
        if (grainTypeRepository.existsByName(grainType.getName())) {
            throw new DuplicateResourceException(
                    "GrainType with name " + grainType.getName() + " already exists");
        }
        grainType.setId(null);
        return grainTypeRepository.save(grainType);
    }

    public List<GrainType> findAll() {
        return grainTypeRepository.findAll();
    }
}
