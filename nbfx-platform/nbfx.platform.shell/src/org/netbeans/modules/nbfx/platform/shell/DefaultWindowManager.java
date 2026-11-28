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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.geometry.Side;
import javafx.scene.control.Tab;
import javafx.scene.image.Image;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import org.netbeans.api.nbfx.content.Content;
import org.netbeans.api.nbfx.content.ContentId;
import org.netbeans.api.nbfx.content.ContentManager;
import org.netbeans.api.nbfx.content.DockPlacement;
import org.netbeans.api.nbfx.editor.EditorDocument;
import org.netbeans.api.nbfx.layout.DockGroupId;
import org.netbeans.api.nbfx.resources.ResourceRef;
import org.netbeans.api.nbfx.shell.DockDecision;
import org.netbeans.api.nbfx.shell.DockPolicy;
import org.netbeans.api.nbfx.shell.DockWindow;
import org.netbeans.api.nbfx.shell.DockRequest;
import org.netbeans.api.nbfx.shell.DockResult;
import org.netbeans.api.nbfx.shell.PaneRole;
import org.netbeans.api.nbfx.shell.WindowClosePolicy;
import org.netbeans.api.nbfx.shell.WindowContent;
import org.netbeans.api.nbfx.shell.WindowId;
import org.netbeans.api.nbfx.shell.WindowKind;
import org.netbeans.api.nbfx.shell.WindowManager;
import org.netbeans.api.nbfx.shell.WindowOptions;
import org.netbeans.api.nbfx.shell.WindowState;
import org.netbeans.api.nbfx.views.ViewContent;
import org.netbeans.modules.nbfx.platform.workspace.DockArea;
import org.netbeans.modules.nbfx.platform.workspace.DropTarget;
import org.openide.util.Lookup;

/**
 * The {@link WindowManager} over the shell's dock groups and the content manager. It translates
 * stable content and group identities into the tab-pane operations the runtime already supports.
 */
final class DefaultWindowManager implements WindowManager {

    private static final Logger LOG = Logger.getLogger(DefaultWindowManager.class.getName());

    private final ContentManager contentManager;
    private final Supplier<Collection<? extends DockPolicy>> policies;
    private final Supplier<WindowState> mainState;
    private final AppState appState;
    private final Predicate<List<EditorDocument>> closeConfirmation;
    private final Supplier<Lookup> chromeLookup;
    private final Map<NbfxTabPane, FloatingWindow> floating = new LinkedHashMap<>();
    private final Map<WindowId, CustomWindow> custom = new LinkedHashMap<>();
    private final Deque<DockEdit> undoStack = new ArrayDeque<>();
    private final Deque<DockEdit> redoStack = new ArrayDeque<>();
    private MainDockWindow mainWindow;

    private record Location(DockGroupId group, int index, boolean selected) {
    }

    private record DockEdit(List<ContentId> contents, List<Location> undoLocations,
            Runnable undo, Supplier<Boolean> redo, ContentId selected, ContentId redoSelected,
            BooleanSupplier undoApplicable, BooleanSupplier redoApplicable) {
    }

    enum GroupMoveResult {
        MOVED, UNAVAILABLE, REJECTED
    }

    private record GroupSourceState(NbfxTabPane pane, DockArea<TabPane> area,
            boolean inArea, boolean minimized, boolean floating, boolean rootOnly, boolean supported,
            DockArea.Anchor<TabPane> anchor, DockArea.Anchor<TabPane> floatingAnchor,
            double x, double y, double width, double height, WindowOptions options,
            Side slideSide, double slideSize) {
    }

    DefaultWindowManager(ContentManager contentManager) {
        this(contentManager, CloseConfirmation::confirmClose);
    }

    DefaultWindowManager(ContentManager contentManager, Predicate<List<EditorDocument>> closeConfirmation) {
        this(contentManager, () -> Lookup.getDefault().lookupAll(DockPolicy.class), () -> null,
                null, closeConfirmation);
    }

    DefaultWindowManager(ContentManager contentManager, Supplier<Collection<? extends DockPolicy>> policies) {
        this(contentManager, policies, () -> null);
    }

    DefaultWindowManager(ContentManager contentManager, Supplier<Collection<? extends DockPolicy>> policies,
            Supplier<WindowState> mainState) {
        this(contentManager, policies, mainState, null);
    }

    DefaultWindowManager(ContentManager contentManager, Supplier<Collection<? extends DockPolicy>> policies,
            Supplier<WindowState> mainState, AppState appState) {
        this(contentManager, policies, mainState, appState, CloseConfirmation::confirmClose);
    }

    DefaultWindowManager(ContentManager contentManager, Supplier<Collection<? extends DockPolicy>> policies,
            Supplier<WindowState> mainState, AppState appState,
            Predicate<List<EditorDocument>> closeConfirmation) {
        this(contentManager, policies, mainState, appState, Lookup::getDefault, closeConfirmation);
    }

    DefaultWindowManager(ContentManager contentManager, Supplier<Collection<? extends DockPolicy>> policies,
            Supplier<WindowState> mainState, AppState appState, Supplier<Lookup> chromeLookup,
            Predicate<List<EditorDocument>> closeConfirmation) {
        this.contentManager = Objects.requireNonNull(contentManager, "contentManager");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.mainState = Objects.requireNonNull(mainState, "mainState");
        this.appState = appState;
        this.closeConfirmation = Objects.requireNonNull(closeConfirmation, "closeConfirmation");
        this.chromeLookup = Objects.requireNonNull(chromeLookup, "chromeLookup");
    }

    @Override
    public List<DockGroupId> groups() {
        return NbfxTabPane.panesByRole().stream()
                .filter(NbfxTabPane.class::isInstance)
                .map(pane -> ((NbfxTabPane) pane).groupId())
                .toList();
    }

    @Override
    public Optional<DockGroupId> primary(PaneRole role) {
        if (role == null) {
            return Optional.empty();
        }
        TabPane pane = NbfxTabPane.paneWithRole(role);
        return pane instanceof NbfxTabPane nbfx ? Optional.of(nbfx.groupId()) : Optional.empty();
    }

    @Override
    public Optional<DockGroupId> groupOf(ContentId content) {
        return findTab(content)
                .map(Tab::getTabPane)
                .filter(NbfxTabPane.class::isInstance)
                .map(pane -> ((NbfxTabPane) pane).groupId());
    }

    @Override
    public void select(ContentId content) {
        findTab(content).ifPresent(NbfxTabPane::selectTabAndMoveToFront);
    }

    @Override
    public void move(ContentId content, DockGroupId target, int index) {
        Location before = location(content);
        if (!moveInternal(content, target, index, false)) {
            return;
        }
        Location after = location(content);
        recordEdit(content, before, false,
                () -> moveInternal(content, after.group(), after.index(), false));
    }

    @Override
    public void moveGroup(DockGroupId source, DockGroupId target, int index) {
        moveGroupForGesture(source, target, index);
    }

