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
# 23. Public API catalog

**Baseline:** `f2e43e8b58` on `docs/cjap-shell-ui-manual`.
**Scope:** the public contracts actually present in this checkout, not the complete CJAP target.

## 23.1 How to read this catalog

The labels have the same meaning as in the [manual introduction](README.md):

- **Implemented** — present with direct implementation and test coverage.
- **Partial** — usable, but narrower than the CJAP target.
- **Legacy** — still public and used during migration.
- **Target only** — described by the ADR/reference but absent here.

Unless a row says otherwise:

- JavaFX scene/window mutation and Node creation happen on the JavaFX Application Thread (FX thread).
- Immutable records and pure resolvers may be used from any thread.
- A method documented as returning a list/snapshot returns an immutable list unless its own contract says otherwise.
- “Nullable” means the implementation actually accepts or returns `null`; it is not an invitation to invent a fallback.

Two package distinctions matter throughout:

| Concept | Baseline package | Target package |
|---|---|---|
| Logical layout area identity | `org.netbeans.api.nbfx.layout.PaneRole` | same |
| Legacy physical pane role | `org.netbeans.api.nbfx.shell.PaneRole` | removed/replaced |
| Docking request/decision/policy | `org.netbeans.api.nbfx.shell` | `api.docking` |
| Neutral placement/mode | `org.netbeans.api.nbfx.content` | same |
| Theme API | token types in `platform.theme`; static `NbfxTheme` | `api.theme` |

See [current-to-target migration](27-current-to-target-migration.md) for the package moves.

## 23.2 Shell composition: application, provider, frame, context, window

### `ShellProvider` — Implemented

```java
@FunctionalInterface
public interface ShellProvider {
    ShellFrame create(ShellContext context, ShellChromeProfile profile);
}
```

- Called on the FX thread after runtime services and the selected profile are in `ShellContext.lookup()`.
- Zero providers select the neutral default frame; one selects the product frame; more than one fails before frame creation.
- The returned frame must be nonnull.
- There is no priority, superseding, or class-name selection.
- Source: [`ShellProvider.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/ShellProvider.java).

### `ShellFrame` — Implemented, but narrower than target

```java
public interface ShellFrame extends AutoCloseable {
    Node root();
    void setDockRoot(Node dockRoot);
    ShellRegionHost regions();
    @Override void close();
}
```

| Member | Contract and ownership |
|---|---|
| `root()` | Stable, nonnull frame root. The shell places it in the main scene. A provider must return an unparented root. |
| `setDockRoot(Node)` | Mounts the exact runtime-owned dock root. It does not transfer dock ownership to the frame. FX thread only. |
| `regions()` | Must return the exact runtime `ShellRegionHost` published in the context, not a replacement host. |
| `close()` | Releases frame-specific resources. The neutral frame is idempotent. The shell closes the frame during startup rollback and shutdown. |

**Absent:** `ShellFrame.overlays()`. It is deliberately deferred until `OverlayHost` exists in `api.content`.

Source: [`ShellFrame.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/ShellFrame.java), [`DefaultShellFrame.java`](../../../nbfx-platform/nbfx.platform.shell/src/org/netbeans/modules/nbfx/platform/shell/DefaultShellFrame.java).

### `ShellContext` — Implemented, Legacy shape

```java
public record ShellContext(Lookup lookup) {
    public ActionRegistry actions();
    public ActionContext actionContext();
    public ShellWindow window();
    public ShellChromeProfile profile();
    public Command command(String id);
    @Deprecated public Command command(String id, ObservableValue<Boolean> preferFile);
    public javafx.scene.Node icon(Command command, double size);
}
```

- `lookup` is required and nonnull.
- `actions()`, `actionContext()`, and `window()` return `null` when absent. `window()` is normally absent before shell startup.
- `profile()` returns the lookup profile or `ShellChromeProfile.ALL`.
- `command(id)` consults `ShellCommandResolver` first, then `ActionRegistry.find(id)`, and returns `null` when unresolved.
- The two-argument `command` overload ignores `preferFile` and delegates to `command(id)`; it is deprecated.
- `icon(...)` returns a new Node or `null`. A command's own icon wins over `ShellIconProvider`.
- The target reference's multi-service `ShellContext(Product, ShellWindow, WindowManager, WindowSystem, ContentManager, ViewManager, ActionRegistry, ActionContext, ThemeManager, Lookup)` **does not exist** in this baseline.

Source: [`ShellContext.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/ShellContext.java).

### `ShellWindow` — Legacy

```java
public interface ShellWindow {
    Stage stage();
    BorderPane root();
    TabPane mainPane();
    Node slidingArea();
    WindowManager windowManager();
}
```

This is the stable facade currently offered, but it still exposes JavaFX `Stage`, `BorderPane`, and `TabPane`. It does **not** expose the target `Window window()`, `Parent root()`, or `WindowSystem windowSystem()` members.

Source: [`ShellWindow.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/ShellWindow.java).

