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
# 19. Content, view, and editor lifecycle

This chapter describes the content lifecycle at baseline `f2e43e8b58`. The important distinction is
between the **generic content model**, the **per-shell generic manager**, and the older
**editor-tab manager** that happens to have the same simple class name.

Navigation: [previous: branding, theming, status, and overlays](18-branding-theming-status-and-overlays.md) ·
[next: drag and drop](20-drag-and-drop.md) · [manual home](README.md)

## 19.1 Current status

| Area | Status | Baseline behavior |
|---|---|---|
| Generic `Content`, identity, state, requirements, and factory contracts | **Implemented** | Public immutable/value contracts and tests exist. |
| `DefaultContentManager` open set, close veto, close/dispose, active value | **Implemented** | Synchronized per-shell manager with direct unit coverage. |
| One generic manager per `ShellApplication`, published in its lookup | **Implemented** | The shell constructs it and passes the same instance to `DefaultWindowManager`. |
| `ViewContent` and internal `EditorContent` adapters | **Implemented** | Views and editor tabs report open/close through the generic manager. |
| Editor file/tab operations through `org.netbeans.api.nbfx.editor.ContentManager` | **Legacy** | The older, file-oriented interface and its service remain the actual editor-tab API. |
| Manager-driven source routing, content recreation, and programmatic tab close | **Target only** | The generic manager accepts ready-made content; it has no `open(ContentSource)` or factory registry. |
| Selection synchronization from every tab/window into `activeContent()` | **Target only** | Selection changes do not update the generic manager's active value. |
| Automatic `ContentStateStore`/`ContentFactory` session integration | **Target only** | The store exists, but the shell neither constructs it nor saves/restores content through it. |
| Complete host/manager ownership protocol and shutdown disposal | **Partial** | Manager close/dispose works, but it does not detach a tab or close every open item at shell stop. |

## 19.2 The generic content contract

The public type is
[`org.netbeans.api.nbfx.content.Content`](../../../nbfx-platform/nbfx.api.content/src/org/netbeans/api/nbfx/content/Content.java):

```java
public interface Content extends Lookup.Provider, AutoCloseable {
    ContentId id();
    String instanceKey();
    ContentKind kind();
    String title();
    Node node();
    ContentRequirements requirements();
    boolean canClose();
    void close();
    ContentState saveState();
    void dispose();
}
```

`Lookup.Provider` supplies `getLookup()`; there is no separately declared `lookup()` method on the
current interface.

| Member | Current contract |
|---|---|
| `id()` | Stable `ContentId`; the manager indexes the open set by this value. |
| `instanceKey()` | Stable, nonblank discriminator within an identity. It distinguishes persisted instances, although the current manager indexes only by `id()`. |
| `kind()` | Extensible `ContentKind`, not a file/URI source type. Built-ins are `EDITOR`, `VIEW`, `OUTPUT`, `TERMINAL`, and `CANVAS`. |
| `title()` | Already-localized user-visible title. |
| `node()` | Stable JavaFX node. A host reparents this same node on move/float/redock; it must not recreate content for a presentation change. |
| `getLookup()` | Content/action context. |
| `requirements()` | Host/store hints; see the enforcement gap below. |
| `canClose()` | Close veto. The manager calls `close()` only after this returns `true`. |
| `close()` | Releases editing resources but preserves what `saveState()` needs. It is not final disposal. |
| `saveState()` | Non-null recreation state; it remains callable after `close()` and before `dispose()`. |
| `dispose()` | Final resource release. The node must not be used afterwards. |

Removing a node from a host is **not** content close. `ContentManager` owns close/dispose; a host must
not call them merely because a node was detached.

### Identity, kind, and state validation

- [`ContentId`](../../../nbfx-platform/nbfx.api.content/src/org/netbeans/api/nbfx/content/ContentId.java)
  is a record with `namespace` and `name`. Both parts must match
  `[A-Za-z0-9][A-Za-z0-9._-]*`. `ContentId.of("product:document.1")` is valid; missing colons,
  blank parts, embedded whitespace, and other punctuation are rejected with
  `IllegalArgumentException`. The complete input is trimmed before parsing, so surrounding whitespace
  is accepted; whitespace inside either namespace/name part is not.
