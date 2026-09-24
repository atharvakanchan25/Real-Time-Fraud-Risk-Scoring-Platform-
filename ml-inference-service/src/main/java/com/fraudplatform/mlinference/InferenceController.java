package com.fraudplatform.mlinference;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.util.Map;

/**
 * Stub ML inference endpoint.
 * Accepts a feature payload and returns a random fraud probability [0.0, 1.0].
 * In production this would call a real model server (e.g. SageMaker, TorchServe).
 */
@RestController
public class InferenceController {

    // SecureRandom is thread-safe and cryptographically strong — no fixed seed
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @PostMapping("/infer")
    public Map<String, Object> infer(@RequestBody Map<String, Object> features) {
        double score = SECURE_RANDOM.nextDouble();
        return Map.of(
                "fraudProbability", score,
                "modelVersion",     "stub-1.0"
        );
    }
}