    /** Performs a group move only when its complete inverse can be recorded. */
    GroupMoveResult moveGroupForGesture(DockGroupId source, DockGroupId target, int index) {
        TabPane sourcePane = paneForGroup(source);
        TabPane targetPane = paneForGroup(target);
        if (sourcePane == null || targetPane == null || sourcePane == targetPane) {
            return GroupMoveResult.UNAVAILABLE;
        }
        List<Tab> tabs = groupTabs(sourcePane);
        if (tabs.isEmpty()) {
            return GroupMoveResult.UNAVAILABLE;
        }
        List<ContentId> contents = new ArrayList<>();
        List<Location> locations = new ArrayList<>();
        for (Tab tab : tabs) {
            Optional<ContentId> id = NbfxTabPane.contentIdForTab(tab);
            if (id.isEmpty()) {
                return GroupMoveResult.UNAVAILABLE;
            }
            contents.add(id.get());
            locations.add(new Location(((NbfxTabPane) sourcePane).groupId(),
                    tabs.indexOf(tab), tab.isSelected()));
        }
        NbfxTabPane sourceNbfx = (NbfxTabPane) sourcePane;
        GroupSourceState sourceState = captureGroupSource(sourceNbfx);
        if (!sourceState.supported()) {
            return GroupMoveResult.REJECTED;
        }
        SlidingArea slides = Docking.slides();
        Tab active = sourceState.minimized() && slides != null
                ? slides.selectedOf(sourcePane) : sourcePane.getSelectionModel().getSelectedItem();
        ContentId selected = NbfxTabPane.contentIdForTab(active).orElse(null);
        if (!moveGroupInternal(source, target, index)) {
            return GroupMoveResult.REJECTED;
        }
        redoStack.clear();
        if (!sourceNbfx.role().isPermanent()) {
            recordCustomEdit(() -> restoreGroupSource(sourceState, tabs),
                    () -> moveGroupInternal(source, target, index, tabs), selected,
                    () -> groupSourceUndoApplicable(sourceState, tabs),
                    () -> groupSourceRedoApplicable(sourceState, source, target, tabs));
        } else {
            recordGroupEdit(contents, locations, selected,
                    () -> moveGroupInternal(source, target, index, tabs));
        }
        return GroupMoveResult.MOVED;
    }

    private static List<Tab> groupTabs(TabPane pane) {
        SlidingArea slides = Docking.slides();
        return List.copyOf(slides == null ? pane.getTabs() : slides.tabsOf(pane));
    }

    private static TabPane logicalPane(Tab tab) {
        TabPane pane = tab == null ? null : tab.getTabPane();
        SlidingArea slides = Docking.slides();
        TabPane group = slides == null ? null : slides.groupOf(tab);
        return group == null ? pane : group;
    }

    private GroupSourceState captureGroupSource(NbfxTabPane pane) {
        DockArea<TabPane> area = Docking.area();
        boolean inArea = pane.role() == PaneRole.DOCKED && area != null && area.contains(pane);
        boolean minimized = area != null && area.isMinimized(pane);
        boolean floating = NbfxTabPane.isFloatingPane(pane);
        boolean rootOnly = inArea && area.isRootPane(pane);
        boolean supported = pane.role().isPermanent() || inArea || minimized || floating;
        DockArea.Anchor<TabPane> anchor = inArea ? area.currentAnchor(pane)
                : minimized ? area.anchorOf(pane) : null;
        FloatingWindow window = floating ? this.floating.get(pane) : null;
        DockArea.Anchor<TabPane> floatingAnchor = window == null ? null : window.anchor();
        Stage stage = floating ? NbfxTabPane.stageOf(pane) : null;
        double x = stage == null ? 0 : stage.getX();
        double y = stage == null ? 0 : stage.getY();
        double width = stage == null ? 0 : stage.getWidth();
        double height = stage == null ? 0 : stage.getHeight();
        WindowOptions options = defaultFloatingOptions();
        if (floating) {
            options = window == null ? new WindowOptions(WindowKind.FLOATING, null,
                    stage == null ? null : stage.getTitle(), null, null, false, width, height,
                    stage != null && stage.isResizable(), false, null) : window.options();
        }
        SlidingArea slides = Docking.slides();
        Side slideSide = minimized && slides != null ? slides.sideOf(pane) : null;
        double slideSize = minimized && slides != null ? slides.sizeOf(pane) : 0;
        return new GroupSourceState(pane, area, inArea, minimized, floating, rootOnly, supported, anchor,
                floatingAnchor, x, y, width, height, options, slideSide, slideSize);
    }

    private void restoreGroupSource(GroupSourceState state, List<Tab> tabs) {
        NbfxTabPane pane = state.pane();
        if (!NbfxTabPane.tabPanes().contains(pane)) {
            NbfxTabPane.register(pane);
        }
        for (int i = 0; i < tabs.size(); i++) {
            NbfxTabPane.moveTab(pane, tabs.get(i), i);
        }
        if (state.rootOnly()) {
            state.area().restoreSinglePane(pane);
        } else if (state.inArea()) {
            if (state.area().contains(pane) && !state.area().isRootPane(pane)) {
                state.area().detach(pane);
            }
            if (!state.area().contains(pane)) {
                state.area().reattach(pane, state.anchor());
            }
        } else if (state.minimized()) {
            SlidingArea slides = Docking.slides();
            if (slides == null) {
                state.area().minimize(pane, state.anchor());
            } else {
                slides.minimize(pane, state.slideSide(), state.slideSize(), state.anchor());
            }
        } else if (floating.get(pane) == null) {
            if (NbfxTabPane.isFloatingPane(pane)) {
                NbfxTabPane.closeFloatingPane(pane, true, false);
            }
            NbfxTabPane.showDetachedPane(pane, state.x(), state.y(), state.width(), state.height(),
                    windowStageConfigurator(state.options(), state.x(), state.y()));
            registerFloating(pane, state.floatingAnchor(), state.options());
        } else {
            Stage current = NbfxTabPane.stageOf(pane);
            if (current != null && !current.isShowing()) {
                current.show();
            }
        }
        if (state.floating()) {
            pane.markFloating();
        } else {
            pane.markDocked();
        }
    }

    private boolean canRestoreRootOnly(GroupSourceState state, List<Tab> tabs) {
        Set<TabPane> emptied = new HashSet<>();
        for (Tab tab : tabs) {
            TabPane owner = tab.getTabPane();
            if (owner instanceof NbfxTabPane nbfx && groupTabs(nbfx).stream().allMatch(tabs::contains)) {
                emptied.add(owner);
            }
        }
        SlidingArea slides = Docking.slides();
        if (slides != null) {
            for (TabPane minimized : slides.panesMinimizedForMaximize()) {
                if (minimized == state.pane()) {
                    continue;
                }
                List<Tab> minimizedTabs = groupTabs(minimized);
                if (!minimizedTabs.isEmpty() && !minimizedTabs.stream().allMatch(tabs::contains)) {
                    return false;
                }
                emptied.add(minimized);
            }
        }
        return state.area().canRestoreSinglePane(state.pane(), emptied);
    }

    private boolean groupSourceUndoApplicable(GroupSourceState state, List<Tab> tabs) {
        if (!state.supported() || Docking.area() != state.area()
                || tabs.stream().anyMatch(tab -> tab.getTabPane() == null)) {
            return false;
        }
        if (state.inArea()) {
            return !NbfxTabPane.isFloatingPane(state.pane())
                    && !state.area().isMinimized(state.pane())
                    && !state.area().isSetAside(state.pane())
                    && (!state.rootOnly() || canRestoreRootOnly(state, tabs));
        }
        if (state.minimized()) {
            return !state.area().contains(state.pane());
        }
        return (state.area() == null || !state.area().contains(state.pane()))
                && (NbfxTabPane.stageOf(state.pane()) == null || NbfxTabPane.isFloatingPane(state.pane()));
    }

