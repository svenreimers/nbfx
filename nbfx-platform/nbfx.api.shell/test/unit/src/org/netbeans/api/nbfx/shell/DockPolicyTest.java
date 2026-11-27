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

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.netbeans.api.nbfx.content.ContentId;
import org.netbeans.api.nbfx.content.ContentKind;
import org.netbeans.api.nbfx.content.DockPlacement;
import org.netbeans.api.nbfx.content.TransferMode;
import org.netbeans.api.nbfx.layout.DockGroupId;

import static org.junit.Assert.*;

public class DockPolicyTest {

    @Test
    public void allOfKeepsFirstRejectionAndShortCircuits() {
        DockRequest request = request();
        AtomicInteger evaluated = new AtomicInteger();
        DockPolicy allow = r -> {
            evaluated.incrementAndGet();
            return DockDecision.allow(DockPlacement.TAB_STRIP);
        };
        DockPolicy reject = r -> {
            evaluated.incrementAndGet();
            return DockDecision.reject("dock.policy.denied");
        };
        DockPolicy unreachable = r -> {
            evaluated.incrementAndGet();
            return DockDecision.allow(DockPlacement.SPLIT_LEFT);
        };

        DockDecision decision = DockPolicy.allOf(allow, reject, unreachable).evaluate(request);

        assertFalse(decision.allowed());
        assertEquals("dock.policy.denied", decision.reasonKey());
        assertEquals(2, evaluated.get());
    }

    @Test
    public void allOfRefinesPlacementWithLastAllowance() {
        DockDecision decision = DockPolicy.allOf(
                r -> DockDecision.allow(DockPlacement.TAB_STRIP),
                r -> DockDecision.allow(DockPlacement.SPLIT_RIGHT))
                .evaluate(request());

        assertTrue(decision.allowed());
        assertEquals(DockPlacement.SPLIT_RIGHT, decision.placement());
    }

    @Test
    public void anyOfReturnsFirstAllowedDecision() {
        AtomicInteger evaluated = new AtomicInteger();
        DockDecision decision = DockPolicy.anyOf(
                r -> {
                    evaluated.incrementAndGet();
                    return DockDecision.reject("dock.policy.first");
                },
                r -> {
                    evaluated.incrementAndGet();
                    return DockDecision.allow(DockPlacement.EDGE_BOTTOM);
                },
                r -> {
                    evaluated.incrementAndGet();
                    return DockDecision.allow(DockPlacement.FLOAT);
                })
                .evaluate(request());

        assertTrue(decision.allowed());
        assertEquals(DockPlacement.EDGE_BOTTOM, decision.placement());
        assertEquals(2, evaluated.get());
    }

    @Test
    public void anyOfReturnsFirstRejectionWhenAllReject() {
        DockDecision decision = DockPolicy.anyOf(
                r -> DockDecision.reject("dock.policy.first"),
                r -> DockDecision.reject("dock.policy.second"))
                .evaluate(request());

        assertFalse(decision.allowed());
        assertEquals("dock.policy.first", decision.reasonKey());
    }

    @Test
    public void anyOfWithoutPoliciesRejectsWithStableReason() {
        DockDecision decision = DockPolicy.anyOf().evaluate(request());

        assertFalse(decision.allowed());
        assertEquals("dock.policy.none", decision.reasonKey());
        assertTrue(DockPolicy.allOf().evaluate(request()).allowed());
    }

