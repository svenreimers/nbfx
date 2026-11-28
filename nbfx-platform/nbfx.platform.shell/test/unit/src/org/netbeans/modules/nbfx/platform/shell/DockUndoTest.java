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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.stage.WindowEvent;
import javafx.scene.control.Tab;
import javafx.scene.layout.Pane;
import org.junit.Test;
import org.netbeans.api.nbfx.content.DockPlacement;
import org.netbeans.api.nbfx.content.TransferMode;
import org.netbeans.api.nbfx.layout.DockGroupId;
import org.netbeans.api.nbfx.shell.DockDecision;
import org.netbeans.api.nbfx.shell.DockRequest;
import org.netbeans.api.nbfx.shell.DockResult;
import org.netbeans.api.nbfx.shell.PaneRole;
import org.netbeans.api.nbfx.shell.WindowOptions;
import org.netbeans.api.nbfx.view.ViewProvider;
import org.netbeans.api.nbfx.views.ViewContent;
import org.netbeans.modules.nbfx.platform.content.DefaultContentManager;
import org.netbeans.modules.nbfx.platform.workspace.DropTarget;
import org.netbeans.modules.nbfx.test.fx.JavaFxTestSupport;

import static org.junit.Assert.*;

public class DockUndoTest {

    @Test
    public void undoesAndRedoesMoveAttachAndSplit() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
            NbfxTabPane other = new NbfxTabPane(PaneRole.NAVIGATOR);
            DefaultContentManager contentManager = new DefaultContentManager();
            try {
                Map<String, NbfxTabPane> panes = new LinkedHashMap<>();
                panes.put(PaneRole.MAIN.name(), main);
                panes.put(PaneRole.NAVIGATOR.name(), other);
                Docking.create(new WindowComposition(List.of(), null), panes);
                DefaultWindowManager manager = new DefaultWindowManager(contentManager);
                ViewContent content = new ViewContent(new FakeView("undo.view", "Undo"));
                contentManager.open(content);
                Tab tab = new Tab("Undo");
                NbfxTabPane.setViewId(tab, "undo.view");
                NbfxTabPane.attachTab(main, tab);
                assertFalse(manager.canUndo());

                manager.move(content.id(), other.groupId(), 0);
                assertEquals(other.groupId(), manager.groupOf(content.id()).orElseThrow());
                assertTrue(manager.canUndo());
                manager.undo();
                assertEquals(main.groupId(), manager.groupOf(content.id()).orElseThrow());
                assertTrue(manager.canRedo());
                manager.redo();
                assertEquals(other.groupId(), manager.groupOf(content.id()).orElseThrow());

                manager.attach(content.id(), main.groupId(), -1);
                manager.undo();
                assertEquals(other.groupId(), manager.groupOf(content.id()).orElseThrow());
                manager.redo();
                assertEquals(main.groupId(), manager.groupOf(content.id()).orElseThrow());

                DockRequest split = new DockRequest(content.id(), main.groupId(), null,
                        main.groupId(), DockPlacement.SPLIT_RIGHT, TransferMode.MOVE, Set.of());
                DockGroupId splitGroup = manager.dock(split).group();
                assertTrue(!splitGroup.equals(main.groupId()));
                manager.undo();
                assertEquals(main.groupId(), manager.groupOf(content.id()).orElseThrow());
                manager.redo();
                DockGroupId redoneGroup = manager.groupOf(content.id()).orElseThrow();
                assertTrue(!redoneGroup.equals(main.groupId()));
                assertFalse(manager.canRedo());
            } finally {
                for (javafx.scene.control.TabPane pane : new java.util.ArrayList<>(NbfxTabPane.panesByRole())) {
                    if (pane instanceof NbfxTabPane nbfx && nbfx.role() == PaneRole.DOCKED) {
                        NbfxTabPane.unregister(pane);
                    }
                }
                NbfxTabPane.unregister(main);
                NbfxTabPane.unregister(other);
                DockRuntime runtime = Docking.runtime();
                if (runtime != null) {
                    runtime.dispose();
                }
            }
            return null;
        });
    }

    @Test
    public void newEditAndCloseInvalidateRedoAndUndo() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
            NbfxTabPane other = new NbfxTabPane(PaneRole.NAVIGATOR);
            DefaultContentManager contentManager = new DefaultContentManager();
            try {
                Map<String, NbfxTabPane> panes = new LinkedHashMap<>();
                panes.put(PaneRole.MAIN.name(), main);
                panes.put(PaneRole.NAVIGATOR.name(), other);
                Docking.create(new WindowComposition(List.of(), null), panes);
                DefaultWindowManager manager = new DefaultWindowManager(contentManager);
                ViewContent content = new ViewContent(new FakeView("undo.invalidate", "Invalidate"));
                contentManager.open(content);
                Tab tab = new Tab("Invalidate");
                NbfxTabPane.setViewId(tab, "undo.invalidate");
                NbfxTabPane.attachTab(main, tab);

                manager.move(content.id(), other.groupId(), 0);
                manager.undo();
                assertTrue(manager.canRedo());
                manager.move(content.id(), other.groupId(), 0);
                assertFalse(manager.canRedo());
                assertTrue(manager.canUndo());
                manager.closeGroup(other.groupId());
                assertFalse(manager.canUndo());
                assertFalse(manager.canRedo());
            } finally {
                NbfxTabPane.unregister(main);
                NbfxTabPane.unregister(other);
                DockRuntime runtime = Docking.runtime();
                if (runtime != null) {
                    runtime.dispose();
                }
            }
            return null;
        });
    }

    @Test
    public void groupMoveWithoutStableIdentityDoesNotMutate() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
            NbfxTabPane other = new NbfxTabPane(PaneRole.NAVIGATOR);
            try {
                DefaultWindowManager manager = new DefaultWindowManager(new DefaultContentManager());
                Tab tab = new Tab("No identity");
                NbfxTabPane.attachTab(main, tab);

                assertEquals(DefaultWindowManager.GroupMoveResult.UNAVAILABLE,
                        manager.moveGroupForGesture(main.groupId(), other.groupId(), -1));
                assertEquals(List.of(tab), main.getTabs());
                assertTrue(other.getTabs().isEmpty());
                assertFalse(manager.canUndo());
            } finally {
                NbfxTabPane.unregister(main);
                NbfxTabPane.unregister(other);
            }
            return null;
        });
    }

    @Test
    public void nonRestorableGroupMoveIsRejectedWithoutMutation() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
            NbfxTabPane source = new NbfxTabPane(PaneRole.DOCKED);
            DefaultContentManager contentManager = new DefaultContentManager();
            try {
                DefaultWindowManager manager = new DefaultWindowManager(contentManager);
                ViewContent content = new ViewContent(new FakeView("undo.group.rejected", "Rejected"));
                contentManager.open(content);
                Tab tab = new Tab("Rejected");
                NbfxTabPane.setViewId(tab, "undo.group.rejected");
                NbfxTabPane.attachTab(source, tab);

                assertEquals(DefaultWindowManager.GroupMoveResult.REJECTED,
                        manager.moveGroupForGesture(source.groupId(), main.groupId(), -1));
                assertEquals(List.of(tab), source.getTabs());
                assertTrue(main.getTabs().isEmpty());
                DockRequest request = new DockRequest(content.id(), source.groupId(), null,
                        main.groupId(), DockPlacement.INTO_GROUP, TransferMode.MOVE, Set.of());
                assertEquals("dock.source.unrestorable", manager.canDock(request).reasonKey());
                assertEquals("dock.source.unrestorable", manager.dock(request).decision().reasonKey());
                assertEquals(List.of(tab), source.getTabs());
                assertFalse(manager.canUndo());
            } finally {
                NbfxTabPane.unregister(source);
                NbfxTabPane.unregister(main);
            }
            return null;
        });
    }

    @Test
    public void groupMoveIsOneUndoableGesture() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
            NbfxTabPane other = new NbfxTabPane(PaneRole.NAVIGATOR);
            DefaultContentManager contentManager = new DefaultContentManager();
            try {
                Map<String, NbfxTabPane> panes = new LinkedHashMap<>();
                panes.put(PaneRole.MAIN.name(), main);
                panes.put(PaneRole.NAVIGATOR.name(), other);
                Docking.create(new WindowComposition(List.of(), null), panes);
                DefaultWindowManager manager = new DefaultWindowManager(contentManager);
                ViewContent first = new ViewContent(new FakeView("undo.group.first", "First"));
                ViewContent second = new ViewContent(new FakeView("undo.group.second", "Second"));
                contentManager.open(first);
                contentManager.open(second);
                Tab firstTab = new Tab("First");
                Tab secondTab = new Tab("Second");
                NbfxTabPane.setViewId(firstTab, "undo.group.first");
                NbfxTabPane.setViewId(secondTab, "undo.group.second");
                NbfxTabPane.attachTab(main, firstTab);
                NbfxTabPane.attachTab(main, secondTab);

                manager.moveGroup(main.groupId(), other.groupId(), 0);
                assertEquals(List.of(firstTab, secondTab), other.getTabs());
                assertTrue(manager.canUndo());
                manager.undo();
                assertEquals(List.of(firstTab, secondTab), main.getTabs());
                assertTrue(other.getTabs().isEmpty());
                ViewContent third = new ViewContent(new FakeView("undo.group.third", "Third"));
                contentManager.open(third);
                Tab thirdTab = new Tab("Third");
                NbfxTabPane.setViewId(thirdTab, "undo.group.third");
                NbfxTabPane.attachTab(main, thirdTab);
                manager.redo();
                assertEquals(List.of(firstTab, secondTab), other.getTabs());
                assertEquals(List.of(thirdTab), main.getTabs());
                assertEquals(secondTab, other.getSelectionModel().getSelectedItem());
                manager.undo();
                assertEquals(List.of(firstTab, secondTab, thirdTab), main.getTabs());
                assertTrue(other.getTabs().isEmpty());
            } finally {
                NbfxTabPane.unregister(main);
                NbfxTabPane.unregister(other);
                DockRuntime runtime = Docking.runtime();
                if (runtime != null) {
                    runtime.dispose();
                }
            }
            return null;
        });
    }

    @Test
    public void rootDockedGroupUndoRestoresRootOnlyLayoutAfterDisposal() throws InterruptedException {
        AtomicReference<RootGroupFixture> fixtureRef = new AtomicReference<>();
        try {
            JavaFxTestSupport.onFx(() -> {
                NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
                DefaultContentManager contentManager = new DefaultContentManager();
                Map<String, NbfxTabPane> panes = new LinkedHashMap<>();
                panes.put(PaneRole.MAIN.name(), main);
                Docking.create(new WindowComposition(List.of(), null), panes);
                Docking.runtime().completeStartupLayout();
                DefaultWindowManager manager = new DefaultWindowManager(contentManager);
                ViewContent content = new ViewContent(new FakeView("undo.root.group", "Root"));
                contentManager.open(content);
                Tab tab = new Tab("Root");
                NbfxTabPane.setViewId(tab, "undo.root.group");
                NbfxTabPane root = (NbfxTabPane) Docking.area().applyTree(
                        org.netbeans.modules.nbfx.platform.workspace.DockTrees.parse("L")).getFirst();
                NbfxTabPane.attachTab(root, tab);
                fixtureRef.set(new RootGroupFixture(manager, main, tab, root));
                assertTrue(Docking.area().isRootPane(root));

                assertEquals(DefaultWindowManager.GroupMoveResult.MOVED,
                        manager.moveGroupForGesture(root.groupId(), main.groupId(), -1));
                assertTrue(manager.canUndo());
                manager.undo();
                assertTrue(Docking.area().isRootPane(root));
                assertEquals(List.of(tab), root.getTabs());
                assertTrue(manager.canRedo());
                manager.redo();
                return null;
            });
            JavaFxTestSupport.onFx(() -> {
                RootGroupFixture f = fixtureRef.get();
                assertFalse(NbfxTabPane.tabPanes().contains(f.root()));
                assertTrue(f.manager().canUndo());
                f.manager().undo();
                assertTrue(Docking.area().isRootPane(f.root()));
                assertEquals(List.of(f.root()), Docking.area().leaves());
                assertEquals(List.of(f.tab()), f.root().getTabs());
                return null;
            });
        } finally {
            JavaFxTestSupport.onFx(() -> {
                RootGroupFixture f = fixtureRef.get();
                if (f != null) {
                    NbfxTabPane.unregister(f.main());
                }
                for (javafx.scene.control.TabPane pane : new java.util.ArrayList<>(NbfxTabPane.panesByRole())) {
                    if (pane instanceof NbfxTabPane nbfx && nbfx.role() == PaneRole.DOCKED) {
                        NbfxTabPane.unregister(pane);
                    }
                }
                DockRuntime runtime = Docking.runtime();
                if (runtime != null) {
                    runtime.dispose();
                }
                return null;
            });
        }
    }

    @Test
    public void rootOnlyUndoRejectsContentMinimizedForMaximization() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
            NbfxTabPane navigator = new NbfxTabPane(PaneRole.NAVIGATOR);
            DefaultContentManager contentManager = new DefaultContentManager();
            try {
                Map<String, NbfxTabPane> panes = new LinkedHashMap<>();
                panes.put(PaneRole.MAIN.name(), main);
                panes.put(PaneRole.NAVIGATOR.name(), navigator);
                Docking.create(new WindowComposition(List.of(), null), panes);
                Docking.runtime().completeStartupLayout();
                DefaultWindowManager manager = new DefaultWindowManager(contentManager);
                ViewContent rootContent = new ViewContent(new FakeView("undo.root.maximize", "Root"));
                ViewContent navigatorContent = new ViewContent(new FakeView("undo.root.maximize.nav", "Navigator"));
                contentManager.open(rootContent);
                contentManager.open(navigatorContent);
                Tab rootTab = new Tab("Root");
                Tab navigatorTab = new Tab("Navigator");
                NbfxTabPane.setViewId(rootTab, "undo.root.maximize");
                NbfxTabPane.setViewId(navigatorTab, "undo.root.maximize.nav");
                NbfxTabPane root = (NbfxTabPane) Docking.area().applyTree(
                        org.netbeans.modules.nbfx.platform.workspace.DockTrees.parse("L")).getFirst();
                NbfxTabPane.attachTab(root, rootTab);

                assertEquals(DefaultWindowManager.GroupMoveResult.MOVED,
                        manager.moveGroupForGesture(root.groupId(), main.groupId(), -1));
                NbfxTabPane.attachTab(navigator, navigatorTab);
                Docking.slides().maximize(main.getTabs().getFirst());
                assertTrue(Docking.slides().isMaximized());
                assertFalse(manager.canUndo());
                assertTrue(Docking.slides().isMaximized());
            } finally {
                for (javafx.scene.control.TabPane pane : new java.util.ArrayList<>(NbfxTabPane.panesByRole())) {
                    if (pane instanceof NbfxTabPane nbfx && nbfx.role() == PaneRole.DOCKED) {
                        NbfxTabPane.unregister(pane);
                    }
                }
                NbfxTabPane.unregister(main);
                NbfxTabPane.unregister(navigator);
                DockRuntime runtime = Docking.runtime();
                if (runtime != null) {
                    runtime.dispose();
                }
            }
            return null;
        });
    }

    @Test
    public void minimizedGroupMoveUndoRestoresMinimizedPane() throws InterruptedException {
        AtomicReference<MinimizedGroupFixture> fixtureRef = new AtomicReference<>();
        try {
            JavaFxTestSupport.onFx(() -> {
                NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
                DefaultContentManager contentManager = new DefaultContentManager();
                Map<String, NbfxTabPane> panes = new LinkedHashMap<>();
                panes.put(PaneRole.MAIN.name(), main);
                Docking.create(new WindowComposition(List.of(), null), panes);
                Docking.runtime().completeStartupLayout();
                DefaultWindowManager manager = new DefaultWindowManager(contentManager);
                Docking.runtime().setWindowManager(manager);
                ViewContent first = new ViewContent(new FakeView("undo.minimized.group.first", "First"));
                ViewContent second = new ViewContent(new FakeView("undo.minimized.group.second", "Second"));
                contentManager.open(first);
                contentManager.open(second);
                Tab firstTab = new Tab("First");
                Tab secondTab = new Tab("Second");
                NbfxTabPane.setViewId(firstTab, "undo.minimized.group.first");
                NbfxTabPane.setViewId(secondTab, "undo.minimized.group.second");
                NbfxTabPane source = (NbfxTabPane) Docking.area().split(
                        new DropTarget<>(main, Side.RIGHT));
                NbfxTabPane.attachTab(source, firstTab);
                NbfxTabPane.attachTab(source, secondTab);
                Docking.slides().minimize(source, Side.BOTTOM, 180,
                        Docking.area().currentAnchor(source));
                Docking.slides().slideIn(firstTab);
                fixtureRef.set(new MinimizedGroupFixture(manager, main, firstTab, secondTab, source));

                assertEquals(DefaultWindowManager.GroupMoveResult.MOVED,
                        manager.moveGroupForGesture(source.groupId(), main.groupId(), -1));
                assertEquals(List.of(firstTab, secondTab), main.getTabs());
                assertSame(firstTab, main.getSelectionModel().getSelectedItem());
                assertTrue(manager.canUndo());
                return null;
            });
            JavaFxTestSupport.onFx(() -> {
                MinimizedGroupFixture f = fixtureRef.get();
                assertFalse(NbfxTabPane.tabPanes().contains(f.source()));
                assertTrue(f.manager().canUndo());
                f.manager().undo();
                assertTrue(Docking.area().isMinimized(f.source()));
                assertEquals(Side.BOTTOM, Docking.slides().sideOf(f.source()));
                assertEquals(180, Docking.slides().sizeOf(f.source()), 0.001);
                assertSame(f.source(), Docking.slides().groupOf(f.firstTab()));
                assertTrue(f.main().getTabs().isEmpty());
                assertTrue(f.manager().canRedo());
                f.manager().redo();
                assertEquals(List.of(f.firstTab(), f.secondTab()), f.main().getTabs());
                assertSame(f.firstTab(), f.main().getSelectionModel().getSelectedItem());
                return null;
            });
        } finally {
            JavaFxTestSupport.onFx(() -> {
                MinimizedGroupFixture f = fixtureRef.get();
                if (f != null) {
                    NbfxTabPane.unregister(f.main());
                }
                for (javafx.scene.control.TabPane pane : new java.util.ArrayList<>(NbfxTabPane.panesByRole())) {
                    if (pane instanceof NbfxTabPane nbfx && nbfx.role() == PaneRole.DOCKED) {
                        NbfxTabPane.unregister(pane);
                    }
                }
                DockRuntime runtime = Docking.runtime();
                if (runtime != null) {
                    runtime.dispose();
                }
                return null;
            });
        }
    }

    @Test
    public void floatingGroupMoveUndoRecreatesClosedWindow() throws InterruptedException {
        AtomicReference<FloatingGroupFixture> fixtureRef = new AtomicReference<>();
        try {
            JavaFxTestSupport.onFx(() -> {
                NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
                NbfxTabPane navigator = new NbfxTabPane(PaneRole.NAVIGATOR);
                DefaultContentManager contentManager = new DefaultContentManager();
                Map<String, NbfxTabPane> panes = new LinkedHashMap<>();
                panes.put(PaneRole.MAIN.name(), main);
                panes.put(PaneRole.NAVIGATOR.name(), navigator);
                Docking.create(new WindowComposition(List.of(), null), panes);
                DefaultWindowManager manager = new DefaultWindowManager(contentManager);
                Docking.runtime().setWindowManager(manager);
                ViewContent content = new ViewContent(new FakeView("undo.floating.group.move", "Floating Group"));
                contentManager.open(content);
                Tab tab = new Tab("Floating Group");
                NbfxTabPane.setViewId(tab, "undo.floating.group.move");
                NbfxTabPane.attachTab(main, tab);
                WindowOptions options = new WindowOptions(org.netbeans.api.nbfx.shell.WindowKind.FLOATING,
                        null, "Floating Group", null, null, false, 400, 300, true, false, null);
                manager.floatContent(content.id(), options);
                NbfxTabPane source = (NbfxTabPane) tab.getTabPane();
                AtomicInteger shown = new AtomicInteger();
                NbfxTabPane.stageOf(source).addEventHandler(WindowEvent.WINDOW_SHOWN, _ -> shown.incrementAndGet());
                fixtureRef.set(new FloatingGroupFixture(manager, main, navigator, content, tab, source, shown));
                manager.windows().getFirst().hide();

                assertEquals(DefaultWindowManager.GroupMoveResult.MOVED,
                        manager.moveGroupForGesture(source.groupId(), navigator.groupId(), -1));
                assertEquals(List.of(tab), navigator.getTabs());
                assertEquals(0, shown.get());
                assertTrue(manager.canUndo());
                return null;
            });
            JavaFxTestSupport.onFx(() -> {
                FloatingGroupFixture f = fixtureRef.get();
                assertFalse(NbfxTabPane.tabPanes().contains(f.source()));
                assertTrue(f.manager().windows().isEmpty());
                assertTrue(f.manager().canUndo());
                f.manager().undo();
                assertSame(f.source(), f.tab().getTabPane());
                assertEquals(1, f.manager().windows().size());
                assertEquals(400, f.manager().windows().getFirst().window().getWidth(), 0.001);
                assertEquals(300, f.manager().windows().getFirst().window().getHeight(), 0.001);
                assertEquals(0, f.shown().get());
                assertTrue(f.manager().canRedo());
                f.manager().redo();
                assertEquals(List.of(f.tab()), f.navigator().getTabs());
                assertTrue(f.manager().canUndo());
                return null;
            });
        } finally {
            JavaFxTestSupport.onFx(() -> {
                FloatingGroupFixture f = fixtureRef.get();
                if (f != null) {
                    f.manager().windows().forEach(org.netbeans.api.nbfx.shell.DockWindow::close);
                    NbfxTabPane.unregister(f.main());
                    NbfxTabPane.unregister(f.navigator());
                }
                DockRuntime runtime = Docking.runtime();
                if (runtime != null) {
                    runtime.dispose();
                }
                return null;
            });
        }
    }

    @Test
    public void floatFromDisposablePaneUndoRestoresSourceAfterDisposal() throws InterruptedException {
        AtomicReference<FloatDisposableFixture> fixtureRef = new AtomicReference<>();
        try {
            JavaFxTestSupport.onFx(() -> {
                NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
                DefaultContentManager contentManager = new DefaultContentManager();
                Map<String, NbfxTabPane> panes = new LinkedHashMap<>();
                panes.put(PaneRole.MAIN.name(), main);
                Docking.create(new WindowComposition(List.of(), null), panes);
                Docking.runtime().completeStartupLayout();
                DefaultWindowManager manager = new DefaultWindowManager(contentManager);
                ViewContent content = new ViewContent(new FakeView("undo.float.disposable", "Disposable"));
                contentManager.open(content);
                Tab tab = new Tab("Disposable");
                NbfxTabPane.setViewId(tab, "undo.float.disposable");
                NbfxTabPane source = (NbfxTabPane) Docking.area().split(
                        new DropTarget<>(main, Side.RIGHT));
                NbfxTabPane.attachTab(source, tab);
                String before = org.netbeans.modules.nbfx.platform.workspace.DockTrees.format(
                        Docking.area().tree());
                fixtureRef.set(new FloatDisposableFixture(manager, main, content, tab, source, before));
                WindowOptions options = new WindowOptions(org.netbeans.api.nbfx.shell.WindowKind.FLOATING,
                        null, "Disposable", null, null, false, 400, 300, true, false, null);

                manager.floatContent(content.id(), options);
                assertEquals(1, manager.windows().size());
                assertTrue(manager.canUndo());
                return null;
            });
            JavaFxTestSupport.onFx(() -> {
                FloatDisposableFixture f = fixtureRef.get();
                assertFalse(NbfxTabPane.tabPanes().contains(f.source()));
                assertTrue(f.manager().canUndo());
                f.manager().undo();
                assertTrue(Docking.area().contains(f.source()));
                assertEquals(List.of(f.tab()), f.source().getTabs());
                assertEquals(f.before(), org.netbeans.modules.nbfx.platform.workspace.DockTrees.format(
                        Docking.area().tree()));
                assertTrue(f.manager().canRedo());
                f.manager().redo();
                assertTrue(NbfxTabPane.isFloatingPane(f.tab().getTabPane()));
                assertTrue(f.manager().canUndo());
                return null;
            });
        } finally {
            JavaFxTestSupport.onFx(() -> {
                FloatDisposableFixture f = fixtureRef.get();
                if (f != null) {
                    f.manager().windows().forEach(org.netbeans.api.nbfx.shell.DockWindow::close);
                    NbfxTabPane.unregister(f.main());
                }
                for (javafx.scene.control.TabPane pane : new java.util.ArrayList<>(NbfxTabPane.panesByRole())) {
                    if (pane instanceof NbfxTabPane nbfx && nbfx.role() == PaneRole.DOCKED) {
                        NbfxTabPane.unregister(pane);
                    }
                }
                DockRuntime runtime = Docking.runtime();
                if (runtime != null) {
                    runtime.dispose();
                }
                return null;
            });
        }
    }

    @Test
    public void floatLentTabUndoRestoresLogicalMinimizedGroup() throws InterruptedException {
        AtomicReference<FloatLentFixture> fixtureRef = new AtomicReference<>();
        try {
            JavaFxTestSupport.onFx(() -> {
                NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
                DefaultContentManager contentManager = new DefaultContentManager();
                Map<String, NbfxTabPane> panes = new LinkedHashMap<>();
                panes.put(PaneRole.MAIN.name(), main);
                Docking.create(new WindowComposition(List.of(), null), panes);
                Docking.runtime().completeStartupLayout();
                DefaultWindowManager manager = new DefaultWindowManager(contentManager);
                ViewContent content = new ViewContent(new FakeView("undo.float.lent", "Lent"));
                contentManager.open(content);
                Tab tab = new Tab("Lent");
                NbfxTabPane.setViewId(tab, "undo.float.lent");
                NbfxTabPane source = (NbfxTabPane) Docking.area().split(
                        new DropTarget<>(main, Side.RIGHT));
                NbfxTabPane.attachTab(source, tab);
                Docking.slides().minimize(source, Side.BOTTOM, 180,
                        Docking.area().currentAnchor(source));
                Docking.slides().slideIn(tab);
                fixtureRef.set(new FloatLentFixture(manager, main, tab, source));
                WindowOptions options = new WindowOptions(org.netbeans.api.nbfx.shell.WindowKind.FLOATING,
                        null, "Lent", null, null, false, 400, 300, true, false, null);

                manager.floatContent(content.id(), options);
                assertTrue(manager.canUndo());
                return null;
            });
            JavaFxTestSupport.onFx(() -> {
                FloatLentFixture f = fixtureRef.get();
                assertFalse(NbfxTabPane.tabPanes().contains(f.source()));
                assertTrue(f.manager().canUndo());
                f.manager().undo();
                assertTrue(Docking.area().isMinimized(f.source()));
                assertEquals(Side.BOTTOM, Docking.slides().sideOf(f.source()));
                assertSame(f.source(), Docking.slides().groupOf(f.tab()));
                assertTrue(f.manager().canRedo());
                f.manager().redo();
                assertTrue(NbfxTabPane.isFloatingPane(f.tab().getTabPane()));
                return null;
            });
        } finally {
            JavaFxTestSupport.onFx(() -> {
                FloatLentFixture f = fixtureRef.get();
                if (f != null) {
                    f.manager().windows().forEach(org.netbeans.api.nbfx.shell.DockWindow::close);
                    NbfxTabPane.unregister(f.main());
                }
                for (javafx.scene.control.TabPane pane : new java.util.ArrayList<>(NbfxTabPane.panesByRole())) {
                    if (pane instanceof NbfxTabPane nbfx && nbfx.role() == PaneRole.DOCKED) {
                        NbfxTabPane.unregister(pane);
                    }
                }
                DockRuntime runtime = Docking.runtime();
                if (runtime != null) {
                    runtime.dispose();
                }
                return null;
            });
        }
    }

    @Test
    public void floatContentIsUndoableWhenSourceGroupSurvives() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
            NbfxTabPane navigator = new NbfxTabPane(PaneRole.NAVIGATOR);
            DefaultContentManager contentManager = new DefaultContentManager();
            DefaultWindowManager manager = null;
            try {
                Map<String, NbfxTabPane> panes = new LinkedHashMap<>();
                panes.put(PaneRole.MAIN.name(), main);
                panes.put(PaneRole.NAVIGATOR.name(), navigator);
                Docking.create(new WindowComposition(List.of(), null), panes);
                manager = new DefaultWindowManager(contentManager);
                ViewContent content = new ViewContent(new FakeView("undo.float", "Float"));
                contentManager.open(content);
                Tab tab = new Tab("Float");
                NbfxTabPane.setViewId(tab, "undo.float");
                NbfxTabPane.attachTab(main, tab);
                WindowOptions options = new WindowOptions(org.netbeans.api.nbfx.shell.WindowKind.FLOATING,
                        null, "Float", null, null, false, 400, 300, true, false, null);

                manager.floatContent(content.id(), options);
                assertTrue(!manager.groupOf(content.id()).orElseThrow().equals(main.groupId()));
                assertTrue(manager.canUndo());
                manager.undo();
                assertEquals(main.groupId(), manager.groupOf(content.id()).orElseThrow());
                assertTrue(manager.canRedo());
                manager.redo();
                assertTrue(!manager.groupOf(content.id()).orElseThrow().equals(main.groupId()));
                assertTrue(manager.canUndo());
            } finally {
                if (manager != null) {
                    manager.windows().forEach(org.netbeans.api.nbfx.shell.DockWindow::close);
                }
                NbfxTabPane.unregister(main);
                NbfxTabPane.unregister(navigator);
                DockRuntime runtime = Docking.runtime();
                if (runtime != null) {
                    runtime.dispose();
                }
            }
            return null;
        });
    }

    @Test
    public void groupFloatUndoRestoresFormerAreaAnchor() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
            NbfxTabPane navigator = new NbfxTabPane(PaneRole.NAVIGATOR);
            DefaultContentManager contentManager = new DefaultContentManager();
            DefaultWindowManager manager = null;
            try {
                Map<String, NbfxTabPane> panes = new LinkedHashMap<>();
                panes.put(PaneRole.MAIN.name(), main);
                panes.put(PaneRole.NAVIGATOR.name(), navigator);
                Docking.create(new WindowComposition(List.of(), null), panes);
                manager = new DefaultWindowManager(contentManager);
                ViewContent content = new ViewContent(new FakeView("undo.group.float", "Group Float"));
                contentManager.open(content);
                Tab tab = new Tab("Group Float");
                NbfxTabPane.setViewId(tab, "undo.group.float");
                NbfxTabPane.attachTab(main, tab);
                DockRequest split = new DockRequest(content.id(), main.groupId(), null,
                        main.groupId(), DockPlacement.SPLIT_RIGHT, TransferMode.MOVE, Set.of());
                DockGroupId group = manager.dock(split).group();
                NbfxTabPane pane = (NbfxTabPane) tab.getTabPane();
                String before = org.netbeans.modules.nbfx.platform.workspace.DockTrees.format(
                        Docking.area().tree());
                WindowOptions options = new WindowOptions(org.netbeans.api.nbfx.shell.WindowKind.FLOATING,
                        null, "Group Float", null, null, false, 400, 300, true, false, null);

                manager.floatGroup(group, options);
                assertFalse(Docking.area().contains(pane));
                assertTrue(manager.canUndo());
                manager.undo();
                assertTrue(Docking.area().contains(pane));
                assertEquals(before, org.netbeans.modules.nbfx.platform.workspace.DockTrees.format(
                        Docking.area().tree()));
                manager.redo();
                assertFalse(Docking.area().contains(pane));
                assertTrue(manager.canUndo());
            } finally {
                if (manager != null) {
                    manager.windows().forEach(org.netbeans.api.nbfx.shell.DockWindow::close);
                }
                for (javafx.scene.control.TabPane p : new java.util.ArrayList<>(NbfxTabPane.panesByRole())) {
                    if (p instanceof NbfxTabPane nbfx && nbfx.role() == PaneRole.DOCKED) {
                        NbfxTabPane.unregister(p);
                    }
                }
                NbfxTabPane.unregister(main);
                NbfxTabPane.unregister(navigator);
                DockRuntime runtime = Docking.runtime();
                if (runtime != null) {
                    runtime.dispose();
                }
            }
            return null;
        });
    }

    @Test
    public void splitEdgeRedockIsUndoable() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
            NbfxTabPane navigator = new NbfxTabPane(PaneRole.NAVIGATOR);
            DefaultContentManager contentManager = new DefaultContentManager();
            DefaultWindowManager manager = null;
            try {
                Map<String, NbfxTabPane> panes = new LinkedHashMap<>();
                panes.put(PaneRole.MAIN.name(), main);
                panes.put(PaneRole.NAVIGATOR.name(), navigator);
                Docking.create(new WindowComposition(List.of(), null), panes);
                manager = new DefaultWindowManager(contentManager);
                ViewContent content = new ViewContent(new FakeView("undo.redock.edge", "Redock"));
                contentManager.open(content);
                Tab tab = new Tab("Redock");
                NbfxTabPane.setViewId(tab, "undo.redock.edge");
                NbfxTabPane.attachTab(main, tab);
                WindowOptions options = new WindowOptions(org.netbeans.api.nbfx.shell.WindowKind.FLOATING,
                        null, "Redock", null, null, false, 400, 300, true, false, null);
                org.netbeans.api.nbfx.shell.DockWindow window = manager.floatContent(content.id(), options);
                NbfxTabPane pane = (NbfxTabPane) tab.getTabPane();
                DockRequest edge = new DockRequest(content.id(), pane.groupId(), PaneRole.MAIN,
                        null, DockPlacement.EDGE_BOTTOM, TransferMode.MOVE, Set.of());

                assertTrue(manager.redock(window, edge).decision().allowed());
                assertTrue(Docking.area().contains(pane));
                assertTrue(manager.canUndo());
                manager.undo();
                assertFalse(Docking.area().contains(pane));
                assertEquals(1, manager.windows().size());
                assertEquals(400, manager.windows().getFirst().window().getWidth(), 0.001);
                assertEquals(300, manager.windows().getFirst().window().getHeight(), 0.001);
                manager.redo();
                assertTrue(Docking.area().contains(pane));
                assertTrue(manager.canUndo());
            } finally {
                if (manager != null) {
                    manager.windows().forEach(org.netbeans.api.nbfx.shell.DockWindow::close);
                }
                for (javafx.scene.control.TabPane p : new java.util.ArrayList<>(NbfxTabPane.panesByRole())) {
                    if (p instanceof NbfxTabPane nbfx && nbfx.role() == PaneRole.DOCKED) {
                        NbfxTabPane.unregister(p);
                    }
                }
                NbfxTabPane.unregister(main);
                NbfxTabPane.unregister(navigator);
                DockRuntime runtime = Docking.runtime();
                if (runtime != null) {
                    runtime.dispose();
                }
            }
            return null;
        });
    }

    @Test
    public void groupPlacementRedockRestoresFloatingPaneOnUndo() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
            NbfxTabPane navigator = new NbfxTabPane(PaneRole.NAVIGATOR);
            DefaultContentManager contentManager = new DefaultContentManager();
            DefaultWindowManager manager = null;
            try {
                Map<String, NbfxTabPane> panes = new LinkedHashMap<>();
                panes.put(PaneRole.MAIN.name(), main);
                panes.put(PaneRole.NAVIGATOR.name(), navigator);
                Docking.create(new WindowComposition(List.of(), null), panes);
                manager = new DefaultWindowManager(contentManager);
                ViewContent content = new ViewContent(new FakeView("undo.redock.group", "Group Redock"));
                contentManager.open(content);
                Tab tab = new Tab("Group Redock");
                NbfxTabPane.setViewId(tab, "undo.redock.group");
                NbfxTabPane.attachTab(main, tab);
                WindowOptions options = new WindowOptions(org.netbeans.api.nbfx.shell.WindowKind.FLOATING,
                        null, "Group Redock", null, null, false, 400, 300, true, false, null);
                org.netbeans.api.nbfx.shell.DockWindow window = manager.floatContent(content.id(), options);
                DockGroupId group = manager.groupOf(content.id()).orElseThrow();
                DockRequest join = new DockRequest(content.id(), group, null,
                        navigator.groupId(), DockPlacement.INTO_GROUP, TransferMode.MOVE, Set.of());

                assertTrue(manager.redock(window, join).decision().allowed());
                assertEquals(navigator.groupId(), manager.groupOf(content.id()).orElseThrow());
                assertTrue(manager.canUndo());
                manager.undo();
                assertEquals(group, manager.groupOf(content.id()).orElseThrow());
                assertEquals(1, manager.windows().size());
                assertEquals(400, manager.windows().getFirst().window().getWidth(), 0.001);
                manager.redo();
                assertEquals(navigator.groupId(), manager.groupOf(content.id()).orElseThrow());
            } finally {
                if (manager != null) {
                    manager.windows().forEach(org.netbeans.api.nbfx.shell.DockWindow::close);
                }
                NbfxTabPane.unregister(main);
                NbfxTabPane.unregister(navigator);
                DockRuntime runtime = Docking.runtime();
                if (runtime != null) {
                    runtime.dispose();
                }
            }
            return null;
        });
    }

    @Test
    public void paneRelocationUndoRestoresCurrentAnchor() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
            NbfxTabPane navigator = new NbfxTabPane(PaneRole.NAVIGATOR);
            DefaultContentManager contentManager = new DefaultContentManager();
            try {
                Map<String, NbfxTabPane> panes = new LinkedHashMap<>();
                panes.put(PaneRole.MAIN.name(), main);
                panes.put(PaneRole.NAVIGATOR.name(), navigator);
                Docking.create(new WindowComposition(List.of(), null), panes);
                DefaultWindowManager manager = new DefaultWindowManager(contentManager);
                ViewContent content = new ViewContent(new FakeView("undo.pane.move", "Pane"));
                contentManager.open(content);
                Tab tab = new Tab("Pane");
                NbfxTabPane.setViewId(tab, "undo.pane.move");
                NbfxTabPane.attachTab(navigator, tab);
                String before = org.netbeans.modules.nbfx.platform.workspace.DockTrees.format(
                        Docking.area().tree());

                assertTrue(manager.movePaneForGesture(navigator.groupId(),
                        new DropTarget<>(main, Side.RIGHT)));
                assertTrue(manager.canUndo());
                manager.undo();
                assertEquals(before, org.netbeans.modules.nbfx.platform.workspace.DockTrees.format(
                        Docking.area().tree()));
                assertSame(navigator, Docking.area().primary(PaneRole.NAVIGATOR.name()));
                manager.redo();
                assertTrue(!before.equals(org.netbeans.modules.nbfx.platform.workspace.DockTrees.format(
                        Docking.area().tree())));
                assertSame(navigator, Docking.area().primary(PaneRole.NAVIGATOR.name()));
            } finally {
                NbfxTabPane.unregister(main);
                NbfxTabPane.unregister(navigator);
                DockRuntime runtime = Docking.runtime();
                if (runtime != null) {
                    runtime.dispose();
                }
            }
            return null;
        });
    }

    @Test
    public void policyRefinedGroupDockFromSinglePaneIsUndoable() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
            DefaultContentManager contentManager = new DefaultContentManager();
            try {
                Map<String, NbfxTabPane> panes = new LinkedHashMap<>();
                panes.put(PaneRole.MAIN.name(), main);
                Docking.create(new WindowComposition(List.of(), null), panes);
                Docking.runtime().completeStartupLayout();
                DefaultWindowManager manager = new DefaultWindowManager(contentManager,
                        () -> List.of(request -> request.placement() == DockPlacement.EDGE_BOTTOM
                                ? DockDecision.allow(DockPlacement.INTO_GROUP)
                                : DockDecision.allow(request.placement())));
                ViewContent content = new ViewContent(new FakeView("undo.policy.refined", "Refined"));
                contentManager.open(content);
                Tab tab = new Tab("Refined");
                NbfxTabPane.setViewId(tab, "undo.policy.refined");
                NbfxTabPane source = (NbfxTabPane) Docking.area().split(
                        new DropTarget<>(main, Side.RIGHT));
                NbfxTabPane.attachTab(source, tab);
                String before = org.netbeans.modules.nbfx.platform.workspace.DockTrees.format(
                        Docking.area().tree());
                DockRequest request = new DockRequest(content.id(), source.groupId(), PaneRole.MAIN,
                        null, DockPlacement.EDGE_BOTTOM, TransferMode.MOVE, Set.of());

                DockResult result = manager.dock(request);
                assertTrue(result.decision().allowed());
                assertEquals(DockPlacement.INTO_GROUP, result.decision().placement());
                assertEquals(List.of(tab), main.getTabs());
                assertTrue(manager.canUndo());
                manager.undo();
                assertTrue(Docking.area().contains(source));
                assertEquals(List.of(tab), source.getTabs());
                assertEquals(before, org.netbeans.modules.nbfx.platform.workspace.DockTrees.format(
                        Docking.area().tree()));
                assertTrue(manager.canRedo());
                manager.redo();
                assertEquals(List.of(tab), main.getTabs());
            } finally {
                for (javafx.scene.control.TabPane pane : new java.util.ArrayList<>(NbfxTabPane.panesByRole())) {
                    if (pane instanceof NbfxTabPane nbfx && nbfx.role() == PaneRole.DOCKED) {
                        NbfxTabPane.unregister(pane);
                    }
                }
                NbfxTabPane.unregister(main);
                DockRuntime runtime = Docking.runtime();
                if (runtime != null) {
                    runtime.dispose();
                }
            }
            return null;
        });
    }

    @Test
    public void singlePaneRelocationInvalidatesHistory() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
            NbfxTabPane navigator = new NbfxTabPane(PaneRole.NAVIGATOR);
            DefaultContentManager contentManager = new DefaultContentManager();
            try {
                Map<String, NbfxTabPane> panes = new LinkedHashMap<>();
                panes.put(PaneRole.MAIN.name(), main);
                panes.put(PaneRole.NAVIGATOR.name(), navigator);
                Docking.create(new WindowComposition(List.of(), null), panes);
                DefaultWindowManager manager = new DefaultWindowManager(contentManager);
                ViewContent history = new ViewContent(new FakeView("undo.relocate.history", "History"));
                ViewContent moved = new ViewContent(new FakeView("undo.relocate.moved", "Moved"));
                contentManager.open(history);
                contentManager.open(moved);
                Tab historyTab = new Tab("History");
                Tab movedTab = new Tab("Moved");
                NbfxTabPane.setViewId(historyTab, "undo.relocate.history");
                NbfxTabPane.setViewId(movedTab, "undo.relocate.moved");
                NbfxTabPane.attachTab(main, historyTab);
                NbfxTabPane source = (NbfxTabPane) Docking.area().split(
                        new DropTarget<>(main, Side.RIGHT));
                NbfxTabPane.attachTab(source, movedTab);
                manager.move(history.id(), navigator.groupId(), -1);
                assertTrue(manager.canUndo());

                DockRequest relocate = new DockRequest(moved.id(), source.groupId(), PaneRole.MAIN,
                        null, DockPlacement.EDGE_BOTTOM, TransferMode.MOVE, Set.of());
                assertTrue(manager.dock(relocate).decision().allowed());
                assertSame(source, movedTab.getTabPane());
                assertFalse(manager.canUndo());
                assertFalse(manager.canRedo());
            } finally {
                for (javafx.scene.control.TabPane pane : new java.util.ArrayList<>(NbfxTabPane.panesByRole())) {
                    if (pane instanceof NbfxTabPane nbfx && nbfx.role() == PaneRole.DOCKED) {
                        NbfxTabPane.unregister(pane);
                    }
                }
                NbfxTabPane.unregister(main);
                NbfxTabPane.unregister(navigator);
                DockRuntime runtime = Docking.runtime();
                if (runtime != null) {
                    runtime.dispose();
                }
            }
            return null;
        });
    }

    @Test
    public void rejectedRedoDoesNotRestoreUndoHistory() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
            NbfxTabPane other = new NbfxTabPane(PaneRole.NAVIGATOR);
            DefaultContentManager contentManager = new DefaultContentManager();
            AtomicBoolean allow = new AtomicBoolean(true);
            try {
                Map<String, NbfxTabPane> panes = new LinkedHashMap<>();
                panes.put(PaneRole.MAIN.name(), main);
                panes.put(PaneRole.NAVIGATOR.name(), other);
                Docking.create(new WindowComposition(List.of(), null), panes);
                DefaultWindowManager manager = new DefaultWindowManager(contentManager,
                        () -> List.of(request -> allow.get()
                                ? DockDecision.allow(request.placement())
                                : DockDecision.reject("dock.policy.denied")));
                ViewContent content = new ViewContent(new FakeView("undo.redock", "Redo"));
                contentManager.open(content);
                Tab tab = new Tab("Redo");
                NbfxTabPane.setViewId(tab, "undo.redock");
                NbfxTabPane.attachTab(main, tab);

                DockRequest split = new DockRequest(content.id(), main.groupId(), null,
                        main.groupId(), DockPlacement.SPLIT_RIGHT, TransferMode.MOVE, Set.of());
                assertTrue(manager.dock(split).decision().allowed());
                manager.undo();
                assertTrue(manager.canRedo());
                allow.set(false);
                manager.redo();
                assertEquals(main.groupId(), manager.groupOf(content.id()).orElseThrow());
                assertFalse(manager.canUndo());
                assertFalse(manager.canRedo());
            } finally {
                for (javafx.scene.control.TabPane pane : new java.util.ArrayList<>(NbfxTabPane.panesByRole())) {
                    if (pane instanceof NbfxTabPane nbfx && nbfx.role() == PaneRole.DOCKED) {
                        NbfxTabPane.unregister(pane);
                    }
                }
                NbfxTabPane.unregister(main);
                NbfxTabPane.unregister(other);
                DockRuntime runtime = Docking.runtime();
                if (runtime != null) {
                    runtime.dispose();
                }
            }
            return null;
        });
    }

    private record FloatLentFixture(DefaultWindowManager manager, NbfxTabPane main,
            Tab tab, NbfxTabPane source) {
    }

    private record RootGroupFixture(DefaultWindowManager manager, NbfxTabPane main,
            Tab tab, NbfxTabPane root) {
    }

    private record FloatDisposableFixture(DefaultWindowManager manager, NbfxTabPane main,
            ViewContent content, Tab tab, NbfxTabPane source, String before) {
    }

    private record MinimizedGroupFixture(DefaultWindowManager manager, NbfxTabPane main,
            Tab firstTab, Tab secondTab, NbfxTabPane source) {
    }

    private record FloatingGroupFixture(DefaultWindowManager manager, NbfxTabPane main,
            NbfxTabPane navigator, ViewContent content, Tab tab, NbfxTabPane source, AtomicInteger shown) {
    }

    private static final class FakeView implements ViewProvider {
        private final String id;
        private final String title;

        FakeView(String id, String title) {
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
            return new Pane();
        }
    }
}
