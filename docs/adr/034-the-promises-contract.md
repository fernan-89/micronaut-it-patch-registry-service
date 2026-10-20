# ADR-034: The Promises Contract

## Status
Accepted. Implements platform ADR-001 (`thinklab-platform/docs/adr/001-the-promises-contract.md`).

## Context
The alerting service used to know this registry's fields and verdicts (which states are a broken promise, and the code of each). A rule that lived in a consumer was untested with the domain and had to be kept in step by hand.

## Decision
- The registry answers `GET /it-patch-registry/v1/promises/retrieve?changedWithinDays=&limit=` as the platform contract says: every broken promise and every one that changed within the window (30 days unless asked, 1 to 365), at most `limit` of each (500 unless asked, 1 to 1000), broken first.
- **The judgement lives here:** a policy is **broken** (`PATCH_OVERDUE`) when an open finding (pending, or waived with a waiver that ran out) is past its deadline, **kept** when none is, and **not judged** when it is paused. The name, the asset and the status are the policy's.
- **`changedAt`:** the later of the policy's last update and the last change of any of its findings (reported, applied, waived), read for the window asked, so it is exact inside the window.
- Staff only, tenant-scoped, read-only.

## Consequences
- Positive: the rule is judged and tested with the domain; the consumer needs no knowledge of this registry.
- Negative: a policy that stops being overdue only by a waiver or a deadline passing is not listed unless it changed: a waiver is a change of the finding, so it is; a deadline passing makes a promise broken, never kept.
