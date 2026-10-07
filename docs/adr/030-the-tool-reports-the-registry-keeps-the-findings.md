# ADR-030: The Scanner Reports, the Registry Keeps the Findings

## Status
Accepted

## Context
Patches are found by scanners and applied by tools (a vulnerability scanner, an update agent, a cloud service) that the registry does not control. The registry has to say whether the promise made about an asset holds, without ever holding a credential, reaching an asset or applying anything.

## Decision
- **The registry only records.** A `PatchPolicy` is the promise about one asset: how many days a patch of each severity (`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`) may wait after it was announced. A `PatchFinding` is one patch that is available for that asset, reported by the scanner (`finding/initiate`), and later reported applied by the tool (`finding/control/apply`) or waived by staff. Nothing here scans, schedules or applies.
- **A finding is identified by its patch reference**, one per policy (unique index on `(organisationId, policyId, patchRef)`). Reporting it again, or twenty at once, answers the stored finding with 200 instead of 201, spends no sovereign id, and **never reopens or changes it**: a patch that was applied stays applied when the scanner reports it again.
- **The reference is an identifier, never a sentence**: up to 64 characters from letters, digits and `. _ : / + -`, starting with a letter or digit (a KB number, a CVE, a package version). Anything else is a 400, so nothing a tool says in its own words is stored.
- **Waivers use a fixed vocabulary and run out.** A waiver has a reason from a list (`NOT_APPLICABLE`, `COMPENSATING_CONTROL`, `ACCEPTED_RISK`, `VENDOR_NO_FIX`) and an end in the future, at most 365 days ahead. When it ends the finding is open again by itself, with its original deadline. Staff may withdraw it earlier (`finding/control/unwaive`).
- **An applied finding is terminal.** The time it was applied cannot be before the announcement or in the future (a 5 minute clock skew is allowed).

## Consequences
- Positive: no credentials, no access to assets; any tool that can call an HTTP route can report.
- Negative: the registry trusts what the tool says. A scanner that stops reporting leaves the registry looking compliant, because it cannot judge what it was never told about.
