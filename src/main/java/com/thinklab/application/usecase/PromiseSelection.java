package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.PromiseResponse;
import com.thinklab.domain.model.Promise.Verdict;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * What the promises route returns (platform ADR-001): every broken promise (at most {@code limit}) and every other promise that changed at or after
 * {@code since} (at most {@code limit}), broken first, each once. Also the validation of the two query parameters, so every registry answers them alike.
 */
final class PromiseSelection {

    static final int DEFAULT_DAYS = 30;
    static final int MAX_DAYS = 365;
    static final int DEFAULT_LIMIT = 500;
    static final int MAX_LIMIT = 1000;

    private PromiseSelection() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    static int days(Integer changedWithinDays) {
        if (changedWithinDays == null) {
            return DEFAULT_DAYS;
        }
        if (changedWithinDays < 1 || changedWithinDays > MAX_DAYS) {
            throw new IllegalArgumentException("The days within which a promise changed must be between 1 and " + MAX_DAYS + ".");
        }
        return changedWithinDays;
    }

    static int limit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("The limit must be between 1 and " + MAX_LIMIT + ".");
        }
        return limit;
    }

    static List<PromiseResponse> select(List<PromiseResponse> all, Instant since, int limit) {
        List<PromiseResponse> selected = new ArrayList<>(all.stream().filter(promise -> promise.verdict().equals(Verdict.BROKEN.name())).limit(limit).toList());
        selected.addAll(all.stream().filter(promise -> !promise.verdict().equals(Verdict.BROKEN.name()) && !promise.changedAt().isBefore(since)).limit(limit).toList());
        return selected;
    }
}
