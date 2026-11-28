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

import java.util.List;
import java.util.Objects;
import org.netbeans.api.nbfx.product.ProductProvider;
import org.netbeans.api.nbfx.shell.ChromeProfilePolicy;
import org.netbeans.api.nbfx.shell.ChromeProfilePolicyProvider;
import org.netbeans.api.nbfx.shell.ShellProfileHint;
import org.netbeans.api.nbfx.shell.ShellProfileRegistry;
import org.netbeans.api.nbfx.shell.ShellProfileResolver;
import org.netbeans.api.nbfx.shell.ShellProfileSelection;
import org.netbeans.api.nbfx.shell.WindowOptions;
import org.openide.util.Lookup;

/** Runtime product-policy and content-hint adapter around the pure shell-profile resolver. */
final class WindowChromeProfiles {

    private WindowChromeProfiles() {
    }

    static ChromeProfilePolicy productPolicy(Lookup lookup) {
        Objects.requireNonNull(lookup, "lookup");
        List<ProductProvider> providers = List.copyOf(lookup.lookupAll(ProductProvider.class));
        if (providers.size() > 1) {
            throw new IllegalStateException("Exactly one ProductProvider is allowed; found "
                    + providers.size());
        }
        if (providers.isEmpty()) {
            return null;
        }
        String policyId = ProductProvider.resolve(lookup).chromeProfilePolicyId();
        return ChromeProfilePolicyProvider.resolve(policyId, lookup).orElseThrow(
                () -> new IllegalStateException("No ChromeProfilePolicyProvider for product policy "
                        + policyId));
    }

    static ShellProfileHint hint(Lookup lookup) {
        Objects.requireNonNull(lookup, "lookup");
        List<ShellProfileHint> hints = List.copyOf(lookup.lookupAll(ShellProfileHint.class));
        if (hints.size() > 1) {
            throw new IllegalStateException("At most one ShellProfileHint is allowed; found "
                    + hints.size());
        }
        return hints.isEmpty() ? null : hints.getFirst();
    }

    static WindowOptions effective(WindowOptions options, Lookup contentLookup, Lookup serviceLookup) {
        Objects.requireNonNull(options, "options");
        ShellProfileSelection selection = ShellProfileResolver.resolve(options, hint(contentLookup),
                productPolicy(serviceLookup), ShellProfileRegistry.discover());
        return new WindowOptions(options.kind(), selection.profile(), options.title(), options.icon(),
                options.owner(), options.modal(), options.width(), options.height(), options.resizable(),
                options.rememberBounds(), selection.decoration(), options.closePolicy());
    }
}
