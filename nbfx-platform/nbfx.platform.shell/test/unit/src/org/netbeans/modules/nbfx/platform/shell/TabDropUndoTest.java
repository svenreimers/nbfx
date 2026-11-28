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
import java.util.concurrent.atomic.AtomicReference;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import org.junit.Test;
import org.netbeans.api.nbfx.content.DockPlacement;
import org.netbeans.api.nbfx.editor.CaretInfo;
import org.netbeans.api.nbfx.editor.EditorDocument;
import org.netbeans.api.nbfx.content.TransferMode;
import org.netbeans.api.nbfx.shell.DockDecision;
import org.netbeans.api.nbfx.shell.PaneRole;
import org.netbeans.api.nbfx.view.ViewProvider;
import org.netbeans.api.nbfx.views.ViewContent;
import org.netbeans.modules.nbfx.platform.content.DefaultContentManager;
import org.netbeans.modules.nbfx.platform.workspace.DockTrees;
import org.netbeans.modules.nbfx.platform.workspace.DropTarget;
import org.netbeans.modules.nbfx.test.fx.JavaFxTestSupport;
import org.openide.filesystems.FileObject;

import static org.junit.Assert.*;

public class TabDropUndoTest {

    @Test
    public void pointerJoinAndSplitRecordUndoEdits() throws InterruptedException {
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
                Docking.runtime().setWindowManager(manager);
                ViewContent content = new ViewContent(new FakeView("pointer.drop", "Drop"));
                contentManager.open(content);
                Tab tab = new Tab("Drop");
                NbfxTabPane.setViewId(tab, "pointer.drop");
                NbfxTabPane.attachTab(main, tab);

                assertTrue(NbfxTabPane.applyTabDrop(navigator, tab, null, 0));
                assertEquals(navigator.groupId(), manager.groupOf(content.id()).orElseThrow());
                assertTrue(manager.canUndo());
                manager.undo();
                assertEquals(main.groupId(), manager.groupOf(content.id()).orElseThrow());

                assertTrue(NbfxTabPane.applyTabDrop(main, tab,
                        new DropTarget<>(main, Side.RIGHT), -1));
                assertTrue(!manager.groupOf(content.id()).orElseThrow().equals(main.groupId()));
                assertTrue(manager.canUndo());
                manager.undo();
                assertEquals(main.groupId(), manager.groupOf(content.id()).orElseThrow());
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
    public void pointerDetachRecordsUndoAndKeepsDropPosition() throws InterruptedException {
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
                Docking.runtime().setWindowManager(manager);
                ViewContent content = new ViewContent(new FakeView("pointer.float", "Float"));
                contentManager.open(content);
                Tab tab = new Tab("Float");
                NbfxTabPane.setViewId(tab, "pointer.float");
                NbfxTabPane.attachTab(main, tab);

                NbfxTabPane.detachTabToWindowAt(tab, 10, 20);
                assertTrue(!manager.groupOf(content.id()).orElseThrow().equals(main.groupId()));
                assertEquals(10, manager.windows().getFirst().window().getX(), 0.001);
                assertEquals(20, manager.windows().getFirst().window().getY(), 0.001);
                assertTrue(manager.canUndo());
                manager.undo();
                assertEquals(main.groupId(), manager.groupOf(content.id()).orElseThrow());
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
    public void pointerGroupFloatUsesRecordedManagerPath() throws InterruptedException {
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
                Docking.runtime().setWindowManager(manager);
                ViewContent content = new ViewContent(new FakeView("pointer.group.float", "Group"));
                contentManager.open(content);
                Tab tab = new Tab("Group");
                NbfxTabPane.setViewId(tab, "pointer.group.float");
                NbfxTabPane.attachTab(navigator, tab);

                TabContextMenu.floatGroup(navigator);
                assertFalse(Docking.area().contains(navigator));
                assertEquals(1, manager.windows().size());
                assertTrue(manager.canUndo());
                manager.undo();
                assertTrue(Docking.area().contains(navigator));
                assertEquals(List.of(tab), navigator.getTabs());
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
    public void contextNewGroupUsesManagerUndoAndRedo() throws InterruptedException {
        AtomicReference<NewGroupFixture> fixtureRef = new AtomicReference<>();
        try {
            JavaFxTestSupport.onFx(() -> {
                NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
                NbfxTabPane navigator = new NbfxTabPane(PaneRole.NAVIGATOR);
                DefaultContentManager contentManager = new DefaultContentManager();
                Map<String, NbfxTabPane> panes = new LinkedHashMap<>();
                panes.put(PaneRole.MAIN.name(), main);
                panes.put(PaneRole.NAVIGATOR.name(), navigator);
                Docking.create(new WindowComposition(List.of(), null), panes);
                Docking.runtime().completeStartupLayout();
                DefaultWindowManager manager = new DefaultWindowManager(contentManager);
                Docking.runtime().setWindowManager(manager);
                ViewContent first = new ViewContent(new FakeView("context.new.first", "First"));
                ViewContent second = new ViewContent(new FakeView("context.new.second", "Second"));
                contentManager.open(first);
                contentManager.open(second);
                Tab firstTab = new Tab("First");
                Tab secondTab = new Tab("Second");
                NbfxTabPane.setViewId(firstTab, "context.new.first");
                NbfxTabPane.setViewId(secondTab, "context.new.second");
                NbfxTabPane.attachTab(main, firstTab);
                NbfxTabPane.attachTab(main, secondTab);

                TabContextMenu.newGroup(firstTab);
                NbfxTabPane created = (NbfxTabPane) firstTab.getTabPane();
                fixtureRef.set(new NewGroupFixture(manager, main, navigator, first, firstTab, secondTab, created));
                assertEquals(List.of(secondTab), main.getTabs());
                assertTrue(Docking.area().contains(created));
                assertTrue(!main.groupId().equals(manager.groupOf(first.id()).orElseThrow()));
                assertSame(firstTab, created.getSelectionModel().getSelectedItem());
                assertTrue(manager.canUndo());
                return null;
            });
            JavaFxTestSupport.onFx(() -> {
                NewGroupFixture f = fixtureRef.get();
                f.manager().undo();
                assertEquals(List.of(f.firstTab(), f.secondTab()), f.main().getTabs());
                assertEquals(f.main().groupId(), f.manager().groupOf(f.first().id()).orElseThrow());
                assertSame(f.secondTab(), f.main().getSelectionModel().getSelectedItem());
                assertTrue(f.manager().canRedo());
                return null;
            });
            JavaFxTestSupport.onFx(() -> {
                NewGroupFixture f = fixtureRef.get();
                assertFalse(Docking.area().contains(f.created()));
                assertFalse(NbfxTabPane.tabPanes().contains(f.created()));
                f.manager().redo();
                assertEquals(List.of(f.secondTab()), f.main().getTabs());
                assertTrue(!f.main().groupId().equals(f.manager().groupOf(f.first().id()).orElseThrow()));
                assertSame(f.firstTab(), f.firstTab().getTabPane().getSelectionModel().getSelectedItem());
                assertFalse(f.manager().canRedo());
                return null;
            });
        } finally {
            JavaFxTestSupport.onFx(() -> {
                NewGroupFixture f = fixtureRef.get();
                if (f != null) {
                    NbfxTabPane.unregister(f.main());
                    NbfxTabPane.unregister(f.navigator());
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
    public void newGroupFloatPolicyRefinementRecordsFreshUndo() throws InterruptedException {
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
                manager = new DefaultWindowManager(contentManager, () -> List.of(request ->
                        request.placement() == DockPlacement.SPLIT_RIGHT
                                ? DockDecision.allow(DockPlacement.FLOAT)
                                : DockDecision.allow(request.placement())));
                Docking.runtime().setWindowManager(manager);
                ViewContent first = new ViewContent(new FakeView("context.float.policy.first", "First"));
                ViewContent second = new ViewContent(new FakeView("context.float.policy.second", "Second"));
                contentManager.open(first);
                contentManager.open(second);
                Tab firstTab = new Tab("First");
                Tab secondTab = new Tab("Second");
                NbfxTabPane.setViewId(firstTab, "context.float.policy.first");
                NbfxTabPane.setViewId(secondTab, "context.float.policy.second");
                NbfxTabPane.attachTab(main, firstTab);
                NbfxTabPane.attachTab(main, secondTab);
                manager.move(second.id(), navigator.groupId(), 0);
                manager.undo();
                assertTrue(manager.canRedo());

                TabContextMenu.newGroup(firstTab);
                assertEquals(1, manager.windows().size());
                assertTrue(manager.canUndo());
                assertFalse(manager.canRedo());
                manager.undo();
                assertEquals(List.of(firstTab, secondTab), main.getTabs());
                assertFalse(manager.canUndo());
                assertTrue(manager.canRedo());
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
    public void editorContextGroupActionsUseStableContentIdentity() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
            DefaultContentManager contentManager = new DefaultContentManager();
            try {
                Map<String, NbfxTabPane> panes = new LinkedHashMap<>();
                panes.put(PaneRole.MAIN.name(), main);
                Docking.create(new WindowComposition(List.of(), null), panes);
                Docking.runtime().completeStartupLayout();
                DefaultWindowManager manager = new DefaultWindowManager(contentManager);
                Docking.runtime().setWindowManager(manager);
                EditorContent first = new EditorContent(new FakeEditorDocument("First"));
                EditorContent second = new EditorContent(new FakeEditorDocument("Second"));
                contentManager.open(first);
                contentManager.open(second);
                Tab firstTab = new Tab("First");
                Tab secondTab = new Tab("Second");
                NbfxTabPane.setDocument(firstTab, first.document());
                NbfxTabPane.setDocument(secondTab, second.document());
                NbfxTabPane.attachTab(main, firstTab);
                NbfxTabPane.attachTab(main, secondTab);

                TabContextMenu.newGroup(firstTab);
                assertEquals(first.id(), NbfxTabPane.contentIdForTab(firstTab).orElseThrow());
                assertTrue(!main.groupId().equals(manager.groupOf(first.id()).orElseThrow()));
                assertSame(firstTab, firstTab.getTabPane().getSelectionModel().getSelectedItem());
                assertTrue(manager.canUndo());
                manager.undo();
                assertEquals(List.of(firstTab, secondTab), main.getTabs());
                assertSame(secondTab, main.getSelectionModel().getSelectedItem());
                assertTrue(manager.canRedo());
                manager.redo();
                assertSame(firstTab, firstTab.getTabPane().getSelectionModel().getSelectedItem());

                NbfxTabPane group = (NbfxTabPane) firstTab.getTabPane();
                TabContextMenu.collapseGroup(group);
                assertEquals(List.of(secondTab, firstTab), main.getTabs());
                assertSame(firstTab, main.getSelectionModel().getSelectedItem());
                assertTrue(manager.canUndo());
                manager.undo();
                assertTrue(Docking.area().contains(group));
                assertEquals(List.of(firstTab), group.getTabs());
                assertEquals(List.of(secondTab), main.getTabs());
                assertTrue(manager.canRedo());
                manager.redo();
                assertEquals(List.of(secondTab, firstTab), main.getTabs());
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
    public void contextCollapseGroupUndoRestoresDisposedDockedPane() throws InterruptedException {
        AtomicReference<CollapseFixture> fixtureRef = new AtomicReference<>();
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
                Scene scene = new Scene(Docking.runtime().slides(), 800, 600);
                Stage stage = new Stage();
                stage.setScene(scene);
                stage.show();
                ViewContent target = new ViewContent(new FakeView("context.collapse.target", "Target"));
                ViewContent first = new ViewContent(new FakeView("context.collapse.first", "First"));
                ViewContent second = new ViewContent(new FakeView("context.collapse.second", "Second"));
                contentManager.open(target);
                contentManager.open(first);
                contentManager.open(second);
                Tab targetTab = new Tab("Target");
                Tab firstTab = new Tab("First");
                Tab secondTab = new Tab("Second");
                NbfxTabPane.setViewId(targetTab, "context.collapse.target");
                NbfxTabPane.setViewId(firstTab, "context.collapse.first");
                NbfxTabPane.setViewId(secondTab, "context.collapse.second");
                NbfxTabPane.attachTab(main, targetTab);
                NbfxTabPane source = (NbfxTabPane) Docking.area().split(
                        new DropTarget<>(main, Side.RIGHT));
                NbfxTabPane.attachTab(source, firstTab);
                NbfxTabPane.attachTab(source, secondTab);
                String before = DockTrees.format(Docking.area().tree());
                fixtureRef.set(new CollapseFixture(manager, main, source, targetTab, firstTab, secondTab, before, stage));

                TabContextMenu.collapseGroup(source);
                assertEquals(List.of(targetTab, firstTab, secondTab), main.getTabs());
                assertTrue(source.getTabs().isEmpty());
                assertSame(secondTab, main.getSelectionModel().getSelectedItem());
                assertTrue(manager.canUndo());
                return null;
            });
            JavaFxTestSupport.onFx(() -> {
                CollapseFixture f = fixtureRef.get();
                assertFalse(Docking.area().contains(f.source()));
                assertFalse(NbfxTabPane.tabPanes().contains(f.source()));
                assertTrue(f.manager().canUndo());
                f.manager().undo();
                assertTrue(Docking.area().contains(f.source()));
                assertEquals(List.of(f.firstTab(), f.secondTab()), f.source().getTabs());
                assertEquals(List.of(f.targetTab()), f.main().getTabs());
                assertEquals(f.before(), DockTrees.format(Docking.area().tree()));
                assertSame(f.secondTab(), f.source().getSelectionModel().getSelectedItem());
                assertTrue(f.manager().canRedo());
                f.manager().redo();
                assertEquals(List.of(f.targetTab(), f.firstTab(), f.secondTab()), f.main().getTabs());
                assertTrue(f.source().getTabs().isEmpty());
                assertSame(f.secondTab(), f.main().getSelectionModel().getSelectedItem());
                assertFalse(f.manager().canRedo());
                return null;
            });
            JavaFxTestSupport.onFx(() -> {
                CollapseFixture f = fixtureRef.get();
                assertFalse(Docking.area().contains(f.source()));
                assertFalse(NbfxTabPane.tabPanes().contains(f.source()));
                return null;
            });
        } finally {
            JavaFxTestSupport.onFx(() -> {
                CollapseFixture f = fixtureRef.get();
                if (f != null) {
                    f.stage().close();
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
    public void contextGroupActionsRespectPolicyWithoutPartialFallback() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
            DefaultContentManager contentManager = new DefaultContentManager();
            try {
                Map<String, NbfxTabPane> panes = new LinkedHashMap<>();
                panes.put(PaneRole.MAIN.name(), main);
                Docking.create(new WindowComposition(List.of(), null), panes);
                EditorContent first = new EditorContent(new FakeEditorDocument("First"));
                EditorContent second = new EditorContent(new FakeEditorDocument("Second"));
                EditorContent collapseFirst = new EditorContent(new FakeEditorDocument("Collapse First"));
                EditorContent collapseSecond = new EditorContent(new FakeEditorDocument("Collapse Second"));
                contentManager.open(first);
                contentManager.open(second);
                contentManager.open(collapseFirst);
                contentManager.open(collapseSecond);
                Tab firstTab = new Tab("First");
                Tab secondTab = new Tab("Second");
                Tab collapseFirstTab = new Tab("Collapse First");
                Tab collapseSecondTab = new Tab("Collapse Second");
                NbfxTabPane.setDocument(firstTab, first.document());
                NbfxTabPane.setDocument(secondTab, second.document());
                NbfxTabPane.setDocument(collapseFirstTab, collapseFirst.document());
                NbfxTabPane.setDocument(collapseSecondTab, collapseSecond.document());
                NbfxTabPane.attachTab(main, firstTab);
                NbfxTabPane.attachTab(main, secondTab);
                NbfxTabPane source = (NbfxTabPane) Docking.area().split(
                        new DropTarget<>(main, Side.RIGHT));
                NbfxTabPane.attachTab(source, collapseFirstTab);
                NbfxTabPane.attachTab(source, collapseSecondTab);
                DefaultWindowManager manager = new DefaultWindowManager(contentManager, () -> List.of(request -> {
                    if (request.placement() == DockPlacement.SPLIT_RIGHT
                            || (request.placement() == DockPlacement.INTO_GROUP
                            && request.content().equals(collapseSecond.id()))) {
                        return DockDecision.reject("dock.policy.denied");
                    }
                    return DockDecision.allow(request.placement());
                }));
                Docking.runtime().setWindowManager(manager);
                String before = DockTrees.format(Docking.area().tree());

                TabContextMenu.newGroup(firstTab);
                TabContextMenu.collapseGroup(source);
                assertEquals(List.of(firstTab, secondTab), main.getTabs());
                assertEquals(List.of(collapseFirstTab, collapseSecondTab), source.getTabs());
                assertEquals(before, DockTrees.format(Docking.area().tree()));
                assertFalse(manager.canUndo());
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
    public void samePaneDropAtCurrentPositionDoesNotCreateEdit() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
            DefaultContentManager contentManager = new DefaultContentManager();
            try {
                DefaultWindowManager manager = new DefaultWindowManager(contentManager);
                ViewContent content = new ViewContent(new FakeView("pointer.same", "Same"));
                contentManager.open(content);
                Tab tab = new Tab("Same");
                NbfxTabPane.setViewId(tab, "pointer.same");
                NbfxTabPane.attachTab(main, tab);
                assertTrue(NbfxTabPane.applyTabDrop(main, tab, null, 0));
                assertFalse(manager.canUndo());
            } finally {
                NbfxTabPane.unregister(main);
            }
            return null;
        });
    }

    private record NewGroupFixture(DefaultWindowManager manager, NbfxTabPane main, NbfxTabPane navigator,
            ViewContent first, Tab firstTab, Tab secondTab, NbfxTabPane created) {
    }

    private record CollapseFixture(DefaultWindowManager manager, NbfxTabPane main, NbfxTabPane source,
            Tab targetTab, Tab firstTab, Tab secondTab, String before, Stage stage) {
    }

    private static final class FakeEditorDocument implements EditorDocument {

        private final String title;
        private final ReadOnlyBooleanWrapper modified = new ReadOnlyBooleanWrapper(false);
        private final ReadOnlyBooleanWrapper canUndo = new ReadOnlyBooleanWrapper(false);
        private final ReadOnlyBooleanWrapper canRedo = new ReadOnlyBooleanWrapper(false);
        private final ReadOnlyBooleanWrapper hasSelection = new ReadOnlyBooleanWrapper(false);
        private final ReadOnlyBooleanWrapper editable = new ReadOnlyBooleanWrapper(true);
        private final SimpleObjectProperty<CaretInfo> caretInfo = new SimpleObjectProperty<>();
        private final SimpleObjectProperty<String> lineSeparator = new SimpleObjectProperty<>("\n");

        FakeEditorDocument(String title) {
            this.title = title;
        }

        @Override
        public FileObject getFileObject() {
            return null;
        }

        @Override
        public Node getNode() {
            return new Pane();
        }

        @Override
        public String getTitle() {
            return title;
        }

        @Override
        public boolean isModified() {
            return modified.get();
        }

        @Override
        public ReadOnlyBooleanProperty modifiedProperty() {
            return modified.getReadOnlyProperty();
        }

        @Override
        public void save() {
        }

        @Override
        public void undo() {
        }

        @Override
        public void redo() {
        }

        @Override
        public void copy() {
        }

        @Override
        public void cut() {
        }

        @Override
        public void paste() {
        }

        @Override
        public ReadOnlyBooleanProperty canUndoProperty() {
            return canUndo.getReadOnlyProperty();
        }

        @Override
        public ReadOnlyBooleanProperty canRedoProperty() {
            return canRedo.getReadOnlyProperty();
        }

        @Override
        public ReadOnlyBooleanProperty hasSelectionProperty() {
            return hasSelection.getReadOnlyProperty();
        }

        @Override
        public ReadOnlyBooleanProperty editableProperty() {
            return editable.getReadOnlyProperty();
        }

        @Override
        public ObservableValue<CaretInfo> caretInfoProperty() {
            return caretInfo;
        }

        @Override
        public ObservableValue<String> lineSeparatorProperty() {
            return lineSeparator;
        }

        @Override
        public void setLineSeparator(String separator) {
            lineSeparator.set(separator);
        }
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
