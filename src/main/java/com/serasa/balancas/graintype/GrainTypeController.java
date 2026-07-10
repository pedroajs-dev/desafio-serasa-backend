package com.serasa.balancas.graintype;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/grain-types")
public class GrainTypeController {

    private final GrainTypeRepository grainTypeRepository;

    public GrainTypeController(GrainTypeRepository grainTypeRepository) {
        this.grainTypeRepository = grainTypeRepository;
    }

    @PostMapping
    public ResponseEntity<GrainType> create(@Valid @RequestBody GrainType grainType) {
        grainType.setId(null);
        GrainType saved = grainTypeRepository.save(grainType);
        return ResponseEntity.created(URI.create("/api/grain-types/" + saved.getId())).body(saved);
    }

    @GetMapping
    public List<GrainType> findAll() {
        return grainTypeRepository.findAll();
    }
}
