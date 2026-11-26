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
# CJAP Shell/UI implementation manual

This manual explains the WindowSystem, AppShell, docking, window, and chrome behavior implemented on
`feat/cjap-shell-ui`. It is written for product developers, module contributors, shell maintainers, and
test authors who need to use or extend the nbfx application framework without importing platform
implementation types.

**Documentation baseline:** commit `f2e43e8b58` (`C3-2r: persist remembered custom window bounds`).
The manual describes that committed branch state. Later or uncommitted work is outside its baseline.

The existing [CJAP target reference](../../reference/cjap-shell-ui.md) describes the intended end state.
This manual describes what the branch actually does. Where the two differ, the difference is called out
explicitly rather than presenting a planned API as available.

## Status legend

| Label | Meaning |
|---|---|
| **Implemented** | The behavior exists in this branch and has direct implementation/test coverage. |
| **Partial** | A usable subset exists, but the complete CJAP contract is not implemented. |
| **Legacy** | Transitional behavior remains available for existing consumers. |
| **Target only** | Described by the ADR/reference but not available in this baseline. |

## Read this first

- New products should normally use the neutral default shell or provide a `ShellProvider`; they should
  not subclass `ShellApplication` merely to obtain standard chrome.
- `WindowSystem` orchestrates lifecycle and legacy view-area/geometry state. It does not yet snapshot a
  complete `SessionState`.
- `WindowManager` is the public docking/window operation surface. Its requests use `ContentId` and
  `DockGroupId`, not tabs or pane implementation objects.
- The pure `LayoutSpec` API exists, but the live dock is still assembled from the legacy window
  composition/layer model.
- Declarative profiles select the **main window's** chrome before frame creation. Floating and custom
  windows do not yet build profile-driven shell frames.
- A custom `ShellFrame` must use the runtime `ShellRegionHost` and mount the exact runtime DOCK root.
- The visual shell OVERLAY region is not the target anchored `OverlayHost`/HUD model.
- JavaFX scene and window mutations belong on the JavaFX Application Thread unless a chapter explicitly
  documents a headless/persistence exception.

## Quick navigation by task

| Task | Start here |
|---|---|
| Understand the architecture | [Architecture overview](02-architecture-overview.md) |
| Start or stop an application | [AppShell startup](04-appshell-startup.md), [shutdown](05-appshell-shutdown.md) |
| Drive window lifecycle or reset layout | [WindowSystem lifecycle](06-window-system-lifecycle.md) |
| Understand saved state | [Window state and persistence](07-window-state-and-persistence.md) |
| Move, split, float, or redock content | [WindowManager](09-window-manager-and-identities.md), [docking operations](11-docking-operations-and-pane-behavior.md) |
| Create a custom window | [Main, custom, and multiwindow handles](13-main-custom-and-multiwindow-handles.md) |
| Provide a custom shell | [ShellProvider and ShellFrame](14-shell-provider-and-frame-contract.md) |
| Add chrome contributions | [Shell regions](15-shell-regions-and-contributions.md), [profiles](16-chrome-profiles.md) |
| Add menus/toolbars/actions | [Actions and surfaces](17-actions-menus-toolbars-and-ribbons.md) |
| Register declarative metadata | [Annotations and layer schema](24-annotations-and-layer-schema.md) |
| Test a change | [Testing, build, and conformance](26-testing-build-and-conformance.md) |
| Diagnose a failure | [Errors and troubleshooting](25-errors-and-troubleshooting.md) |
| Plan migration to the target | [Current-to-target migration](27-current-to-target-migration.md) |

## Table of contents

### Part I — Orientation and architecture

1. [Scope and current status](01-scope-and-current-status.md)
2. [Architecture overview](02-architecture-overview.md)
3. [Module map and dependencies](03-module-map-and-dependencies.md)

### Part II — AppShell and WindowSystem lifecycle

4. [AppShell startup](04-appshell-startup.md)
5. [AppShell shutdown](05-appshell-shutdown.md)
6. [WindowSystem lifecycle](06-window-system-lifecycle.md)
7. [Window state and persistence](07-window-state-and-persistence.md)

### Part III — Layout, docking, and windows

8. [Layout and window composition](08-layout-and-window-composition.md)
9. [WindowManager and identities](09-window-manager-and-identities.md)
10. [Docking requests and policies](10-docking-requests-and-policies.md)
11. [Docking operations and pane behavior](11-docking-operations-and-pane-behavior.md)
12. [Floating and redocking](12-floating-and-redocking.md)
13. [Main, custom, and multiwindow handles](13-main-custom-and-multiwindow-handles.md)

### Part IV — ShellProvider and Chrome

14. [ShellProvider and frame contract](14-shell-provider-and-frame-contract.md)
15. [Shell regions and contributions](15-shell-regions-and-contributions.md)
16. [Chrome profiles](16-chrome-profiles.md)
17. [Actions, menus, toolbars, and ribbons](17-actions-menus-toolbars-and-ribbons.md)
18. [Branding, theming, status, and overlays](18-branding-theming-status-and-overlays.md)

### Part V — Content, interaction, and integration

19. [Content, view, and editor lifecycle](19-content-view-and-editor-lifecycle.md)
20. [Drag and drop](20-drag-and-drop.md)
21. [Threading, focus, and accessibility](21-threading-focus-and-accessibility.md)
22. [Product integration recipes](22-product-integration-recipes.md)

### Part VI — Practical guides and reference

23. [Public API catalog](23-public-api-catalog.md)
24. [Annotations and layer schema](24-annotations-and-layer-schema.md)
25. [Errors and troubleshooting](25-errors-and-troubleshooting.md)
26. [Testing, build, and conformance](26-testing-build-and-conformance.md)
27. [Current-to-target migration](27-current-to-target-migration.md)
28. [Glossary and source index](28-glossary-and-source-index.md)

## Documentation conventions

Every chapter aims to provide:

1. a plain-language behavior summary;
2. current public API signatures;
3. a step-by-step runtime sequence;
4. Mermaid or ASCII diagrams for significant behavior;
5. defaults, invariants, ownership, and disposal rules;
6. error cases and rejection reasons;
7. threading and accessibility notes;
8. a copyable example where applicable;
9. links to implementing source files and tests; and
10. a **Current vs target** note.

All diagrams are source-controlled text. No screenshot is required to understand a lifecycle or state
transition.

## Architectural context

- [ADR 0001 — CJAP shell and UI architecture](../../architecture/adr-0001-cjap-shell-ui.md)
- [CJAP target reference](../../reference/cjap-shell-ui.md)
- [CJAP shell/UI implementation plan](../../plan/cjap-shell-ui-implementation.md)
- [Repository and build guide](../../repo-and-build.md)
