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

import java.io.File;
import java.util.List;
import java.util.Set;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import org.junit.Test;
import org.netbeans.api.nbfx.content.DropDecision;
import org.netbeans.api.nbfx.content.DropHandler;
import org.netbeans.api.nbfx.content.DropRequest;
import org.netbeans.api.nbfx.content.DropTarget;
import org.netbeans.api.nbfx.content.DropTargetKind;
import org.netbeans.api.nbfx.content.TransferKind;
import org.netbeans.api.nbfx.content.TransferMode;
import org.netbeans.api.nbfx.shell.PaneRole;
import org.netbeans.modules.nbfx.test.fx.JavaFxTestSupport;

import static org.junit.Assert.*;

public class ExternalDropRoutingTest {

    @Test
    public void routesFilesUrisTextAndNodes() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            NbfxTabPane pane = new NbfxTabPane(PaneRole.MAIN);
            try {
                DropTarget target = new DropTarget(DropTargetKind.GROUP, pane.groupId().value());
                RecordingHandler handler = new RecordingHandler(target,
                        Set.of(TransferKind.FILES, TransferKind.URIS, TransferKind.TEXT, TransferKind.NODES),
                        DropDecision.allow(TransferMode.COPY, null));
                List<DropHandler> handlers = List.of(handler);

                assertKind(TransferKind.FILES, NbfxTabPane.routeExternalDrop(pane,
                        drop(List.of(new File("a.txt")), null, null, null, false, false), handlers));
                assertKind(TransferKind.URIS, NbfxTabPane.routeExternalDrop(pane,
                        drop(List.of(), "https://example.test", null, null, false, false), handlers));
                assertKind(TransferKind.TEXT, NbfxTabPane.routeExternalDrop(pane,
                        drop(List.of(), null, "text", null, false, false), handlers));
                assertKind(TransferKind.NODES, NbfxTabPane.routeExternalDrop(pane,
                        drop(List.of(), null, null, new StackPane(), false, false), handlers));
            } finally {
                NbfxTabPane.unregister(pane);
            }
            return null;
        });
    }

    @Test
    public void preservesSourcePriorityAndRejectionReason() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            NbfxTabPane pane = new NbfxTabPane(PaneRole.MAIN);
            try {
                DropTarget target = new DropTarget(DropTargetKind.GROUP, pane.groupId().value());
                RecordingHandler files = new RecordingHandler(target, Set.of(TransferKind.FILES),
                        DropDecision.reject("drop.files.denied"));
                RecordingHandler uris = new RecordingHandler(target, Set.of(TransferKind.URIS),
                        DropDecision.allow(TransferMode.COPY, null));
                NbfxTabPane.DropRoute routed = NbfxTabPane.routeExternalDrop(pane,
                        drop(List.of(new File("a.txt")), "https://example.test", null, null, false, false),
                        List.of(files, uris));
                assertNotNull(routed.resolved());
                assertEquals(TransferKind.URIS, routed.resolved().request().source().kind());
                assertEquals(1, files.evaluations);

                NbfxTabPane.DropRoute rejected = NbfxTabPane.routeExternalDrop(pane,
                        drop(List.of(new File("a.txt")), null, null, null, false, false), List.of(files));
                assertNull(rejected.resolved());
                assertTrue(rejected.external());
                assertEquals("drop.files.denied", rejected.rejection().reasonKey());
            } finally {
                NbfxTabPane.unregister(pane);
            }
            return null;
        });
    }

    @Test
    public void ignoresTabDragsAndNavigatorOwnedTargets() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            NbfxTabPane pane = new NbfxTabPane(PaneRole.MAIN);
            try {
                DropTarget target = new DropTarget(DropTargetKind.GROUP, pane.groupId().value());
                RecordingHandler handler = new RecordingHandler(target, Set.of(TransferKind.NODES),
                        DropDecision.allow(TransferMode.MOVE, null));
                NbfxTabPane.DropRoute tabDrag = NbfxTabPane.routeExternalDrop(pane,
                        drop(List.of(), null, null, new StackPane(), true, false), List.of(handler));
                assertNull(tabDrag.resolved());
                assertFalse(tabDrag.external());
                assertEquals(0, handler.evaluations);

                NbfxTabPane.DropRoute owned = NbfxTabPane.routeExternalDrop(pane,
                        drop(List.of(new File("a.txt")), null, null, null, false, true), List.of(handler));
                assertNull(owned.resolved());
                assertFalse(owned.external());
            } finally {
                NbfxTabPane.unregister(pane);
            }
            return null;
        });
    }

    private static void assertKind(TransferKind expected, NbfxTabPane.DropRoute route) {
        assertNotNull(route.resolved());
        assertEquals(expected, route.resolved().request().source().kind());
    }

    private static ExternalDrop drop(List<File> files, String url, String text, Node source,
            boolean tabDrag, boolean withinDropTarget) {
        return new ExternalDrop(files, url, text, source, null, Point2D.ZERO,
                withinDropTarget, tabDrag);
    }

    private static final class RecordingHandler implements DropHandler {
        private final DropTarget target;
        private final Set<TransferKind> kinds;
        private final DropDecision decision;
        private int evaluations;

        RecordingHandler(DropTarget target, Set<TransferKind> kinds, DropDecision decision) {
            this.target = target;
            this.kinds = kinds;
            this.decision = decision;
        }

        @Override
        public DropTarget target() {
            return target;
        }

        @Override
        public Set<TransferKind> sourceKinds() {
            return kinds;
        }

        @Override
        public DropDecision evaluate(DropRequest request) {
            evaluations++;
            return decision;
        }

        @Override
        public void perform(DropRequest request, DropDecision accepted) {
        }
    }
}
