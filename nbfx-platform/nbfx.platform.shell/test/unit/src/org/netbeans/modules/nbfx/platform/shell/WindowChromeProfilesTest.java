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
import java.util.Map;
import org.junit.Test;
import org.netbeans.api.nbfx.product.AboutInfo;
import org.netbeans.api.nbfx.product.Product;
import org.netbeans.api.nbfx.product.ProductIdentity;
import org.netbeans.api.nbfx.product.ProductIcons;
import org.netbeans.api.nbfx.product.ProductProvider;
import org.netbeans.api.nbfx.product.ProductStyle;
import org.netbeans.api.nbfx.shell.ChromeProfilePolicy;
import org.netbeans.api.nbfx.shell.ChromeProfilePolicyProvider;
import org.netbeans.api.nbfx.shell.ShellChromeProfile;
import org.netbeans.api.nbfx.shell.ShellProfileHint;
import org.netbeans.api.nbfx.shell.WindowClosePolicy;
import org.netbeans.api.nbfx.shell.WindowDecoration;
import org.netbeans.api.nbfx.shell.WindowKind;
import org.netbeans.api.nbfx.shell.WindowOptions;
import org.openide.util.Lookup;
import org.openide.util.lookup.Lookups;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

/** Runtime profile discovery and effective-option copying for secondary windows. */
public class WindowChromeProfilesTest {

    @Test
    public void neutralAndExplicitProfilesProduceEffectiveOptions() {
        WindowOptions floating = WindowChromeProfiles.effective(
                options(WindowKind.FLOATING, null, null), Lookup.EMPTY, Lookup.EMPTY);
        assertEquals("floating-editor", floating.profile().id());
        assertEquals(WindowDecoration.NATIVE, floating.decoration());

        WindowOptions custom = WindowChromeProfiles.effective(
                options(WindowKind.CUSTOM, null, null), Lookup.EMPTY, Lookup.EMPTY);
        assertEquals("presentation", custom.profile().id());
        assertEquals(WindowDecoration.UNDECORATED, custom.decoration());

        ShellChromeProfile explicit = legacyProfile("product.explicit");
        WindowOptions explicitOptions = WindowChromeProfiles.effective(
                options(WindowKind.CUSTOM, explicit, WindowDecoration.NATIVE),
                Lookups.singleton(new ShellProfileHint("presentation")), Lookup.EMPTY);
        assertSame(explicit, explicitOptions.profile());
        assertEquals(WindowDecoration.NATIVE, explicitOptions.decoration());
        assertEquals("Window", explicitOptions.title());
        assertEquals(640, explicitOptions.width(), 0.001);
        assertEquals(WindowClosePolicy.HIDE, explicitOptions.closePolicy());
    }

    @Test
    public void contentHintPrecedesProductPolicyAndUnknownIdsFallThrough() {
        ChromeProfilePolicy policy = new ChromeProfilePolicy("product:chrome",
                Map.of(WindowKind.FLOATING, "presentation", WindowKind.CUSTOM, "main"), "main");
        Lookup services = Lookups.fixed((ChromeProfilePolicyProvider) () -> policy, productProvider());

        WindowOptions hinted = WindowChromeProfiles.effective(
                options(WindowKind.FLOATING, null, null),
                Lookups.singleton(new ShellProfileHint("floating-editor")), services);
        assertEquals("floating-editor", hinted.profile().id());

        WindowOptions unknownHint = WindowChromeProfiles.effective(
                options(WindowKind.FLOATING, null, null),
                Lookups.singleton(new ShellProfileHint("missing")), services);
        assertEquals("presentation", unknownHint.profile().id());

        WindowOptions custom = WindowChromeProfiles.effective(
                options(WindowKind.CUSTOM, null, null), Lookup.EMPTY, services);
        assertEquals("main", custom.profile().id());

        ChromeProfilePolicy fallback = new ChromeProfilePolicy("product:chrome",
                Map.of(WindowKind.MAIN, "main"), "floating-editor");
        WindowOptions fallbackCustom = WindowChromeProfiles.effective(
                options(WindowKind.CUSTOM, null, null), Lookup.EMPTY,
                Lookups.fixed((ChromeProfilePolicyProvider) () -> fallback, productProvider()));
        assertEquals("floating-editor", fallbackCustom.profile().id());
    }

    @Test
    public void ambiguousOrMissingRuntimeConfigurationFails() {
        assertThrows(IllegalStateException.class, () -> WindowChromeProfiles.effective(
                options(WindowKind.FLOATING, null, null),
                Lookups.fixed(new ShellProfileHint("main"), new ShellProfileHint("presentation")),
                Lookup.EMPTY));
        assertThrows(IllegalStateException.class, () -> WindowChromeProfiles.effective(
                options(WindowKind.FLOATING, null, null), Lookup.EMPTY,
                Lookups.singleton(productProvider())));
        assertThrows(IllegalStateException.class, () -> WindowChromeProfiles.effective(
                options(WindowKind.FLOATING, null, null), Lookup.EMPTY,
                Lookups.fixed(productProvider(), productProvider())));
    }

    private static WindowOptions options(WindowKind kind, ShellChromeProfile profile,
            WindowDecoration decoration) {
        return new WindowOptions(kind, profile, "Window", null, null, false,
                640, 480, true, false, decoration, WindowClosePolicy.HIDE);
    }

    private static ShellChromeProfile legacyProfile(String id) {
        return new ShellChromeProfile() {
            @Override
            public boolean includeMenu(String menuId) {
                return true;
            }

            @Override
            public boolean includeMenuItem(String menuId, String itemId) {
                return true;
            }

            @Override
            public boolean includeToolBar(String toolBarId) {
                return true;
            }

            @Override
            public String id() {
                return id;
            }
        };
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
}
