package com.serasa.balancas.scalereading;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class ReadingIdempotencyService {

    private final ConcurrentHashMap.KeySetView<String, Boolean> seenKeys = ConcurrentHashMap.newKeySet();

    /**
     * Records the {@code scaleId:seq} key and reports whether it had already been seen.
     * This mutates state (the key is claimed as a side effect); a {@code null} seq is never
     * recorded and never counts as a duplicate.
     *
     * @return {@code true} if this {@code scaleId:seq} was already registered (a duplicate),
     *         {@code false} if it is seen for the first time (and is now recorded)
     */
    public boolean registerAndCheckDuplicate(String scaleId, Long seq) {
        if (seq == null) {
            return false;
        }
        return !seenKeys.add(scaleId + ":" + seq);
    }

    /**
     * Removes a previously recorded {@code scaleId:seq} key so the reading can be retried.
     * Used when downstream processing fails after the key was already claimed, keeping this
     * guard consistent with {@code StabilizationService.markPersistenceFailed()}.
     */
    public void evict(String scaleId, Long seq) {
        if (seq == null) {
            return;
        }
        seenKeys.remove(scaleId + ":" + seq);
    }
}