### `ShellWindowProvider` — Implemented, process-wide limitation

```java
public interface ShellWindowProvider {
    ShellWindow window();
}
```

The runtime publishes one window through global lookup while the shell runs and clears it on stop. It returns `null` before start and after stop. This reflects the current one-shell-per-JVM model, not the target per-application `ShellRuntime` model.

Source: [`ShellWindowProvider.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/ShellWindowProvider.java).

### `ShellApplication` — Legacy composition root

`ShellApplication` is an implementation class, not a product API to subclass. Its `start(Stage)` is final. New products use `ShellProvider` or the neutral frame. Existing subclasses retain the legacy hooks `prepare`, pane creation, `decorate`, `sceneReady`, `started`, `restoreSession`, `captureSession`, and `stopping` until consumer migration.

The class owns a `DockRuntime`, but there is no public `ShellRuntime` type. Docking/tab registries and the static `Docking` facade remain process-wide.

Source: [`ShellApplication.java`](../../../nbfx-platform/nbfx.platform.shell/src/org/netbeans/modules/nbfx/platform/shell/ShellApplication.java).

## 23.3 Shell regions and contributions

### Region identity and kind — Implemented

```java
public record ShellRegionId(String namespace, String name) {
    // BRAND, MENUBAR, TOOLBAR, RIBBON, DOCK, STATUS, OVERLAY
    public static ShellRegionId of(String value);
    public String value();
}

public record ShellRegionKind(String id) {
    // BRAND, MENUBAR, TOOLBAR, RIBBON, DOCK, STATUS, OVERLAY
    public static ShellRegionKind of(String value);
    public String value();
}
```

Built-ins accept case-insensitive short and canonical forms. Product ids/kinds require `namespace:name`; namespace and name match `[A-Za-z0-9][A-Za-z0-9._-]*`.

### `ShellRegionSpec` — Implemented

```java
public record ShellRegionSpec(
        ShellRegionId id,
        ShellRegionKind kind,
        int position,
        Set<String> profiles,
        AreaStyle style,
        boolean visibleByDefault)
```

- `id`, `kind`, `profiles`, and `style` are nonnull.
- Profiles are copied and must be nonblank atomic tokens: no whitespace, ISO control, or comma.
- Empty `profiles` means all profiles.
- `visibleByDefault` is independent of profile applicability.

### `ShellRegionRegistry` — Implemented

```java
public static List<ShellRegionSpec> discover();
public static Optional<ShellRegionSpec> find(ShellRegionId id);
public static List<ShellRegionSpec> forProfile(String profileId);
```

Discovery reads direct child folders under `NbFx/ShellRegions`, orders by position then canonical id, logs and skips malformed entries, and keeps the first duplicate canonical id. `find(null)` throws; `forProfile(null/blank)` means all profiles. Profile ids match exact, case-sensitive tokens.

### `ShellRegionHost` — Implemented

```java
public interface ShellRegionHost {
    List<ShellRegionSpec> regions(ShellChromeProfile profile);
    Node mount(ShellRegionSpec region, ShellRegionContext context);
    void update(ShellRegionSpec region, Node mounted);
    void dispose(ShellRegionSpec region, Node mounted);
}
```

- `regions(profile)` only reads/filters metadata and has no FX-thread check. `mount`, `update`, `dispose`, and `close` enforce the FX thread.
- `regions` intersects region metadata with `profile.includeRegion(...)`.
- `mount` returns a stable wrapper Node. Updates replace contents, not wrapper identity.
- A region whose **kind** is `DOCK` mounts the exact runtime dock root, regardless of its id. It never loads ordinary contributions.
- `dispose` is idempotent for an already-disposed mount.
- The host owns mounted contribution/renderer/scoped-command disposal.

### `ShellRegionContext` and `ShellRegionRenderer` — Implemented

```java
public record ShellRegionContext(ShellContext shell) {
    public ShellChromeProfile profile();
}

public interface ShellRegionRenderer {
    ShellRegionKind kind();
    Node render(ShellRegionSpec region, ShellRegionContext context);
    default void dispose(ShellRegionSpec region, Node mounted);
}
```

`shell` is nonnull. Renderers create fresh unparented Nodes on the FX thread. The neutral frame currently supplies menu-bar, toolbar, and status renderers; brand/ribbon/overlay can receive ordinary contributions, but the full target renderer set is not present.

### `ShellContribution` — Implemented

```java
public interface ShellContribution {
    Node create(ShellContext context);
    default void dispose();
}
```

Each mount receives a fresh instance and Node. `create` must return a fresh, unparented Node on the FX thread. Ownership transfers to the shell when the Node is returned. Hiding/detaching does not imply disposal; the host calls `dispose()` when releasing the mount.

### Contribution metadata and registry — Implemented

```java
public record ShellContributionSpec(
        String id, ShellRegionId region, int position,
        Set<String> profiles, Class<? extends ShellContribution> type) {}

public record ShellContributionEntry(ShellContributionSpec spec, ShellContribution contribution) {}

public final class ShellContributionRegistry {
    public static List<ShellContributionSpec> discover();
    public static List<ShellContributionSpec> discover(ShellRegionId region, String profileId);
    public static List<ShellContributionEntry> load(ShellRegionId region, String profileId);
}
```

Discovery does not construct classes or Nodes. `load` filters before construction and returns fresh instances; the caller owns disposal. Direct `.instance` children only are read. `DOCK` is skipped at discovery and rejected by the annotation processor.

Sources: [`ShellRegionSpec.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/ShellRegionSpec.java), [`ShellRegionRegistry.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/ShellRegionRegistry.java), [`ShellRegionHost.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/ShellRegionHost.java), [`ShellContributionRegistry.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/ShellContributionRegistry.java), [`ShellRegionHostImpl.java`](../../../nbfx-platform/nbfx.platform.shell/src/org/netbeans/modules/nbfx/platform/shell/ShellRegionHostImpl.java).

## 23.4 Chrome profiles

### `ShellChromeProfile` — Implemented with Legacy defaults

```java
public interface ShellChromeProfile {
    ShellChromeProfile ALL = ...;
    boolean includeMenu(String menuId);
    boolean includeMenuItem(String menuId, String itemId);
    boolean includeToolBar(String toolBarId);
    default boolean includeToolBarItem(String toolBarId, String itemId) { return true; }
    default boolean movableToolBars() { return true; }
    default boolean useSystemMenuBar() { return true; }
    default String id() { return "main"; }
    default boolean includeRegion(ShellRegionId regionId) { return true; }
    static ShellChromeProfile all() { return ALL; }
}
```

Legacy predicate profiles default to id `main` and remain inclusive for regions/toolbar items.

### `ShellProfileSpec` — Implemented

```java
public record ShellProfileSpec(
        String id,
        WindowKind windowKind,
        Set<ShellRegionId> regions,
        WindowDecoration decoration,
        boolean systemMenuBar) implements ShellChromeProfile
```

- `id` is a nonblank atomic token; `regions` is nonnull and defensively copied.
- `windowKind` and `decoration` are nullable.
- Empty `regions` means no profile-level restriction.
- Menu and toolbar predicates remain inclusive; `useSystemMenuBar()` returns the record field.

### Hint, registry, policy — Implemented

```java
public record ShellProfileHint(String profileId) {}

public final class ShellProfileRegistry {
    public static List<ShellProfileSpec> discover();
    public static Optional<ShellProfileSpec> find(String id);
}

public record ChromeProfilePolicy(
        String id, Map<WindowKind, String> defaultProfiles, String fallbackProfile) {}

@FunctionalInterface
public interface ChromeProfilePolicyProvider {
    ChromeProfilePolicy policy();
    static Optional<ChromeProfilePolicy> resolve(String policyId, Lookup lookup);
}
```

- `ShellProfileHint.profileId` is required and atomic.
- `ShellProfileRegistry.find(null/blank)` returns empty. Malformed folders are logged/skipped; valid profiles are sorted by id.
- `ChromeProfilePolicy` copies its map; keys and profile ids are nonnull, ids/fallback are required atomic tokens.
- `ChromeProfilePolicyProvider.resolve` requires a nonblank policy id and nonnull lookup. Zero matching providers returns empty; null providers/results or multiple matches throw `IllegalStateException`.

### `ShellProfileResolver` and selection — Implemented, main-window integration only

```java
public static ShellProfileSelection resolve(
        WindowOptions options, ShellProfileHint hint,
        ChromeProfilePolicy policy, List<ShellProfileSpec> profiles);

public static ShellProfileSelection resolve(
        WindowKind kind, ShellProfileHint hint,
        ChromeProfilePolicy policy, List<ShellProfileSpec> profiles);

public static ShellProfileSelection resolve(
        WindowKind kind, ShellChromeProfile explicitProfile, ShellProfileHint hint,
        ChromeProfilePolicy policy, List<ShellProfileSpec> profiles);

public record ShellProfileSelection(ShellChromeProfile profile, WindowDecoration decoration) {}
```

Selection precedence is explicit profile, hint, product default for the kind, policy fallback, then neutral fallback (`main`, `floating-editor`, or `presentation`). Unknown hint/policy ids fall through. Duplicate declared ids always throw `IllegalArgumentException`; a needed but missing neutral profile throws `IllegalStateException`. Effective decoration is options override, then `ShellProfileSpec.decoration`, then `NATIVE`.

The runtime currently applies this selection to the **main window before frame creation**. Floating/custom windows do not yet construct profile-driven frames.

Sources: [`ShellChromeProfile.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/ShellChromeProfile.java), [`ShellProfileSpec.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/ShellProfileSpec.java), [`ShellProfileResolver.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/ShellProfileResolver.java), [`ShellProfileRegistry.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/ShellProfileRegistry.java), [`ChromeProfilePolicy.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/ChromeProfilePolicy.java), [`ChromeProfilePolicyProvider.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/ChromeProfilePolicyProvider.java).

## 23.5 WindowSystem, sink, state, and listeners

### `WindowSystem` — Implemented

```java
public interface WindowSystem {
    void setSink(WindowSink sink);
    void init();
    void load();
    void show();
    void hide();
    void save();
    void resetLayout();
    void addWindowSystemListener(WindowSystemListener listener);
    void removeWindowSystemListener(WindowSystemListener listener);
}
```

The interface requires FX-thread use. The implementation is synchronized and pure with respect to JavaFX; tests can drive it headlessly through a fake sink.

| Phase | Legal operations |
|---|---|
| `IDLE` | `setSink`, listener registration, `init` |
| `INIT` | `load`; `hide` is a no-op |
| `LOADED` | `show`, `hide`, `save`, `resetLayout` |
| `SHOWN` | `hide`, `save`, `resetLayout`; `show` cannot be repeated |

`init` discovers view registrations once and adds service-provided listeners. `load` merges persisted state over layer defaults. `resetLayout` clears the window-state store and reapplies defaults; it does not emit load/save listener callbacks.

### `WindowSink` — Implemented

```java
public interface WindowSink {
    void apply(WindowState state);
    WindowState snapshot();
    void show();
    void hide();
}
```

The running shell implements this seam. `apply` receives merged defaults/persisted state. The interface intent is for `snapshot()` to reflect visible tabs, active tab, geometry, and per-view payloads, but the actual `ShellApplication` sink currently returns no areas or `viewState`: only main-window geometry, maximized state, and profile id. A no-op sink is used before registration or in headless use.

### `WindowSystemListener` — Implemented

```java
public interface WindowSystemListener {
    default void loadStarted() {}
    default void loadCompleted() {}
    default void saveStarted() {}
    default void saveCompleted() {}
}
```

Callbacks are synchronous on the caller's thread. During load, defaults and persisted state are resolved and merged first; `loadStarted` then runs immediately before `sink.apply`, and `loadCompleted` follows successful application. Save callbacks surround snapshot/store. A thrown callback or sink/store exception prevents the corresponding completion callback.

### `WindowState` — Implemented, Legacy schema

```java
public record WindowState(
        List<AreaState> areas,
        double x, double y, double width, double height,
        boolean maximized,
        Map<String, String> viewState,
        String profileId) {

    public WindowState(List<AreaState> areas, double x, double y,
            double width, double height, boolean maximized,
            Map<String, String> viewState); // profileId = null

    public static WindowState empty();
}
```

- Collections are defensively copied and immutable.
- `profileId` is nullable; nonnull values must be atomic tokens.
- `empty()` uses `NaN` geometry and empty collections.
- This is currently **whole-main-window/view-system state**, not the target one-window `WindowState` containing groups/content/geometry/profile.

### `AreaState` — Implemented, Legacy schema

```java
public record AreaState(
        String areaId, List<String> viewIds, String activeViewId,
        double x, double y, double width, double height, boolean detached) {

    public static AreaState docked(String areaId, List<String> viewIds, String activeViewId);
}
```

`areaId` and `viewIds` are nonnull; `activeViewId` is nullable. `docked` uses `NaN` geometry and `detached=false`. Docked areas carry no geometry.

### `WindowContentState` — Implemented

```java
public record WindowContentState(
        WindowId windowId, LayoutNode layout, Map<String, String> payload) {
    public static WindowContentState empty(WindowId windowId, LayoutNode layout);
}
```

`windowId` and `payload` are nonnull; payload is copied. `layout` is nullable for custom content. The shell treats payload as opaque.

Sources: [`WindowSystem.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/WindowSystem.java), [`WindowSink.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/WindowSink.java), [`WindowSystemListener.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/WindowSystemListener.java), [`WindowState.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/WindowState.java), [`AreaState.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/AreaState.java), [`WindowContentState.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/WindowContentState.java), [`DefaultWindowSystem.java`](../../../nbfx-platform/nbfx.platform.workspace/src/org/netbeans/modules/nbfx/platform/workspace/DefaultWindowSystem.java), [`ViewStateStore.java`](../../../nbfx-platform/nbfx.platform.workspace/src/org/netbeans/modules/nbfx/platform/workspace/ViewStateStore.java).

## 23.6 WindowManager and docking vocabulary

### `WindowManager` — Implemented over Legacy tabs

```java
public interface WindowManager {
    List<DockGroupId> groups();
    Optional<DockGroupId> primary(PaneRole role);
    Optional<DockGroupId> groupOf(ContentId content);

    void select(ContentId content);
    void move(ContentId content, DockGroupId target, int index);
    void attach(ContentId content, DockGroupId group, int index);
    void closeGroup(DockGroupId group);

    DockDecision canDock(DockRequest request);
    DockResult dock(DockRequest request);

    DockWindow floatContent(ContentId content, WindowOptions options);
    DockWindow floatGroup(DockGroupId group, WindowOptions options);
    DockResult redock(DockWindow window, DockRequest request);

    DockWindow createCustomWindow(WindowId id, WindowOptions options, WindowContent content);
    List<DockWindow> windows();
}
```

All scene-changing calls belong on the FX thread.

| Operation | Missing/unknown behavior |
|---|---|
| `groups()` | Never null; includes live docked and detached pane groups in registry order. |
| `primary(role)` | `null` role returns empty. |
| `groupOf(content)` | Empty when content has no resolvable tab. |
| `select(content)` | Unknown/undocked content is ignored. |
| `move(content,target,index)` | Unknown content/target is ignored; does not select/front. |
| `attach(content,group,index)` | Unknown content/target is ignored; negative or out-of-range index appends; otherwise moves, selects, and fronts. |
| `closeGroup(group)` | Unknown group is ignored; known group uses normal editor/view close confirmation and deferred cleanup. |
| `canDock/dock` | Return a nonnull decision/result; structural failures are reason-key rejections. |
| `floatContent/floatGroup` | Throw `IllegalArgumentException` for invalid content/group/options. |
| `redock` | Returns `dock.window.missing`, `dock.window.mismatch`, or another rejection; custom/main handles are not redockable. |
| `createCustomWindow` | Throws for wrong kind, duplicate live id, null arguments, or an already-attached content root. |
| `windows()` | Main first when it has a live scene/window, then live floating windows, then custom windows; immutable snapshot. |

**Arbitrary-content limitation:** the implementation resolves tabs only for `ViewContent`, `EditorContent`, or the `nbfx:view.<id>` convention. A generic `Content` implementation accepted by `ContentManager` is not thereby dockable through `WindowManager`.

### `DockRequest` — Implemented in `api.shell`

```java
public record DockRequest(
        ContentId content,
        DockGroupId sourceGroup,
        PaneRole targetArea,
        DockGroupId targetGroup,
        ContentId targetContent,
        DockPlacement placement,
        TransferMode mode,
        Set<DockModifier> modifiers) {

    public DockRequest(ContentId content, DockGroupId sourceGroup, PaneRole targetArea,
            DockGroupId targetGroup, DockPlacement placement, TransferMode mode,
            Set<DockModifier> modifiers); // targetContent = null
}
```

`content`, `placement`, and `mode` are nonnull. Other target/source handles are nullable. Null modifiers become an empty set; otherwise modifiers are copied. The baseline runtime does not interpret `DockModifier` values or enforce copy/move semantics from `mode`.

### `DockDecision`, `DockResult`, `DockPolicy` — Implemented

```java
public record DockDecision(boolean allowed, DockPlacement placement, String reasonKey) {
    public static DockDecision allow(DockPlacement placement);
    public static DockDecision reject(String reasonKey);
}

public record DockResult(DockDecision decision, DockGroupId group) {
    public static DockResult accepted(DockDecision decision, DockGroupId group);
    public static DockResult rejected(DockDecision decision);
}

@FunctionalInterface
public interface DockPolicy {
    DockDecision evaluate(DockRequest request);
    static DockPolicy allOf(DockPolicy... policies);
    static DockPolicy anyOf(DockPolicy... policies);
}
```

A rejection requires a nonblank reason key. Acceptance normalizes a null reason to empty. An accepted result requires a target group. `allOf` returns the first rejection or the last allowance; `anyOf` returns the first allowance or first rejection, and `dock.policy.none` for an empty composition.

The runtime consults global-lookup policies in order, skips null policies/decisions defensively, stops at the first rejection, and revalidates structure after placement refinement.

### Placements and modifiers — Implemented vocabulary, Partial execution

`DockPlacement` has `INTO_GROUP`, `BEFORE`, `AFTER`, `TAB_STRIP`, `SPLIT_LEFT/RIGHT/TOP/BOTTOM`, `EDGE_LEFT/RIGHT/TOP/BOTTOM`, `FLOAT`, and `NEW_WINDOW`.

- Existing groups, relative placement, split/edge, and `FLOAT` are implemented.
- `NEW_WINDOW` rejects with `dock.placement.unsupported`.
- `DockModifier` has `COPY`, `FLOAT`, and `NEW_WINDOW`, but the runtime does not consume the modifier set.

Complete reason keys are in [errors and troubleshooting](25-errors-and-troubleshooting.md#252-docking-reason-keys).

Sources: [`WindowManager.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/WindowManager.java), [`DockRequest.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/DockRequest.java), [`DockDecision.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/DockDecision.java), [`DockResult.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/DockResult.java), [`DockPolicy.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/DockPolicy.java), [`DockPlacement.java`](../../../nbfx-platform/nbfx.api.content/src/org/netbeans/api/nbfx/content/DockPlacement.java), [`DefaultWindowManager.java`](../../../nbfx-platform/nbfx.platform.shell/src/org/netbeans/modules/nbfx/platform/shell/DefaultWindowManager.java).