    private boolean groupSourceRedoApplicable(GroupSourceState state, List<Tab> tabs) {
        if (!state.supported() || Docking.area() != state.area()
                || tabs.stream().anyMatch(tab -> tab.getTabPane() == null)
                || !groupTabs(state.pane()).containsAll(tabs)) {
            return false;
        }
        if (state.inArea()) {
            return state.area().contains(state.pane())
                    && (!state.rootOnly() || state.area().isRootPane(state.pane()))
                    && !NbfxTabPane.isFloatingPane(state.pane())
                    && !state.area().isMinimized(state.pane())
                    && !state.area().isSetAside(state.pane());
        }
        return state.minimized() ? state.area().isMinimized(state.pane())
                : NbfxTabPane.isFloatingPane(state.pane());
    }

    private boolean groupSourceRedoApplicable(GroupSourceState state, DockGroupId source,
            DockGroupId target, List<Tab> tabs) {
        return paneForGroup(source) != null && paneForGroup(target) != null
                && groupSourceRedoApplicable(state, tabs);
    }

    private boolean moveGroupInternal(DockGroupId source, DockGroupId target, int index) {
        TabPane sourcePane = paneForGroup(source);
        return sourcePane != null && moveGroupInternal(source, target, index, groupTabs(sourcePane));
    }

    private boolean moveGroupInternal(DockGroupId source, DockGroupId target, int index, List<Tab> tabs) {
        TabPane sourcePane = paneForGroup(source);
        TabPane targetPane = paneForGroup(target);
        if (sourcePane == null || targetPane == null || sourcePane == targetPane) {
            return false;
        }
        for (int i = 0; i < tabs.size(); i++) {
            NbfxTabPane.moveTab(targetPane, tabs.get(i), index < 0 ? targetPane.getTabs().size() : index + i);
        }
        releaseHiddenFloatingSource(sourcePane);
        return true;
    }

    private void releaseHiddenFloatingSource(TabPane sourcePane) {
        if (!(sourcePane instanceof NbfxTabPane nbfx) || !nbfx.getTabs().isEmpty()
                || !NbfxTabPane.isFloatingPane(nbfx)) {
            return;
        }
        Stage stage = NbfxTabPane.stageOf(nbfx);
        if (stage != null && !stage.isShowing()) {
            FloatingWindow window = floating.remove(nbfx);
            if (window != null) {
                window.markClosed();
            }
            NbfxTabPane.closeFloatingPane(nbfx, true, false);
            NbfxTabPane.unregister(nbfx);
        }
    }

    boolean movePaneForGesture(DockGroupId group, DropTarget<TabPane> target) {
        TabPane pane = paneForGroup(group);
        DockArea<TabPane> area = Docking.area();
        if (!(pane instanceof NbfxTabPane) || area == null || !area.contains(pane)
                || pane == area.mainPane() || target == null || target.pane() == pane) {
            return false;
        }
        DockArea.Anchor<TabPane> anchor = area.currentAnchor(pane);
        if (anchor == null) {
            return false;
        }
        ContentId selected = NbfxTabPane.contentIdForTab(
                pane.getSelectionModel().getSelectedItem()).orElse(null);
        area.relocate(pane, target);
        recordCustomEdit(() -> {
            area.detach(pane);
            area.reattach(pane, anchor);
        }, () -> {
            area.relocate(pane, target);
            return true;
        }, selected, () -> Docking.area() == area && paneForGroup(group) != null,
            () -> Docking.area() == area && paneForGroup(group) != null);
        return true;
    }

    @Override
    public void attach(ContentId content, DockGroupId group, int index) {
        Location before = location(content);
        if (!attachInternal(content, group, index)) {
            return;
        }
        Location after = location(content);
        recordEdit(content, before, true,
                () -> attachInternal(content, after.group(), after.index()));
    }

    private boolean moveInternal(ContentId content, DockGroupId target, int index, boolean select) {
        Tab tab = findTab(content).orElse(null);
        TabPane pane = paneForGroup(target);
        if (tab == null || pane == null) {
            return false;
        }
        NbfxTabPane.moveTab(pane, tab, index);
        if (select) {
            NbfxTabPane.selectTabAndMoveToFront(tab);
        }
        return true;
    }

    private boolean attachInternal(ContentId content, DockGroupId group, int index) {
        Tab tab = findTab(content).orElse(null);
        TabPane pane = paneForGroup(group);
        if (tab == null || pane == null) {
            return false;
        }
        if (index < 0 || index >= pane.getTabs().size()) {
            NbfxTabPane.moveTab(pane, tab, pane.getTabs().size());
            NbfxTabPane.selectTabAndMoveToFront(tab);
        } else {
            NbfxTabPane.moveTab(pane, tab, index);
            NbfxTabPane.selectTabAndMoveToFront(tab);
        }
        return true;
    }

    @Override
    public void closeGroup(DockGroupId group) {
        TabPane pane = paneForGroup(group);
        if (pane != null && TabContextMenu.closeGroup(pane)) {
            undoStack.clear();
            redoStack.clear();
        }
    }

    @Override
    public DockDecision canDock(DockRequest request) {
        if (request == null || request.content() == null) {
            return DockDecision.reject("dock.request.invalid");
        }
        if (contentManager.find(request.content()).isEmpty()) {
            return DockDecision.reject("dock.content.missing");
        }
        DockPlacement placement = request.placement();
        DockDecision structural = validatePlacement(request, placement);
        if (structural != null) {
            return structural;
        }
        DockDecision decision = DockDecision.allow(placement);
        for (DockPolicy policy : policies.get()) {
            if (policy == null) {
                continue;
            }
            DockDecision policyDecision = policy.evaluate(request);
            if (policyDecision == null) {
                continue;
            }
            if (!policyDecision.allowed()) {
                return policyDecision;
            }
            decision = policyDecision;
        }
        DockPlacement resolvedPlacement = decision.placement();
        DockDecision refined = validatePlacement(request, resolvedPlacement);
        if (refined != null) {
            return refined;
        }
        Tab tab = findTab(request.content()).orElse(null);
        TabPane source = logicalPane(tab);
        if (source instanceof NbfxTabPane nbfx && groupTabs(source).size() == 1
                && dockEmptiesSource(request, resolvedPlacement, source, Docking.area())) {
            GroupSourceState sourceState = captureGroupSource(nbfx);
            if (!sourceState.supported()) {
                return DockDecision.reject("dock.source.unrestorable");
            }
        }
        return decision;
    }

