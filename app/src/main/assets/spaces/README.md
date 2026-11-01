# Page visualization assets

These files are copied from `codex/codex-apps/bundled-plugins/visualize/skills/visualize/assets`
in the monorepo. They provide the shared Page styles, tooltips, and icon initialization used by the web and iOS hosts.

`visualize.html` accepts a Page fragment at `<!--__INLINE_VISUALIZATION_FRAGMENT__-->`.
The Android host supplies `visualizationTheme` and `visualizationStyleVariables` through the `openai` global,
following the iOS `CodexRemoteInlineVisualizationView` contract.

The native host loads these bundled files and isolates authored HTML in an opaque iframe.
Updating these copies is separate from enabling remote scripts or broadening the host's resource policy.