- [`ContentKind.of`](../../../nbfx-platform/nbfx.api.content/src/org/netbeans/api/nbfx/content/ContentKind.java)
  accepts case-insensitive built-in short/canonical forms. A product kind must be namespaced.
- [`ContentState`](../../../nbfx-platform/nbfx.api.content/src/org/netbeans/api/nbfx/content/ContentState.java)
  requires a non-null id, a nonblank instance key, and a non-null payload. It defensively copies the
  payload with `Map.copyOf`; null payload keys/values are therefore rejected by the JDK.
- [`ContentRequirements`](../../../nbfx-platform/nbfx.api.content/src/org/netbeans/api/nbfx/content/ContentRequirements.java)
  has three constants:

| Constant | `closable` | `persistable` |
|---|---:|---:|
| `DEFAULT` | `true` | `true` |
| `SESSION_ONLY` | `true` | `false` |
| `FIXED` | `false` | `false` |

**Implemented** contract with **Partial** enforcement: `DefaultContentManager.close` checks only
`content.canClose()`. It does not inspect `requirements().closable()` or `requirements().persistable()`.
A product that declares `FIXED` must also make `canClose()` return `false` if it wants the manager to
veto user close.

### Factory contract

[`ContentFactory`](../../../nbfx-platform/nbfx.api.content/src/org/netbeans/api/nbfx/content/ContentFactory.java)
is available:

```java
Content create(ContentState state) throws ContentCreationException;
ContentState capture(Content content);
```

A factory is expected to preserve the state's identity and instance key. A missing file/resource is a
normal `ContentCreationException`. **Target only:** no generic manager or shell code currently
discovers factories, calls `create`, or captures content through `capture`.

## 19.3 Two different `ContentManager` interfaces

Do not resolve both by an ambiguous import.

| Concern | `org.netbeans.api.nbfx.content.ContentManager` | `org.netbeans.api.nbfx.editor.ContentManager` |
|---|---|---|
| Status | **Implemented** generic lifecycle | **Legacy** editor-tab facade |
| Main input | Ready-made `Content` | `FileObject`, `EditorDocument` |
| Open | `open(Content)` | `openFile(FileObject, Node)`, `openFile(FileObject, int, int)` |
| Close | `close(Content)` with `canClose()` veto | `closeDocument`, `closeFile`, `closeAll`, delete-file/folder helpers |
| Query | `find(ContentId)`, `openContent()` | main-pane documents, active file/document, document-for-file |
| Selection | `activeContent()` observable | `mainPaneActiveDocument()` observable; selection/focus helpers |
| Other | No source routing or tab operations | Clone, range selection, focus, file deletion |
| Runtime implementation | Per-shell `DefaultContentManager` | Global `@ServiceProvider` `ContentManagerService` |

The complete legacy signature is in
[`api.editor.ContentManager`](../../../nbfx-platform/nbfx.api.editor/src/org/netbeans/api/nbfx/editor/ContentManager.java).
Its implementation,
[`ContentManagerService`](../../../nbfx-platform/nbfx.platform.shell/src/org/netbeans/modules/nbfx/platform/shell/ContentManagerService.java),
creates the main `NbfxTabPane`, opens files through `EditorService`, registers documents with
`EditorContext`, and creates editor tabs. It does **not** implement the generic interface.

When both are needed, use fully qualified names or distinct imports:

```java
org.netbeans.api.nbfx.content.ContentManager lifecycle =
        shellLookup.lookup(org.netbeans.api.nbfx.content.ContentManager.class);
org.netbeans.api.nbfx.editor.ContentManager editors =
        Lookup.getDefault().lookup(org.netbeans.api.nbfx.editor.ContentManager.class);
```

The generic manager is in the shell lookup. The legacy editor manager is resolved from the global
lookup in existing editor code.

## 19.4 Per-shell ownership and composition

`ShellApplication` constructs both objects as field initializers, before `start(Stage)`: one
`DefaultContentManager`, then a `DefaultWindowManager` using that same instance. Its default
`shellLookup()` contains:

```text
Lookups.fixed(this ShellApplication/ShellWindow, WindowManager, generic ContentManager)
    + Lookup.getDefault()
```

The selected profile/runtime region host are added to the frame context later. The content manager
itself is not a global service and is not replaced by a product provider.