    @Override
    public DockResult dock(DockRequest request) {
        Location before = request == null ? null : location(request.content());
        Tab tab = request == null ? null : findTab(request.content()).orElse(null);
        TabPane source = logicalPane(tab);
        DockArea<TabPane> area = Docking.area();
        boolean singlePaneBefore = source != null && groupTabs(source).size() == 1;
        GroupSourceState sourceState = singlePaneBefore && source instanceof NbfxTabPane nbfx
                ? captureGroupSource(nbfx) : null;
        DockDecision decision = canDock(request);
        DockResult result = performDock(request, decision);
        if (!result.decision().allowed()) {
            return result;
        }
        DockPlacement placement = result.decision().placement();
        boolean sourceEmptied = singlePaneBefore && groupTabs(source).isEmpty();
        if (placement != DockPlacement.FLOAT && singlePaneBefore && !sourceEmptied
                && (isSplitPlacement(placement) || isEdgePlacement(placement))
                && area != null && area.contains(source)) {
            undoStack.clear();
            redoStack.clear();
            return result;
        }
        if (placement != DockPlacement.FLOAT && before != null) {
            if (sourceEmptied && sourceState != null && !sourceState.pane().role().isPermanent()) {
                DockGroupId redoTarget = resolveTargetGroup(request);
                recordCustomEdit(() -> restoreGroupSource(sourceState, List.of(tab)),
                        () -> performDock(request).decision().allowed(), request.content(),
                        () -> groupSourceUndoApplicable(sourceState, List.of(tab)),
                        () -> groupSourceRedoApplicable(sourceState, List.of(tab))
                                && (redoTarget == null || paneForGroup(redoTarget) != null));
            } else if (paneForGroup(before.group()) != null) {
                ContentId undoSelected = isGroupPlacement(placement) || before.selected()
                        ? request.content() : null;
                ContentId redoSelected = isGroupPlacement(placement) || isSplitPlacement(placement)
                        || isEdgePlacement(placement) ? request.content() : null;
                recordEdit(request.content(), before, undoSelected, redoSelected,
                        () -> performDock(request).decision().allowed());
            } else {
                undoStack.clear();
                redoStack.clear();
            }
        }
        return result;
    }

    private DockResult performDock(DockRequest request) {
        return performDock(request, canDock(request));
    }

    private DockResult performDock(DockRequest request, DockDecision decision) {
        if (!decision.allowed()) {
            return DockResult.rejected(decision);
        }
        Tab tab = findTab(request.content()).orElse(null);
        if (tab == null) {
            return DockResult.rejected(DockDecision.reject("dock.content.missing"));
        }
        DockPlacement placement = decision.placement();
        if (placement == DockPlacement.FLOAT) {
            floatContent(request.content(), defaultFloatingOptions());
            DockGroupId group = groupOf(request.content()).orElse(null);
            return group == null
                    ? DockResult.rejected(DockDecision.reject("dock.content.missing"))
                    : DockResult.accepted(decision, group);
        }
        if (isGroupPlacement(placement)) {
            DockGroupId target = resolveTargetGroup(request);
            TabPane pane = paneForGroup(target);
            if (pane == null) {
                return DockResult.rejected(DockDecision.reject("dock.target.missing"));
            }
            int index = -1;
            if (isRelativePlacement(placement)) {
                Tab targetTab = findTab(request.targetContent()).orElse(null);
                if (targetTab == null || targetTab.getTabPane() != pane) {
                    return DockResult.rejected(DockDecision.reject("dock.target.missing"));
                }
                index = pane.getTabs().indexOf(targetTab);
                if (tab.getTabPane() == pane && pane.getTabs().indexOf(tab) < index) {
                    index--;
                }
                if (placement == DockPlacement.AFTER) {
                    index++;
                }
                NbfxTabPane.moveTab(pane, tab, index);
                NbfxTabPane.selectTabAndMoveToFront(tab);
            } else {
                NbfxTabPane.moveTab(pane, tab, pane.getTabs().size());
                NbfxTabPane.selectTabAndMoveToFront(tab);
            }
            return DockResult.accepted(decision, ((NbfxTabPane) pane).groupId());
        }
        if (isSplitPlacement(placement) || isEdgePlacement(placement)) {
            DockArea<TabPane> area = Docking.area();
            if (area == null) {
                return DockResult.rejected(DockDecision.reject("dock.target.missing"));
            }
            TabPane targetPane = isSplitPlacement(placement)
                    ? paneForGroup(resolveTargetGroup(request)) : null;
            if (isSplitPlacement(placement) && (targetPane == null || !area.contains(targetPane))) {
                return DockResult.rejected(DockDecision.reject("dock.target.missing"));
            }
            TabPane source = tab.getTabPane();
            if (source != null && area.contains(source) && source.getTabs().size() == 1
                    && (isEdgePlacement(placement) || source != targetPane)) {
                DropTarget<TabPane> target = dropTargetFor(placement, targetPane);
                area.relocate(source, target);
                DockGroupId group = source instanceof NbfxTabPane nbfx ? nbfx.groupId() : null;
                return group == null
                        ? DockResult.rejected(DockDecision.reject("dock.target.missing"))
                        : DockResult.accepted(decision, group);
            }
            if (source != null) {
                source.getTabs().remove(tab);
            }
            TabPane created = area.split(dropTargetFor(placement, targetPane));
            NbfxTabPane.moveTab(created, tab, 0);
            DockGroupId group = created instanceof NbfxTabPane nbfx ? nbfx.groupId() : null;
            return group == null
                    ? DockResult.rejected(DockDecision.reject("dock.target.missing"))
                    : DockResult.accepted(decision, group);
        }
        return DockResult.rejected(DockDecision.reject("dock.placement.unsupported"));
    }

    @Override
    public boolean canUndo() {
        while (!undoStack.isEmpty() && !undoStack.peek().undoApplicable().getAsBoolean()) {
            undoStack.pop();
        }
        return !undoStack.isEmpty();
    }

    @Override
    public void undo() {
        if (!canUndo()) {
            return;
        }
        DockEdit edit = undoStack.pop();
        if (edit.undo() != null) {
            edit.undo().run();
        } else {
            for (int i = 0; i < edit.contents().size(); i++) {
                Location location = edit.undoLocations().get(i);
                moveInternal(edit.contents().get(i), location.group(), location.index(), false);
            }
        }
        if (edit.selected() != null) {
            select(edit.selected());
        }
        redoStack.push(edit);
    }

    @Override
    public boolean canRedo() {
        while (!redoStack.isEmpty() && !redoStack.peek().redoApplicable().getAsBoolean()) {
            redoStack.pop();
        }
        return !redoStack.isEmpty();
    }

    @Override
    public void redo() {
        if (!canRedo()) {
            return;
        }
        DockEdit edit = redoStack.pop();
        boolean success = edit.redo().get();
        if (success) {
            if (edit.redoSelected() != null) {
                select(edit.redoSelected());
            }
            undoStack.push(edit);
        }
    }

    private Location location(ContentId content) {
        Tab tab = findTab(content).orElse(null);
        if (tab == null || !(logicalPane(tab) instanceof NbfxTabPane pane)) {
            return null;
        }
        return new Location(pane.groupId(), groupTabs(pane).indexOf(tab), tab.isSelected());
    }

    private void recordEdit(ContentId content, Location before, boolean select,
            Supplier<Boolean> redo) {
        recordEdit(content, before, select ? content : null, select ? content : null, redo);
    }

    private void recordEdit(ContentId content, Location before, ContentId undoSelected,
            ContentId redoSelected, Supplier<Boolean> redo) {
        if (before == null) {
            return;
        }
        recordGroupEdit(List.of(content), List.of(before), null, undoSelected, redoSelected, redo,
                () -> paneForGroup(before.group()) != null && findTab(content).isPresent(),
                () -> findTab(content).isPresent());
    }

