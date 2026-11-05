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
Use grouped 20dp tonal surfaces, 18sp row labels and muted 14sp summaries. A fixed
header owns close/back; details have independent scroll positions. Root settings
show account, app, connections, data access and version. Technical configuration
belongs in detail pages. Existing permission and OpenRouter sheets remain owners
for those features. Connection claims use pcBridgeStatus, never a constant label.
API keys are masked by default, and key-check feedback belongs to the checked key.
Root settings search opens from the header and navigates to existing detail pages or the permission sheet;
it searches labels and parameter keywords, never secret values. The live Claude
settings evidence is recorded in CLAUDE_SETTINGS_REFERENCE.md.

Reading preferences live in AntigravitySettings and persist on the device.
AppSettingsScreen owns Appearance controls and a real Markdown preview.
ChatGptMessageBubble forwards text size to prompts and replies; response font
affects assistant prose only. MarkdownRenderer scales prose, headings, tables
and code while preserving monospaced code and link annotations. The default
is 18sp prose / 28sp line height. Assistant prose and greeting default to
system serif; user messages and interface controls use sans. The empty ChatGPT-style
conversation stays blank instead of adding a two-line Antigravity headline. Explicitly saved
font choices remain effective. Android accessibility font scaling uses sp units.
`InlineMarkdownText` measures code chips as atomic inline items. Oversized tokens
wrap within a full-width chip; immediately following closing punctuation stays
inside that visual item, with the message, alternate text and URL offsets intact.
Fenced code wraps and retains existing diff colors and copy/save controls.

`SheetHeader` owns modal headings: centered title, close on the left, 48dp button.
Metadata and refresh actions remain below the heading. AppSettingsScreen owns
Profile (existing Google session actions) and Notifications (actual Android
permission status refreshed on resume, with navigation to system controls).
These pages do not imply cloud profile editing or new notification delivery.

## Visual correction after rejected palette-only pass

The original live ChatGPT screenshot `Screenshot_20261104_091531_ChatGPT.jpg`
governs main-screen density: three aligned header controls, 48dp circles, a
48dp high segmented control (up to 190dp wide), and no model/context subtitle.
`ChatModeHeader` owns this geometry for both Chat and Work and shrinks the segment
on narrow phones. `ConversationHeader` adds the conversation action menu.
The right control opens conversation actions, following the supplied Claude Code
conversation-menu pattern; model, context, search and outline remain reachable.
This is an explicit combined-product variant, not a claim of exact ChatGPT copying.

The composer uses the ChatGPT #303030 surface, 1dp outline and 32dp rounding,
56dp minimum height and 32dp outer side insets, a plain plus and larger microphone. Existing attachments,
queue, draft, send, stop and voice handlers are preserved. Claude dark background
and serif assistant responses remain the chosen combined-product treatment.

The live Claude settings screenshot `Screenshot_20261104_090914_Claude.jpg`
governs account-card and grouped-icon-row hierarchy; technical explanations stay
inside detail pages. The Claude drawer screenshot `Screenshot_20261104_090907_Claude.jpg`
governs the larger serif wordmark and bottom white New chat action. Search,
archive, session actions and phone/PC tools remain functional in their owners.

Canonical owners are `ChatGptMainChatScreen`, `ChatGptExactNavigationDrawer`, and
the shared sheets in `ui/components/Round5PanelsAndSheets.kt`. Extend those owners
instead of creating parallel versions of the same interaction.

The populated `ConversationHeader` follows ChatGPT recording f_0043: a back
circle, a title/location capsule and a shared New chat/menu capsule. Location is
the real execution target; the model remains reachable from the title/menu.
Extensions select Connections/Skills/Plugins through the centered header menu
(ChatGPT 091619); refresh is in that menu, rather than an extra row of wrapping tabs.
Attachment tiles use natural content height with a 112dp minimum, never
`fillMaxSize()` inside an unconstrained sheet row. All lower actions must fit.
The native voice surface is transparent above the Compose voice background;
successful shader linking alone is not readiness. Keep the Canvas fallback until
a nonblack rendered frame, and fall back on failure or an 8-second startup deadline.

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

