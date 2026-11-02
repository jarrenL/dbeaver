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

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the production pending-marker state without a display or database. */
class PendingSourceMarkerNavigationTest {
    private static final class Navigation {
        final Class<?> type;
        final Object state;

        Navigation() throws Exception {
            type = Platform.getBundle("org.jkiss.dbeaver.ui.editors.sql").loadClass(
                "org.jkiss.dbeaver.ui.editors.sql.PendingSourceMarkerNavigation");
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            state = constructor.newInstance();
        }

        void remember(Object input, IMarker marker) throws Exception {
            invoke("remember", new Class<?>[] {Object.class, IMarker.class}, input, marker);
        }

        void changedInput(Object input) throws Exception {
            invoke("discardForOtherInput", new Class<?>[] {Object.class}, input);
        }

        IMarker complete(Object input, boolean alive, boolean success) throws Exception {
            return (IMarker) invoke("complete", new Class<?>[] {Object.class, boolean.class, boolean.class}, input, alive, success);
        }

        void clear() throws Exception {
            invoke("clear", new Class<?>[0]);
        }

        Object invoke(String name, Class<?>[] arguments, Object... values) throws Exception {
            var method = type.getDeclaredMethod(name, arguments);
            method.setAccessible(true);
            return method.invoke(state, values);
        }
    }

    private static IMarker marker() {
        IMarker marker = mock(IMarker.class);
        when(marker.exists()).thenReturn(true);
        return marker;
    }

    @Test
    void loadedDocumentConsumesItsOwnMarkerExactlyOnce() throws Exception {
        var navigation = new Navigation();
        Object input = new Object();
        IMarker marker = marker();
        navigation.remember(input, marker);

        assertSame(marker, navigation.complete(input, true, true));
        assertNull(navigation.complete(input, true, true));
    }

    @Test
    void laterBreakpointReplacesTheFirstBeforeSourceLoads() throws Exception {
        var navigation = new Navigation();
        Object input = new Object();
        IMarker first = marker();
        IMarker second = marker();
        navigation.remember(input, first);
        navigation.remember(input, second);

        assertSame(second, navigation.complete(input, true, true));
        verify(first, never()).exists();
    }

    @Test
    void closedEditorCannotReceiveALateMarker() throws Exception {
        var navigation = new Navigation();
        Object input = new Object();
        navigation.remember(input, marker());

        assertNull(navigation.complete(input, false, true));
        assertNull(navigation.complete(input, true, true));
    }

    @Test
    void replacedInputDiscardsOldNavigationEvenIfOldInputReturns() throws Exception {
        var navigation = new Navigation();
        Object oldInput = new Object();
        navigation.remember(oldInput, marker());
        navigation.changedInput(new Object());

        assertNull(navigation.complete(oldInput, true, true));
    }

    @Test
    void sameInputReloadKeepsItsPendingMarker() throws Exception {
        var navigation = new Navigation();
        Object input = new Object();
        IMarker marker = marker();
        navigation.remember(input, marker);
        navigation.changedInput(input);

        assertSame(marker, navigation.complete(input, true, true));
    }

    @Test
    void olderInputCompletionCannotConsumeTheNewerRequest() throws Exception {
        var navigation = new Navigation();
        Object oldInput = new Object();
        Object newInput = new Object();
        IMarker current = marker();
        navigation.remember(oldInput, marker());
        navigation.remember(newInput, current);

        assertNull(navigation.complete(oldInput, false, true));
        assertSame(current, navigation.complete(newInput, true, true));
    }

    @Test
    void failedSourceReadDoesNotApplyAMarkerToItsErrorPlaceholder() throws Exception {
        var navigation = new Navigation();
        Object input = new Object();
        navigation.remember(input, marker());

        assertNull(navigation.complete(input, true, false));
        assertNull(navigation.complete(input, true, true));
    }

    @Test
    void deletedBreakpointIsIgnoredWhenSourceArrives() throws Exception {
        var navigation = new Navigation();
        Object input = new Object();
        IMarker marker = marker();
        navigation.remember(input, marker);
        when(marker.exists()).thenReturn(false);

        assertNull(navigation.complete(input, true, true));
    }

    @Test
    void disposeClearsPendingNavigationWithoutWaitingForTheLoad() throws Exception {
        var navigation = new Navigation();
        Object input = new Object();
        navigation.remember(input, marker());
        navigation.clear();

        assertNull(navigation.complete(input, true, true));
    }
}
