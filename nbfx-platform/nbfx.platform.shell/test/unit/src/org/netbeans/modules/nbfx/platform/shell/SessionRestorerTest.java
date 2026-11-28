/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.netbeans.modules.nbfx.platform.shell;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.junit.Test;
import org.netbeans.api.nbfx.content.DockPlacement;
import org.netbeans.api.nbfx.content.TransferMode;
import org.netbeans.api.nbfx.editor.EditorDocument;
import org.netbeans.api.nbfx.shell.DockRequest;
import org.netbeans.api.nbfx.shell.DockWindow;
import org.netbeans.api.nbfx.shell.PaneRole;
import org.netbeans.api.nbfx.shell.WindowClosePolicy;
import org.netbeans.api.nbfx.shell.WindowKind;
import org.netbeans.api.nbfx.shell.WindowOptions;
import org.netbeans.api.nbfx.view.ViewProvider;
import org.netbeans.api.nbfx.views.PaneKind;
import org.netbeans.api.nbfx.views.ViewContent;
import org.netbeans.modules.nbfx.platform.content.DefaultContentManager;
import org.netbeans.modules.nbfx.platform.shell.AppState.Layout;
import org.netbeans.modules.nbfx.platform.shell.AppState.PaneLayout;
import org.netbeans.modules.nbfx.platform.shell.AppState.TabEntry;
import org.netbeans.modules.nbfx.test.fx.JavaFxTestSupport;
import org.openide.util.Lookup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Detached session panes restore as manager-owned floating windows. */
public class SessionRestorerTest {

    private static final class View implements ViewProvider {

        private final String id;
        private final String title;

        View() {
            this("conformance.removed-pane", "Removed Pane View");
        }

        View(String id, String title) {
            this.id = id;
            this.title = title;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getTitle() {
            return title;
        }

        @Override
        public Node getView() {
            return new Region();
        }
    }

    private static final class Fixture {

        final NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
        final DefaultContentManager contentManager = new DefaultContentManager();
        final DefaultWindowManager manager;
        final DockRuntime runtime;
        final Preferences prefs;
        final AppState appState;

        Fixture() {
            this(null);
        }

        Fixture(Predicate<List<EditorDocument>> closeConfirmation) {
            Map<String, NbfxTabPane> panes = new LinkedHashMap<>();
            panes.put(PaneRole.MAIN.name(), main);
            Docking.create(new WindowComposition(List.of(), null), panes);
            runtime = Docking.runtime();
            manager = closeConfirmation == null ? new DefaultWindowManager(contentManager)
                    : new DefaultWindowManager(contentManager, closeConfirmation);
            runtime.setWindowManager(manager);
            prefs = Preferences.userRoot().node("nbfx-session-restorer-" + System.nanoTime());
            appState = new AppState(prefs);
        }

        ViewContent open(String id) {
            ViewContent content = new ViewContent(new View(id, id));
            contentManager.open(content);
            return content;
        }

        void close() {
            manager.windows().forEach(DockWindow::close);
            for (TabPane pane : new java.util.ArrayList<>(NbfxTabPane.panesByRole())) {
                if (pane instanceof NbfxTabPane nbfx && !nbfx.role().isPermanent()
                        && nbfx.role() != PaneRole.SLIDE) {
                    if (NbfxTabPane.isFloatingPane(nbfx)) {
                        NbfxTabPane.closeFloatingPane(nbfx, true, false);
                    }
                    NbfxTabPane.unregister(pane);
                }
            }
            NbfxTabPane.unregister(main);
            runtime.dispose();
            try {
                prefs.removeNode();
            } catch (BackingStoreException ex) {
                throw new AssertionError(ex);
            }
        }
    }