## 23.7 Window handles, options, and content

### `WindowId` — Implemented

```java
public record WindowId(String namespace, String name) {
    public static WindowId of(String value);
    public String value();
}
```

Both components are nonnull and jointly parsed as a namespaced id. `value()` is `namespace:name`.

### `WindowKind` and `WindowDecoration` — Implemented

```java
public enum WindowKind { MAIN, FLOATING, CUSTOM }
public enum WindowDecoration { NATIVE, UNDECORATED }
```

### `WindowOptions` — Implemented, no convenience constructor

```java
public record WindowOptions(
        WindowKind kind,
        ShellChromeProfile profile,
        String title,
        ResourceRef icon,
        Window owner,
        boolean modal,
        double width,
        double height,
        boolean resizable,
        boolean rememberBounds,
        WindowDecoration decoration)
```

- `kind` is nonnull.
- Width/height must be finite and strictly positive.
- Profile, title, icon, owner, and decoration are nullable.
- There are no record-level defaults for modality, resizability, or bounds persistence. Callers must supply every component.
- The runtime's internal default floating options are `FLOATING`, null profile/title/icon/owner/decoration, nonmodal, `600×400`, resizable, `rememberBounds=false`.

### `DockWindow` — Implemented, Partial state semantics

```java
public interface DockWindow {
    WindowId id();
    WindowKind kind();
    Window window();
    LayoutNode layout();
    WindowContent content();
    void show();
    void hide();
    void close();
    WindowState state();
}
```

