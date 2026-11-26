<!--

    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.

-->
# 1. Scope and current status

## What this manual describes

This manual describes the CJAP shell/UI code at documentation baseline commit
`f2e43e8b58` (`C3-2r: persist remembered custom window bounds`). It covers the implemented
application shell, `WindowSystem`, docking/window handles, shell frames and chrome, content
lifecycle, and the persistence paths that actually run at this baseline.

The governing sources are:

- the [ADR 0001 architecture decision](../../architecture/adr-0001-cjap-shell-ui.md);
- the [CJAP target reference](../../reference/cjap-shell-ui.md);
- the [implementation plan](../../plan/cjap-shell-ui-implementation.md); and
- the linked Java sources and tests in this repository.

The ADR and target reference describe the intended end state. They are **not** evidence that an
API exists. Where source and target differ, this manual says so explicitly. Later branches and
uncommitted work are outside this baseline.

## Status vocabulary

| Label | Meaning in this manual |
|---|---|
| **Implemented** | The behavior exists at this baseline. A direct implementation and relevant test evidence are linked where available. |
| **Partial** | A usable subset exists, but the complete CJAP contract is not present or not wired into the running shell. |
| **Legacy** | Transitional behavior remains for existing consumers; it is not the target ownership/API model. |
| **Target only** | The ADR/reference describes it, but the baseline has no usable implementation. |

“Implemented” does not mean “complete CJAP conformance.” In particular, an implemented
`WindowSystem` lifecycle can coexist with a partial shell integration.

## The shortest accurate description of the baseline

1. **`ShellApplication` is the composition root.** It owns the primary `Stage`, frame or legacy
   chrome, a per-shell `DefaultContentManager`, a `DefaultWindowManager`, `AppState`, and a
   `SessionRestorer`. There is no `ShellRuntime` class yet.
2. **The live dock is still the legacy composition model.** `WindowCompositionResolver`,
   `WindowPaneRegistry`, `WindowLayoutRegistry`, `DockTree`, `NbfxTabPane`, and `SlidingArea` build
   the running UI. The pure `api.layout` `LayoutSpec` model exists, but startup does not construct
   the live dock from it.
3. **`WindowSystem` is real and phase-guarded.** Its phases are `IDLE`, `INIT`, `LOADED`, and
   `SHOWN`; it merges view defaults with persisted `WindowState` and calls a `WindowSink`.
4. **The shell is only a partial `WindowSink` adapter.** `ShellApplication.apply(WindowState)`
   applies main-stage geometry/maximization, not area/tab placement. Its `snapshot()` returns
   empty area/view maps. Actual pane/tab layout capture and restoration run separately through
   `SessionRestorer` and `AppState`.
5. **Two different persistence tracks coexist.** The window-system track stores one
   `WindowState`; the shell/session track stores a richer legacy pane/tab layout, toolbar state,
   editor settings, project-tree state hooks, and custom-window bounds. They are not a single
   `SessionState` transaction.
6. **`ContentStateStore` is standalone.** It correctly stores opaque per-content payloads, but no
   shell startup/shutdown path calls it.
7. **Main-window chrome profiles are implemented.** Profile selection happens before frame
   creation. Floating/custom windows do not yet get profile-driven shell frames or complete
   session restoration.
8. **Shutdown has two paths.** Normal close requests confirm and save before coordinator cleanup;
   JavaFX `stop()` is the fallback and also captures/saves before disposing shell resources.

## Current status by area

| Area | Status | Baseline reality |
|---|---|---|
| Product metadata and chrome-profile policy | **Implemented** | `ProductProvider`/`Product` and policy resolution exist; the main shell resolves a profile deterministically. |
| Pure layout model, builder, XML, registrations | **Implemented** | `api.layout` contains the CJAP value model and authoring APIs. It is not the live dock's startup schema. |
| Default/custom `ShellFrame` and region host | **Implemented** | The default frame and an API-only custom frame use the runtime host and exact runtime DOCK root. |
| Main-window profile selection/publication | **Implemented** | Explicit profile, hint, product default, policy fallback, then neutral fallback; decoration is applied before show. |
| Floating/custom per-window shell chrome | **Partial** | `WindowOptions.profile` is carried and some stage options are applied, but there is no profile-driven frame/region host per floating/custom window. |
| Instance-scoped `DockRuntime` | **Implemented** | A shell creates an instance with its own `DockArea` and `SlidingArea`. |
| Static `Docking` facade | **Legacy** | It delegates to the most recently registered runtime and remains used by `SessionRestorer`, `DefaultWindowManager`, and other shell code. |
| `WindowSystem` lifecycle/default merge | **Implemented** | Phase guards, listeners, defaults/persisted merge, pruning, save, and reset are covered by `FxWindowSystemTest`. |
| `WindowSystem` → live pane/tab integration | **Partial** | The running shell's sink ignores area/tab data and snapshots no area/tab data. |
| Legacy `AppState`/`SessionRestorer` | **Legacy** | This is the functioning pane/tab/session-like capture and restore path, including detached panes and editor view state. |
| Main geometry/profile persistence | **Partial** | Geometry and profile id round-trip through workspace `ViewStateStore`; the selected profile is not read back before frame creation. |
| Remembered custom-window bounds | **Implemented** | `WindowOptions.rememberBounds()` uses `AppState`; bounds save on hide/close and restore by stable `WindowId`. |
| Generic content manager | **Partial** | The per-shell manager owns open/close/dispose, but keys only by `ContentId`; a second instance with the same id is ignored. |
| Standalone `ContentStateStore` | **Implemented** | Opaque payload round-trip, per-instance keys, replacement, removal, and clear are tested. |
| Content-state integration with session restore | **Partial** | The store is not connected to shell capture, shutdown, content factories, or layout restoration. |
| `SessionState` and `WindowStateStore` | **Target only** | Neither public type exists. `WindowSystem` has no public `snapshot()` or `defaultState()`. |
| Coordinated shutdown | **Partial** | Confirmation, ordered participants, asynchronous cleanup, timeout, and `stop()` fallback exist; no direct shutdown unit/integration test was found. |
| Focus controller and multi-window close policy | **Target only** | The baseline has focus bridges and window handles, not the target `FocusController` or explicit `REDOCK`/`CLOSE_CONTENT`/`HIDE` policy model. |
| Anchored HUD `OverlayHost` | **Target only** | The visual shell `OVERLAY` region exists, but it is not the target anchored overlay model. |
| Target theme/design/plugin module split | **Target only** | Current theme and shell implementation modules do not match the target `api.theme`, `platform.design`, `api.plugins`, and `platform.shell.default` map. |