    private void recordGroupEdit(List<ContentId> contents, List<Location> undoLocations,
            ContentId selected, Supplier<Boolean> redo) {
        recordGroupEdit(contents, undoLocations, null, selected, selected, redo,
                () -> undoLocations.stream().allMatch(location -> paneForGroup(location.group()) != null)
                        && contents.stream().allMatch(content -> findTab(content).isPresent()),
                () -> contents.stream().allMatch(content -> findTab(content).isPresent()));
    }

    private void recordGroupEdit(List<ContentId> contents, List<Location> undoLocations,
            Runnable undo, ContentId selected, Supplier<Boolean> redo,
            BooleanSupplier undoApplicable, BooleanSupplier redoApplicable) {
        recordGroupEdit(contents, undoLocations, undo, selected, selected, redo,
                undoApplicable, redoApplicable);
    }

    private void recordGroupEdit(List<ContentId> contents, List<Location> undoLocations,
            Runnable undo, ContentId undoSelected, ContentId redoSelected, Supplier<Boolean> redo,
            BooleanSupplier undoApplicable, BooleanSupplier redoApplicable) {
        if (contents.isEmpty() || contents.size() != undoLocations.size()) {
            return;
        }
        redoStack.clear();
        undoStack.push(new DockEdit(List.copyOf(contents), List.copyOf(undoLocations), undo,
                redo, undoSelected, redoSelected, undoApplicable, redoApplicable));
        while (undoStack.size() > 100) {
            undoStack.removeLast();
        }
    }

    private void recordCustomEdit(Runnable undo, Supplier<Boolean> redo, ContentId selected,
            BooleanSupplier undoApplicable, BooleanSupplier redoApplicable) {
        redoStack.clear();
        undoStack.push(new DockEdit(List.of(), List.of(), undo, redo, selected, selected,
                undoApplicable, redoApplicable));
        while (undoStack.size() > 100) {
            undoStack.removeLast();
        }
    }

    // --- Floating windows ------------------------------------------------------------------------

    private WindowOptions effectiveWindowOptions(WindowOptions options, Lookup contentLookup) {
        return WindowChromeProfiles.effective(options, contentLookup, chromeLookup.get());
    }

    private Lookup contentLookup(Content content) {
        return content == null ? Lookup.EMPTY : content.getLookup();
    }

    private Lookup tabLookup(List<Tab> tabs, Tab active) {
        Tab tab = active != null ? active : tabs.isEmpty() ? null : tabs.getFirst();
        Content content = NbfxTabPane.contentIdForTab(tab)
                .flatMap(contentManager::find).orElse(null);
        return contentLookup(content);
    }

    @Override
    public DockWindow floatContent(ContentId content, WindowOptions options) {
        WindowOptions checked = requireFloatingOptions(options);
        return floatContentRecorded(content, checked, defaultFloatingX(checked), defaultFloatingY(checked));
    }

    DockWindow floatContentAtForGesture(ContentId content, double x, double y) {
        return floatContentRecorded(content, defaultFloatingOptions(), x, y);
    }

    private DockWindow floatContentRecorded(ContentId content, WindowOptions options, double x, double y) {
        Location before = location(content);
        Tab sourceTab = findTab(content).orElse(null);
        if (sourceTab == null) {
            throw new IllegalArgumentException("Content is not docked");
        }
        TabPane sourcePane = logicalPane(sourceTab);
        boolean alreadyFloating = sourcePane instanceof NbfxTabPane source
                && NbfxTabPane.isFloatingPane(source);
        GroupSourceState sourceState = sourcePane instanceof NbfxTabPane nbfx
                && !nbfx.role().isPermanent() && groupTabs(sourcePane).size() == 1
                ? captureGroupSource(nbfx) : null;
        WindowOptions effective = alreadyFloating ? options
                : effectiveWindowOptions(options, contentLookup(contentManager.find(content).orElse(null)));
        DockWindow window = floatContentAt(content, effective, x, y);
        if (alreadyFloating) {
            return window;
        }
        if (sourceState != null && sourceState.supported()) {
            recordCustomEdit(() -> restoreGroupSource(sourceState, List.of(sourceTab)), () -> {
                floatContentAt(content, effective, x, y);
                return true;
            }, before == null || !before.selected() ? null : content,
                    () -> groupSourceUndoApplicable(sourceState, List.of(sourceTab)),
                    () -> groupSourceRedoApplicable(sourceState, List.of(sourceTab)));
        } else if (sourceState == null && before != null && paneForGroup(before.group()) != null) {
            recordEdit(content, before, before.selected(), () -> {
                floatContentAt(content, effective, x, y);
                return true;
            });
        } else {
            undoStack.clear();
            redoStack.clear();
        }
        return window;
    }

    private FloatingWindow floatContentAt(ContentId content, WindowOptions options, double x, double y) {
        Objects.requireNonNull(content, "content");
        WindowOptions checked = requireFloatingOptions(options);
        Tab tab = findTab(content).orElseThrow(() -> new IllegalArgumentException("Content is not docked"));
        if (tab.getTabPane() instanceof NbfxTabPane nbfx && NbfxTabPane.isFloatingPane(nbfx)) {
            FloatingWindow existing = floating.get(nbfx);
            if (existing == null) {
                existing = registerFloating(nbfx, null, defaultFloatingOptions());
            }
            existing.show();
            return existing;
        }
        Consumer<Stage> configure = windowStageConfigurator(checked, x, y);
        NbfxTabPane pane = NbfxTabPane.floatTab(tab, x, y, checked.width(), checked.height(), configure);
        return registerFloating(pane, null, checked);
    }

    @Override
    public DockWindow floatGroup(DockGroupId group, WindowOptions options) {
        WindowOptions checked = requireFloatingOptions(options);
        return floatGroupRecorded(group, checked, defaultFloatingX(checked), defaultFloatingY(checked));
    }

    DockWindow floatGroupAtForGesture(DockGroupId group, double x, double y, double width, double height) {
        WindowOptions options = new WindowOptions(WindowKind.FLOATING, null, null, null, null,
                false, width, height, true, false, null);
        return floatGroupRecorded(group, options, x, y);
    }

    private DockWindow floatGroupRecorded(DockGroupId group, WindowOptions options, double x, double y) {
        TabPane pane = paneForGroup(group);
        if (!(pane instanceof NbfxTabPane)) {
            throw new IllegalArgumentException("Unknown dock group: " + group);
        }
        boolean alreadyFloating = NbfxTabPane.isFloatingPane(pane);
        List<Tab> tabs = pane == null ? List.of() : groupTabs(pane);
        Tab active = pane == null ? null : pane.getSelectionModel().getSelectedItem();
        ContentId selected = NbfxTabPane.contentIdForTab(active).orElse(null);
        WindowOptions effective = alreadyFloating ? options
                : effectiveWindowOptions(options, tabLookup(tabs, active));
        FloatingWindow window = floatGroupAt(group, effective, x, y);
        if (alreadyFloating) {
            return window;
        }
        DockArea.Anchor<TabPane> anchor = window.anchor();
        if (anchor != null) {
            recordCustomEdit(() -> {
                NbfxTabPane.closeFloatingPane(window.pane(), true, false);
                window.markClosed();
                floating.remove(window.pane(), window);
                Docking.area().reattach(window.pane(), anchor);
                window.pane().markDocked();
            }, () -> {
                floatGroupAt(group, effective, x, y);
                return true;
            }, selected, () -> Docking.area() != null && paneForGroup(group) != null,
                () -> Docking.area() != null && paneForGroup(group) != null);
        } else {
            undoStack.clear();
            redoStack.clear();
        }
        return window;
    }