```mermaid
sequenceDiagram
    participant Launcher
    participant Shell as ShellApplication
    participant CM as DefaultContentManager
    participant WM as DefaultWindowManager
    participant Tabs as NbfxTabPane
    participant View as ViewContent
    participant Editor as EditorContent

    Note over Shell: instance construction / field initialization, before start(Stage)
    Shell->>CM: new DefaultContentManager()
    Shell->>WM: new DefaultWindowManager(CM, ...)
    Launcher->>Shell: start(Stage) on FX thread
    Shell->>Shell: bind view/editor lifecycle listeners
    Shell->>Tabs: addViewLifecycle / addEditorLifecycle
    Tabs->>View: ViewTabs.create(view)
    View-->>CM: open(new ViewContent(view))
    Tabs->>Editor: create editor tab
    Editor-->>CM: open(new EditorContent(document))
    Note over CM: first open becomes active; later opens do not
    Tabs->>Tabs: remove tab, then defer one FX pulse
    Tabs-->>CM: find adapter identity, then close(open content)
    CM->>CM: canClose veto? remove + advance active
    CM->>View: close() -> view.viewClosed()
    CM->>Editor: close() -> EditorContext.unregister(document)
    CM->>View: dispose() (adapter no-op)
    CM->>Editor: dispose() (adapter no-op)
```

The shell removes both lifecycle listeners in `stop()` before pane disposal. It does **not** iterate
`contentManager.openContent()` and close every item. Consequently, the manager has no explicit
`closeAll`/`dispose` lifecycle method, and generic products must not assume shell stop performs a
normal manager close for arbitrary content.

## 19.5 `DefaultContentManager` algorithms

Implementation:
[`DefaultContentManager`](../../../nbfx-platform/nbfx.platform.content/src/org/netbeans/modules/nbfx/platform/content/DefaultContentManager.java).
All public methods are `synchronized`; `openContent()` returns `List.copyOf`, and `activeContent()`
returns a read-only JavaFX object property.

### Open

```text
open(content)
  requireNonNull(content)
  if open contains content.id(): return, preserving the original instance
  put(id, content) in LinkedHashMap insertion order
  if active == null: active = content
```

Consequences:

- The first content becomes active. A second open never steals active status.
- A duplicate identity is silently ignored, even if the new object has a different instance key or is
  a different provider instance. Closing the original cannot accidentally remove the ignored one,
  because the ignored one was never registered.
- There is no notification that a duplicate was rejected and no activation API.
- `open` creates no tab, does not attach `content.node()`, and performs no factory/source routing.

### Close

```text
close(content)
  if content == null or open.get(content.id()) != content: return
  if !content.canClose(): return
  remove(id)
  if active == content:
      active = first remaining LinkedHashMap value, or null
  try content.close() finally content.dispose()
```

Consequences and error behavior:

- `null`, unknown content, or a different object with the same id is a no-op. Identity comparison of
  the registered value prevents a stale adapter from closing its replacement.
- A `canClose() == false` veto leaves the open set and active value unchanged. There is no result or
  reason key; the caller observes that the content remains open.
- Active fallback is **open insertion order**, not tab order, last-selected order, or visual
  adjacency. Closing a non-active item leaves active unchanged.
- Removal and active advancement happen **before** `close()` and `dispose()` callbacks.
- `dispose()` runs even if `close()` throws. If both throw, the `finally` disposal exception replaces
  the close exception for the caller. The item is already absent from the manager in either case.
- The manager does not detach `content.node()` from a scene graph. Directly closing a hosted
  `ViewContent` can therefore call `viewClosed()` while its tab is still attached. Normal shell tab
  close removes the tab first; arbitrary products need an explicit host-removal protocol.

The tests cover first-open activation, duplicate suppression, close/dispose, veto, active advancement,
and last-content clearing in
[`DefaultContentManagerTest`](../../../nbfx-platform/nbfx.platform.content/test/unit/src/org/netbeans/modules/nbfx/platform/content/DefaultContentManagerTest.java).

### The active-content selection gap

`WindowManager.select(ContentId)` selects/fronts the matching tab, but neither it nor
`NbfxTabPane` selection/focus listeners calls a generic-manager “set active” operation. The generic
interface has no such operation.

Therefore:

