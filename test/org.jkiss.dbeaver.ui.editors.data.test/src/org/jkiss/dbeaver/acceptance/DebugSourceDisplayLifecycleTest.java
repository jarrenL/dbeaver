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
package org.jkiss.dbeaver.acceptance;

import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the production generation rules across the lookup-to-UI queue boundary. */
class DebugSourceDisplayLifecycleTest {
    private static final class State {
        final Class<?> type = Platform.getBundle("org.jkiss.dbeaver.debug.ui").loadClass(
            "org.jkiss.dbeaver.debug.ui.DebugSourceDisplayState");
        final Object state;

        State() throws Exception {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            state = constructor.newInstance();
        }

        long begin(boolean force, boolean debugViewActive) throws Exception {
            var method = type.getDeclaredMethod("begin", boolean.class, boolean.class);
            method.setAccessible(true);
            return (long) method.invoke(state, force, debugViewActive);
        }

        boolean current(long request) throws Exception {
            var method = type.getDeclaredMethod("isCurrent", long.class);
            method.setAccessible(true);
            return (boolean) method.invoke(state, request);
        }

        void event(String name) throws Exception {
            var method = type.getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(state);
        }

        void executionEvent(String name) throws Exception {
            var eventType = Platform.getBundle("org.eclipse.debug.core").loadClass("org.eclipse.debug.core.DebugEvent");
            int kind = eventType.getField(name).getInt(null);
            var method = type.getDeclaredMethod("executionEvent", int.class);
            method.setAccessible(true);
            method.invoke(state, kind);
        }

        void pointerInput(int clickCount) throws Exception {
            var method = type.getDeclaredMethod("pointerInput", int.class);
            method.setAccessible(true);
            method.invoke(state, clickCount);
        }

        Object prepare(long request, Object result, Runnable prepare) throws Exception {
            var method = type.getDeclaredMethod("prepareSource", long.class, Object.class, Runnable.class);
            method.setAccessible(true);
            try {
                return method.invoke(state, request, result, prepare);
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException runtime) throw runtime;
                throw e;
            }
        }

        Runnable completedLookup(long request, AtomicInteger displays) {
            return () -> {
                try {
                    if (current(request)) displays.incrementAndGet();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            };
        }
    }

    @Test
    void completedLookupCanPublishWhenUserHasNotNavigated() throws Exception {
        var state = new State();
        var displays = new AtomicInteger();
        long request = state.begin(false, true);
        state.completedLookup(request, displays).run();
        assertEquals(1, displays.get());
    }

    @Test
    void closeAfterLookupReturnedPreventsQueuedDisplay() throws Exception {
        var state = new State();
        var displays = new AtomicInteger();
        var uiQueue = new ArrayDeque<Runnable>();
        uiQueue.add(state.completedLookup(state.begin(false, true), displays));
        state.event("userNavigation");
        uiQueue.remove().run();
        assertEquals(0, displays.get());
    }

    @Test
    void cachedInputStillRequiresAValidUiDisplayRequest() throws Exception {
        var state = new State();
        var displays = new AtomicInteger();
        // Cache hits bypass getEditorInput. The adapter's ticket remains authoritative.
        Runnable cachedResult = state.completedLookup(state.begin(false, true), displays);
        state.event("userNavigation");
        cachedResult.run();
        assertEquals(0, displays.get());
    }

    @Test
    void cachedResultReopeningAlwaysPreparesDocumentBeforeDisplay() throws Exception {
        var state = new State();
        Object cachedResult = new Object();
        var prepares = new AtomicInteger();
        long first = state.begin(false, true);
        assertSame(cachedResult, state.prepare(first, cachedResult, prepares::incrementAndGet));
        state.event("userNavigation");
        state.event("userInput");
        long reopened = state.begin(false, true);
        assertSame(cachedResult, state.prepare(reopened, cachedResult, prepares::incrementAndGet));
        assertEquals(2, prepares.get(), "A cached result must not skip preparing the reopened editor");
    }

    @Test
    void obsoleteCachedResultCannotReopenSourceDuringPreparation() throws Exception {
        var state = new State();
        long request = state.begin(false, true);
        state.event("userNavigation");
        var prepares = new AtomicInteger();
        assertThrows(org.eclipse.core.runtime.OperationCanceledException.class,
            () -> state.prepare(request, new Object(), prepares::incrementAndGet));
        assertEquals(0, prepares.get());
    }

