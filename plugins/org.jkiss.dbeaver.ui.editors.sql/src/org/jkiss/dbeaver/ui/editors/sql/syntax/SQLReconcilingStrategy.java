/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package org.jkiss.dbeaver.ui.editors.sql.syntax;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.jface.text.*;
import org.eclipse.jface.text.reconciler.DirtyRegion;
import org.eclipse.jface.text.reconciler.IReconcilingStrategy;
import org.eclipse.jface.text.reconciler.IReconcilingStrategyExtension;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.jface.text.source.projection.ProjectionAnnotation;
import org.eclipse.jface.text.source.projection.ProjectionAnnotationModel;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.texteditor.spelling.ISpellingProblemCollector;
import org.eclipse.ui.texteditor.spelling.SpellingAnnotation;
import org.eclipse.ui.texteditor.spelling.SpellingProblem;
import org.eclipse.ui.texteditor.spelling.SpellingService;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.sql.SQLScriptElement;
import org.jkiss.dbeaver.ui.editors.EditorUtils;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorBase;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorUtils;
import org.jkiss.dbeaver.ui.editors.sql.internal.SQLEditorActivator;
import org.jkiss.utils.CommonUtils;

import java.util.*;
import java.util.stream.Collectors;

public class SQLReconcilingStrategy implements IReconcilingStrategy, IReconcilingStrategyExtension {
    private static final Log log = Log.getLog(SQLReconcilingStrategy.class);

    private static final QualifiedName COLLAPSED_ANNOTATIONS =
        new QualifiedName(SQLEditorActivator.PLUGIN_ID, SQLReconcilingStrategy.class.getName() + ".collapsedFoldingAnnotations");

    private final NavigableSet<SQLScriptElementImpl> cache = new TreeSet<>();

    private final SQLEditorBase editor;

    private IDocument document;
    private IProgressMonitor monitor;

    // Spelling
    private SpellingService spellingService;
    private SQLSpellingContext spellingContext;
    private boolean initialized;

    public SQLReconcilingStrategy(SQLEditorBase editor) {
        this.editor = editor;
    }

    protected IAnnotationModel getAnnotationModel() {
        return editor.getAnnotationModel();
    }

    private boolean isSpellingEnabled() {
        return EditorsUI.getPreferenceStore().getBoolean("spellingEnabled");
    }

    @Override
    public void setDocument(IDocument document) {
        this.document = document;
        this.cache.clear();

        spellingService = EditorsUI.getSpellingService();
        if (spellingService.getActiveSpellingEngineDescriptor(editor.getViewerConfiguration().getPreferenceStore()) != null) {
            this.spellingContext = new SQLSpellingContext(editor);
            this.spellingContext.setContentType(SQLEditorUtils.getSQLContentType());
        }
    }

