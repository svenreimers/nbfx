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
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.layout.Pane;
import org.junit.Test;
import org.netbeans.api.nbfx.content.DockPlacement;
import org.netbeans.api.nbfx.content.TransferMode;
import org.netbeans.api.nbfx.layout.DockGroupId;
import org.netbeans.api.nbfx.shell.DockDecision;
import org.netbeans.api.nbfx.shell.DockRequest;
import org.netbeans.api.nbfx.shell.PaneRole;
import org.netbeans.api.nbfx.shell.WindowOptions;
import org.netbeans.api.nbfx.view.ViewProvider;
import org.netbeans.api.nbfx.views.ViewContent;
import org.netbeans.modules.nbfx.platform.content.DefaultContentManager;
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
                manager.redo();
                assertEquals(List.of(firstTab, secondTab), other.getTabs());
                assertEquals(secondTab, other.getSelectionModel().getSelectedItem());
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
