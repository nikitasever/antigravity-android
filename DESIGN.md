---
version: alpha
name: Antigravity Android
description: Native Android chat informed by the user's ChatGPT and Claude references.
colors:
  background: "#151515"
  surface: "#20201E"
  primary: "#3566EF"
  text: "#FFFFFF"
  secondary: "#9E9E9E"
typography:
  sans:
    fontFamily: Android system sans
  mono:
    fontFamily: Monospace
rounded:
  DEFAULT: 12dp
spacing:
  message-gap: 16dp
components:
  chat: {}
  drawer: {}
  sheet: {}
---

# Antigravity Android

## Overview

Product interface for Android conversations and remote PC sessions. The user's
ChatGPT and Claude references govern direction; do not invent additional features
or claim reference fidelity without inspecting the original reference screens.
Russian is the current UI language. No new visual identity is introduced here.

## Colors

`ui/design/DesignSystem.kt` owns runtime tokens. The 2026-11-04 live references
and the user's combined design specify Claude's #151515 background and #20201E
cards, #2F2F2F user bubbles, and ChatGPT's #3566EF primary action blue.
Semantic success/error, syntax highlighting and provider identity retain their
distinct roles. Material switches use blue tracks and white thumbs through
`designSwitchColors()`. Do not add decorative accent panels.

## Typography

Use existing Compose system text and monospace for technical content. Preserve
Cyrillic support, wrapping, and readable message text.

## Layout

The message list owns available vertical space between header and composer.
Respect system bars and the keyboard. Follow streaming output until the person
scrolls up; do not pull someone away from older messages while reading.

## Elevation & Depth

Use existing tonal surfaces for navigation and modal sheets.

## Shapes

Preserve existing rounded composer, controls, and sheets.

## Components

SettingsModalScreen delegates to AppSettingsScreen, the single settings owner.
Use grouped 16dp tonal surfaces, 16sp row labels and muted 13sp summaries. A fixed
header owns close/back; details have independent scroll positions. Root settings
show account, app, connections, data access and version. Technical configuration
belongs in detail pages. Existing permission and OpenRouter sheets remain owners
for those features. Connection claims use pcBridgeStatus, never a constant label.
API keys are masked by default, and key-check feedback belongs to the checked key.
Root settings search navigates to existing detail pages or the permission sheet;
it searches labels and parameter keywords, never secret values. The live Claude
settings evidence is recorded in CLAUDE_SETTINGS_REFERENCE.md.

Reading preferences live in AntigravitySettings and persist on the device.
AppSettingsScreen owns Appearance controls and a real Markdown preview.
ChatGptMessageBubble forwards text size to prompts and replies; response font
affects assistant prose only. MarkdownRenderer scales prose, headings, tables
and code while preserving monospaced code and link annotations. The default
remains 15sp prose / 22sp line height. Assistant prose and greeting default to
system serif; user messages and interface controls use sans. Explicitly saved
font choices remain effective. Android accessibility font scaling uses sp units.
`InlineMarkdownText` measures code chips as atomic inline items. Oversized tokens
wrap within a full-width chip; original text and URL annotation offsets are kept.
Fenced code wraps and retains existing diff colors and copy/save controls.

`SheetHeader` owns modal headings: centered title, close on the left, 48dp button.
Metadata and refresh actions remain below the heading. AppSettingsScreen owns
Profile (existing Google session actions) and Notifications (actual Android
permission status refreshed on resume, with navigation to system controls).
These pages do not imply cloud profile editing or new notification delivery.

Canonical owners are `ChatGptMainChatScreen`, `ChatGptExactNavigationDrawer`, and
the shared sheets in `ui/components/Round5PanelsAndSheets.kt`. Extend those owners
instead of creating parallel versions of the same interaction.

## Behavior

