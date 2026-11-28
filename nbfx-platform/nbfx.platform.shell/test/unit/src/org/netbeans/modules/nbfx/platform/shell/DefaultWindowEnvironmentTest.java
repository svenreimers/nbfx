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

import org.netbeans.api.nbfx.shell.DetachedBarsContext;
import org.netbeans.api.nbfx.shell.DetachedBarsProvider;
import org.netbeans.api.nbfx.shell.ShellChromeProfile;
import org.netbeans.api.nbfx.shell.ShellProfileSpec;
import org.netbeans.api.nbfx.shell.WindowEnvironmentContribution;
import org.netbeans.api.nbfx.shell.WindowKind;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.scene.layout.Region;
import org.junit.Test;
import org.netbeans.api.nbfx.actions.Command;
import org.netbeans.api.nbfx.editor.EditorDocument;
import org.openide.util.Lookup;
import org.openide.util.lookup.AbstractLookup;
import org.openide.util.lookup.InstanceContent;
import org.openide.util.lookup.Lookups;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The default environment must hand a detached window's own document scope to the contribution;
 * using the global active document would make every detached window act on the focused one.
 */
public class DefaultWindowEnvironmentTest {

    @Test
    public void contributionsAddedAfterConstructionAreConsulted() {
        InstanceContent content = new InstanceContent();
        AbstractLookup services = new AbstractLookup(content);
        DefaultWindowEnvironment environment = new DefaultWindowEnvironment(services);

        WindowEnvironmentContribution contribution = new WindowEnvironmentContribution() {
            @Override
            public boolean revealInNavigator(org.openide.filesystems.FileObject file, int index) {
                return true;
            }
        };
        content.add(contribution);

        assertTrue(environment.revealInNavigator(null, 0));
    }

    @Test
    public void detachedBarsReceiveTheExplicitWindowProfile() {
        SimpleObjectProperty<EditorDocument> scope = new SimpleObjectProperty<>();
        List<Command> scopedCommands = List.of();
        AtomicReference<DetachedBarsContext> received = new AtomicReference<>();
        Region bars = new Region();
        DetachedBarsProvider provider = context -> {
            received.set(context);
            return bars;
        };
        ShellProfileSpec global = new ShellProfileSpec("global", WindowKind.MAIN, Set.of(), null, false);
        ShellProfileSpec selected = new ShellProfileSpec("selected", WindowKind.FLOATING, Set.of(), null, false);
        DefaultWindowEnvironment environment = new DefaultWindowEnvironment(
                Lookups.fixed(provider, global));

        assertSame(bars, environment.createDetachedBars(scope, scopedCommands, selected));
        assertSame(selected, received.get().services().lookup(ShellChromeProfile.class));
        assertEquals(List.of(selected), List.copyOf(received.get().services().lookupAll(ShellChromeProfile.class)));
        Lookup.Item<ShellChromeProfile> item = received.get().services().lookupItem(
                new Lookup.Template<>(ShellChromeProfile.class, "selected", selected));
        assertSame(selected, item.getInstance());
        assertEquals("selected", item.getId());
        assertNull(received.get().services().lookupItem(
                new Lookup.Template<>(ShellChromeProfile.class, "other", null)));
        assertNull(received.get().services().lookupItem(
                new Lookup.Template<>(ShellChromeProfile.class, "selected", ShellChromeProfile.ALL)));
        assertSame(scope, received.get().activeDocument(EditorDocument.class));
        assertSame(scopedCommands, received.get().scopedCommands());
    }

    @Test
    public void legacyDetachedBarsIgnoreAmbientProfiles() {
        AtomicReference<DetachedBarsContext> received = new AtomicReference<>();
        WindowEnvironmentContribution contribution = new WindowEnvironmentContribution() {
            @Override
            public Region createDetachedBars(DetachedBarsContext context) {
                received.set(context);
                return null;
            }
        };
        ShellProfileSpec ambient = new ShellProfileSpec("ambient", WindowKind.MAIN, Set.of(), null, false);
        DefaultWindowEnvironment environment = new DefaultWindowEnvironment(
                Lookups.fixed(contribution, ambient));

        environment.createDetachedBars(new SimpleObjectProperty<>(), List.of());

        assertSame(ShellChromeProfile.ALL, received.get().services().lookup(ShellChromeProfile.class));
        assertEquals(List.of(ShellChromeProfile.ALL),
                List.copyOf(received.get().services().lookupAll(ShellChromeProfile.class)));
    }

    @Test
    public void detachedBarsReceiveTheWindowScope() {
        SimpleObjectProperty<EditorDocument> scope = new SimpleObjectProperty<>();
        List<Command> scopedCommands = List.of();
        AtomicReference<DetachedBarsContext> received = new AtomicReference<>();
        WindowEnvironmentContribution contribution = new WindowEnvironmentContribution() {
            @Override
            public Region createDetachedBars(DetachedBarsContext context) {
                received.set(context);
                return null;
            }
        };
        DefaultWindowEnvironment environment = new DefaultWindowEnvironment(
                Lookups.fixed(contribution));

        assertNull(environment.createDetachedBars(scope, scopedCommands));
        ObservableValue<EditorDocument> actual = received.get().activeDocument(EditorDocument.class);
        assertSame(scope, actual);
        assertSame(scopedCommands, received.get().scopedCommands());
    }
}