    @Override
    public void setProgressMonitor(IProgressMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public void reconcile(DirtyRegion dirtyRegion, IRegion subRegion) {
        if (DirtyRegion.INSERT.equals(dirtyRegion.getType())) {
            reconcile(subRegion.getOffset(), subRegion.getLength(), false);
        } else {
            reconcile(subRegion.getOffset(), 0, false);
        }
    }

    @Override
    public void reconcile(IRegion partition) {
        reconcile(0, document.getLength(), false);
    }

    @Override
    public void initialReconcile() {
        if (!initialized) {
            initialized = true;
            reconcile(0, document.getLength(), true);
        }
    }

    private Set<Integer> getSavedCollapsedAnnotationsOffsets() {
        IResource resource = getResource();
        if (resource == null) {
            return Collections.emptySet();
        }
        String data;
        try {
            data = resource.getPersistentProperty(COLLAPSED_ANNOTATIONS);
        } catch (CoreException e) {
            log.warn("Core Exception caught while reading saved collapsed folding positions: " + e.getMessage());
            return Collections.emptySet();
        }
        if (data == null) {
            return Collections.emptySet();
        }

        Set<Integer> collapsedPositionsOffsets = new HashSet<>();
        String[] offsets = data.split(";");
        for (String offset : offsets) {
            int offsetValue = CommonUtils.toInt(offset, -1);
            if (offsetValue == -1) {
                log.warn("Illegal offset parsed while reading saved collapsed annotation offsets. offset=" + offset);
                continue;
            }
            collapsedPositionsOffsets.add(offsetValue);
        }

        return collapsedPositionsOffsets;
    }

    //format: "offset_1;offset_2;...offset_n"
    public void saveState() {
        IResource resource = getResource();
        ProjectionAnnotationModel annotationModel = editor.getProjectionAnnotationModel();
        if (resource == null || annotationModel == null) {
            return;
        }
        StringJoiner stringJoiner = new StringJoiner(";");
        for (SQLScriptElementImpl position : cache) {
            ProjectionAnnotation annotation = position.getAnnotation();
            if (annotation != null && annotation.isCollapsed()) {
                stringJoiner.add(Integer.toString(position.getOffset()));
            }
        }
        String value;
        if (stringJoiner.length() == 0) {
            value = null;
        } else {
            value = stringJoiner.toString();
        }
        try {
            resource.setPersistentProperty(COLLAPSED_ANNOTATIONS, value);
        } catch (CoreException e) {
            log.warn("Core Exception caught while persisting saved collapsed folding positions", e);
        }
    }

    @Nullable
    private IResource getResource() {
        return EditorUtils.getFileFromInput(editor.getEditorInput());
    }

    public void onDataSourceChange() {
        if (document == null) {
            return;
        }
        if (!initialized) {
            initialReconcile();
        } else {
            reconcile(0, document.getLength(), true);
        }
    }

    private void reconcile(int damagedRegionOffset, int damagedRegionLength, boolean restoreCollapsedAnnotations) {
        if (!editor.isFoldingEnabled()) {
            cache.clear(); // underlying annotation model being cleared, so reset the cache too
            return;
        }
        ProjectionAnnotationModel model = editor.getProjectionAnnotationModel();
        if (model == null) {
            return;
        }

        // Parsing runs in the reconciler while the editor can replace the document.
        // Never combine positions from one revision with line/character data from another.
        IDocument sourceDocument = document;
        long sourceStamp = modificationStamp(sourceDocument);
        Document snapshot = new Document(sourceDocument.get());
        if (!isCurrentRevision(sourceDocument, sourceStamp, snapshot)) {
            return;
        }

        SQLScriptElementImpl leftBound = cache.lower(new SQLScriptElementImpl(damagedRegionOffset, damagedRegionLength));
        if (leftBound != null) {
            leftBound = cache.lower(leftBound);
        }
        SQLScriptElementImpl rightBound = cache.ceiling(new SQLScriptElementImpl(damagedRegionOffset + damagedRegionLength, 0));
        if (leftBound == null) {
            damagedRegionOffset = 0;
        } else {
            damagedRegionOffset = leftBound.getOffset() + leftBound.getLength();
        }
        if (rightBound == null) {
            damagedRegionLength = snapshot.getLength() - damagedRegionOffset;
        } else {
            damagedRegionLength = rightBound.getOffset() + rightBound.getLength() - damagedRegionOffset;
        }

        List<SQLScriptElement> parsedQueries = extractQueries(damagedRegionOffset, damagedRegionLength);
        if (!isUsableParse(parsedQueries, sourceDocument, sourceStamp, snapshot)) {
            return;
        }

        if (rightBound != null && !parsedQueries.isEmpty()) {
            SQLScriptElement rightmostParsedQuery = parsedQueries.get(parsedQueries.size() - 1);
            if (!rightBound.equals(getExpandedScriptElement(rightmostParsedQuery, snapshot))) {
                parsedQueries = extractQueries(damagedRegionOffset, snapshot.getLength() - damagedRegionOffset);
                if (!isUsableParse(parsedQueries, sourceDocument, sourceStamp, snapshot)) {
                    return;
                }
                rightBound = null;
            }
        }

        Collection<SQLScriptElementImpl> cachedQueries;
        if (leftBound == null && rightBound == null) {
            cachedQueries = Collections.unmodifiableNavigableSet(cache);
        } else if (leftBound == null) {
            cachedQueries = Collections.unmodifiableNavigableSet(cache.headSet(rightBound, true));
        } else if (rightBound == null) {
            cachedQueries = Collections.unmodifiableNavigableSet(cache.tailSet(leftBound, false));
        } else {
            cachedQueries = Collections.unmodifiableNavigableSet(cache.subSet(leftBound, false, rightBound, true));
        }

        Collection<SQLScriptElementImpl> parsedElements = parsedQueries.stream()
            .filter(element -> deservesFolding(element, snapshot))
            .map(element -> getExpandedScriptElement(element, snapshot))
            .collect(Collectors.toSet());
        Map<Annotation, SQLScriptElementImpl> additions = new HashMap<>();
        Set<Integer> savedCollapsedAnnotationsOffsets = restoreCollapsedAnnotations ? getSavedCollapsedAnnotationsOffsets() : Collections.emptySet();
        for (SQLScriptElementImpl element : parsedElements) {
            if (!cachedQueries.contains(element)) {
                ProjectionAnnotation annotation = new ProjectionAnnotation();
                element.setAnnotation(annotation);
                additions.put(annotation, element);
                if (savedCollapsedAnnotationsOffsets.contains(element.getOffset())) {
                    annotation.markCollapsed();
                }
            }
        }
        Collection<SQLScriptElementImpl> deletedPositions = cachedQueries.stream()
            .filter(element -> !parsedElements.contains(element))
            .toList();
        Annotation[] deletions = deletedPositions.stream()
            .map(SQLScriptElementImpl::getAnnotation)
            .toArray(Annotation[]::new);
        if (!isCurrentRevision(sourceDocument, sourceStamp, snapshot)) {
            return;
        }
        model.modifyAnnotations(deletions, additions, null);
        cache.removeAll(deletedPositions);
        cache.addAll(additions.values());

        if (isSpellingEnabled() && spellingContext != null) {
            IRegion[] regions = new IRegion[]{
                new Region(damagedRegionOffset, damagedRegionLength)
            };
            ISpellingProblemCollector spellingProblemCollector = new SpellingProblemCollector(
                getAnnotationModel(), damagedRegionOffset, damagedRegionLength);

            spellingService.check(document, regions, spellingContext, spellingProblemCollector, monitor);
        }
    }

    private static long modificationStamp(IDocument source) {
        return source instanceof IDocumentExtension4 extension
            ? extension.getModificationStamp() : IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP;
    }

    private boolean isCurrentRevision(IDocument source, long stamp, IDocument snapshot) {
        if (document != source) {
            return false;
        }
        long currentStamp = modificationStamp(source);
        if (stamp != IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP
            && currentStamp != IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP) {
            return stamp == currentStamp;
        }
        return snapshot.get().equals(source.get());
    }

    private boolean isUsableParse(List<SQLScriptElement> queries, IDocument source, long stamp, IDocument snapshot) {
        if (queries == null || !isCurrentRevision(source, stamp, snapshot)) {
            return false;
        }
        // A parser may itself return cached positions; reject the whole update rather
        // than deleting valid annotations or publishing only part of a stale result.
        return queries.stream().allMatch(element -> element.getOffset() >= 0 && element.getLength() >= 0
            && (long) element.getOffset() + element.getLength() <= snapshot.getLength());
    }

    @Nullable
    private List<SQLScriptElement> extractQueries(int offset, int length) {
        return editor.extractScriptQueries(offset, length, false, true, false);
    }

    private boolean deservesFolding(SQLScriptElement element, IDocument snapshot) {
        int numberOfLines = getNumberOfLines(element, snapshot);
        if (numberOfLines == 1) {
            return false;
        }
        if (element.getOffset() + element.getLength() != snapshot.getLength()
            && expandQueryLength(element, snapshot) == element.getLength()) {
            return numberOfLines > 2;
        }
        return true;
    }

    private int getNumberOfLines(SQLScriptElement element, IDocument snapshot) {
        try {
            return snapshot.getLineOfOffset(element.getOffset() + element.getLength())
                - snapshot.getLineOfOffset(element.getOffset()) + 1;
        } catch (BadLocationException e) {
            throw new SQLReconcilingStrategyException(e);
        }
    }

    //expands query to the end of the line if there are only whitespaces after it. Returns desired length.
    private int expandQueryLength(SQLScriptElement element, IDocument snapshot) { //todo simplify
        int position = element.getOffset() + element.getLength();
        while (position < snapshot.getLength()) {
            char c = unsafeGetChar(position, snapshot);
            if (c == '\n') {
                if (position + 1 < snapshot.getLength()) {
                    position++;
                    break;
                }
            }
            if (Character.isWhitespace(c)) {
                position++;
            } else {
                return element.getLength();
            }
        }
        return position - element.getOffset();
    }

    @NotNull
    private SQLScriptElementImpl getExpandedScriptElement(@NotNull SQLScriptElement element, IDocument snapshot) {
        return new SQLScriptElementImpl(element.getOffset(), expandQueryLength(element, snapshot));
    }

    private char unsafeGetChar(int index, IDocument snapshot) {
        try {
            return snapshot.getChar(index);
        } catch (BadLocationException e) {
            throw new SQLReconcilingStrategyException(e);
        }
    }

    private static class SQLReconcilingStrategyException extends RuntimeException {
        private SQLReconcilingStrategyException(Throwable cause) {
            super(cause);
        }
    }

    private static class SQLScriptElementImpl extends Position implements SQLScriptElement, Comparable<SQLScriptElementImpl> {
        @Nullable
        private ProjectionAnnotation annotation;

        SQLScriptElementImpl(int offset, int length) {
            super(offset, length);
        }

        @Nullable
        public ProjectionAnnotation getAnnotation() {
            return annotation;
        }

        public void setAnnotation(@Nullable ProjectionAnnotation annotation) {
            this.annotation = annotation;
        }

        @Override
        public int compareTo(@NotNull SQLScriptElementImpl o) {
            int diff = getOffset() - o.getOffset();
            if (diff != 0) {
                return diff;
            }
            return getLength() - o.getLength();
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof Position p) {
                return equals(p.getOffset(), p.getLength());
            }
            if (o instanceof SQLScriptElement e) {
                return equals(e.getOffset(), e.getLength());
            }
            return false;
        }

        private boolean equals(int offset, int length) {
            return getOffset() == offset && getLength() == length;
        }

        @Override
        public int hashCode() {
            return Objects.hash(getOffset(), getLength());
        }

        @NotNull
        @Override
        public String getOriginalText() {
            return "";
        }

        @NotNull
        @Override
        public String getText() {
            return "";
        }

        @Override
        public Object getData() {
            return "";
        }

        @Override
        public void setData(Object data) {
            //do nothing
        }

        @Override
        public void reset() {
            //do nothing
        }

        @Nullable
        @Override
        public DBPDataSource getDataSource() {
            return null;
        }
    }

