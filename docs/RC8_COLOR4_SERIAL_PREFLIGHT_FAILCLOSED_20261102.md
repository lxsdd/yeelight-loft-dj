# 2026-11-02: LAN lockout focus – no new WLAN diagnosis

Established before this revision: Cloud/Google Home and voice operation worked while Yeelight Classic/LAN stopped on Esstisch .135/.136/.137; FRITZ!Box online was never a novel discovery. Loft DJ force-stop did not restore control; each individual power-cycle restored that bulb. No proof of the original triggering command sequence.

Concrete source finding: Music preflight previously used `chosen.map { async {...} }.awaitAll()`. If three Esstisch bulbs were actually in the selected target, all three read-only TCP get_prop checks could be in flight together. It is NOT established that the reported selection contained three bulbs; that requires device-side evidence. A single reported .136 firmware quota does NOT prove three separate quota responses.

Mitigation implemented:
- Explicitly sequential read-only music preflight with immediate abort on first error, zero retries/other bulb probes after failure, no new physical commands.
- Release the **one target TCP session** immediately after any preflight read (success or failure), within the same per-IP admission lock, instead of retaining slots until idle cleanup.
- Preserve existing per-IP firmware-quota quarantine, stop/restore journal and target-specific UI failure details from PR #47.
- Fail-closed compile-time LIVE_TEST_APPROVED=false for the dual acceptance. No release/signing or user hardware actions.
- Pure deterministic JVM negative/positive tests for order, first refusal, duplicate IP and no extra attempts. TCP session test validates target-specific close.

Scope: This mitigates avoidable simultaneous connection pressure and source-side risks but does not establish firmware root cause, which requires separate evidence. No more live tests authorized or attempted.