    @Test
    public void viewTabsMoveToTheDefaultViewPane() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            NbfxTabPane navigator = new NbfxTabPane(PaneRole.NAVIGATOR);
            try {
                Tab tab = ViewTabs.create(new View(), "Removed Pane View");

                SessionRestorer.moveTabsToFallback(List.of(tab), null, navigator);

                assertSame(navigator, tab.getTabPane());
                assertTrue(navigator.getTabs().contains(tab));
            } finally {
                NbfxTabPane.unregister(navigator);
            }
            return null;
        });
    }

    @Test
    public void restoredDetachedPaneIsAdoptedAndRedockedAsOneWindow() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            Fixture f = new Fixture();
            try {
                ViewContent first = f.open("restore.first");
                ViewContent second = f.open("restore.second");
                Tab firstTab = tab(first);
                Tab secondTab = tab(second);
                PaneLayout layout = new PaneLayout(PaneRole.DETACHED, 100, 200, 640, 480,
                        List.of(TabEntry.view(first.view().getId()),
                                TabEntry.view(second.view().getId())), 1)
                        .withKind(PaneKind.EDITOR);

                NbfxTabPane pane = (NbfxTabPane) SessionRestorer.restoreDetachedPane(
                        layout, List.of(firstTab, secondTab), secondTab);
                assertEquals(PaneKind.EDITOR, pane.getKind());
                assertEquals(List.of(firstTab, secondTab), pane.getTabs());
                assertSame(secondTab, pane.getSelectionModel().getSelectedItem());
                assertEquals(100, NbfxTabPane.stageOf(pane).getX(), 0.001);
                assertEquals(200, NbfxTabPane.stageOf(pane).getY(), 0.001);
                assertEquals(640, NbfxTabPane.stageOf(pane).getWidth(), 0.001);
                assertEquals(480, NbfxTabPane.stageOf(pane).getHeight(), 0.001);
                assertEquals(1, f.manager.windows().size());
                DockWindow window = f.manager.windows().getFirst();
                assertTrue(window instanceof FloatingWindow);
                FloatingWindow floating = (FloatingWindow) window;
                assertNull(floating.anchor());
                assertEquals(WindowClosePolicy.CLOSE_CONTENT, floating.options().closePolicy());
                assertFalse(floating.options().rememberBounds());
                assertFalse(f.manager.canUndo());
                assertFalse(f.manager.canRedo());
                assertThrows(IllegalStateException.class, () -> f.manager.adoptRestoredFloating(pane));
                assertEquals(1, f.manager.windows().size());

                window.hide();
                assertEquals(List.of(window), f.manager.windows());
                window.show();
                assertTrue(NbfxTabPane.stageOf(pane).isShowing());
                DockRequest join = new DockRequest(first.id(), pane.groupId(), null,
                        f.main.groupId(), DockPlacement.INTO_GROUP, TransferMode.MOVE, Set.of());
                assertTrue(f.manager.redock(window, join).decision().allowed());
                assertEquals(List.of(firstTab, secondTab), f.main.getTabs());
                assertTrue(f.manager.windows().isEmpty());
                assertFalse(NbfxTabPane.tabPanes().contains(pane));
            } finally {
                f.close();
            }
            return null;
        });
    }

    @Test
    public void adoptedRestoredWindowCloseReleasesHandle() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            Fixture f = new Fixture();
            try {
                ViewContent content = f.open("restore.close");
                Tab tab = tab(content);
                NbfxTabPane pane = (NbfxTabPane) SessionRestorer.restoreDetachedPane(
                        detachedLayout("restore.close"), List.of(tab), tab);
                DockWindow window = f.manager.windows().getFirst();

                window.close();

                assertTrue(f.manager.windows().isEmpty());
                assertFalse(NbfxTabPane.tabPanes().contains(pane));
                assertFalse(f.manager.canUndo());
            } finally {
                f.close();
            }
            return null;
        });
    }

    @Test
    public void detachedRestoreKeepsDirectFallbackWithoutManager() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
            DockRuntime runtime = null;
            NbfxTabPane pane = null;
            try {
                Map<String, NbfxTabPane> panes = new LinkedHashMap<>();
                panes.put(PaneRole.MAIN.name(), main);
                Docking.create(new WindowComposition(List.of(), null), panes);
                runtime = Docking.runtime();
                Tab tab = new Tab("Fallback");
                NbfxTabPane.setViewId(tab, "restore.fallback");

                pane = (NbfxTabPane) SessionRestorer.restoreDetachedPane(
                        detachedLayout("restore.fallback"), List.of(tab), tab);

                assertNull(Docking.windowManager());
                assertEquals(PaneKind.EDITOR, pane.getKind());
                assertSame(tab, pane.getSelectionModel().getSelectedItem());
                assertTrue(NbfxTabPane.stageOf(pane).isShowing());
            } finally {
                if (pane != null) {
                    Stage stage = NbfxTabPane.stageOf(pane);
                    if (stage != null) {
                        stage.close();
                    }
                    NbfxTabPane.unregister(pane);
                }
                NbfxTabPane.unregister(main);
                if (runtime != null) {
                    runtime.dispose();
                }
            }
            return null;
        });
    }

    @Test
    public void redockedDetachedPaneIsCapturedAsDocked() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            Fixture f = new Fixture();
            try {
                ViewContent content = f.open("restore.redock");
                Tab tab = tab(content);
                NbfxTabPane pane = (NbfxTabPane) SessionRestorer.restoreDetachedPane(
                        detachedLayout("restore.redock"), List.of(tab), tab);
                DockWindow window = f.manager.windows().getFirst();
                DockRequest edge = new DockRequest(content.id(), pane.groupId(), PaneRole.MAIN,
                        null, DockPlacement.EDGE_BOTTOM, TransferMode.MOVE, Set.of());
                assertTrue(f.manager.redock(window, edge).decision().allowed());
                assertTrue(Docking.area().contains(pane));
                assertEquals(PaneRole.DOCKED, NbfxTabPane.roleOf(pane));

                SessionRestorer restorer = new SessionRestorer(f.appState, _ -> null, () -> null);
                restorer.captureSession(null, null);
                Layout saved = f.appState.getSessionLayout();

                assertTrue(saved.panes().stream().noneMatch(layout -> layout.role() == PaneRole.DETACHED));
                PaneLayout docked = saved.panes().stream()
                        .filter(layout -> layout.role() == PaneRole.DOCKED)
                        .findFirst().orElseThrow();
                assertEquals(List.of(TabEntry.view("restore.redock")), docked.tabs());
                assertEquals(PaneKind.EDITOR, docked.kind());
                assertTrue(saved.dock().leafCount() >= 2);
            } finally {
                f.close();
            }
            return null;
        });
    }

    @Test
    public void floatedDockedPaneIsCapturedAsDetached() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            Fixture f = new Fixture();
            try {
                ViewContent content = f.open("restore.docked.float");
                Tab tab = tab(content);
                NbfxTabPane source = (NbfxTabPane) Docking.area().split(
                        new org.netbeans.modules.nbfx.platform.workspace.DropTarget<>(
                                f.main, javafx.geometry.Side.RIGHT));
                NbfxTabPane.attachTab(source, tab);
                WindowOptions options = new WindowOptions(WindowKind.FLOATING, null, null, null, null,
                        false, 640, 480, true, false, null);
                f.manager.floatGroup(source.groupId(), options);
                assertTrue(NbfxTabPane.isFloatingPane(source));

                SessionRestorer restorer = new SessionRestorer(f.appState, _ -> null, () -> null);
                restorer.captureSession(null, null);
                PaneLayout saved = f.appState.getSessionLayout().panes().stream()
                        .filter(candidate -> candidate.role() == PaneRole.DETACHED)
                        .findFirst().orElseThrow();

                assertEquals(List.of(TabEntry.view("restore.docked.float")), saved.tabs());
                assertEquals(640, saved.width(), 0.001);
                assertEquals(480, saved.height(), 0.001);
            } finally {
                f.close();
            }
            return null;
        });
    }

    @Test
    public void wholePaneRedockUndoRestoresDetachedRole() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            Fixture f = new Fixture();
            try {
                ViewContent content = f.open("restore.role.undo");
                Tab tab = tab(content);
                NbfxTabPane pane = (NbfxTabPane) SessionRestorer.restoreDetachedPane(
                        detachedLayout("restore.role.undo"), List.of(tab), tab);
                DockWindow window = f.manager.windows().getFirst();
                DockRequest edge = new DockRequest(content.id(), pane.groupId(), PaneRole.MAIN,
                        null, DockPlacement.EDGE_BOTTOM, TransferMode.MOVE, Set.of());
                assertTrue(f.manager.redock(window, edge).decision().allowed());
                assertEquals(PaneRole.DOCKED, NbfxTabPane.roleOf(pane));
                assertTrue(f.manager.canUndo());

                f.manager.undo();

                assertEquals(PaneRole.DETACHED, NbfxTabPane.roleOf(pane));
                assertTrue(NbfxTabPane.isFloatingPane(pane));
                assertEquals(1, f.manager.windows().size());
                f.manager.redo();
                assertEquals(PaneRole.DOCKED, NbfxTabPane.roleOf(pane));
                assertTrue(Docking.area().contains(pane));
                assertTrue(f.manager.windows().isEmpty());
            } finally {
                f.close();
            }
            return null;
        });
    }

    @Test
    public void redockedDetachedPaneUsesDockedGroupOperations() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            Fixture f = new Fixture();
            try {
                ViewContent content = f.open("restore.group.move");
                Tab tab = tab(content);
                NbfxTabPane pane = (NbfxTabPane) SessionRestorer.restoreDetachedPane(
                        detachedLayout("restore.group.move"), List.of(tab), tab);
                DockWindow window = f.manager.windows().getFirst();
                DockRequest edge = new DockRequest(content.id(), pane.groupId(), PaneRole.MAIN,
                        null, DockPlacement.EDGE_BOTTOM, TransferMode.MOVE, Set.of());
                assertTrue(f.manager.redock(window, edge).decision().allowed());
                assertEquals(PaneRole.DOCKED, NbfxTabPane.roleOf(pane));

                f.manager.moveGroup(pane.groupId(), f.main.groupId(), -1);

                assertEquals(List.of(tab), f.main.getTabs());
                assertTrue(pane.getTabs().isEmpty());
            } finally {
                f.close();
            }
            return null;
        });
    }

    @Test
    public void minimizedRedockedDetachedPaneIsCaptured() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            Fixture f = new Fixture();
            try {
                ViewContent content = f.open("restore.minimized");
                Tab tab = tab(content);
                PaneLayout layout = new PaneLayout(PaneRole.DETACHED, 100, 200, 640, 480,
                        List.of(TabEntry.view("restore.minimized")), 0).withKind(PaneKind.VIEW);
                NbfxTabPane pane = (NbfxTabPane) SessionRestorer.restoreDetachedPane(
                        layout, List.of(tab), tab);
                DockWindow window = f.manager.windows().getFirst();
                DockRequest edge = new DockRequest(content.id(), pane.groupId(), PaneRole.MAIN,
                        null, DockPlacement.EDGE_BOTTOM, TransferMode.MOVE, Set.of());
                assertTrue(f.manager.redock(window, edge).decision().allowed());
                Docking.slides().minimize(pane);
                assertFalse(Docking.area().contains(pane));
                assertTrue(Docking.slides().isMinimized(pane));

                SessionRestorer restorer = new SessionRestorer(f.appState, _ -> null, () -> null);
                restorer.captureSession(null, null);
                PaneLayout saved = f.appState.getSessionLayout().panes().stream()
                        .filter(candidate -> candidate.role() == PaneRole.DOCKED)
                        .findFirst().orElseThrow();

                assertTrue(saved.isMinimized());
                assertEquals(List.of(TabEntry.view("restore.minimized")), saved.tabs());
                assertTrue(AppState.parsePane(AppState.serialize(saved)).isMinimized());
            } finally {
                f.close();
            }
            return null;
        });
    }

    @Test
    public void closingBackgroundRestoredWindowKeepsActiveComponent() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            Fixture f = new Fixture();
            try {
                ViewContent floatingContent = f.open("restore.background.floating");
                Tab floatingTab = tab(floatingContent);
                SessionRestorer.restoreDetachedPane(detachedLayout("restore.background.floating"),
                        List.of(floatingTab), floatingTab);
                ViewContent mainContent = f.open("restore.background.main");
                Tab mainTab = tab(mainContent);
                NbfxTabPane.attachTab(f.main, mainTab);
                assertSame(f.main, NbfxTabPane.componentPane());

                f.manager.windows().getFirst().close();

                assertSame(f.main, NbfxTabPane.componentPane());
                assertSame(mainTab, f.main.getSelectionModel().getSelectedItem());
            } finally {
                f.close();
            }
            return null;
        });
    }

    @Test
    public void closingActiveRestoredWindowPublishesFallback() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            Fixture f = new Fixture();
            AtomicReference<TabPane> published = new AtomicReference<>();
            Consumer<TabPane> listener = published::set;
            NbfxTabPane.addActivePaneListener(listener);
            try {
                ViewContent content = f.open("restore.active.close");
                Tab tab = tab(content);
                NbfxTabPane pane = (NbfxTabPane) SessionRestorer.restoreDetachedPane(
                        detachedLayout("restore.active.close"), List.of(tab), tab);
                assertSame(pane, NbfxTabPane.componentPane());
                published.set(null);

                f.manager.windows().getFirst().close();

                assertTrue(published.get() != null);
                assertTrue(published.get() != pane);
                assertSame(NbfxTabPane.componentPane(), published.get());
            } finally {
                NbfxTabPane.removeActivePaneListener(listener);
                f.close();
            }
            return null;
        });
    }

    @Test
    public void hiddenRestoredWindowRedockDoesNotShowStage() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            Fixture f = new Fixture();
            try {
                ViewContent content = f.open("restore.hidden.redock");
                Tab tab = tab(content);
                NbfxTabPane pane = (NbfxTabPane) SessionRestorer.restoreDetachedPane(
                        detachedLayout("restore.hidden.redock"), List.of(tab), tab);
                DockWindow window = f.manager.windows().getFirst();
                Stage stage = NbfxTabPane.stageOf(pane);
                AtomicInteger shown = new AtomicInteger();
                stage.addEventHandler(WindowEvent.WINDOW_SHOWN, _ -> shown.incrementAndGet());
                window.hide();

                DockRequest join = new DockRequest(content.id(), pane.groupId(), null,
                        f.main.groupId(), DockPlacement.INTO_GROUP, TransferMode.MOVE, Set.of());
                assertTrue(f.manager.redock(window, join).decision().allowed());

                assertEquals(0, shown.get());
                assertEquals(List.of(tab), f.main.getTabs());
            } finally {
                f.close();
            }
            return null;
        });
    }

    @Test
    public void hiddenFloatingGroupUndoDoesNotShowStage() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            Fixture f = new Fixture();
            try {
                ViewContent content = f.open("restore.hidden.float.undo");
                Tab tab = tab(content);
                NbfxTabPane source = (NbfxTabPane) Docking.area().split(
                        new org.netbeans.modules.nbfx.platform.workspace.DropTarget<>(
                                f.main, javafx.geometry.Side.RIGHT));
                NbfxTabPane.attachTab(source, tab);
                WindowOptions options = new WindowOptions(WindowKind.FLOATING, null, null, null, null,
                        false, 640, 480, true, false, null);
                DockWindow window = f.manager.floatGroup(source.groupId(), options);
                Stage stage = NbfxTabPane.stageOf(source);
                AtomicInteger shown = new AtomicInteger();
                stage.addEventHandler(WindowEvent.WINDOW_SHOWN, _ -> shown.incrementAndGet());
                window.hide();

                f.manager.undo();

                assertEquals(0, shown.get());
                assertTrue(Docking.area().contains(source));
                assertEquals(PaneRole.DOCKED, NbfxTabPane.roleOf(source));
                assertTrue(f.manager.windows().isEmpty());
            } finally {
                f.close();
            }
            return null;
        });
    }

    @Test
    public void restoredWindowClosePublishesContentLifecycleOnce() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            AtomicInteger viewClosed = new AtomicInteger();
            AtomicInteger editorClosed = new AtomicInteger();
            Fixture f = new Fixture(documents -> true);
            NbfxTabPane.ViewLifecycle viewLifecycle = new NbfxTabPane.ViewLifecycle() {
                @Override
                public void viewOpened(ViewProvider view) {
                }

                @Override
                public void viewClosed(ViewProvider view) {
                    viewClosed.incrementAndGet();
                    f.contentManager.find(new ViewContent(view).id())
                            .ifPresent(f.contentManager::close);
                }
            };
            NbfxTabPane.EditorLifecycle editorLifecycle = new NbfxTabPane.EditorLifecycle() {
                @Override
                public void editorOpened(EditorDocument document) {
                }

                @Override
                public void editorClosed(EditorDocument document) {
                    editorClosed.incrementAndGet();
                    f.contentManager.find(new EditorContent(document).id())
                            .ifPresent(f.contentManager::close);
                }
            };
            NbfxTabPane.addViewLifecycle(viewLifecycle);
            NbfxTabPane.addEditorLifecycle(editorLifecycle);
            try {
                ViewContent view = f.open("restore.lifecycle.view");
                EditorContent editor = new EditorContent(dirtyDocument());
                f.contentManager.open(editor);
                Tab viewTab = tab(view);
                Tab editorTab = new Tab("Dirty");
                NbfxTabPane.setDocument(editorTab, editor.document());
                NbfxTabPane pane = (NbfxTabPane) SessionRestorer.restoreDetachedPane(
                        detachedLayout("unused"), List.of(viewTab, editorTab), editorTab);

                f.manager.windows().getFirst().close();

                assertEquals(1, viewClosed.get());
                assertEquals(1, editorClosed.get());
                assertTrue(f.contentManager.openContent().isEmpty());
                assertFalse(NbfxTabPane.tabPanes().contains(pane));
            } finally {
                NbfxTabPane.removeViewLifecycle(viewLifecycle);
                NbfxTabPane.removeEditorLifecycle(editorLifecycle);
                f.close();
            }
            return null;
        });
    }

    @Test
    public void visibleRestoredEditorCloseConfirmsOnce() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            AtomicInteger confirmations = new AtomicInteger();
            Fixture f = new Fixture(documents -> {
                confirmations.incrementAndGet();
                return true;
            });
            try {
                EditorContent content = new EditorContent(dirtyDocument());
                f.contentManager.open(content);
                Tab tab = new Tab("Dirty");
                NbfxTabPane.setDocument(tab, content.document());
                NbfxTabPane pane = (NbfxTabPane) SessionRestorer.restoreDetachedPane(
                        detachedLayout("unused"), List.of(tab), tab);

                f.manager.windows().getFirst().close();

                assertEquals(1, confirmations.get());
                assertTrue(f.manager.windows().isEmpty());
                assertFalse(NbfxTabPane.tabPanes().contains(pane));
            } finally {
                f.close();
            }
            return null;
        });
    }

    @Test
    public void hiddenRestoredEditorCloseConfirmationCanCancel() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            AtomicBoolean allowClose = new AtomicBoolean();
            AtomicInteger confirmations = new AtomicInteger();
            AtomicInteger shown = new AtomicInteger();
            Fixture f = new Fixture(documents -> {
                confirmations.incrementAndGet();
                assertEquals(1, documents.size());
                assertTrue(documents.getFirst().isModified());
                return allowClose.get();
            });
            try {
                EditorDocument document = dirtyDocument();
                EditorContent content = new EditorContent(document);
                f.contentManager.open(content);
                Tab tab = new Tab("Dirty");
                NbfxTabPane.setDocument(tab, document);
                NbfxTabPane pane = (NbfxTabPane) SessionRestorer.restoreDetachedPane(
                        detachedLayout("unused"), List.of(tab), tab);
                DockWindow window = f.manager.windows().getFirst();
                Stage stage = NbfxTabPane.stageOf(pane);
                stage.addEventHandler(WindowEvent.WINDOW_SHOWN, _ -> shown.incrementAndGet());
                window.hide();

                window.close();

                assertEquals(1, confirmations.get());
                assertEquals(List.of(window), f.manager.windows());
                assertFalse(stage.isShowing());
                assertEquals(0, shown.get());
                assertSame(pane, tab.getTabPane());

                allowClose.set(true);
                window.close();

                assertEquals(2, confirmations.get());
                assertTrue(f.manager.windows().isEmpty());
                assertFalse(NbfxTabPane.tabPanes().contains(pane));
                assertTrue(NbfxTabPane.componentPane() != null);
                assertTrue(NbfxTabPane.componentPane() != pane);
                assertEquals(0, shown.get());
            } finally {
                f.close();
            }
            return null;
        });
    }

    private static EditorDocument dirtyDocument() {
        return (EditorDocument) Proxy.newProxyInstance(EditorDocument.class.getClassLoader(),
                new Class<?>[]{EditorDocument.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isModified" -> true;
                    case "getTitle" -> "Dirty";
                    case "getFileObject" -> null;
                    case "getNode" -> new Region();
                    case "getLookup" -> Lookup.EMPTY;
                    case "sharesContentWith" -> false;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "DirtyEditorDocument";
                    default -> {
                        Class<?> type = method.getReturnType();
                        if (type == boolean.class) {
                            yield false;
                        }
                        if (type == int.class) {
                            yield 0;
                        }
                        if (type == long.class) {
                            yield 0L;
                        }
                        if (type == double.class) {
                            yield 0d;
                        }
                        yield null;
                    }
                });
    }

    private static PaneLayout detachedLayout(String viewId) {
        return new PaneLayout(PaneRole.DETACHED, 100, 200, 640, 480,
                List.of(TabEntry.view(viewId)), 0).withKind(PaneKind.EDITOR);
    }

    private static Tab tab(ViewContent content) {
        Tab tab = new Tab(content.title());
        NbfxTabPane.setViewId(tab, content.view().getId());
        tab.getProperties().put(NbfxTabPane.VIEW_PROVIDER_KEY, content.view());
        return tab;
    }
}
