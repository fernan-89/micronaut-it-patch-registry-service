# ADR-031: The Promise Is Judged When Read

## Status
Accepted

## Context
Whether a policy is met depends on the clock: a patch becomes overdue without any event. Storing a verdict would need a job to keep it true.

## Decision
- **Nothing about the verdict is stored.** `compliance`, the counts, the deadline of a finding and whether it is overdue are computed at the moment of each read, like the SLA of the incident service and the protection of the backup registry.
- **Deadline**: a finding is due `announcedAt` plus the number of days its policy gives its severity. A finding is **open** while it is `PENDING`, or `WAIVED` with a waiver that has run out; it is **overdue** when it is open past its deadline. Applied findings and findings under a waiver still in force do not count (the waived ones are counted apart).
- **Compliance**: `COMPLIANT` has no overdue finding (including none at all), `OVERDUE` has at least one (the oldest deadline is shown), `PAUSED` is not judged.
- **A policy cannot promise the absurd**: every number is 1 to 730 days and a more severe patch cannot be given more time than a less severe one (`critical <= high <= medium <= low`).
- The policy list is filterable by status, asset and compliance. The compliance filter runs in memory over the tenant's policies and its unresolved findings (everything not applied), read in one query.

## Consequences
- Positive: always true at the moment of reading, nothing to repair after a restart or a clock change, no scheduler.
- Negative: the list reads every unresolved finding of the tenant; fine for the expected size, a limit for very large tenants. Telling someone about an overdue patch needs a reader (pairing with alerting is a later slice).
