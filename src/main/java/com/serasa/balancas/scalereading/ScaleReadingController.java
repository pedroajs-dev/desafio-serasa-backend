package com.serasa.balancas.scalereading;

import com.serasa.balancas.common.exception.UnauthorizedException;
import com.serasa.balancas.scale.ScaleRepository;
import com.serasa.balancas.stabilization.StabilizationService;
import com.serasa.balancas.stabilization.WeighingPersistencePort;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scales/readings")
public class ScaleReadingController {

    private final ScaleRepository scaleRepository;
    private final StabilizationService stabilizationService;
    private final WeighingPersistencePort weighingPersistencePort;

    public ScaleReadingController(ScaleRepository scaleRepository, StabilizationService stabilizationService,
            WeighingPersistencePort weighingPersistencePort) {
        this.scaleRepository = scaleRepository;
        this.stabilizationService = stabilizationService;
        this.weighingPersistencePort = weighingPersistencePort;
    }

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestHeader(value = "X-Scale-Key", required = false) String scaleKey,
            @Valid @RequestBody ScaleReadingRequest request) {

        if (!StringUtils.hasText(scaleKey)
                || scaleRepository.findByIdAndApiKey(request.id(), scaleKey).isEmpty()) {
            throw new UnauthorizedException("Invalid or missing X-Scale-Key for scale " + request.id());
        }

        stabilizationService.process(request.id(), request.plate(), request.weight())
                .ifPresent(result -> {
                    try {
                        weighingPersistencePort.persist(result);
                    } catch (RuntimeException ex) {
                        stabilizationService.markPersistenceFailed(request.id());
                        throw ex;
                    }
                });

        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