    /**
     * Spelling
     */
    private static class SpellingProblemCollector implements ISpellingProblemCollector {

        @Nullable
        private final IAnnotationModel annotationModel;
        private Map<Annotation, Position> addedAnnotations;
        private final int regionOffset;
        private final int regionLength;
        private final Object lockObject;

        public SpellingProblemCollector(
            @Nullable IAnnotationModel annotationModel,
            int regionOffset,
            int regionLength
        ) {
            this.annotationModel = annotationModel;
            if (this.annotationModel instanceof ISynchronizable) {
                Object amLock = ((ISynchronizable) this.annotationModel).getLockObject();
                lockObject = Objects.requireNonNullElse(amLock, this.annotationModel);
            } else {
                lockObject = Objects.requireNonNullElse(this.annotationModel, this);
            }
            this.regionOffset = regionOffset;
            this.regionLength = regionLength;
        }

        @Override
        public void accept(SpellingProblem problem) {
            addedAnnotations.put(
                new SpellingAnnotation(problem),
                new Position(problem.getOffset(), problem.getLength()));
        }

        @Override
        public void beginCollecting() {
            addedAnnotations = new HashMap<>();
        }

        @Override
        public void endCollecting() {
            if (annotationModel == null) {
                return;
            }
            List<Annotation> toRemove = new ArrayList<>();

            synchronized (lockObject) {
                if (annotationModel == null) {
                    addedAnnotations = null;
                    return;
                }
                Iterator<Annotation> iter = annotationModel.getAnnotationIterator();
                while (iter.hasNext()) {
                    Annotation annotation = iter.next();
                    if (annotation instanceof SpellingAnnotation) {
                        SpellingProblem spellingProblem = ((SpellingAnnotation) annotation).getSpellingProblem();
                        int problemOffset = spellingProblem.getOffset();
                        if (problemOffset >= regionOffset && problemOffset < regionOffset + regionLength) {
                            toRemove.add(annotation);
                        }
                    }
                }
                Annotation[] annotationsToRemove = toRemove.toArray(new Annotation[0]);

                if (annotationModel instanceof IAnnotationModelExtension) {
                    ((IAnnotationModelExtension) annotationModel).replaceAnnotations(annotationsToRemove, addedAnnotations);
                } else {
                    for (Annotation element : annotationsToRemove) {
                        annotationModel.removeAnnotation(element);
                    }
                    for (Map.Entry<Annotation, Position> entry : addedAnnotations.entrySet()) {
                        annotationModel.addAnnotation(entry.getKey(), entry.getValue());
                    }
                }
            }

            addedAnnotations = null;
        }
    }

}