```text
open A -> active A
open B -> active A
user selects tab B -> generic active remains A
close A -> generic active becomes B (because A was active and B is first remaining)
```

This is **Target only** synchronization work. For current action context, use
`ActiveComponentContext`/`ViewManager.activeView()` as described in
[chapter 21](21-threading-focus-and-accessibility.md), not `ContentManager.activeContent()`.

## 19.6 `ViewContent`: public view adapter

[`ViewContent`](../../../nbfx-platform/nbfx.api.views/src/org/netbeans/api/nbfx/views/ViewContent.java)
wraps an `org.netbeans.api.nbfx.view.ViewProvider`.

| Adapter result | Rule |
|---|---|
| `instanceKey()` | `view.getId()`, or `"view"` when null/blank. |
| `id()` | `nbfx:view.<sanitized instance key>`; every character outside the content-id grammar becomes `-`. |
| `kind()` | `ContentKind.VIEW`. |
| `title()` | `view.getTitle()`. |
| `node()` | `view.getView()`. |
| `getLookup()` | `view.getLookup()`, defaulting to `Lookup.EMPTY` in `ViewProvider`. |
| `requirements()` | `ContentRequirements.DEFAULT`. |
| `canClose()` | Always `true`. |
| `close()` | Calls `view.viewClosed()`. |
| `saveState()` | Empty payload with adapter id/instance key. |
| `dispose()` | No-op; the wrapped view owns its resources. |

Examples from the tests: view id `navigator` maps to `nbfx:view.navigator`; `a/b:c d` maps to
`nbfx:view.a-b-c-d` while its instance key remains the original string; a blank id maps to
`nbfx:view.view`.

The adapter is stateless, so constructing it again yields the same identity. It does not make two
live `ViewProvider` objects with the same id into two content instances. A product with multiple live
instances must give each provider a distinct id.

## 19.7 `EditorContent`: internal editor adapter

[`EditorContent`](../../../nbfx-platform/nbfx.platform.shell/src/org/netbeans/modules/nbfx/platform/shell/EditorContent.java)
is package-private. Products do not construct or reference it.

| Adapter result | Rule |
|---|---|
| Identity | Stable per `EditorDocument` object through a synchronized weak map. |
| Shape | `nbfx:editor.<sanitized file path>-<process sequence>`. Untitled documents use `untitled`. |
| Instance key | `<file path or untitled>#<sequence>`. |
| Clones | Different document objects receive different identities, even for the same file. |
| `kind()` | `ContentKind.EDITOR`. |
| Title/node/lookup | Delegated to the document. |
| `requirements()` | `ContentRequirements.DEFAULT`. |
| `canClose()` | Always `true`; the tab's close-request handler already performed Save/Discard/Cancel confirmation. |
| `close()` | Looks up global `EditorContext`; unregisters only if it contains the document. |
| `saveState()` | Empty payload. The sequence/path identity is not a persisted document locator. |
| `dispose()` | No-op; `EditorContext.unregister` disposes the document. |

`EditorContextService.unregister` removes the document, clears it if it was the active document, and
calls `document.dispose()`. If no `EditorContext` exists, `EditorContent.close()` is harmless but does
not dispose the document. If a context exists but does not contain the document, it also does not
dispose it.

The weak map avoids pinning documents, and the atomic sequence separates clones. The identity is not
stable across process restarts, and the empty state cannot recreate an editor. Editor session restore
continues to use the legacy layout/file path, not `ContentFactory`.

## 19.8 Tab lifecycle bindings and fallbacks

The shell binds two package-private `NbfxTabPane` lifecycle interfaces at the beginning of `start`.

### View tabs

1. `ViewTabs.create(view, tooltip)` stores the provider/id on the tab and calls
   `notifyViewOpened(view)`.
2. The shell listener executes `contentManager.open(new ViewContent(view))`.
3. Moving/reordering a tab removes and re-adds it, but does not call `ViewTabs.create` again.
4. On a real close, the pane defers `unregisterIfClosed` by one FX pulse. If the tab is attached to any
   registered pane, nothing is closed; this protects same-pulse moves.
5. A per-tab `nbfx.viewClosed` marker makes close publication one-shot.
6. The shell reconstructs `ViewContent`, finds the registered content by id, and closes that original
   instance. If no content is open, it directly calls `view.viewClosed()`.
