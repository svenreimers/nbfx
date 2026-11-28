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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.junit.Test;
import org.netbeans.api.nbfx.product.AboutInfo;
import org.netbeans.api.nbfx.product.Product;
import org.netbeans.api.nbfx.product.ProductIdentity;
import org.netbeans.api.nbfx.product.ProductIcons;
import org.netbeans.api.nbfx.product.ProductProvider;
import org.netbeans.api.nbfx.product.ProductStyle;
import org.netbeans.api.nbfx.shell.ChromeProfilePolicy;
import org.netbeans.api.nbfx.shell.ChromeProfilePolicyProvider;
import org.netbeans.api.nbfx.shell.DockWindow;
import org.netbeans.api.nbfx.shell.PaneRole;
import org.netbeans.api.nbfx.shell.ShellChromeProfile;
import org.netbeans.api.nbfx.shell.WindowContent;
import org.netbeans.api.nbfx.shell.WindowContentState;
import org.netbeans.api.nbfx.shell.WindowId;
import org.netbeans.api.nbfx.shell.WindowKind;
import org.netbeans.api.nbfx.shell.WindowOptions;
import org.netbeans.api.nbfx.view.ViewProvider;
import org.netbeans.api.nbfx.views.ViewContent;
import org.netbeans.modules.nbfx.platform.content.DefaultContentManager;
import org.netbeans.modules.nbfx.platform.workspace.WindowEnvironment;
import org.netbeans.modules.nbfx.test.fx.JavaFxTestSupport;
import org.openide.util.Lookup;
import org.openide.util.test.MockLookup;
import org.openide.util.lookup.Lookups;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Secondary windows resolve and retain effective chrome before their first show. */
public class WindowChromeRuntimeTest {

