package com.thinklab.domain.model;

/**
 * What a registry says about one promise when it is read (platform ADR-001, the promises contract): {@code BROKEN} with a fixed code of the registry's own
 * vocabulary, {@code KEPT}, or {@code NOT_JUDGED}. The code is never a value or a word of a person.
 */
public record Promise(Verdict verdict, String code) {

    public enum Verdict { KEPT, BROKEN, NOT_JUDGED }

    public static Promise kept() {
        return new Promise(Verdict.KEPT, null);
    }

    public static Promise notJudged() {
        return new Promise(Verdict.NOT_JUDGED, null);
    }

    public static Promise broken(String code) {
        return new Promise(Verdict.BROKEN, code);
    }
}
