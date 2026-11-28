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
package org.netbeans.modules.nbfx.ide.workbench;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.scene.Node;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.VBox;
import junit.framework.Test;
import org.netbeans.api.nbfx.actions.ActionIds;
import org.netbeans.api.nbfx.actions.ActionRegistry;
import org.netbeans.api.nbfx.actions.Command;
import org.netbeans.api.nbfx.actions.RunnableCommand;
import org.netbeans.api.nbfx.editor.EditorDocument;
import org.netbeans.junit.NbModuleSuite;
import org.netbeans.junit.NbTestCase;
import org.netbeans.modules.nbfx.platform.shell.ToolBarContainer;
import org.netbeans.modules.nbfx.platform.shell.ToolBarHost;
import org.netbeans.api.nbfx.shell.DetachedBarsContext;
import org.netbeans.api.nbfx.shell.ShellChromeProfile;
import org.netbeans.api.nbfx.shell.ShellProfileSpec;
import org.netbeans.api.nbfx.shell.WindowKind;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.Lookup;
import org.openide.util.lookup.Lookups;
import org.openide.util.lookup.ProxyLookup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Detached windows use the generic builders with a reduced profile, not the old ActionBars path.
 */
public class DetachedBarsTest extends NbTestCase {

    public DetachedBarsTest(String name) {
        super(name);
    }

    public static Test suite() {
        return NbModuleSuite.createConfiguration(DetachedBarsTest.class)
                .clusters(".*")
                // every module but the GraalVM-only autoload, which cannot be enabled on a plain JDK
                .enableModules("(?!org\\.netbeans\\.libs\\.graalsdk\\.system).*")
                .gui(false)
                .suite();
    }

    public void testDetachedProfileAndToolBars() throws Exception {
        startFx();
        ScenarioSupport.onFx(() -> {
            RecordingRegistry registry = new RecordingRegistry();
            List<Command> scoped = new ArrayList<>();
            // The default lookup supplies the factories of the dynamic menus the real layers declare.
            DetachedBarsContext context = new DetachedBarsContext(
                    new ProxyLookup(Lookups.fixed(registry), Lookup.getDefault()),
                    new SimpleObjectProperty<EditorDocument>(), scoped);
            Node chrome = DetachedBars.create(context, null);
            VBox box = (VBox) chrome;
            MenuBar menuBar = (MenuBar) box.getChildren().get(0);
            ToolBarContainer toolBars = (ToolBarContainer) box.getChildren().get(1);

            assertEquals(List.of("File", "Edit", "View", "Window"), menuIds(menuBar));
            Menu file = menu(menuBar, "File");
            assertNotNull("File menu missing", file);
            assertEquals(List.of(ActionIds.SAVE, ActionIds.SAVE_ALL), itemTexts(file));
            assertFalse("recent projects must not be in a detached File menu",
                    hasText(file, "Open Recent Project"));
            assertFalse("project actions must not be in a detached File menu",
                    hasText(file, "Open Project"));

            assertEquals(List.of("file", "clipboard", "edit"),
                    toolBars.getToolBars().stream().map(ToolBar::getId).toList());
            ToolBar fileBar = toolBars.getToolBars().get(0);
            assertEquals("the detached File bar keeps only the save actions",
                    2, fileBar.getItems().size());
            for (ToolBar bar : toolBars.getToolBars()) {
                if (!bar.getItems().isEmpty()) {
                    assertFalse("detached tool bars must not have drag handles",
                            bar.getItems().get(0).getStyleClass().contains("toolbar-drag-handle"));
                }
            }
            return null;
        });
    }

    public void testSelectedProfileNarrowsLegacyChrome() throws Exception {
        startFx();
        ScenarioSupport.onFx(() -> {
            RecordingRegistry registry = new RecordingRegistry();
            ShellChromeProfile selected = new ShellChromeProfile() {
                @Override
                public boolean includeMenu(String menuId) {
                    return "File".equals(menuId) || "View".equals(menuId);
                }

                @Override
                public boolean includeMenuItem(String menuId, String itemId) {
                    return !"File".equals(menuId) || ActionIds.SAVE.equals(itemId);
                }

                @Override
                public boolean includeToolBar(String toolBarId) {
                    return "file".equals(toolBarId) || "edit".equals(toolBarId);
                }

                @Override
                public boolean includeToolBarItem(String toolBarId, String itemId) {
                    return !"file".equals(toolBarId) || ActionIds.SAVE.equals(itemId);
                }

                @Override
                public boolean useSystemMenuBar() {
                    return false;
                }

                @Override
                public String id() {
                    return "floating-editor";
                }
            };
            DetachedBarsContext context = new DetachedBarsContext(
                    new ProxyLookup(Lookups.fixed(registry, selected), Lookup.getDefault()),
                    new SimpleObjectProperty<EditorDocument>(), new ArrayList<>());
            VBox box = (VBox) DetachedBars.create(context, null);
            MenuBar menuBar = (MenuBar) box.getChildren().get(0);
            ToolBarContainer toolBars = (ToolBarContainer) box.getChildren().get(1);

            assertEquals(List.of("File", "View"), menuIds(menuBar));
            assertFalse(menuBar.isUseSystemMenuBar());
            assertEquals(List.of(ActionIds.SAVE), itemTexts(menu(menuBar, "File")));
            assertEquals(List.of("file", "edit"),
                    toolBars.getToolBars().stream().map(ToolBar::getId).toList());
            assertEquals(1, toolBars.getToolBars().get(0).getItems().size());
            for (ToolBar bar : toolBars.getToolBars()) {
                if (!bar.getItems().isEmpty()) {
                    assertFalse(bar.getItems().get(0).getStyleClass().contains("toolbar-drag-handle"));
                }
            }
            return null;
        });
    }

