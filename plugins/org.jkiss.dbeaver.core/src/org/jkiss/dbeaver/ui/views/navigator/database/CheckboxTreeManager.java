/*
 * Copyright (C) 2010-2014 Serge Rieder
 * serge@jkiss.org
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package org.jkiss.dbeaver.ui.views.navigator.database;

import org.eclipse.jface.viewers.CheckStateChangedEvent;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.viewers.ICheckStateListener;
import org.eclipse.jface.viewers.ViewerFilter;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.core.DBeaverUI;
import org.jkiss.dbeaver.model.navigator.DBNDataSource;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.DBRRunnableWithProgress;
import org.jkiss.dbeaver.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.CommonUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.*;

public class CheckboxTreeManager implements ICheckStateListener {

    private final CheckboxTreeViewer viewer;
    private final Class<?>[] targetTypes;
    private Object[] checkedElements;
    private final ViewerFilter[] filters;

    public CheckboxTreeManager(CheckboxTreeViewer viewer, Class<?>[] targetTypes) {
        this.viewer = viewer;
        this.targetTypes = targetTypes;
        this.filters = viewer.getFilters();
    }

    @Override
    public void checkStateChanged(final CheckStateChangedEvent event) {
        updateElementsCheck(
            new Object[] {event.getElement()},
            event.getChecked(),
            true);
    }

    private void updateElementsCheck(final Object[] elements, final boolean checked, final boolean change) {
        checkedElements = viewer.getCheckedElements();
        try {
            DBeaverUI.runInProgressService(new DBRRunnableWithProgress() {
                @Override
                public void run(DBRProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
                    monitor.beginTask("Load sources tree", 100 * elements.length);
                    try {
                        for (Object element : elements) {
                            updateElementHierarchy(monitor, element, checked, change);

                            if (change) {
                                // Update parent state
                                if (element instanceof DBNDatabaseNode) {
                                    for (DBNNode node = ((DBNDatabaseNode) element).getParentNode(); node != null; node = node.getParentNode()) {
                                        if (node instanceof DBNDatabaseNode) {
                                            updateElementHierarchy(monitor, node, checked, false);
                                        }
                                        if (node instanceof DBNDataSource) {
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    } catch (DBException e) {
                        throw new InvocationTargetException(e);
                    } finally {
                        monitor.done();
                    }
                }
            });
        } catch (InvocationTargetException e) {
            UIUtils.showErrorDialog(viewer.getControl().getShell(), "Error", "Can't collect child nodes", e.getTargetException());
        } catch (InterruptedException e) {
            // ignore
        }
    }

    private void updateElementHierarchy(final DBRProgressMonitor monitor, final Object element, final boolean checked, final boolean change) throws DBException {
        final List<DBNDatabaseNode> targetChildren = new ArrayList<DBNDatabaseNode>();
        final List<DBNDatabaseNode> targetContainers = new ArrayList<DBNDatabaseNode>();
        collectChildren(monitor, element, targetChildren, targetContainers, !change);

        // Run ui
        viewer.getControl().getDisplay().syncExec(new Runnable() {
            @Override
            public void run() {
                if (change) {
                    for (DBNDatabaseNode child : targetChildren) {
                        viewer.setChecked(child, checked);
                    }
                }
                for (DBNDatabaseNode container : change ? targetContainers : Collections.singletonList((DBNDatabaseNode) element)) {
                    try {
                        List<DBNDatabaseNode> directChildren = CommonUtils.safeList(container.getChildren(VoidProgressMonitor.INSTANCE));
                        boolean missing = Collections.disjoint(directChildren, targetChildren);

                        viewer.setChecked(container, change ? checked : !missing || !Collections.disjoint(directChildren, targetContainers));
                        viewer.setGrayed(container, missing);
                    } catch (DBException e) {
                        // shouldn't be here
                    }
                }
            }
        });
    }

    private boolean collectChildren(DBRProgressMonitor monitor, final Object element, List<DBNDatabaseNode> targetChildren, List<DBNDatabaseNode> targetContainers, boolean onlyChecked) throws DBException {
        if (element instanceof DBNDatabaseNode) {
            for (ViewerFilter filter : filters) {
                if (!filter.select(viewer, ((DBNDatabaseNode) element).getParentNode(), element)) {
                    return false;
                }
            }

            boolean isChecked = ArrayUtils.contains(checkedElements, element);
            for (Class<?> type : targetTypes) {
                if (type.isInstance(((DBNDatabaseNode) element).getObject())) {
                    if (!onlyChecked || isChecked) {
                        targetChildren.add((DBNDatabaseNode) element);
                    }
                    return true;
                }
            }
            ((DBNDatabaseNode) element).initializeNode(monitor, null);
            List<DBNDatabaseNode> children = ((DBNDatabaseNode) element).getChildren(monitor);
            if (!CommonUtils.isEmpty(children)) {
                boolean foundChild = false;
                for (DBNDatabaseNode child : children) {
                    if (collectChildren(monitor, child, targetChildren, targetContainers, onlyChecked)) {
                        foundChild = true;
                    }
                }
                if (foundChild) {
                    if (!onlyChecked || isChecked) {
                        targetContainers.add((DBNDatabaseNode) element);
                    }
                }
                return foundChild;
            }
        }
        return false;
    }

    public void updateCheckStates() {
        Set<DBNDatabaseNode> parentList = new LinkedHashSet<DBNDatabaseNode>();
        for (Object element : viewer.getCheckedElements()) {
            for (DBNNode node = ((DBNDatabaseNode)element).getParentNode(); node != null; node = node.getParentNode()) {
                if (node instanceof DBNDatabaseNode) {
                    parentList.add((DBNDatabaseNode) node);
                    viewer.setChecked(node, true);
                }
            }
        }
        updateElementsCheck(parentList.toArray(), true, false);
    }
}
