# ADR-033: Guarded Writes, and Unique Indexes as the Atomic Backstops

## Status
Accepted

## Context
Several instances serve the registry, staff edit policies while tools report and apply patches, and tools retry. Checking first and writing later is not atomic.

## Decision
- Every change to an existing policy or finding is **one atomic update guarded by the status that was loaded**, which also appends the audit entry. A lost race is 409 (`ERR-PCH-00409`): read again and decide again. Two people waiving and a tool applying the same patch at once: one wins, the others get the conflict.
- **A policy name is unique per organisation** (`(organisationId, name)`), on create and on rename.
- **One finding per `(organisationId, policyId, patchRef)`** is a unique index. The loser of a simultaneous insert reads the stored finding and answers it as not created.
- Indexes are created at startup, fail-open and idempotent; `thinklab.mongo.create-indexes=false` turns them off. A third index `(organisationId, policyId, status)` serves the query of the unresolved findings.
- The mongo adapters set the update time themselves and never write what the domain did not ask for.

## Consequences
- Positive: with N instances and a retrying scanner there is still exactly one finding per patch and no lost updates.
- Negative: without the index (it is fail-open) a simultaneous double report could store two findings; the startup log says when an index could not be created.
