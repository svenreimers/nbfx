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
package org.netbeans.api.nbfx.shell;

import java.util.Objects;
import java.util.Set;
import org.netbeans.api.nbfx.content.ContentKind;
import org.netbeans.api.nbfx.content.DockPlacement;
import org.netbeans.api.nbfx.content.TransferMode;

/**
 * Immutable declarative docking-policy data. Empty sets mean unrestricted.
 *
 * @param acceptedKinds accepted content kinds
 * @param acceptedAreas accepted target pane roles
 * @param placements accepted dock placements
 * @param modes accepted transfer modes
 * @param allowCrossArea whether content may move between different pane roles
 * @param allowFloat whether FLOAT/NEW_WINDOW placements are allowed
 * @since 1.16
 */
public record DockPolicySpec(
        Set<ContentKind> acceptedKinds,
        Set<PaneRole> acceptedAreas,
        Set<DockPlacement> placements,
        Set<TransferMode> modes,
        boolean allowCrossArea,
        boolean allowFloat) {

    public DockPolicySpec {
        acceptedKinds = copy(acceptedKinds, "acceptedKinds");
        acceptedAreas = copy(acceptedAreas, "acceptedAreas");
        placements = copy(placements, "placements");
        modes = copy(modes, "modes");
    }

    /** An unrestricted policy specification. */
    public static DockPolicySpec allowAll() {
        return new DockPolicySpec(Set.of(), Set.of(), Set.of(), Set.of(), true, true);
    }

    private static <T> Set<T> copy(Set<T> values, String label) {
        Objects.requireNonNull(values, label);
        return Set.copyOf(values);
    }
}