    public void testSelectedProfileFiltersStaticActionReferences() throws Exception {
        startFx();
        ScenarioSupport.onFx(() -> {
            RecordingRegistry registry = new RecordingRegistry();
            ShellProfileSpec selected = new ShellProfileSpec("floating-editor", WindowKind.FLOATING,
                    Set.of(), null, false);
            FileObject view = FileUtil.createFolder(FileUtil.getConfigRoot(), "NbFx/Menus/View");
            FileObject any = createRef(view, "chrome-any.ref", "chrome.any", 910);
            FileObject main = createRef(view, "chrome-main.ref", "chrome.main", 911);
            FileObject floating = createRef(view, "chrome-floating.ref", "chrome.floating", 912);
            FileObject other = createRef(view, "chrome-other.ref", "chrome.other", 913);
            main.setAttribute("profiles", "main");
            floating.setAttribute("profiles", "floating-editor");
            other.setAttribute("profiles", "some-other-profile");
            try {
                DetachedBarsContext context = new DetachedBarsContext(
                        new ProxyLookup(Lookups.fixed(registry, selected), Lookup.getDefault()),
                        new SimpleObjectProperty<EditorDocument>(), new ArrayList<>());
                VBox box = (VBox) DetachedBars.create(context, null);
                Menu viewMenu = menu((MenuBar) box.getChildren().get(0), "View");

                assertTrue(hasText(viewMenu, "chrome.any"));
                assertTrue(hasText(viewMenu, "chrome.floating"));
                assertFalse(hasText(viewMenu, "chrome.main"));
                assertFalse(hasText(viewMenu, "chrome.other"));
            } finally {
                deleteTree(any);
                deleteTree(main);
                deleteTree(floating);
                deleteTree(other);
            }
            return null;
        });
    }

    public void testViewMenuTogglesTheMainToolBars() throws Exception {
        startFx();
        ScenarioSupport.onFx(() -> {
            RecordingRegistry registry = new RecordingRegistry();
            ToolBar mainBar = new ToolBar();
            mainBar.setId("file");
            mainBar.setAccessibleText("File");
            ToolBarContainer main = new ToolBarContainer(mainBar);
            ToolBarHost host = () -> main;

            DetachedBarsContext context = new DetachedBarsContext(
                    new ProxyLookup(Lookups.fixed(registry), Lookup.getDefault()),
                    new SimpleObjectProperty<EditorDocument>(), new ArrayList<>());
            VBox box = (VBox) DetachedBars.create(context, host);
            MenuBar menuBar = (MenuBar) box.getChildren().get(0);
            Menu view = menu(menuBar, "View");
            assertNotNull("View menu missing", view);
            Menu toolbars = (Menu) view.getItems().stream()
                    .filter(item -> item instanceof Menu)
                    .map(Menu.class::cast)
                    .filter(item -> "Toolbars".equals(item.getText()))
                    .findFirst().orElse(null);
            assertNotNull("Toolbars submenu missing", toolbars);
            CheckMenuItem file = toolbars.getItems().stream()
                    .filter(item -> item instanceof CheckMenuItem)
                    .map(CheckMenuItem.class::cast)
                    .filter(item -> "File".equals(item.getText()))
                    .findFirst().orElse(null);
            assertNotNull("File toolbar toggle missing", file);

            file.setSelected(false);
            assertFalse("the main toolbar is not hidden", mainBar.isVisible());
            ToolBar detachedFile = ((ToolBarContainer) box.getChildren().get(1)).getToolBars().stream()
                    .filter(bar -> "file".equals(bar.getId()))
                    .findFirst().orElseThrow();
            assertFalse("the detached toolbar does not follow the main one", detachedFile.isVisible());
            assertFalse("a hidden detached toolbar still takes up space", detachedFile.isManaged());
            return null;
        });
    }

    private static FileObject createRef(FileObject folder, String name, String actionId, int position) {
        try {
            FileObject ref = FileUtil.createData(folder, name);
            ref.setAttribute("actionId", actionId);
            ref.setAttribute("position", position);
            return ref;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void deleteTree(FileObject file) {
        if (file == null) {
            return;
        }
        for (FileObject child : file.getChildren()) {
            deleteTree(child);
        }
        try {
            file.delete();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void startFx() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
        } catch (IllegalStateException alreadyRunning) {
            latch.countDown();
        }
        assertTrue("the JavaFX toolkit did not start", latch.await(30, TimeUnit.SECONDS));
    }

    private static List<String> menuIds(MenuBar bar) {
        return bar.getMenus().stream().map(Menu::getId).toList();
    }

    private static Menu menu(MenuBar bar, String id) {
        return bar.getMenus().stream().filter(menu -> id.equals(menu.getId())).findFirst().orElse(null);
    }

    private static List<String> itemTexts(Menu menu) {
        return menu.getItems().stream().map(MenuItem::getText).toList();
    }

    private static boolean hasText(Menu menu, String text) {
        return menu.getItems().stream().anyMatch(item -> text.equals(item.getText()));
    }

    private static final class RecordingRegistry implements ActionRegistry {

        private final Map<String, Command> commands = new LinkedHashMap<>();

        @Override
        public void register(Command command) {
            commands.put(command.getId(), command);
        }

        @Override
        public Optional<Command> find(String id) {
            return Optional.of(commands.computeIfAbsent(id,
                    key -> new RunnableCommand(key, key, null, () -> {
                    })));
        }

        @Override
        public Collection<Command> getCommands() {
            return commands.values();
        }

        @Override
        public Optional<Command> createScoped(String id, ObservableValue<EditorDocument> activeDocument) {
            return Optional.empty();
        }
    }
}