    @Test
    void closeWhilePreparingSourceDiscardsResultBeforeDisplay() throws Exception {
        var state = new State();
        long request = state.begin(false, true);
        assertThrows(org.eclipse.core.runtime.OperationCanceledException.class,
            () -> state.prepare(request, new Object(), () -> {
                try {
                    state.event("userNavigation");
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }));
        assertFalse(state.current(request));
    }

    @Test
    void sourcePreparationKeepsTheOriginalCachedLookupResult() throws Exception {
        var state = new State();
        Object result = new Object();
        assertSame(result, state.prepare(state.begin(false, true), result, () -> { }));
    }

    @Test
    void tabClickCancelsQueuedDisplayEvenIfSameViewRemainsActive() throws Exception {
        var state = new State();
        long request = state.begin(false, false);
        state.event("userInput");
        assertFalse(state.current(request));
        assertEquals(0, state.begin(false, false));
    }

    @Test
    void closeSuppressesLateSameFrameActivationInBreakpoints() throws Exception {
        var state = new State();
        state.begin(false, true);
        state.event("userNavigation");
        assertEquals(0, state.begin(false, false));
        assertEquals(0, state.begin(false, false));
    }

    @Test
    void closeDoesNotAuthorizeAnAutomaticDebugViewActivation() throws Exception {
        var state = new State();
        state.event("userInput");
        state.event("userNavigation");
        assertEquals(0, state.begin(false, true));
    }

    @Test
    void freshStackFrameClickCanReopenPreviouslyClosedSource() throws Exception {
        var state = new State();
        state.event("userNavigation");
        state.event("userInput");
        long request = state.begin(false, true);
        assertNotEquals(0, request);
        assertTrue(state.current(request));
    }

    @Test
    void explicitStackClickSupersedesPendingSourceSelection() throws Exception {
        var state = new State();
        long first = state.begin(false, true);
        state.event("userInput");
        long second = state.begin(false, true);
        assertFalse(state.current(first));
        assertTrue(state.current(second));
    }

    @Test
    void firstPointerPressCancelsPreviouslyQueuedSourceDisplay() throws Exception {
        var state = new State();
        long previous = state.begin(false, true);
        state.pointerInput(1);
        assertFalse(state.current(previous));
    }

    @Test
    void secondAndThirdPressKeepTheSourceLookupStartedByFirstSelection() throws Exception {
        var state = new State();
        state.pointerInput(1);
        long selectedFrame = state.begin(false, true);
        state.pointerInput(2);
        assertTrue(state.current(selectedFrame));
        state.pointerInput(3);
        assertTrue(state.current(selectedFrame));
    }

    @Test
    void secondPressDoesNotClearAnExistingCloseOrNavigationOverride() throws Exception {
        var state = new State();
        state.event("userNavigation");
        state.pointerInput(2);
        assertEquals(0, state.begin(false, true));
        assertEquals(0, state.begin(false, false));
    }

    @Test
    void forceCanExplicitlyReopenWithoutAnotherMouseEvent() throws Exception {
        var state = new State();
        state.event("userNavigation");
        assertTrue(state.current(state.begin(true, false)));
    }

    @Test
    void nextStepOrSuspensionClearsPriorUserOverride() throws Exception {
        var state = new State();
        state.event("userNavigation");
        state.event("executionChanged");
        assertTrue(state.current(state.begin(false, false)));
    }

    @Test
    void resumeInvalidatesResultAlreadyQueuedForDisplay() throws Exception {
        var state = new State();
        long request = state.begin(false, true);
        state.event("executionChanged");
        assertFalse(state.current(request));
    }

    @Test
    void lateSuspendDoesNotDiscardLookupAlreadyStartedForTheNewPausedFrame() throws Exception {
        var state = new State();
        long oldRequest = state.begin(false, true);
        state.executionEvent("RESUME");
        long pausedFrameRequest = state.begin(false, true);
        // Eclipse's UI listener may start the source lookup before this same
        // SUSPEND event reaches our listener on the event-dispatch thread.
        state.executionEvent("SUSPEND");
        assertFalse(state.current(oldRequest));
        assertTrue(state.current(pausedFrameRequest));
    }

    @Test
    void resumeEventCancelsTheQueuedSourceDisplayAndAllowsNextSuspension() throws Exception {
        var state = new State();
        long request = state.begin(false, true);
        state.executionEvent("RESUME");
        assertFalse(state.current(request));
        assertTrue(state.current(state.begin(false, false)));
    }

    @Test
    void terminateEventCancelsTheQueuedSourceDisplay() throws Exception {
        var state = new State();
        long request = state.begin(false, true);
        state.executionEvent("TERMINATE");
        assertFalse(state.current(request));
    }

    @Test
    void terminateInvalidatesAllOlderFrameResults() throws Exception {
        var state = new State();
        long first = state.begin(false, true);
        long second = state.begin(false, true);
        state.event("executionChanged");
        assertFalse(state.current(first));
        assertFalse(state.current(second));
    }

    @Test
    void newerFrameResultWinsWhenOldLookupFinishesLast() throws Exception {
        var state = new State();
        var displays = new AtomicInteger();
        Runnable oldResult = state.completedLookup(state.begin(false, true), displays);
        Runnable latestResult = state.completedLookup(state.begin(false, true), displays);
        latestResult.run();
        oldResult.run();
        assertEquals(1, displays.get());
    }

    @Test
    void closedPageRejectsPendingAndForcedFutureDisplay() throws Exception {
        var state = new State();
        long request = state.begin(false, true);
        state.event("dispose");
        assertFalse(state.current(request));
        assertEquals(0, state.begin(true, true));
    }

    @Test
    void zeroIsNeverAValidDisplayTicket() throws Exception {
        assertFalse(new State().current(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void capturedLookupGuardSurvivesThreadLocalScopeAndTracksCancellation() throws Exception {
        var state = new State();
        long request = state.begin(false, true);
        var field = state.type.getDeclaredField("LOOKUP_REQUEST");
        field.setAccessible(true);
        ThreadLocal<BooleanSupplier> context = (ThreadLocal<BooleanSupplier>) field.get(null);
        var method = state.type.getDeclaredMethod("currentLookup");
        method.setAccessible(true);
        try {
            context.set(() -> {
                try {
                    return state.current(request);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            BooleanSupplier captured = (BooleanSupplier) method.invoke(null);
            context.remove();
            assertTrue(captured.getAsBoolean());
            state.event("userNavigation");
            assertFalse(captured.getAsBoolean());
            assertTrue(((BooleanSupplier) method.invoke(null)).getAsBoolean());
        } finally {
            context.remove();
        }
    }
}
