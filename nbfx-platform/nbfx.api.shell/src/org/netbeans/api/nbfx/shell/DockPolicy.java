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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.netbeans.api.nbfx.content.ContentId;
import org.netbeans.api.nbfx.content.ContentKind;
import org.netbeans.api.nbfx.content.DockPlacement;
import org.netbeans.api.nbfx.content.TransferMode;
import org.netbeans.api.nbfx.layout.DockGroupId;

/**
 * Evaluates a docking request. Policies are consulted by {@link WindowManager#canDock(DockRequest)};
 * the first rejection wins.
 *
 * @since 1.9
 */
@FunctionalInterface
public interface DockPolicy {

    /**
     * Evaluates {@code request}.
     *
     * @param request the dock request
     * @return the decision, never {@code null}
     */
    DockDecision evaluate(DockRequest request);

    /**
     * Builds a policy from declarative data. Empty sets are unrestricted. Content-kind and
     * group-area resolvers may return empty when the requested identity is unknown; structural
     * validation in {@link WindowManager} remains authoritative for missing identities.
     *
     * @param spec the policy specification
     * @param contentKinds resolves a content id to its kind
     * @param groupAreas resolves a physical group to its pane role
     * @return the executable policy
     * @since 1.16
     */
    static DockPolicy fromSpec(DockPolicySpec spec,
            Function<ContentId, Optional<ContentKind>> contentKinds,
            Function<DockGroupId, Optional<PaneRole>> groupAreas) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(contentKinds, "contentKinds");
        Objects.requireNonNull(groupAreas, "groupAreas");
        return request -> {
            if (!spec.acceptedKinds().isEmpty()) {
                ContentKind kind = resolve(contentKinds, request.content());
                if (kind == null || !spec.acceptedKinds().contains(kind)) {
                    return DockDecision.reject("dock.policy.kind");
                }
            }
            PaneRole targetArea = targetArea(request, groupAreas);
            if (targetArea != null && !spec.acceptedAreas().isEmpty()
                    && !spec.acceptedAreas().contains(targetArea)) {
                return DockDecision.reject("dock.policy.area");
            }
            if (!spec.placements().isEmpty() && !spec.placements().contains(request.placement())) {
                return DockDecision.reject("dock.policy.placement");
            }
            if (!spec.modes().isEmpty() && !spec.modes().contains(request.mode())) {
                return DockDecision.reject("dock.policy.mode");
            }
            if (!spec.allowCrossArea() && request.sourceGroup() != null && targetArea != null) {
                PaneRole sourceArea = resolve(groupAreas, request.sourceGroup());
                if (sourceArea != null && !sourceArea.equals(targetArea)) {
                    return DockDecision.reject("dock.policy.cross-area");
                }
            }
            if (!spec.allowFloat() && (request.placement() == DockPlacement.FLOAT
                    || request.placement() == DockPlacement.NEW_WINDOW)) {
                return DockDecision.reject("dock.policy.float");
            }
            return DockDecision.allow(request.placement());
        };
    }

    private static PaneRole targetArea(DockRequest request,
            Function<DockGroupId, Optional<PaneRole>> groupAreas) {
        if (request.targetArea() != null) {
            return request.targetArea();
        }
        return request.targetGroup() == null ? null : resolve(groupAreas, request.targetGroup());
    }

    private static <I, T> T resolve(Function<I, Optional<T>> resolver, I id) {
        if (id == null) {
            return null;
        }
        Optional<T> result = resolver.apply(id);
        return result == null ? null : result.orElse(null);
    }

    /**
     * Combines policies so every policy must allow the request. The first rejection wins; allowing
     * decisions may refine the placement, and the last allowing decision is returned.
     *
     * @param policies the policies in evaluation order
     * @return the composed policy
     * @since 1.10
     */
    static DockPolicy allOf(DockPolicy... policies) {
        List<DockPolicy> copy = List.of(Objects.requireNonNull(policies, "policies"));
        copy.forEach(Objects::requireNonNull);
        return request -> {
            DockDecision decision = DockDecision.allow(request.placement());
            for (DockPolicy policy : copy) {
                DockDecision next = Objects.requireNonNull(policy.evaluate(request), "policy decision");
                if (!next.allowed()) {
                    return next;
                }
                decision = next;
            }
            return decision;
        };
    }

    /**
     * Combines policies so the first policy that allows the request wins. If all reject, the first
     * rejection is returned.
     *
     * @param policies the policies in evaluation order
     * @return the composed policy
     * @since 1.10
     */
    static DockPolicy anyOf(DockPolicy... policies) {
        List<DockPolicy> copy = List.of(Objects.requireNonNull(policies, "policies"));
        copy.forEach(Objects::requireNonNull);
        return request -> {
            DockDecision firstRejection = null;
            for (DockPolicy policy : copy) {
                DockDecision decision = Objects.requireNonNull(policy.evaluate(request), "policy decision");
                if (decision.allowed()) {
                    return decision;
                }
                if (firstRejection == null) {
                    firstRejection = decision;
                }
            }
            return firstRejection == null
                    ? DockDecision.reject("dock.policy.none")
                    : firstRejection;
        };
    }
}
