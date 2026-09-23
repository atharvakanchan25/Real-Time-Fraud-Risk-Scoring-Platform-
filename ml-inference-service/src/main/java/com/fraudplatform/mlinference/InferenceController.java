package com.fraudplatform.mlinference;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stub ML inference endpoint.
 * Accepts a feature payload and returns a random fraud probability [0.0, 1.0].
 * In production this would call a real model server (e.g. SageMaker, TorchServe).
 */
@RestController
public class InferenceController {

    @PostMapping("/infer")
    public Map<String, Object> infer(@RequestBody Map<String, Object> features) {
        double score = ThreadLocalRandom.current().nextDouble();
        return Map.of(
                "fraudProbability", score,
                "modelVersion",     "stub-1.0"
        );
    }
}
