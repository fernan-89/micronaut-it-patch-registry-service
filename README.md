# micronaut-it-patch-registry-service

BIAN-aligned Service Domain **it-patch-registry** (Control Record: `PatchPolicy`), port `8106`.

The fourth slice of Journey 14 (operations): a **registry of patches**. A `PatchPolicy` is the promise made about one asset (how many days a
patch of each severity may wait). A `PatchFinding` is one patch the scanner reported as available for it, which a tool later reports applied or
staff waive for a fixed reason and a limited time. The registry says, **when you ask**, whether each promise holds.

## What it guarantees, and what it does not

- **It only records** (ADR-030): scanners report patches and tools report them applied. The registry never scans, schedules or applies
  anything, holds no credential and reaches no asset.
- **One finding per patch** (ADR-030, ADR-033): the same policy and patch reference is the same finding. A repeat, or 20 at once, answers the
  stored finding with 200 (a new one is 201), and a patch already applied is never reopened by being reported again.
- **The reference is an identifier, never a sentence** (ADR-030): up to 64 characters of letters, digits and `. _ : / + -`. Anything else is
  a 400 and is not repeated in the answer or the log.
- **Waivers are fixed reasons and run out** (ADR-030): `NOT_APPLICABLE`, `COMPENSATING_CONTROL`, `ACCEPTED_RISK`, `VENDOR_NO_FIX`, for at most
  365 days; when it ends the finding is open again with its original deadline.
- **The promise is judged when read** (ADR-031), never stored: `compliance` is `COMPLIANT`, `OVERDUE` (an open finding past its deadline,
  announcement plus the days its severity is given) or `PAUSED` (not judged). A more severe patch cannot be given more time than a less severe one.
- **Staff only** (ADR-032): every route refuses a `REQUESTER` with 403 `ERR-PCH-00403`; another tenant's policy or finding answers 404.
  There is no delete (a policy is paused).
- **Safe with several instances** (ADR-033): a policy name is unique per organisation, one finding per `(policy, patch)` is a unique index,
  and every change is guarded by the status loaded.
- **Not here yet:** telling someone when a policy goes overdue (a later pairing with alerting), a catalogue of vulnerabilities (CVE scores,
  exploit data), approval of a patch, scheduling a patch window (that is the operation-window service), asset-level inventory of what is installed.

## BIAN Behavior Qualifier Contract

`X-Tenant-Id` is mandatory on every call; `X-Executor` is mandatory on the actions (for a finding, the name of the tool); `X-Role` is optional
and, with platform security on, comes from the verified token.

| Behavior Qualifier | Route |
|---|---|
| initiate | `POST /it-patch-registry/v1/initiate` `{"name":"Servers","assetId":"<uuid>","criticalDays":2,"highDays":7,"mediumDays":30,"lowDays":90}` |
| retrieve | `GET /it-patch-registry/v1/{id}/retrieve` (judged now) |
| retrieve (collection) | `GET /it-patch-registry/v1/retrieve?status=&assetId=&compliance=` (oldest first) |
| update | `PUT /it-patch-registry/v1/{id}/update` (the whole definition again, in any status) |
| control/pause · control/resume | `PUT /it-patch-registry/v1/{id}/control/pause` · `.../resume` |
| audit-log/retrieve | `GET /it-patch-registry/v1/{id}/audit-log/retrieve` |
| finding/initiate | `POST /it-patch-registry/v1/finding/initiate` `{"policyId":"<uuid>","patchRef":"KB5030211","severity":"HIGH","announcedAt":"<instant>"}` |
| finding/retrieve | `GET /it-patch-registry/v1/finding/{id}/retrieve` (with `dueAt` and `overdue`) |
| finding/retrieve (collection) | `GET /it-patch-registry/v1/finding/retrieve?policyId=&status=&severity=&limit=` (newest announcement first, 50 unless asked, at most 200) |
| finding/control/apply | `PUT /it-patch-registry/v1/finding/{id}/control/apply` `{"appliedAt":"<instant, optional: now>"}` (PENDING or WAIVED -> APPLIED) |
| finding/control/waive | `PUT /it-patch-registry/v1/finding/{id}/control/waive` `{"reason":"ACCEPTED_RISK","until":"<instant>"}` (PENDING -> WAIVED) |
| finding/control/unwaive | `PUT /it-patch-registry/v1/finding/{id}/control/unwaive` (WAIVED -> PENDING) |
| finding/audit-log/retrieve | `GET /it-patch-registry/v1/finding/{id}/audit-log/retrieve` |

```text
policy:  ACTIVE <-> PAUSED
finding: PENDING -> APPLIED (terminal)
         PENDING -> WAIVED (until a moment) -> APPLIED, or -> PENDING by unwaive or when the waiver runs out
```

## Error catalog

| Code | HTTP | Meaning |
|---|---|---|
| `ERR-PCH-00403` | 403 | A REQUESTER used the patch registry (ADR-032) |
| `ERR-PCH-00404` | 404 | Policy or finding not found (another tenant's answers the same) |
| `ERR-PCH-00409` | 409 | Duplicate policy name, illegal transition (apply an applied patch), or it changed while the write was applied (retry) |
| `ERR-VALIDATION-00400` | 400 | Payload/header/identifier validation failure, days out of range or out of order, a reference that is not an identifier, an applied time before the announcement, a waiver that does not end in the future |
| `ERR-INTERNAL-00500` | 500 | Unexpected technical failure |

## Configuration

`HASH_SERVICE_URL` (default `http://localhost:8080`, the sovereign ids), `MICRONAUT_SERVER_PORT` (default 8106), `mongodb.uri`,
`thinklab.mongo.create-indexes` (default on). The service calls no other service and has no scheduler.

## Architecture decisions

001 hexagonal architecture · 005 UUID identity sovereignty and audit tracing · 013 BIAN conventions · 019 HTTP 409 for state conflicts ·
030 the scanner reports, the registry keeps the findings · 031 the promise is judged when read · 032 staff only · 033 guarded writes and unique backstops.

## License

Licensed under the [PolyForm Strict License 1.0.0](LICENSE): you may read and use this software for noncommercial purposes only. Modifying it, creating derivative works, redistributing it and any commercial use are not permitted without a separate written license. This software is not open source.