## Names that must not be conflated

| Name | Current meaning | Target distinction |
|---|---|---|
| `org.netbeans.api.nbfx.shell.PaneRole` | Legacy permanent/docked/detached/slide pane identity used by the live shell. | Target `PaneRole` is a logical-area identity in `api.layout`; runtime states are not roles. |
| `org.netbeans.api.nbfx.layout.PaneRole` | Pure CJAP logical-area identity. | It exists, but the live shell imports the shell package's role type. |
| `org.netbeans.api.nbfx.views.ViewArea` | Lightweight live area with `id`, name, location, and weight. | Target `ViewArea` is the richer record in `api.layout`. |
| `WindowState` | One current record: areas/view ids, main geometry, view payload map, profile id. | Target adds groups/tree/content order/decoration and is one window in a `SessionState`. |
| `WindowContentState` | A `WindowContent.saveState()` snapshot with window id, layout, and payload. | It is not `api.content.ContentState` and is not automatically persisted. |
| `ContentState` | `ContentId`, instance key, and opaque payload in `api.content`. | It is the input to the standalone `ContentStateStore`. |
| `platform.workspace.ViewStateStore` | Preferences-backed store for `WindowSystem`'s `WindowState`. | It is not the target `WindowStateStore`; there is no `SessionState`. |
| `platform.shell.ViewStateStore` | SPI for per-project navigator expansion/selection; default is `NONE`. | Unrelated to the workspace class despite the simple name. |
| `DockRuntime.saveLayout()` | Instance method whose body is intentionally empty. | Target persistence belongs to a real window-state store in a later slice. |
| `WindowSink.snapshot()` | Sink operation used by `WindowSystem.save()`. | It does not create a public `WindowSystem.snapshot()` API. |

## Reading paths

- **Part I:** [architecture overview](02-architecture-overview.md) and
  [module map/dependencies](03-module-map-and-dependencies.md).
- **Part II:** [startup](04-appshell-startup.md), [shutdown](05-appshell-shutdown.md),
  [WindowSystem lifecycle](06-window-system-lifecycle.md), and
  [state/persistence](07-window-state-and-persistence.md).
- Later chapters cover layout/docking/windows, shell providers/chrome, content/interaction, and
  practical migration guidance. This chapter does not assume those unread chapters.

## Primary evidence

- [Shell composition root](../../../nbfx-platform/nbfx.platform.shell/src/org/netbeans/modules/nbfx/platform/shell/ShellApplication.java)
- [WindowSystem contract](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/WindowSystem.java)
- [WindowSystem implementation](../../../nbfx-platform/nbfx.platform.workspace/src/org/netbeans/modules/nbfx/platform/workspace/DefaultWindowSystem.java)
- [WindowSystem tests](../../../nbfx-platform/nbfx.platform.workspace/test/unit/src/org/netbeans/modules/nbfx/platform/workspace/FxWindowSystemTest.java)
- [AppShell conformance tests](../../../nbfx-platform/nbfx.platform.shell/test/unit/src/org/netbeans/modules/nbfx/platform/shell/AppShellConformanceTest.java)
- [Custom shell/rollback tests](../../../nbfx-platform/nbfx.platform.shell/test/unit/src/org/netbeans/modules/nbfx/platform/shell/CustomShellProviderTest.java)
- [Remembered custom bounds tests](../../../nbfx-platform/nbfx.platform.shell/test/unit/src/org/netbeans/modules/nbfx/platform/shell/CustomWindowTest.java)
- [ContentStateStore tests](../../../nbfx-platform/nbfx.platform.content/test/unit/src/org/netbeans/modules/nbfx/platform/content/ContentStateStoreTest.java)

## Current versus target

The target is one application-scoped runtime owning one dock runtime, window system, content
manager, view manager, theme manager, and focus controller; `WindowStateStore` owns a complete
`SessionState`, while `ContentStateStore` owns only content payloads. The baseline has many of the
individual pieces, but not that unified runtime or persistence transaction. Use the current APIs
above when coding against this baseline; use the ADR/reference only when planning migration.