7. With no shell lifecycle listener at all, `NbfxTabPane.closed(view)` directly calls
   `view.viewClosed()`.

### Editor tabs

1. `ContentManagerService.createTab` registers the document and calls `notifyEditorOpened(document)`.
2. The shell listener executes `contentManager.open(new EditorContent(document))`.
3. A normal tab close has already run `CloseConfirmation.confirmClose`; Cancel consumes the close
   request, so removal and generic close do not occur.
4. After deferred removal, the shell reconstructs `EditorContent` and closes the registered adapter.
5. If no generic content is found, it looks up global `EditorContext` and calls `unregister(document)`.
6. With no shell lifecycle listener, `NbfxTabPane` performs the same editor-context fallback directly.

Editor split cleanup (`EditorSplit.dispose`) remains tab-owned. The lifecycle tests deliberately drain
an extra FX pulse before asserting close publication:
[`ViewContentLifecycleTest`](../../../nbfx-platform/nbfx.platform.shell/test/unit/src/org/netbeans/modules/nbfx/platform/shell/ViewContentLifecycleTest.java)
and
[`EditorContentLifecycleTest`](../../../nbfx-platform/nbfx.platform.shell/test/unit/src/org/netbeans/modules/nbfx/platform/shell/EditorContentLifecycleTest.java).

### Close, veto, and disposal boundaries

| Path | Confirmation/veto | Who closes/disposes |
|---|---|---|
| Generic `DefaultContentManager.close` | `Content.canClose()` | Manager calls `close()` then `dispose()`. |
| View tab close | No view confirmation contract; adapter always allows | Shell lifecycle closes `ViewContent`; view releases resources in `viewClosed()`. |
| Editor tab close | `CloseConfirmation` before removal; adapter then allows | Shell lifecycle unregisters through `EditorContext`, which disposes the document. |
| Editor close without manager entry | Same tab confirmation if user-initiated | Fallback `EditorContext.unregister`. |
| Detached window close with tabs still present | Existing detached close behavior | Pane/window cleanup unregisters documents; redock paths preserve content. |
| Shell `stop()` | Normal quit confirmation happened earlier, if requested | Listeners are removed; there is no generic close-all pass. |

`CloseConfirmation` offers Save/Discard/Cancel, suppresses Save for locked documents, avoids prompting
for a modified document when another clone remains open, and aborts close when saving fails. See
[`CloseConfirmation`](../../../nbfx-platform/nbfx.platform.shell/src/org/netbeans/modules/nbfx/platform/shell/CloseConfirmation.java).

## 19.9 `ContentStateStore` is not wired into the shell

[`ContentStateStore`](../../../nbfx-platform/nbfx.platform.content/src/org/netbeans/modules/nbfx/platform/content/ContentStateStore.java)
is a public class in an implementation package, not a public API-module service.

- Default preferences node: `org/netbeans/modules/nbfx/content`.
- Key: SHA-256 of `id.value() + '\0' + instanceKey`, lowercase hex.
- Stored values: canonical id, instance key, and URL-safe unpadded Base64 payload entries separated by
  newlines, with `=` between encoded key/value.
- `save` replaces the complete payload and is idempotent. `load` returns `Optional.empty` for a
  missing node. `loadAll` returns an immutable snapshot. `remove` and `clear` are best-effort.
- All methods are synchronized. The class does not call `Preferences.flush()` explicitly.
- Invalid/missing id or blank/missing instance key is skipped. An invalid Base64 payload is **not**
  caught by `read`; `IllegalArgumentException` can escape despite the “unreadable entry” wording.
  `loadAll` catches `BackingStoreException` while enumerating children, not payload decode failures.

The only construction outside the class is in
[`ContentStateStoreTest`](../../../nbfx-platform/nbfx.platform.content/test/unit/src/org/netbeans/modules/nbfx/platform/content/ContentStateStoreTest.java),
using its package-visible preferences-node constructor. `ShellApplication` has no store field and no
save/load calls. Ordering, group membership, and window placement remain window-state concerns.

## 19.10 Copyable generic-content example

This compiles against the current generic API. It demonstrates lifecycle ownership only; opening the
content does **not** create a tab. Run `open`/`close` on the FX thread when the node is part of a live
shell.

