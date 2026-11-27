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
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import org.junit.Test;
import org.netbeans.api.nbfx.shell.PaneRole;
import org.netbeans.api.nbfx.view.ViewProvider;
import org.netbeans.api.nbfx.views.ViewContent;
import org.netbeans.modules.nbfx.platform.content.DefaultContentManager;
import org.netbeans.modules.nbfx.test.fx.JavaFxTestSupport;

import static org.junit.Assert.*;

public class TabMoverUndoTest {

    @Test
    public void confirmedKeyboardMoveRecordsOneUndoEdit() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
            NbfxTabPane navigator = new NbfxTabPane(PaneRole.NAVIGATOR);
            DefaultContentManager contentManager = new DefaultContentManager();
            Stage stage = null;
            try {
                Map<String, NbfxTabPane> panes = new LinkedHashMap<>();
                panes.put(PaneRole.MAIN.name(), main);
                panes.put(PaneRole.NAVIGATOR.name(), navigator);
                Docking.create(new WindowComposition(List.of(), null), panes);
                DefaultWindowManager manager = new DefaultWindowManager(contentManager);
                Docking.runtime().setWindowManager(manager);
                Scene scene = new Scene(Docking.runtime().slides(), 800, 600);
                stage = new Stage();
                stage.setScene(scene);
                stage.show();

                ViewContent content = new ViewContent(new FakeView("keyboard.undo", "Keyboard"));
                contentManager.open(content);
                Tab tab = new Tab("Keyboard");
                NbfxTabPane.setViewId(tab, "keyboard.undo");
                NbfxTabPane.attachTab(main, tab);

                TabMover.moveTab(tab);
                assertTrue(TabMover.isActive());
                Event.fireEvent(scene, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER,
                        false, false, false, false));
                assertFalse(TabMover.isActive());
                assertEquals(navigator.groupId(), manager.groupOf(content.id()).orElseThrow());
                assertTrue(manager.canUndo());
                manager.undo();
                assertEquals(main.groupId(), manager.groupOf(content.id()).orElseThrow());
            } finally {
                TabMover.cancelActive();
                if (stage != null) {
                    stage.close();
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
