package com.thinklab.application.usecase;

import com.thinklab.domain.exception.PatchAccessDeniedException;

/** The patch registry is for IT staff and for the tools they run (ADR-032): a REQUESTER is refused everywhere. */
final class PatchAccess {

    static final String REQUESTER_ROLE = "REQUESTER";

    private PatchAccess() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    static void requireStaff(String role, String operation) {
        if (REQUESTER_ROLE.equals(role)) {
            throw new PatchAccessDeniedException("A requester cannot " + operation + ": patches are handled by IT staff.");
        }
    }
}
