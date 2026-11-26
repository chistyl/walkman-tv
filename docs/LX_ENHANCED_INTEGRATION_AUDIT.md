# LX Music Enhanced Integration Audit

## Scope and safety boundary

- Target repository: chistyl/walkman-tv
- Integration branch: integration/lx-enhanced-adaptive-source
- Base commit: 5d86cd73fd3d037d8ed484032253beb7198ad488
- Upstream enhanced source: junlovejing/lx-music-mobile-auto-source
- Upstream ref: maintainable-v1.9.2
- Upstream pinned commit: 9d48d9cea6712baa0f9423e49c062dbe43124e2f
- Upstream license: Apache-2.0
- TV reference implementation: bmizhou/lx-music-tv
- TV reference commit: 73f3b4db986be9b28f711e9d5bd7816d7d3b46f5

Do not modify the Walkman-TV delivery baseline, rewrite its UI, merge this branch automatically, or change PR #8. All implementation work remains isolated on this branch until CI and device regression checks pass.

## Verified architecture

### Walkman-TV target

The target is already a native Kotlin Android TV application. Its current source stack includes:

- SourceManager: loads user scripts, inspects capabilities, resolves music URLs and quality cascades.
- JsScriptRuntime: executes LX v4 user scripts in QuickJS with request correlation, timeouts, HTTP cancellation and user-agent propagation.
- OtherSourceFinder: searches other platforms and ranks same-song candidates.
- PlayUrlValidator: rejects invalid and non-HTTP(S) playback URLs.
- PlaybackController: owns Media3 ExoPlayer, performs retries, quality downgrade and resolver failover.
- SourceID and Quality models: map platform keys and eight quality tiers.

### LX Music Enhanced 1.9.2

The enhanced upstream is a React Native application. The most relevant portable behavior found in source review:

- src/sources/sourceHealth.ts: per-source success/failure counts, EWMA latency, consecutive failure tracking, cooldown and adaptive ranking.
- src/sources/subscriptionRecovery.ts: bounded subscription refresh recovery, with one refresh attempt per song and per-URL rate limiting.
- src/sources/remoteSourceFallbackCore.ts: safe catalog parsing, stable source identity, URL resolution, metadata extraction and content-based change detection.
- src/sources/remoteSourceFallback.ts: React Native storage and background timer integration around catalog refresh. This adapter is platform-specific and must not be copied as-is.

Pinned upstream SHA is confirmed unchanged at 9d48d9cea6712baa0f9423e49c062dbe43124e2f.

### Android TV reference

bmizhou/lx-music-tv is also a native Kotlin Android TV project. Reviewed files include SourceManagerImpl and SourceExecutor. They provide useful reference patterns for source script lifecycle, event-driven LX requests and TV-oriented source management. This repository is a reference only; its UI and application architecture are not to replace Walkman-TV.

## Integration decisions

1. Keep Walkman-TV UI, navigation, focus order, remote-control behavior, Media3 player and existing source script contract.
2. Port adaptive source health into Kotlin as a platform-neutral component using persistent Android storage. Track success, failure, EWMA latency, consecutive failures, cooldown and deterministic tie-breaking.
3. Apply health ranking only among scripts already eligible for the requested platform/action. Do not bypass user enable/disable settings or platform filters.
4. Keep the current source order as the stable tie-breaker when health evidence is equal or absent.
5. Phase B records resolver health: success only after a script returns an accepted HTTP(S) URL; failure once per script/song after its quality cascade is exhausted. Cancellation is rethrown and is not counted. Playback-level feedback is a later phase and must be wired to actual Media3 state transitions before it is used to score playback reliability.
6. Add bounded subscription/catalog refresh as a separate follow-up. Require explicit user-configured catalog URL, validate HTTPS/HTTP URLs, parse the complete catalog before mutation, preserve locally-added scripts and selected source, and never overwrite local data on partial fetch failure.
7. Use the existing Kotlin QuickJS runtime and ScriptHttpClient. Do not import React Native timers, storage, navigation, or UI components.
8. Preserve existing quality cascade, cross-platform matching, URL validation, retry limits and local-file playback.

## Proposed implementation sequence

- Phase A: add Kotlin source-health model/store and deterministic unit tests.
- Phase B: integrate ranking into SourceManager without changing source eligibility or fallback semantics.
- Phase C: wire playback outcome feedback from PlaybackController, excluding cancellation and stale generations.
- Phase D: evaluate catalog subscription recovery separately, with parser and atomic-update tests.
- Phase E: run Android build, unit tests and regression checks for search, source import, quality cascade, lyrics, downloads, local playback, queue, background playback and remote focus.

## Acceptance criteria

- Existing UI and remote-control behavior remain unchanged.
- No regression to currently supported source scripts or platform filters.
- A failing source is cooled down after repeated failures and healthy sources are preferred.
- Resolver health data survives process restart and malformed stored data falls back safely to an empty map.
- User cancellation, track switching and stale async results do not poison source health.
- Catalog refresh failures never delete or partially replace existing scripts.
- CI build and tests pass before proposing any merge.


## Phase C completion and Phase D subscription-recovery audit

### Phase C status (commit ca8b58f42c253ff2b8bfd6b9626386bf21147d4a)

- Media3 failover now reports the concrete failed resolver attempt to SourceManager.
- Only `script:<script-id>:<platform-key>` attempts affect script health; direct/local/other-source keys are ignored.
- The callback path records asynchronously and retains the existing generation, media-ID and failover-in-progress guards.
- GitHub Actions Run #415 (run ID 45844807148) completed successfully: regression tests, four ABI APK builds/uploads, and full source ZIP upload all succeeded.

### Subscription recovery feasibility findings

The Android TV app currently persists imported scripts as `UserScript` records in `scripts.json`. The record contains script content and display metadata, but no subscription catalog URL, remote catalog ID, or subscription ownership metadata. `ScriptStore.import(raw)` is a manual raw-script import/update path; it replaces a same-name + same-author script and otherwise appends a new independent script. There is no existing subscription catalog manager or remote-update transaction.

The pinned Enhanced implementation separates portable catalog parsing/change detection from its React Native adapter. The portable concepts are suitable, but its adapter is not:

- Reuse the safety properties of complete-catalog parsing, stable remote IDs, relative URL resolution, metadata extraction and content-based change detection.
- Do not copy the React Native timer/storage adapter.
- Do not copy the upstream catalog writer as-is: it removes missing subscription entries, replaces lists, and changes selected-source state. That is too destructive for Walkman-TV's current manual-import semantics.
- A subscription must be explicitly created by the user with a catalog URL. Existing manually imported scripts must never be silently claimed as subscription-managed.
- A refresh must stage and validate the entire catalog and all script bodies before any local mutation. Failed/partial fetches must leave every existing script unchanged.
- Preserve local scripts, script IDs where remote identity matches, enabled/disabled choices, list ordering, and current source selection. Removed remote entries should be handled by an explicit policy, not implicit deletion.
- Use bounded HTTP timeouts and coroutine cancellation that cancels the underlying OkHttp call. Do not count cancellation as a failed refresh.

### Phase D implementation gate

Before implementing automatic recovery, introduce an additive subscription model/store with explicit URL and stable remote IDs, and define user-facing opt-in/import semantics. Keep the existing `UserScript` JSON schema backward-compatible through defaults or a separate store. First implement and test pure parsing, URL resolution, identity mapping, and atomic plan generation; only then wire network fetching and ScriptStore mutation. The playback recovery trigger must be bounded to one refresh per song/subscription and rate-limited per catalog URL, following the Enhanced policy without importing its mutable module-global state.
