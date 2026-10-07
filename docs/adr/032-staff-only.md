# ADR-032: The Patch Registry Is for IT Staff and Their Tools

## Status
Accepted

## Context
Policies name assets and how well they are patched; findings name which patches are missing. A requester (end user) has no business reading them.

## Decision
- Every route refuses a `REQUESTER` with 403 `ERR-PCH-00403`; any other role, or none, is staff (the same rule as the monitor, the alerting service and the incident service). The tools that report findings are service identities, which are not requesters.
- Every lookup is scoped by `X-Tenant-Id`: another tenant's policy or finding is a 404, never a 403.
- **There is no delete.** A policy is paused and a finding is applied or waived (the history stays).
- Audit is kept on the policy and on the finding, for real events (initiated, updated, paused, resumed; reported, applied, waived, un-waived), with the executor id.
- **Nothing a tool or staff say is kept as free text**: the patch reference is a restricted identifier and the waiver reason is a fixed list. A value outside a fixed list is never repeated in the answer or the log.

## Consequences
- Positive: one access rule across the operations services; no free text that could leak a secret.
- Negative: a waiver says why only in one of four reasons; the detail stays in the ticket or the tool.