`AntigravityViewModel` owns conversation and generation state. A stopped or old
generation must not overwrite a newly selected conversation. Loading must finish
on failure. Empty server results stay empty; example files are not user data.
Only report a successful save after its result is known. The current `/api/gdrive`
implementation stores local workspace files and is not a Google Drive connection.

## Verification

MCP sign-in uses the external system browser and a validated return to Android.
The existing connection sheet owns sign-in/sign-out; OAuth authorization and a
successful MCP handshake are separate states. Tokens remain in a private bridge
vault, never in Android UI, manifests, catalog exports or prompts. Stdio entries
do not show an enabled OAuth action.

Run Android unit tests and assembleDebug with `scripts/build.ps1`, and check the
Node bridge syntax. Device verification is required for visual fidelity,
streaming scroll, keyboard, accessibility, and session switching.

## Reference evidence

Local ChatGPT and Claude base APK hashes and screenshot classification are in
`REFERENCE_EVIDENCE.md`. Only screens identified as original apps govern visual
comparisons; screenshots of earlier Antigravity builds document defects.
Never force a model to produce artificial thinking text for presentation.
Show waiting separately from actual provider reasoning. Keep user expansion
state during streaming and show tool success only for a completed operation.

## Unified chat and artifact navigation

The user approved combining ChatGPT and Claude functionality. Preserve the
existing dark palette and rounded surfaces. Keep conversations, projects,
library and work sessions visible; put device/bridge extras under Tools.
`ChatHistoryDrawer` is the shared history destination; the existing drawer
entry point delegates to it. `ChatLibrary` owns filtering, ordering and stored
presentation preferences. `CodeArtifactViewer` owns full-screen code reading.
No independent alternative drawers or code viewers should be introduced.
Runtime colors remain in these Compose owners; no duplicate theme is generated.

## Library workflow

`ArtifactLibraryScreen` is the library owner, reached through the existing
ChatGptLibraryScreen entry point. Keep its search, saved-answer filter and
list/detail behavior together. Saved copies use stable message identity;
remote artifacts remain read-only. File export and code reading reuse the
existing ViewModel file writer and CodeArtifactViewer.

## Current reference verification scope

For the current comparison pass, use only the supplied ChatGPT 1.2026.293 APK,
resources and identified original screenshots. Keep existing project features.
Track observed behavior separately from resource names and implementation
assumptions in CHATGPT_COMPARISON.md. Claude is outside this comparison pass.

## Tasks on phone and PC

The supplied historical Claude Code Remote control screenshots guide the work
conversation: narrative, actual tool summaries, expandable results and feedback
while processing. They do not establish a manual subagent creation form.
The current authenticated web reference separately confirms edit-version behavior.

The main conversation owns task launch: an Android model may request a bounded
child task with explicit context. PhoneAgentEngine runs on the phone using the
configured direct model API, or the existing phone-mode bridge route for a CLI
model. CLI inference requires the PC; phone orchestration and tools stay on Android.
The existing PC executor
continues to handle PC turns. ToolCallEvent is the shared presentation/persistence
record, including destination, parent task and leading visible text.

BackgroundTasksDetailContent is a read-only view of tasks belonging to the current
conversation, not a separate task creation form. CodeArtifactViewer remains the
owner for code/change previews. Legacy CLI analysis/worktree routines are retained
internally and are not claimed to reproduce the originals.

ChatGptBottomInputPill owns queued follow-up entry/removal/manual resume. Feedback
queued while a turn runs is sent after successful completion, rather than silently
interrupting the current turn. Stop pauses this automatic progression. Restored
queues never automatically start inference at application startup. Temporary chat
queue entries remain in memory. Existing dark surfaces and text tokens are reused.

MainScreen message bubbles own version navigation. Editing or retrying archives
the current continuation in the same chat; previous/next restores its prompt and
reply. ChatMessageVersions owns the pure transition logic and ChatSessionStore
persists the alternatives. Backend execution contexts and follow-up queues are
separated by selected alternative. Navigation is blocked during streaming.