    @Test
    public void floatResolvesOnceAndUndoRedoRetainsProfile() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
            DefaultContentManager contentManager = new DefaultContentManager();
            AtomicReference<Lookup> services = new AtomicReference<>(services("presentation", "main"));
            AtomicInteger resolutions = new AtomicInteger();
            DefaultWindowManager manager = manager(contentManager, () -> {
                resolutions.incrementAndGet();
                return services.get();
            });
            try {
                Map<String, NbfxTabPane> panes = new LinkedHashMap<>();
                panes.put(PaneRole.MAIN.name(), main);
                Docking.create(new WindowComposition(List.of(), null), panes);
                Docking.runtime().setWindowManager(manager);
                ViewContent content = new ViewContent(new FakeView("chrome.float", "Float"));
                contentManager.open(content);
                Tab tab = new Tab("Float");
                NbfxTabPane.setViewId(tab, "chrome.float");
                NbfxTabPane.attachTab(main, tab);

                DockWindow window = manager.floatContent(content.id(), options(WindowKind.FLOATING));
                assertEquals(StageStyle.UNDECORATED, ((Stage) window.window()).getStyle());
                assertEquals("presentation", window.state().profileId());
                assertEquals("presentation", ((FloatingWindow) window).options().profile().id());
                assertEquals(1, resolutions.get());
                manager.undo();
                assertSame(main, tab.getTabPane());
                services.set(Lookup.EMPTY);
                manager.redo();
                Stage redoneStage = NbfxTabPane.stageOf(tab.getTabPane());
                DockWindow redone = manager.windows().stream()
                        .filter(candidate -> candidate.window() == redoneStage)
                        .findFirst().orElseThrow();
                assertEquals(StageStyle.UNDECORATED, ((Stage) redone.window()).getStyle());
                assertEquals("presentation", redone.state().profileId());
                assertEquals(1, resolutions.get());

                services.set(Lookups.singleton(productProvider()));
                DockWindow current = manager.windows().stream()
                        .filter(candidate -> candidate.window() == redoneStage)
                        .findFirst().orElseThrow();
                DockWindow again = manager.floatContent(content.id(), options(WindowKind.FLOATING));
                assertSame(current, again);
                assertEquals(StageStyle.UNDECORATED, ((Stage) again.window()).getStyle());
                assertEquals("presentation", again.state().profileId());
                assertEquals(1, resolutions.get());
            } finally {
                manager.windows().forEach(DockWindow::close);
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
    public void floatPassesEffectiveProfileToDetachedBars() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
            DefaultContentManager contentManager = new DefaultContentManager();
            DefaultWindowManager manager = manager(contentManager,
                    () -> services("presentation", "main"));
            AtomicReference<ShellChromeProfile> received = new AtomicReference<>();
            WindowEnvironment environment = new WindowEnvironment() {
                @Override
                public Region createDetachedBars(
                        javafx.beans.value.ObservableValue<org.netbeans.api.nbfx.editor.EditorDocument> activeDocument,
                        List<org.netbeans.api.nbfx.actions.Command> scopedCommands,
                        ShellChromeProfile profile) {
                    received.set(profile);
                    return new Region();
                }
            };
            MockLookup.setInstances(environment);
            try {
                Map<String, NbfxTabPane> panes = new LinkedHashMap<>();
                panes.put(PaneRole.MAIN.name(), main);
                Docking.create(new WindowComposition(List.of(), null), panes);
                ViewContent content = new ViewContent(new FakeView("chrome.bars", "Bars"));
                contentManager.open(content);
                Tab tab = new Tab("Bars");
                NbfxTabPane.setViewId(tab, "chrome.bars");
                NbfxTabPane.attachTab(main, tab);

                DockWindow window = manager.floatContent(content.id(), options(WindowKind.FLOATING));

                assertSame(((FloatingWindow) window).options().profile(), received.get());
                assertEquals("presentation", received.get().id());
            } finally {
                manager.windows().forEach(DockWindow::close);
                NbfxTabPane.unregister(main);
                DockRuntime runtime = Docking.runtime();
                if (runtime != null) {
                    runtime.dispose();
                }
                MockLookup.setInstances();
            }
            return null;
        });
    }

    @Test
    public void mixedGroupKeepsCreationProfileAndBarsWhenSelectionChanges() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
            NbfxTabPane navigator = new NbfxTabPane(PaneRole.NAVIGATOR);
            DefaultContentManager contentManager = new DefaultContentManager();
            DefaultWindowManager manager = manager(contentManager, () -> Lookup.EMPTY);
            AtomicReference<ShellChromeProfile> received = new AtomicReference<>();
            AtomicInteger builds = new AtomicInteger();
            Region bars = new Region();
            WindowEnvironment environment = new WindowEnvironment() {
                @Override
                public Region createDetachedBars(
                        javafx.beans.value.ObservableValue<org.netbeans.api.nbfx.editor.EditorDocument> activeDocument,
                        List<org.netbeans.api.nbfx.actions.Command> scopedCommands,
                        ShellChromeProfile profile) {
                    builds.incrementAndGet();
                    received.set(profile);
                    return bars;
                }
            };
            MockLookup.setInstances(environment);
            try {
                Map<String, NbfxTabPane> panes = new LinkedHashMap<>();
                panes.put(PaneRole.MAIN.name(), main);
                panes.put(PaneRole.NAVIGATOR.name(), navigator);
                Docking.create(new WindowComposition(List.of(), null), panes);
                ViewContent first = new ViewContent(new FakeView("chrome.mixed.first", "First",
                        Lookups.singleton(new org.netbeans.api.nbfx.shell.ShellProfileHint("presentation"))));
                ViewContent second = new ViewContent(new FakeView("chrome.mixed.second", "Second",
                        Lookups.singleton(new org.netbeans.api.nbfx.shell.ShellProfileHint("floating-editor"))));
                contentManager.open(first);
                contentManager.open(second);
                Tab firstTab = new Tab("First");
                Tab secondTab = new Tab("Second");
                NbfxTabPane.setViewId(firstTab, "chrome.mixed.first");
                NbfxTabPane.setViewId(secondTab, "chrome.mixed.second");
                NbfxTabPane.attachTab(navigator, firstTab);
                NbfxTabPane.attachTab(navigator, secondTab);
                navigator.getSelectionModel().select(firstTab);

                DockWindow window = manager.floatGroup(navigator.groupId(), options(WindowKind.FLOATING));
                Stage stage = (Stage) window.window();
                assertEquals("presentation", received.get().id());
                assertEquals(1, builds.get());
                assertSame(bars, ((BorderPane) stage.getScene().getRoot()).getTop());

                navigator.getSelectionModel().select(secondTab);

                assertEquals("presentation", window.state().profileId());
                assertSame(received.get(), ((FloatingWindow) window).options().profile());
                assertEquals(1, builds.get());
                assertSame(bars, ((BorderPane) stage.getScene().getRoot()).getTop());
            } finally {
                manager.windows().forEach(DockWindow::close);
                NbfxTabPane.unregister(main);
                NbfxTabPane.unregister(navigator);
                DockRuntime runtime = Docking.runtime();
                if (runtime != null) {
                    runtime.dispose();
                }
                MockLookup.setInstances();
            }
            return null;
        });
    }

    @Test
    public void floatGroupUsesSelectedContentHint() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
            NbfxTabPane navigator = new NbfxTabPane(PaneRole.NAVIGATOR);
            DefaultContentManager contentManager = new DefaultContentManager();
            DefaultWindowManager manager = manager(contentManager,
                    () -> services("presentation", "main"));
            try {
                Map<String, NbfxTabPane> panes = new LinkedHashMap<>();
                panes.put(PaneRole.MAIN.name(), main);
                panes.put(PaneRole.NAVIGATOR.name(), navigator);
                Docking.create(new WindowComposition(List.of(), null), panes);
                ViewContent content = new ViewContent(new FakeView("chrome.group", "Group",
                        Lookups.singleton(new org.netbeans.api.nbfx.shell.ShellProfileHint("floating-editor"))));
                contentManager.open(content);
                Tab tab = new Tab("Group");
                NbfxTabPane.setViewId(tab, "chrome.group");
                NbfxTabPane.attachTab(navigator, tab);

                DockWindow window = manager.floatGroup(navigator.groupId(), options(WindowKind.FLOATING));

                assertEquals("floating-editor", window.state().profileId());
                assertEquals(StageStyle.DECORATED, ((Stage) window.window()).getStyle());
            } finally {
                manager.windows().forEach(DockWindow::close);
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
    public void customWindowUsesEffectiveProfileAndDecoration() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            DefaultContentManager contentManager = new DefaultContentManager();
            DefaultWindowManager manager = manager(contentManager, () -> services("main", "presentation"));
            WindowContent content = new WindowContent() {
                private final StackPane root = new StackPane();

                @Override
                public Node root() {
                    return root;
                }

                @Override
                public Lookup lookup() {
                    return Lookup.EMPTY;
                }

                @Override
                public WindowContentState saveState() {
                    return WindowContentState.empty(WindowId.of("product:chrome.test"), null);
                }

                @Override
                public void close() {
                }
            };
            DockWindow window = manager.createCustomWindow(WindowId.of("product:chrome.test"),
                    options(WindowKind.CUSTOM), content);
            try {
                assertEquals(StageStyle.UNDECORATED, ((Stage) window.window()).getStyle());
                assertEquals("presentation", window.state().profileId());
                assertEquals("presentation", ((CustomWindow) window).options().profile().id());
            } finally {
                window.close();
            }
            return null;
        });
    }

    @Test
    public void profileFailurePrecedesFloatMutation() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            NbfxTabPane main = new NbfxTabPane(PaneRole.MAIN);
            DefaultContentManager contentManager = new DefaultContentManager();
            DefaultWindowManager manager = manager(contentManager,
                    () -> Lookups.singleton(productProvider()));
            try {
                Map<String, NbfxTabPane> panes = new LinkedHashMap<>();
                panes.put(PaneRole.MAIN.name(), main);
                Docking.create(new WindowComposition(List.of(), null), panes);
                ViewContent content = new ViewContent(new FakeView("chrome.failure", "Failure"));
                contentManager.open(content);
                Tab tab = new Tab("Failure");
                NbfxTabPane.setViewId(tab, "chrome.failure");
                NbfxTabPane.attachTab(main, tab);

                assertThrows(IllegalStateException.class,
                        () -> manager.floatContent(content.id(), options(WindowKind.FLOATING)));

                assertSame(main, tab.getTabPane());
                assertTrue(manager.windows().isEmpty());
            } finally {
                NbfxTabPane.unregister(main);
                DockRuntime runtime = Docking.runtime();
                if (runtime != null) {
                    runtime.dispose();
                }
            }
            return null;
        });
    }

    private static DefaultWindowManager manager(DefaultContentManager contentManager,
            java.util.function.Supplier<Lookup> services) {
        return new DefaultWindowManager(contentManager, () -> List.of(), () -> null, null,
                services, CloseConfirmation::confirmClose);
    }

    private static WindowOptions options(WindowKind kind) {
        return new WindowOptions(kind, null, "Chrome", null, null, false,
                640, 480, true, false, null);
    }

    private static Lookup services(String floating, String custom) {
        ChromeProfilePolicy policy = new ChromeProfilePolicy("product:chrome",
                Map.of(WindowKind.FLOATING, floating, WindowKind.CUSTOM, custom), "main");
        return Lookups.fixed((ChromeProfilePolicyProvider) () -> policy, productProvider());
    }

    private static ProductProvider productProvider() {
        Product product = new Product(
                new ProductIdentity("example", "Example", "1.0", null, null, null, null),
                new ProductIcons(List.of(), null, List.of()),
                new ProductStyle(List.of(), List.of(), List.of()),
                "theme.default", "example.default", "shell.default", "product:chrome",
                List.of(), new AboutInfo("", List.of(), List.of(), null));
        return () -> product;
    }

    private static final class FakeView implements ViewProvider {
        private final String id;
        private final String title;
        private final Lookup lookup;

        FakeView(String id, String title) {
            this(id, title, Lookup.EMPTY);
        }

        FakeView(String id, String title, Lookup lookup) {
            this.id = id;
            this.title = title;
            this.lookup = lookup;
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

        @Override
        public Lookup getLookup() {
            return lookup;
        }
    }
}
