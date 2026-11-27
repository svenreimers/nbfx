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
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.TabPane;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.PickResult;

/** Neutral payload adapted from a JavaFX drag event for testable drop routing. */
record ExternalDrop(
        List<File> files,
        String url,
        String text,
        Node gestureSource,
        Node pickedNode,
        Point2D location,
        boolean withinDropTarget,
        boolean tabDrag) {

    public ExternalDrop {
        files = files == null ? List.of() : List.copyOf(files);
    }

    static ExternalDrop from(TabPane pane, DragEvent event) {
        Dragboard db = event.getDragboard();
        String url = db != null && db.hasUrl() ? db.getUrl() : null;
        String text = db != null && db.hasString() ? db.getString() : null;
        PickResult pick = event.getPickResult();
        Point2D location = pane.sceneToLocal(event.getSceneX(), event.getSceneY());
        if (location == null) {
            location = new Point2D(event.getX(), event.getY());
        }
        return new ExternalDrop(FileDragAndDrop.localFilesFrom(db), url, text,
                event.getGestureSource() instanceof Node node ? node : null,
                pick == null ? null : pick.getIntersectedNode(), location,
                FileDragAndDrop.isWithinDropTarget(event.getTarget()),
                db != null && NbfxTabPane.draggedTab(db).isPresent());
    }

    boolean hasPayload() {
        return !files.isEmpty()
                || url != null && !url.isBlank()
                || text != null && !text.isEmpty()
                || gestureSource != null && !tabDrag;
    }
}