```java
package example.content;

import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import java.util.Map;
import org.netbeans.api.nbfx.content.*;
import org.openide.util.Lookup;

public final class NoteContent implements Content {

    private final ContentId id = ContentId.of("example:note");
    private final String instanceKey;
    private final StackPane node = new StackPane(new Label("Note"));
    private boolean modified;
    private boolean disposed;

    public NoteContent(String instanceKey) {
        if (instanceKey == null || instanceKey.isBlank()) {
            throw new IllegalArgumentException("instanceKey must not be blank");
        }
        this.instanceKey = instanceKey;
    }

    @Override public ContentId id() { return id; }
    @Override public String instanceKey() { return instanceKey; }
    @Override public ContentKind kind() { return ContentKind.of("example:note"); }
    @Override public String title() { return "Note " + instanceKey; }
    @Override public StackPane node() { return node; }
    @Override public Lookup getLookup() { return Lookup.EMPTY; }
    @Override public ContentRequirements requirements() { return ContentRequirements.DEFAULT; }

    @Override public boolean canClose() {
        return !modified; // A real editor may prompt here or veto until saved.
    }

    @Override public void close() {
        modified = false; // Release editing resources, not recreation state.
    }

    @Override public ContentState saveState() {
        return new ContentState(id, instanceKey, Map.of("title", title()));
    }

    @Override public void dispose() {
        disposed = true;
        node.getChildren().clear();
    }

    public void markModified() { modified = true; }
    public void markSaved() { modified = false; }
    public boolean isDisposed() { return disposed; }
}
```

```java
ContentManager manager = shellLookup.lookup(ContentManager.class);
NoteContent note = new NoteContent("draft-1");
manager.open(note);                       // No tab is created.
manager.find(note.id()).orElseThrow();    // Identity lookup.
manager.close(note);                      // Vetoed while modified.
note.markModified();
manager.close(note);                      // Still vetoed.
note.markSaved();
manager.close(note);                      // Calls close(), then dispose().
```

For a view, prefer `ViewProvider` + `ViewManager.show`; for a file editor, prefer the legacy editor
`ContentManager`. There is no current generic-public-API operation that turns an arbitrary
`ContentManager.open(content)` call into a docked tab.

## 19.11 Threading, accessibility, and errors

- **Threading — Implemented/Partial:** shell calls occur on the FX thread. `DefaultContentManager` is
  synchronized for headless tests, but it does not marshal calls or JavaFX-property notifications to
  FX. A live-shell product must call it on FX. `ContentStateStore` is synchronized and JavaFX-free at
  its own code level.
- **Accessibility — Partial:** content supplies its node, localized title, and lookup. The shell does
  not assign an accessible role/name to arbitrary content or synchronize generic active content with
  assistive technology. View/editor tabs use JavaFX control semantics; product content remains
  responsible for its own accessible name, role, keyboard traversal, and state.
- **Errors:** null `open` throws `NullPointerException`; null/unknown/stale close is a no-op; a close
  veto is silent. `canClose()` runs before any state mutation, so an exception from it propagates with
  the open set and active value unchanged. After the veto passes, removal/active advancement happen
  before `close()`; exceptions from `close()` or `dispose()` therefore propagate after manager state
  has changed, with `dispose()` still attempted in `finally`. Factory failure is represented by
  checked `ContentCreationException`, but no current shell path invokes a factory.
- **Ownership:** hosts detach; the manager closes/disposes. The current adapters delegate final view
  or editor resource ownership, so their own `dispose()` methods are intentionally no-ops.

## 19.12 Source and test map

### Implementing source

- [`Content`](../../../nbfx-platform/nbfx.api.content/src/org/netbeans/api/nbfx/content/Content.java),
  [`ContentManager`](../../../nbfx-platform/nbfx.api.content/src/org/netbeans/api/nbfx/content/ContentManager.java),
  [`ContentFactory`](../../../nbfx-platform/nbfx.api.content/src/org/netbeans/api/nbfx/content/ContentFactory.java)
- [`ContentId`](../../../nbfx-platform/nbfx.api.content/src/org/netbeans/api/nbfx/content/ContentId.java),
  [`ContentKind`](../../../nbfx-platform/nbfx.api.content/src/org/netbeans/api/nbfx/content/ContentKind.java),
  [`ContentState`](../../../nbfx-platform/nbfx.api.content/src/org/netbeans/api/nbfx/content/ContentState.java),
  [`ContentRequirements`](../../../nbfx-platform/nbfx.api.content/src/org/netbeans/api/nbfx/content/ContentRequirements.java)
