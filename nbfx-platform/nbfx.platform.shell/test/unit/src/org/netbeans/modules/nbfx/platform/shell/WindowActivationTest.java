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

import javafx.stage.Stage;
import org.junit.Test;
import org.netbeans.modules.nbfx.test.fx.JavaFxTestSupport;

import static org.junit.Assert.*;

public class WindowActivationTest {

    @Test
    public void showBringsStageToFront() throws InterruptedException {
        JavaFxTestSupport.onFx(() -> {
            TrackingStage stage = new TrackingStage();
            try {
                WindowActivation.show(stage);
                assertTrue(stage.isShowing());
                assertTrue(stage.frontRequested);
            } finally {
                stage.close();
            }
            return null;
        });
    }

    private static final class TrackingStage extends Stage {
        private boolean frontRequested;

        @Override
        public void toFront() {
            frontRequested = true;
        }
    }
}
