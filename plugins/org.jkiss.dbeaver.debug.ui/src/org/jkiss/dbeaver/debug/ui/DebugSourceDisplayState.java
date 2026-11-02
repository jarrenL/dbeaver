/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.debug.ui;

import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.debug.core.DebugEvent;

import java.util.function.BooleanSupplier;

/** User intent and execution generations outlive a source lookup's cached result. */
final class DebugSourceDisplayState {
    static final ThreadLocal<BooleanSupplier> LOOKUP_REQUEST = new ThreadLocal<>();

    private long generation;
    private long interaction;
    private long overriddenAt;
    private boolean overridden;
    private boolean disposed;

    synchronized long begin(boolean force, boolean debugViewActive) {
        if (disposed || overridden && !force && !(debugViewActive && interaction > overriddenAt)) {
            return 0;
        }
        overridden = false;
        return ++generation;
    }

    synchronized boolean isCurrent(long request) {
        return request != 0 && request == generation && !overridden && !disposed;
    }

    <T> T prepareSource(long request, T result, Runnable prepare) {
        if (!isCurrent(request)) {
            throw new OperationCanceledException();
        }
        // Preparation is required even for cached lookup results. Do not hold the
        // state lock across source loading: user navigation must be able to cancel it.
        prepare.run();
        if (!isCurrent(request)) {
            throw new OperationCanceledException();
        }
        return result;
    }

    synchronized void userInput() {
        interaction++;
        // This input may itself select a stack frame. A later activation with no
        // new input, for example after closing an editor, must remain suppressed.
        override(interaction - 1);
    }

    void pointerInput(int clickCount) {
        // The first press can already select a frame and start source lookup.
        // Later presses in that same double/triple-click must not cancel it.
        if (clickCount <= 1) {
            userInput();
        }
    }

    synchronized void userNavigation() {
        override(interaction);
    }

    private void override(long at) {
        generation++;
        overridden = true;
        overriddenAt = at;
    }

    synchronized void executionChanged() {
        generation++;
        overridden = false;
    }

    void executionEvent(int kind) {
        // View listeners can already have started the new paused-frame lookup
        // when this listener receives SUSPEND. RESUME has invalidated the old
        // generation; invalidating again here would discard the new location.
        if (kind == DebugEvent.RESUME || kind == DebugEvent.TERMINATE) {
            executionChanged();
        }
    }

    synchronized void dispose() {
        generation++;
        disposed = true;
    }

    static BooleanSupplier currentLookup() {
        BooleanSupplier request = LOOKUP_REQUEST.get();
        return request == null ? () -> true : request;
    }
}