    private FloatingWindow floatGroupAt(DockGroupId group, WindowOptions options, double x, double y) {
        Objects.requireNonNull(group, "group");
        WindowOptions checked = requireFloatingOptions(options);
        TabPane pane = paneForGroup(group);
        if (!(pane instanceof NbfxTabPane nbfx)) {
            throw new IllegalArgumentException("Unknown dock group: " + group);
        }
        if (nbfx.role() == PaneRole.MAIN) {
            throw new IllegalArgumentException("The main pane cannot be floated");
        }
        if (NbfxTabPane.isFloatingPane(nbfx)) {
            FloatingWindow existing = floating.get(nbfx);
            if (existing == null) {
                existing = registerFloating(nbfx, null, defaultFloatingOptions());
            }
            existing.show();
            return existing;
        }
        DockArea<TabPane> area = Docking.area();
        if (area == null || !area.contains(nbfx)) {
            throw new IllegalArgumentException("Dock group is not in the dock area: " + group);
        }
        DockArea.Anchor<TabPane> anchor = area.detach(nbfx);
        nbfx.markFloating();
        Consumer<Stage> configure = windowStageConfigurator(checked, x, y);
        NbfxTabPane.showDetachedPane(nbfx, x, y, checked.width(), checked.height(), configure);
        return registerFloating(nbfx, anchor, checked);
    }