    @Test
    public void specEvaluatesKindAreaPlacementAndMode() {
        ContentId content = ContentId.of("nbfx:editor.test");
        DockGroupId target = DockGroupId.of("nbfx:main");
        DockRequest request = new DockRequest(content, null, null, target,
                DockPlacement.INTO_GROUP, TransferMode.COPY, Set.of());
        DockPolicySpec matching = new DockPolicySpec(Set.of(ContentKind.EDITOR),
                Set.of(PaneRole.MAIN), Set.of(DockPlacement.INTO_GROUP),
                Set.of(TransferMode.COPY), true, true);
        DockPolicy policy = DockPolicy.fromSpec(matching,
                id -> Optional.of(ContentKind.EDITOR),
                group -> Optional.of(PaneRole.MAIN));
        assertTrue(policy.evaluate(request).allowed());

        assertEquals("dock.policy.kind", DockPolicy.fromSpec(matching,
                id -> Optional.of(ContentKind.VIEW), group -> Optional.of(PaneRole.MAIN))
                .evaluate(request).reasonKey());
        assertEquals("dock.policy.area", DockPolicy.fromSpec(matching,
                id -> Optional.of(ContentKind.EDITOR), group -> Optional.of(PaneRole.NAVIGATOR))
                .evaluate(request).reasonKey());
        assertEquals("dock.policy.placement", DockPolicy.fromSpec(matching,
                id -> Optional.of(ContentKind.EDITOR), group -> Optional.of(PaneRole.MAIN))
                .evaluate(new DockRequest(content, null, null, target,
                        DockPlacement.SPLIT_LEFT, TransferMode.COPY, Set.of())).reasonKey());
        assertEquals("dock.policy.mode", DockPolicy.fromSpec(matching,
                id -> Optional.of(ContentKind.EDITOR), group -> Optional.of(PaneRole.MAIN))
                .evaluate(new DockRequest(content, null, null, target,
                        DockPlacement.INTO_GROUP, TransferMode.MOVE, Set.of())).reasonKey());
    }

    @Test
    public void specGatesCrossAreaAndFloating() {
        DockGroupId source = DockGroupId.of("nbfx:source");
        ContentId content = ContentId.of("nbfx:view.test");
        DockRequest crossArea = new DockRequest(content, source, PaneRole.NAVIGATOR, null,
                DockPlacement.INTO_GROUP, TransferMode.COPY, Set.of());
        DockPolicySpec cross = new DockPolicySpec(Set.of(), Set.of(), Set.of(), Set.of(),
                false, true);
        DockPolicy crossPolicy = DockPolicy.fromSpec(cross, id -> Optional.empty(),
                group -> Optional.of(PaneRole.MAIN));
        assertEquals("dock.policy.cross-area", crossPolicy.evaluate(crossArea).reasonKey());
        assertTrue(DockPolicy.fromSpec(cross, id -> Optional.empty(),
                group -> Optional.of(PaneRole.NAVIGATOR)).evaluate(crossArea).allowed());

        DockPolicySpec noFloat = new DockPolicySpec(Set.of(), Set.of(), Set.of(), Set.of(),
                true, false);
        DockPolicy noFloatPolicy = DockPolicy.fromSpec(noFloat, id -> Optional.empty(),
                group -> Optional.empty());
        assertEquals("dock.policy.float", noFloatPolicy.evaluate(new DockRequest(content,
                null, null, null, DockPlacement.FLOAT, TransferMode.COPY, Set.of())).reasonKey());
        assertEquals("dock.policy.float", noFloatPolicy.evaluate(new DockRequest(content,
                null, null, null, DockPlacement.NEW_WINDOW, TransferMode.COPY, Set.of())).reasonKey());
    }

    @Test
    public void specCopiesPredicateSets() {
        Set<ContentKind> kinds = new HashSet<>();
        kinds.add(ContentKind.EDITOR);
        DockPolicySpec spec = new DockPolicySpec(kinds, Set.of(), Set.of(), Set.of(), true, true);
        kinds.add(ContentKind.VIEW);
        assertEquals(Set.of(ContentKind.EDITOR), spec.acceptedKinds());
        assertTrue(DockPolicySpec.allowAll().acceptedKinds().isEmpty());
    }

    private static DockRequest request() {
        return new DockRequest(ContentId.of("nbfx:view.test"), null, null, null,
                DockPlacement.INTO_GROUP, TransferMode.COPY, Set.of());
    }
}
