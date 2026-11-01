# Antigravity Android 2.0.1

- Phone task orchestration uses the configured API directly; PC tasks use the bridge. The phone does not require a connected PC for API chat and supported phone tools.
- Delegated phone tasks appear within the conversation and in read-only task details. Child tasks have bounded, read-only tools.
- Follow-up prompts queue during generation, with stop, remove and manual resume controls. Queues are scoped to conversation, execution target and message version.
- Editing and retrying preserve previous versions and their replies within the same chat. Version selection restores the corresponding continuation; local persistence retains alternatives.
- Chat history, artifacts, project workspaces, HTTP cancellation, voice diagnostics and bridge task lifecycle fixes are included.
- Fixed a foreground-service startup race on immediately completed phone requests.
- Source resources, native libraries, reference evidence, tests and implementation reports are included in the repository. API credentials and private runtime state are excluded.

## Limits

Phone orchestration is local, but model inference requires a configured external API. Binary multimodal attachment delivery is not implemented by the phone task engine. Native Claude login was blocked by Google Play verification; observed Claude web behavior and supplied Remote Control screenshots are documented separately. The extracted APK assets do not establish backend feature parity.

The downloadable APK is a debug build. Updating an existing installation requires the same signing key; back up app data before any reinstall.