    @Override
    public DockWindow createCustomWindow(WindowId id, WindowOptions options, WindowContent content) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(content, "content");
        if (options.kind() != WindowKind.CUSTOM) {
            throw new IllegalArgumentException("Custom windows require WindowKind.CUSTOM");
        }
        if (options.closePolicy() == WindowClosePolicy.REDOCK) {
            throw new IllegalArgumentException("Custom windows cannot redock content");
        }
        if (custom.containsKey(id)) {
            throw new IllegalArgumentException("Custom window already exists: " + id);
        }
        double x = defaultFloatingX(options);
        double y = defaultFloatingY(options);
        WindowOptions effective = effectiveWindowOptions(options, content.lookup());
        if (effective.rememberBounds() && appState != null) {
            AppState.WindowBounds bounds = appState.customWindowBounds(id);
            if (bounds != null) {
                double[] clamped = AppState.clampBounds(bounds.x(), bounds.y(),
                        bounds.width(), bounds.height());
                x = clamped[0];
                y = clamped[1];
                effective = new WindowOptions(effective.kind(), effective.profile(), effective.title(),
                        effective.icon(), effective.owner(), effective.modal(), clamped[2], clamped[3],
                        effective.resizable(), true, effective.decoration(), effective.closePolicy());
            }
        }
        Consumer<Stage> configure = windowStageConfigurator(effective, x, y);
        CustomWindow window = new CustomWindow(this, id, effective, content, configure);
        custom.put(id, window);
        window.show();
        undoStack.clear();
        redoStack.clear();
        return window;
    }

    @Override
    public DockResult redock(DockWindow window, DockRequest request) {
        if (!(window instanceof FloatingWindow floating) || floating.owner() != this) {
            return DockResult.rejected(DockDecision.reject("dock.window.missing"));
        }
        double x = floating.stage().getX();
        double y = floating.stage().getY();
        double width = floating.stage().getWidth();
        double height = floating.stage().getHeight();
        WindowOptions options = floating.options();
        DockGroupId group = floating.pane().groupId();
        ContentId selected = NbfxTabPane.contentIdForTab(
                floating.pane().getSelectionModel().getSelectedItem()).orElse(null);
        List<Tab> tabs = List.copyOf(floating.pane().getTabs());
        boolean unregisterPane = floating.pane().role() == PaneRole.DETACHED
                || floating.pane().role() == PaneRole.DOCKED;
        DockResult result = redockWindow(floating, request);
        if (!result.decision().allowed()) {
            return result;
        }
        if (isSplitPlacement(result.decision().placement()) || isEdgePlacement(result.decision().placement())) {
            recordCustomEdit(() -> {
                floatGroupAt(group, options, x, y);
            }, () -> {
                FloatingWindow current = floatingWindow(group);
                return current != null && redockWindow(current, request).decision().allowed();
            }, selected, () -> Docking.area() != null && paneForGroup(group) != null,
                () -> Docking.area() != null && paneForGroup(group) != null);
        } else {
            recordCustomEdit(() -> {
                if (unregisterPane) {
                    NbfxTabPane.register(floating.pane());
                }
                for (int i = 0; i < tabs.size(); i++) {
                    NbfxTabPane.moveTab(floating.pane(), tabs.get(i), i);
                }
                NbfxTabPane.showDetachedPane(floating.pane(), x, y, width, height,
                        windowStageConfigurator(options, x, y));
                registerFloating(floating.pane(), null, options);
            }, () -> {
                FloatingWindow current = floatingWindow(group);
                return current != null && redockWindow(current, request).decision().allowed();
            }, selected, () -> tabs.stream().allMatch(tab -> tab.getTabPane() != null),
                () -> paneForGroup(group) != null);
        }
        return result;
    }

    private FloatingWindow floatingWindow(DockGroupId group) {
        TabPane pane = paneForGroup(group);
        return pane instanceof NbfxTabPane nbfx ? floating.get(nbfx) : null;
    }

    private DockResult redockWindow(FloatingWindow window, DockRequest request) {
        if (window.stage() == null || !NbfxTabPane.tabPanes().contains(window.pane())) {
            return DockResult.rejected(DockDecision.reject("dock.window.missing"));
        }
        if (request == null) {
            return DockResult.rejected(DockDecision.reject("dock.request.invalid"));
        }
        Tab representative = findTab(request.content()).orElse(null);
        if (representative == null || representative.getTabPane() != window.pane()) {
            return DockResult.rejected(DockDecision.reject("dock.window.mismatch"));
        }
        if (window.pane().getTabs().isEmpty()) {
            return DockResult.rejected(DockDecision.reject("dock.content.missing"));
        }
        DockDecision decision = canDock(request);
        if (!decision.allowed()) {
            return DockResult.rejected(decision);
        }
        DockPlacement placement = decision.placement();
        Tab selected = window.pane().getSelectionModel().getSelectedItem();
        if (selected == null) {
            selected = window.pane().getTabs().getFirst();
        }
        if (isGroupPlacement(placement)) {
            DockGroupId target = resolveTargetGroup(request);
            TabPane pane = paneForGroup(target);
            if (pane == null) {
                return DockResult.rejected(DockDecision.reject("dock.target.missing"));
            }
            List<Tab> tabs = List.copyOf(window.pane().getTabs());
            int base = pane.getTabs().size();
            if (isRelativePlacement(placement)) {
                Tab targetTab = findTab(request.targetContent()).orElse(null);
                if (targetTab == null || targetTab.getTabPane() != pane) {
                    return DockResult.rejected(DockDecision.reject("dock.target.missing"));
                }
                base = pane.getTabs().indexOf(targetTab) + (placement == DockPlacement.AFTER ? 1 : 0);
            }
            for (int i = 0; i < tabs.size(); i++) {
                int index = isRelativePlacement(placement) ? base + i : pane.getTabs().size();
                NbfxTabPane.moveTab(pane, tabs.get(i), index);
            }
            NbfxTabPane.closeFloatingPane(window.pane(), true, false);
            window.markClosed();
            if (window.pane().role() == PaneRole.DETACHED || window.pane().role() == PaneRole.DOCKED) {
                NbfxTabPane.unregister(window.pane());
            }
            floating.remove(window.pane());
            NbfxTabPane.selectTabAndMoveToFront(selected);
            return DockResult.accepted(decision, ((NbfxTabPane) pane).groupId());
        }
        if (isSplitPlacement(placement) || isEdgePlacement(placement)) {
            DockArea<TabPane> area = Docking.area();
            if (area == null) {
                return DockResult.rejected(DockDecision.reject("dock.target.missing"));
            }
            TabPane targetPane = isSplitPlacement(placement)
                    ? paneForGroup(resolveTargetGroup(request)) : null;
            if (isSplitPlacement(placement) && (targetPane == null || !area.contains(targetPane))) {
                return DockResult.rejected(DockDecision.reject("dock.target.missing"));
            }
            NbfxTabPane.closeFloatingPane(window.pane(), true, false);
            window.markClosed();
            area.dockDetached(window.pane(), dropTargetFor(placement, targetPane));
            window.pane().markDocked();
            floating.remove(window.pane());
            NbfxTabPane.selectTabAndMoveToFront(selected);
            return DockResult.accepted(decision, window.pane().groupId());
        }
        return DockResult.rejected(DockDecision.reject("dock.placement.unsupported"));
    }

    @Override
    public List<DockWindow> windows() {
        List<DockWindow> result = new ArrayList<>();
        DockArea<TabPane> area = Docking.area();
        if (area != null && area.getScene() != null && area.getScene().getWindow() instanceof Stage stage) {
            SlidingArea slides = Docking.runtime().slides();
            if (mainWindow == null || mainWindow.root() != slides
                    || mainWindow.area() != area || mainWindow.stage() != stage) {
                mainWindow = new MainDockWindow(slides, area, stage, mainState);
            }
            result.add(mainWindow);
        } else {
            mainWindow = null;
        }
        for (FloatingWindow window : List.copyOf(floating.values())) {
            if (NbfxTabPane.tabPanes().contains(window.pane())) {
                result.add(window);
            } else {
                floating.remove(window.pane(), window);
            }
        }
        result.addAll(custom.values());
        return List.copyOf(result);
    }

    void saveCustomWindowBounds(CustomWindow window) {
        if (appState != null && window.options().rememberBounds()) {
            appState.saveCustomWindowBounds(window.windowId(), window.stage().getX(),
                    window.stage().getY(), window.stage().getWidth(), window.stage().getHeight());
        }
    }

    void removeCustomWindow(CustomWindow window) {
        custom.remove(window.windowId(), window);
        undoStack.clear();
        redoStack.clear();
    }

    boolean closeFloatingWindow(FloatingWindow window) {
        if (window.owner() != this) {
            throw new IllegalArgumentException("Window belongs to another manager");
        }
        boolean closed = false;
        switch (window.options().closePolicy()) {
            case HIDE -> window.hide();
            case REDOCK -> {
                redockFloatingOnClose(window);
                closed = !window.stage().isShowing();
            }
            case CLOSE_CONTENT -> {
                if (closeConfirmation.test(NbfxTabPane.documentsOf(window.pane()))) {
                    NbfxTabPane.closeFloatingPane(window.pane(), false, false);
                    closed = !window.stage().isShowing();
                }
            }
        }
        if (closed) {
            window.markClosed();
            floating.remove(window.pane(), window);
            undoStack.clear();
            redoStack.clear();
        }
        return closed;
    }

    private void redockFloatingOnClose(FloatingWindow window) {
        List<Tab> tabs = List.copyOf(window.pane().getTabs());
        if (tabs.isEmpty()) {
            NbfxTabPane.closeFloatingPane(window.pane(), true, false);
            window.markClosed();
            floating.remove(window.pane());
            return;
        }
        DockArea<TabPane> area = Docking.area();
        TabPane fallback = area == null ? null : area.mainPane();
        if (fallback == null) {
            fallback = NbfxTabPane.paneWithRole(PaneRole.MAIN);
        }
        Map<Tab, TabPane> targets = new LinkedHashMap<>();
        for (Tab tab : tabs) {
            TabPane target = NbfxTabPane.homeOf(tab);
            if (target == null || target == window.pane() || !NbfxTabPane.tabPanes().contains(target)) {
                target = fallback;
            }
            if (target == null || target == window.pane()) {
                return;
            }
            targets.put(tab, target);
        }
        Tab selected = window.pane().getSelectionModel().getSelectedItem();
        targets.forEach((tab, target) -> NbfxTabPane.moveTab(target, tab, target.getTabs().size()));
        NbfxTabPane.closeFloatingPane(window.pane(), true, false);
        window.markClosed();
        if (window.pane().role() == PaneRole.DETACHED || window.pane().role() == PaneRole.DOCKED) {
            NbfxTabPane.unregister(window.pane());
        }
        floating.remove(window.pane());
        if (selected != null) {
            NbfxTabPane.selectTabAndMoveToFront(selected);
        }
    }

    /** Resolves neutral restored-window options before the restored stage is shown. */
    WindowOptions restoredFloatingOptions(List<Tab> tabs, Tab active, double width, double height) {
        WindowOptions restored = new WindowOptions(WindowKind.FLOATING, null, null, null, null,
                false, width, height, true, false, null, WindowClosePolicy.CLOSE_CONTENT);
        return effectiveWindowOptions(restored, tabLookup(tabs, active));
    }

    /** Adopts a pane recreated by session restore without recording a user gesture. */
    FloatingWindow adoptRestoredFloating(NbfxTabPane pane, WindowOptions options) {
        WindowOptions checked = requireFloatingOptions(options);
        if (floating.containsKey(pane)) {
            throw new IllegalStateException("Floating pane is already registered");
        }
        if (!NbfxTabPane.isFloatingPane(pane)) {
            throw new IllegalArgumentException("Restored pane is not in a floating window");
        }
        Stage stage = NbfxTabPane.stageOf(pane);
        windowStageConfigurator(checked, stage.getX(), stage.getY()).accept(stage);
        FloatingWindow window = registerFloating(pane, null, checked);
        undoStack.clear();
        redoStack.clear();
        return window;
    }

    private FloatingWindow registerFloating(NbfxTabPane pane, DockArea.Anchor<TabPane> anchor,
            WindowOptions options) {
        if (floating.containsKey(pane)) {
            throw new IllegalStateException("Floating pane is already registered");
        }
        Stage stage = NbfxTabPane.stageOf(pane);
        if (stage == null) {
            throw new IllegalStateException("Floating pane has no stage");
        }
        pane.markFloating();
        FloatingWindow window = new FloatingWindow(this, pane, stage, anchor, options);
        floating.put(pane, window);
        stage.addEventFilter(WindowEvent.WINDOW_HIDDEN, _ -> {
            if (NbfxTabPane.isClosingWindow(stage)) {
                window.markClosed();
                floating.remove(pane, window);
            }
        });
        return window;
    }

    private static WindowOptions defaultFloatingOptions() {
        return new WindowOptions(WindowKind.FLOATING, null, null, null, null,
                false, 600, 400, true, false, null);
    }

    private static WindowOptions requireFloatingOptions(WindowOptions options) {
        Objects.requireNonNull(options, "options");
        if (options.kind() != WindowKind.FLOATING) {
            throw new IllegalArgumentException("Floating windows require WindowKind.FLOATING");
        }
        return options;
    }

    private static Consumer<Stage> windowStageConfigurator(WindowOptions options, double x, double y) {
        return stage -> {
            if (options.owner() != null) {
                stage.initOwner(options.owner());
            }
            if (options.modal()) {
                stage.initModality(options.owner() != null
                        ? javafx.stage.Modality.WINDOW_MODAL : javafx.stage.Modality.APPLICATION_MODAL);
            }
            if (options.title() != null && !options.title().isBlank()) {
                stage.setTitle(options.title());
            }
            if (options.decoration() != null) {
                ShellApplication.applyWindowDecoration(stage, options.decoration());
            }
            applyWindowIcon(stage, options.icon());
            stage.setResizable(options.resizable());
            stage.setX(x);
            stage.setY(y);
        };
    }

    private static void applyWindowIcon(Stage stage, ResourceRef icon) {
        if (icon == null) {
            return;
        }
        ClassLoader loader = Lookup.getDefault().lookup(ClassLoader.class);
        URL resource = loader == null ? null : loader.getResource(icon.path());
        if (resource == null) {
            LOG.warning(() -> "Window icon resource not found: " + icon.path());
            return;
        }
        try {
            stage.getIcons().setAll(new Image(resource.toExternalForm()));
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "Cannot load window icon: " + icon.path(), ex);
        }
    }

    private static double defaultFloatingX(WindowOptions options) {
        Window owner = options.owner();
        if (owner == null) {
            DockArea<TabPane> area = Docking.area();
            owner = area != null && area.getScene() != null ? area.getScene().getWindow() : null;
        }
        return owner != null && Double.isFinite(owner.getX()) ? owner.getX() + 40 : 100;
    }

    private static double defaultFloatingY(WindowOptions options) {
        Window owner = options.owner();
        if (owner == null) {
            DockArea<TabPane> area = Docking.area();
            owner = area != null && area.getScene() != null ? area.getScene().getWindow() : null;
        }
        return owner != null && Double.isFinite(owner.getY()) ? owner.getY() + 40 : 100;
    }

    private DockDecision validatePlacement(DockRequest request, DockPlacement placement) {
        if (placement == DockPlacement.FLOAT) {
            return findTab(request.content()).isEmpty()
                    ? DockDecision.reject("dock.content.missing") : null;
        }
        if (isGroupPlacement(placement)) {
            DockGroupId target = resolveTargetGroup(request);
            TabPane pane = paneForGroup(target);
            if (pane == null) {
                return DockDecision.reject("dock.target.missing");
            }
            if (isRelativePlacement(placement)) {
                if (request.targetContent() == null || request.targetContent().equals(request.content())) {
                    return DockDecision.reject("dock.target.missing");
                }
                Tab targetTab = findTab(request.targetContent()).orElse(null);
                if (targetTab == null || targetTab.getTabPane() != pane) {
                    return DockDecision.reject("dock.target.missing");
                }
            }
            return null;
        }
        if (isSplitPlacement(placement)) {
            DockArea<TabPane> area = Docking.area();
            DockGroupId target = resolveTargetGroup(request);
            TabPane pane = paneForGroup(target);
            if (area == null || !(pane instanceof NbfxTabPane) || !area.contains(pane)) {
                return DockDecision.reject("dock.target.missing");
            }
            return null;
        }
        if (isEdgePlacement(placement)) {
            return Docking.area() == null ? DockDecision.reject("dock.target.missing") : null;
        }
        return DockDecision.reject("dock.placement.unsupported");
    }

    private boolean dockEmptiesSource(DockRequest request, DockPlacement placement,
            TabPane source, DockArea<TabPane> area) {
        boolean outsideArea = area == null || !area.contains(source);
        if (isGroupPlacement(placement)) {
            return source instanceof NbfxTabPane nbfx
                    && !nbfx.groupId().equals(resolveTargetGroup(request));
        }
        if (isSplitPlacement(placement)) {
            return outsideArea || source == paneForGroup(resolveTargetGroup(request));
        }
        return isEdgePlacement(placement) && outsideArea;
    }

    private DockGroupId resolveTargetGroup(DockRequest request) {
        if (request.targetGroup() != null) {
            return request.targetGroup();
        }
        if (request.targetArea() != null) {
            TabPane pane = NbfxTabPane.paneWithRole(request.targetArea());
            if (pane instanceof NbfxTabPane nbfx) {
                return nbfx.groupId();
            }
        }
        return null;
    }

    private static boolean isGroupPlacement(DockPlacement placement) {
        return placement == DockPlacement.INTO_GROUP || placement == DockPlacement.TAB_STRIP
                || isRelativePlacement(placement);
    }

    private static boolean isRelativePlacement(DockPlacement placement) {
        return placement == DockPlacement.BEFORE || placement == DockPlacement.AFTER;
    }

    private static boolean isSplitPlacement(DockPlacement placement) {
        return placement == DockPlacement.SPLIT_LEFT || placement == DockPlacement.SPLIT_RIGHT
                || placement == DockPlacement.SPLIT_TOP || placement == DockPlacement.SPLIT_BOTTOM;
    }

    private static boolean isEdgePlacement(DockPlacement placement) {
        return placement == DockPlacement.EDGE_LEFT || placement == DockPlacement.EDGE_RIGHT
                || placement == DockPlacement.EDGE_TOP || placement == DockPlacement.EDGE_BOTTOM;
    }

    private static DropTarget<TabPane> dropTargetFor(DockPlacement placement, TabPane targetPane) {
        Side side = switch (placement) {
            case SPLIT_LEFT, EDGE_LEFT -> Side.LEFT;
            case SPLIT_RIGHT, EDGE_RIGHT -> Side.RIGHT;
            case SPLIT_TOP, EDGE_TOP -> Side.TOP;
            case SPLIT_BOTTOM, EDGE_BOTTOM -> Side.BOTTOM;
            default -> throw new IllegalArgumentException("Not a split/edge placement: " + placement);
        };
        return isEdgePlacement(placement) ? new DropTarget<>(null, side) : new DropTarget<>(targetPane, side);
    }

    private Optional<Tab> findTab(ContentId contentId) {
        if (contentId == null) {
            return Optional.empty();
        }
        Content content = contentManager.find(contentId).orElse(null);
        if (content instanceof ViewContent viewContent) {
            return NbfxTabPane.findViewTab(viewContent.view().getId());
        }
        if (content instanceof EditorContent editorContent) {
            return NbfxTabPane.findTab(editorContent.document());
        }
        // Fallback: scan tabs by view id derived from content id convention.
        String value = contentId.value();
        if (value.startsWith("nbfx:view.")) {
            String viewId = value.substring("nbfx:view.".length());
            return NbfxTabPane.findViewTab(viewId);
        }
        return Optional.empty();
    }

    private static TabPane paneForGroup(DockGroupId groupId) {
        if (groupId == null) {
            return null;
        }
        for (TabPane pane : NbfxTabPane.panesByRole()) {
            if (pane instanceof NbfxTabPane nbfx && groupId.equals(nbfx.groupId())) {
                return pane;
            }
        }
        return null;
    }
}