- `layout()` is nullable for custom content without a layout.
- `hide()` preserves content and handle liveness. Floating/custom `close()` runs cleanup and prevents re-showing; `MainDockWindow.close()` merely calls `stage.close()`, and its `show()` can be called again.
- The current `state()` is a legacy view/geometry snapshot, not the target complete per-window state.
- The main handle id is `nbfx:window.main`; floating ids derive from the group id.

### `WindowContent` — Implemented

```java
public interface WindowContent extends AutoCloseable {
    Node root();
    Lookup lookup();
    WindowContentState saveState();
    @Override default void close();
}
```

`root()` must be nonnull and unparented when creating a custom window. `lookup()` and `saveState()` are expected to describe the current content. `close()` releases host resources only; it is not a substitute for content-manager close/dispose.

### Current window ownership

- Floating windows preserve pane/content identity across show/hide/redock.
- Custom windows call `WindowContent.close()` exactly once and remove themselves from enumeration.
- `rememberBounds` is honored for custom windows through `AppState`; floating windows continue to use detached-pane layout persistence.
- A custom window's `WindowOptions.profile` is retained for `state().profileId()` but is **not** used to build profile-driven chrome.

Sources: [`WindowId.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/WindowId.java), [`WindowOptions.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/WindowOptions.java), [`DockWindow.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/DockWindow.java), [`WindowContent.java`](../../../nbfx-platform/nbfx.api.shell/src/org/netbeans/api/nbfx/shell/WindowContent.java), [`FloatingWindow.java`](../../../nbfx-platform/nbfx.platform.shell/src/org/netbeans/modules/nbfx/platform/shell/FloatingWindow.java), [`CustomWindow.java`](../../../nbfx-platform/nbfx.platform.shell/src/org/netbeans/modules/nbfx/platform/shell/CustomWindow.java), [`MainDockWindow.java`](../../../nbfx-platform/nbfx.platform.shell/src/org/netbeans/modules/nbfx/platform/shell/MainDockWindow.java).

## 23.8 Content and drop APIs

### Content identity, kind, state, requirements — Implemented

```java
public record ContentId(String namespace, String name) {
    public static ContentId of(String value);
    public String value();
}

public final class ContentKind {
    // EDITOR, VIEW, OUTPUT, TERMINAL, CANVAS
    public static ContentKind of(String value);
    public String value();
}

public record ContentState(ContentId id, String instanceKey, Map<String, String> payload) {
    public static ContentState empty(ContentId id, String instanceKey);
}

public record ContentRequirements(boolean closable, boolean persistable) {
    public static final ContentRequirements DEFAULT;      // true, true
    public static final ContentRequirements SESSION_ONLY; // true, false
    public static final ContentRequirements FIXED;        // false, false
}
```

`ContentState.id` and `instanceKey` are nonnull; the key must not be blank; payload is copied. Product kinds require namespaced ids.

### `Content` and `ContentFactory` — Implemented

```java
public interface Content extends Lookup.Provider, AutoCloseable {
    ContentId id();
    String instanceKey();
    ContentKind kind();
    String title();
    Node node();
    ContentRequirements requirements();
    boolean canClose();
    @Override void close();
    ContentState saveState();
    void dispose();
}

public interface ContentFactory {
    Content create(ContentState state) throws ContentCreationException;
    ContentState capture(Content content);
}
```

`node()` is stable across host moves. Host removal never closes content. The manager calls `close()` only after `canClose()` succeeds. In the baseline `DefaultContentManager`, hosts detach first, then `close()` is followed immediately by `dispose()` in a `finally` block; it does not defer disposal until a later last-host notification. `saveState()` remains callable after close but before disposal.

### `ContentManager` — Implemented, ready-made content only

```java
public interface ContentManager {
    void open(Content content);
    void close(Content content);
    Optional<Content> find(ContentId id);
    List<Content> openContent();
    ObservableValue<Content> activeContent();
}
```

There is no `open(ContentSource)` overload in this baseline. `DefaultContentManager`:

- keeps content by `ContentId` in open order;
- ignores a second content with an already-open id;
- makes the first open content active;
- closes/disposes in `finally`, or leaves content open when `canClose()` is false;
- advances active content to the first remaining open item, or `null`;
- is synchronized for headless tests; shell mutations are FX-thread operations.

`ContentStateStore` exists and is synchronized, but no production shell code constructs or wires it. It is not yet the target session persistence owner.

### Transfer and drop contracts — Implemented vocabulary, Partial routing

```java
public interface TransferSource {
    TransferKind kind();
    Lookup data();
}

public record DropTarget(DropTargetKind kind, String id) {
    public static DropTarget global();
}

public record DropRequest(
        TransferSource source, DropTarget target, Point2D location,
        TransferMode requestedMode, Lookup context) {}

public record DropDecision(
        boolean allowed, TransferMode effect, String reasonKey, DockPlacement placement) {
    public static DropDecision allow(TransferMode effect, DockPlacement placement);
    public static DropDecision reject(String reasonKey);
    public static DropDecision reject(String reasonKey, DockPlacement placement);
}

public interface DropHandler {
    DropTarget target();
    Set<TransferKind> sourceKinds();
    DropDecision evaluate(DropRequest request);
    void perform(DropRequest request, DropDecision accepted);
}
```

- `DropRequest.source`, `target`, `requestedMode`, and `context` are nonnull; location is nullable for keyboard transfer.
- Accepted drops require a nonnull effect and normalize reason to empty. Rejected drops require a reason key.
- `DropHandler.evaluate` is pure and may run repeatedly; `perform` runs once and must honor the accepted decision.
- Built-in transfer kinds are `CONTENT`, `FILES`, `URIS`, `TEXT`, and `NODES`; modes are `MOVE`, `COPY`, and `LINK`.
- Built-in target kinds are `CONTENT`, `OVERLAY`, `GROUP`, `AREA`, `WINDOW`, and `GLOBAL`.

`DropRouter` is an internal `platform.content` utility, not public API. It discovers global services plus `NbFx/DropHandlers`, tries caller-ordered targets, and retains the first rejection when nothing accepts. Although `DropRequest.location` is nullable for a future keyboard transfer, both `DropRouter.resolve` and `resolveDetailed` currently require a nonnull location. The shell's tab-pane integration tries content (when under a tab), group, then area. It does not supply overlay/window/global targets or an explicit Escape-cancel path.

Sources: [`Content.java`](../../../nbfx-platform/nbfx.api.content/src/org/netbeans/api/nbfx/content/Content.java), [`ContentManager.java`](../../../nbfx-platform/nbfx.api.content/src/org/netbeans/api/nbfx/content/ContentManager.java), [`ContentFactory.java`](../../../nbfx-platform/nbfx.api.content/src/org/netbeans/api/nbfx/content/ContentFactory.java), [`DefaultContentManager.java`](../../../nbfx-platform/nbfx.platform.content/src/org/netbeans/modules/nbfx/platform/content/DefaultContentManager.java), [`ContentStateStore.java`](../../../nbfx-platform/nbfx.platform.content/src/org/netbeans/modules/nbfx/platform/content/ContentStateStore.java), [`DropHandler.java`](../../../nbfx-platform/nbfx.api.content/src/org/netbeans/api/nbfx/content/DropHandler.java), [`DropRouter.java`](../../../nbfx-platform/nbfx.platform.content/src/org/netbeans/modules/nbfx/platform/content/DropRouter.java).

## 23.9 Adjacent public contracts used by the shell

### Actions

```java
public interface ActionRegistry {
    void register(Command command);
    Optional<Command> find(String id);
    default Optional<Command> resolve(String id, Lookup context);
    Collection<Command> getCommands();
    Optional<Command> createScoped(String id, ObservableValue<EditorDocument> activeDocument);
}

public final class ActionLayerReader {
    public static List<ActionRef> read(FileObject folder);
    public static List<ActionRef> read(String layerPath);
    public static List<ActionRef> readForMime(FileObject folder, String mimeType);
    public static List<ActionRef> readForMime(String layerPath, String mimeType);
    public static List<ActionRef> readForProfile(FileObject folder, String profileId);
    public static List<ActionRef> readForProfile(String layerPath, String profileId);
    public static List<ActionRef> readForContext(FileObject folder, Lookup context);
    public static List<ActionRef> readForContext(String layerPath, Lookup context);
    public static List<Submenu> readSubmenus(FileObject folder);
}
```

Layer commands override provider commands with the same id. Context dimensions are ANDed; profile ids are ORed. Null/blank profile means all profiles, while missing MIME/language/context excludes references restricted on those dimensions.

### Product metadata

`ProductProvider`, `Product`, `ProductIdentity`, `ProductIcons`, `ProductStyle`, `AboutInfo`, and `DesktopAssociation` are implemented immutable API values. `ProductProvider.resolve(Lookup)` requires exactly one nonnull product. The shell currently consumes only `Product.chromeProfilePolicyId()` for main-profile selection; the other metadata is not yet the runtime source for title/icons/style/layout.

### Theme tokens

The public token types are `ThemeToken`, `ThemeTokenProvider`, `ThemeTokenRegistration`, `ThemeTokenType`, and `ThemeMode`, located in `nbfx.platform.theme`. `ThemeManager`, `Theme`, `ThemeTokens`, `ThemeScope`, `HostTheme`, and `ThemePolicy` are **Target only**.

### Shutdown and UI-ready

`ShutdownCoordinator`, `ShutdownParticipant`, `ShutdownImpact`, and `ShutdownSelection` exist under `api.shell.shutdown`. Impact/confirmation run on the FX thread; normal participant shutdown runs off it. The coordinator implementation must be idempotent. `@OnUiReady(position=...)` registers a `Runnable` at `NbFx/UIReady`; tasks run on the FX thread after the window is shown.

## 23.10 Deprecations, Legacy APIs, and target deletions

| API | Baseline status | Migration direction |
|---|---|---|
| `ShellContext.command(String, ObservableValue<Boolean>)` | **Legacy**, `@Deprecated` | Use `command(String)`; resolver owns dispatch. |
| `ShellCommandResolver.resolve(String, ObservableValue<Boolean>)` | **Legacy**, `@Deprecated` | Use `resolve(String)`. |
| `WindowEnvironmentContribution` | **Legacy**, `@Deprecated` | Use focused `DetachedBarsProvider`, `TabHomeResolver`, `ProjectSwitcher`, `NavigatorRevealState`, and `NavigatorRevealer`. |
| `StatusAlignment.CENTER` | **Legacy**, `@Deprecated` | Use `FILL`; renderer treats both as fill. |
| `Docking.DEFAULT_NAVIGATOR_SHARE` | **Legacy**, `@Deprecated` | Derive from `ViewArea.EXPLORER.weight()`. |
| `ShellBranding` | **Legacy** | Replace with `Product`/identity/style. |
| `ShellChromeProvider` / `ShellChrome` | **Legacy** | Replace with `ShellProvider`/`ShellFrame` and declarative contributions/renderers. |
| `ShellWindow.mainPane()` / concrete root types | **Legacy** | Target facade exposes `Window`, `Parent`, `WindowManager`, `WindowSystem`. |
| `api.shell.PaneRole` (`MAIN`, `NAVIGATOR`, `DOCKED`, `DETACHED`, `SLIDE`) | **Legacy** | Replace with `api.layout.PaneRole` plus group/window state. |
| `WindowPane*` SPI and `WindowPaneHost` | **Legacy** | Replace pane construction with layout/group/host model. |
| Duplicate `org.netbeans.api.nbfx.view` package | **Legacy** | Consolidate under the target `views` package. |
| `AppState`, `SessionRestorer`, static `Docking` | **Legacy implementation** | Replace with `ShellRuntime`/`DockRuntime` and stores; delete public exposure after migration. |
| Static `NbfxTheme` | **Legacy** | Replace with `api.theme.ThemeManager`. |
| `FileContextMenuContributor`, `ElementContextMenuContributor` | **Legacy** | Replace with layer references and `NodeContextMenuBuilder`. |

## 23.11 Explicitly absent target APIs

Do not compile against or document these as available in this baseline:

- `ShellRuntime`
- public `org.netbeans.api.nbfx.docking.*` (`DockHost`, `DockPolicySpec`, target module placement)
- public `org.netbeans.api.nbfx.theme.ThemeManager`, `Theme`, `ThemeTokens`, `ThemeScope`, `HostTheme`, `ThemePolicy`
- `SessionState`
- `WindowStateStore`
- `WindowClosePolicy` — **no such type exists in this baseline**
- `FocusController`
- `OverlayHost`, `OverlaySpec`, `OverlayAnchor`, `OverlayInputMode`, `OverlayContent`
- `ShellFrame.overlays()`
- `ContentSource`, `SourceHandler`
- `NodeModel`, `ChildrenModel`, `ChildFactory`, `ExplorerContext`, `NodeView`
- target `ViewManager`/`ViewRegistry` module/package shape
- target `DiagnosticsService`/event model
- `Workspace`/`WorkspaceService` target contracts

For sequencing and deletion gates, continue with [current-to-target migration](27-current-to-target-migration.md). For every annotation and generated layer attribute, use [annotations and layer schema](24-annotations-and-layer-schema.md).
