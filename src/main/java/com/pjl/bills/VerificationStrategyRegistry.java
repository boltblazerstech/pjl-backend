package com.pjl.bills;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Central registry that maps each {@code BillCategory.verificationStrategyKey}
 * to its {@link VerificationStrategy} implementation.
 * <p>
 * Spring automatically injects every bean that implements
 * {@link VerificationStrategy}. The registry indexes them by {@link VerificationStrategy#getKey()}
 * at startup and fails fast if two strategies claim the same key.
 * <p>
 * <strong>Adding a new category</strong>: implement {@link VerificationStrategy},
 * annotate the class with {@code @Component}, return a unique key from
 * {@code getKey()} — this class needs no changes.
 */
@Slf4j
@Component
public class VerificationStrategyRegistry {

    private final List<VerificationStrategy> strategies;
    private final Map<String, VerificationStrategy> strategyMap = new HashMap<>();

    public VerificationStrategyRegistry(List<VerificationStrategy> strategies) {
        this.strategies = strategies;
    }

    @PostConstruct
    void init() {
        for (VerificationStrategy strategy : strategies) {
            String key = strategy.getKey();
            VerificationStrategy previous = strategyMap.put(key, strategy);
            if (previous != null) {
                throw new IllegalStateException(String.format(
                        "Duplicate VerificationStrategy key '%s': claimed by both %s and %s",
                        key,
                        previous.getClass().getName(),
                        strategy.getClass().getName()
                ));
            }
            log.info("Registered VerificationStrategy key='{}' -> {}",
                    key, strategy.getClass().getSimpleName());
        }
        log.info("VerificationStrategyRegistry ready with {} strategy/strategies: {}",
                strategyMap.size(), strategyMap.keySet());
    }

    /**
     * Resolve the strategy registered for the given key.
     *
     * @param key the value of {@code BillCategory.verificationStrategyKey}
     * @return the matching strategy
     * @throws IllegalArgumentException if no strategy is registered for {@code key}
     */
    public VerificationStrategy resolve(String key) {
        VerificationStrategy strategy = strategyMap.get(key);
        if (strategy == null) {
            throw new IllegalArgumentException(String.format(
                    "No VerificationStrategy registered for key '%s'. " +
                    "Registered keys: %s",
                    key, strategyMap.keySet()
            ));
        }
        return strategy;
    }
}
