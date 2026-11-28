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
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.layout.Pane;
import org.junit.Test;
import org.netbeans.api.nbfx.shell.PaneRole;
import org.netbeans.api.nbfx.view.ViewProvider;
import org.netbeans.api.nbfx.views.ViewContent;
import org.netbeans.modules.nbfx.platform.content.DefaultContentManager;
import org.netbeans.modules.nbfx.platform.workspace.DropTarget;
import org.netbeans.modules.nbfx.test.fx.JavaFxTestSupport;

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
