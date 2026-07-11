package com.serasa.balancas.scale;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScaleRepository extends JpaRepository<Scale, String> {

    Optional<Scale> findByIdAndApiKey(String id, String apiKey);
}