- [`DefaultContentManager`](../../../nbfx-platform/nbfx.platform.content/src/org/netbeans/modules/nbfx/platform/content/DefaultContentManager.java),
  [`ContentStateStore`](../../../nbfx-platform/nbfx.platform.content/src/org/netbeans/modules/nbfx/platform/content/ContentStateStore.java)
- [`ViewContent`](../../../nbfx-platform/nbfx.api.views/src/org/netbeans/api/nbfx/views/ViewContent.java),
  [`ViewProvider`](../../../nbfx-platform/nbfx.api.views/src/org/netbeans/api/nbfx/view/ViewProvider.java),
  [`ViewTabs`](../../../nbfx-platform/nbfx.platform.shell/src/org/netbeans/modules/nbfx/platform/shell/ViewTabs.java)
- [`EditorContent`](../../../nbfx-platform/nbfx.platform.shell/src/org/netbeans/modules/nbfx/platform/shell/EditorContent.java),
  [`EditorContextService`](../../../nbfx-platform/nbfx.platform.shell/src/org/netbeans/modules/nbfx/platform/shell/EditorContextService.java)
- [`ShellApplication`](../../../nbfx-platform/nbfx.platform.shell/src/org/netbeans/modules/nbfx/platform/shell/ShellApplication.java)
  (manager field, lookup, lifecycle bindings, stop), and
  [`NbfxTabPane`](../../../nbfx-platform/nbfx.platform.shell/src/org/netbeans/modules/nbfx/platform/shell/NbfxTabPane.java)
  (deferred close and fallbacks)
- [`ContentManagerService`](../../../nbfx-platform/nbfx.platform.shell/src/org/netbeans/modules/nbfx/platform/shell/ContentManagerService.java),
  [`DefaultWindowManager`](../../../nbfx-platform/nbfx.platform.shell/src/org/netbeans/modules/nbfx/platform/shell/DefaultWindowManager.java)

### Tests

- [`DefaultContentManagerTest`](../../../nbfx-platform/nbfx.platform.content/test/unit/src/org/netbeans/modules/nbfx/platform/content/DefaultContentManagerTest.java)
- [`ContentStateStoreTest`](../../../nbfx-platform/nbfx.platform.content/test/unit/src/org/netbeans/modules/nbfx/platform/content/ContentStateStoreTest.java)
- [`ShellContentManagerTest`](../../../nbfx-platform/nbfx.platform.shell/test/unit/src/org/netbeans/modules/nbfx/platform/shell/ShellContentManagerTest.java)
- [`ViewContentTest`](../../../nbfx-platform/nbfx.api.views/test/unit/src/org/netbeans/api/nbfx/views/ViewContentTest.java),
  [`EditorContentTest`](../../../nbfx-platform/nbfx.platform.shell/test/unit/src/org/netbeans/modules/nbfx/platform/shell/EditorContentTest.java)
- [`ViewContentLifecycleTest`](../../../nbfx-platform/nbfx.platform.shell/test/unit/src/org/netbeans/modules/nbfx/platform/shell/ViewContentLifecycleTest.java),
  [`EditorContentLifecycleTest`](../../../nbfx-platform/nbfx.platform.shell/test/unit/src/org/netbeans/modules/nbfx/platform/shell/EditorContentLifecycleTest.java)

## 19.13 Current vs target

The [target content reference](../../reference/cjap-shell-ui.md#11-content-and-documents) keeps the
same `Content`/`ContentManager` names but adds source routing and expects view/editor documents to
share a fully synchronized lifecycle. The baseline has the vocabulary, one per-shell manager, and
tab adapters; it does not have generic source handling, factory-driven recreation, manager-driven
selection, automatic content-state persistence, or a manager close-all phase.

The [ADR persistence decision](../../architecture/adr-0001-cjap-shell-ui.md#13-review-resolution-persistence-ownership)
explicitly separates window order/placement from per-content payloads. That separation is reflected
in `ContentStateStore`, but the shell integration is still **Target only**.

Next: [20. Drag and drop](20-drag-and-drop.md).