For the current visual pass, use the identified original ChatGPT and Claude live
screenshots together with the supplied historical Claude Code remote-control
screens. APK resources supplement implementation evidence; they do not prove
rendered fidelity. Keep existing phone/PC features, and distinguish observations
from assumptions. The earlier ChatGPT-only comparison scope is superseded here.

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

## Populated conversation and voice verification — 2026-11-04

Empty-home screenshots do not validate a conversation. ConversationHeader has an
explicit populated variant: title, execution location/model, new chat and menu.
The empty variant retains Chat/Work. ConversationActivity owns compact reasoning
and tool rows and their Summary sheet; approval actions remain visible outside it.
No synthetic reasoning steps, unconditional green success cards or per-token
vibration. ProviderReasoning owns Gemini thought/answer separation and cumulative
phone summaries. ChatSessionStore persists the actual supplied summary.

MarkdownRenderer owns answer typography: normal prose 18/28 sp, code 13/20 sp,
headings 18–25 sp, scaled by the existing reading preference. User prompts are
literal sans text, 16/24 sp, with a left gutter and a right-aligned neutral bubble.
The response-font preference applies to answers and reasoning summaries.

OrbitVoiceOverlay follows the supplied ChatGPT 091536 screenshot: black canvas,
responsive renderer canvas up to 330 dp, compact lower text composer, mute and white close
button. VoiceConversationSettingsSheet owns the grouped voice/language/audio panel;
captions and visualization choices are secondary controls under More. The measured
sheet boundary constrains the voice column so the sphere stays above the panel.
SheetHeader owns the optional trailing slot without moving the centered title.
Captions are opt-in; transient reconnect failures
remain visible. Text, attachment and mute buttons invoke existing real handlers.
The default GLES3 visualization uses the supplied voice_prepass/voice_main assets;
the existing Canvas is a fallback after shader failure or without GLES3 support.
Renderer frame requests are limited to about 30 fps while the lifecycle is resumed.
Disposed surfaces cannot deliver readiness callbacks into a later call. A device
EGL test exercises both shader passes and checks visible center/transparent exterior.
VoiceOrbMotion owns elapsed-time state transitions using zr31.c's atan/sin curve
and normal 0.65 s duration. Interrupted transitions start from the current weights;
listen/user-speaking do not restart the same visual channel. Recreating the EGL
surface resets the motion timeline and audio integrals, and reports initializing
separately from shader failure so the Canvas fallback covers context restoration.
The overlay's initialization timeout follows the same resumed lifecycle as GPU
frame requests; time spent in the background cannot permanently disable the orb.
Sheet and Activity geometry use screen coordinates, since window origins can differ
after resume. Unplaced sheet measurements are ignored; the square orb fits both
available axes instead of overflowing a body with zero/limited height.
The vertex loader computes equivalent fullscreen coordinates to avoid the reproduced
SwiftShader constant-array failure; raw APK resources remain intact. Shader colors
use fw9.e defaults with the Main/Low/Mid/High mapping established in vml0.java.
See REFERENCE_CONVERSATION_RECHECK.md for item-level evidence and limitations.

ReferencePageHeader owns centered 22 sp serif titles for Settings, Projects and
Library. These screens share Bg/Surface tokens. Root Settings uses the drawer
navigation icon and a simple profile row; separators span the group width.
Library document actions live in an overflow menu, with at most export and menu
in the header. Extension lists follow the mobile ChatGPT 091805 hierarchy: grouped
name/status rows with a chevron; controls remain in detail views. No promotional
hero artwork is added to installed extensions or the catalog.
Inline code uses 84% of prose size, 6 dp horizontal and 2 dp vertical total
padding, and Surface background. Attachment tiles use neutral surfaces, 48+ dp
icon targets and short labels Camera/Photos/Files in the active Russian locale.
Permission descriptions wrap instead of hiding the capability name behind ellipses.
