/*
 * Copyright (c) 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package org.graalvm.visualvm.heapviewer.java.impl;

import java.awt.event.ActionEvent;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.SortOrder;
import org.graalvm.visualvm.lib.jfluid.heap.ArrayItemValue;
import org.graalvm.visualvm.lib.jfluid.heap.Heap;
import org.graalvm.visualvm.lib.jfluid.heap.Instance;
import org.graalvm.visualvm.lib.jfluid.heap.ObjectFieldValue;
import org.graalvm.visualvm.lib.jfluid.heap.Value;
import org.graalvm.visualvm.lib.profiler.api.icons.Icons;
import org.graalvm.visualvm.lib.profiler.api.icons.ProfilerIcons;
import org.graalvm.visualvm.heapviewer.HeapContext;
import org.graalvm.visualvm.heapviewer.java.InstanceNode;
import org.graalvm.visualvm.heapviewer.java.InstanceNodeRenderer;
import org.graalvm.visualvm.heapviewer.java.InstanceReferenceNode;
import org.graalvm.visualvm.heapviewer.java.JavaHeapFragment;
import org.graalvm.visualvm.heapviewer.model.DataType;
import org.graalvm.visualvm.heapviewer.model.HeapViewerNode;
import org.graalvm.visualvm.heapviewer.model.HeapViewerNodeFilter;
import org.graalvm.visualvm.heapviewer.model.Progress;
import org.graalvm.visualvm.heapviewer.model.RootNode;
import org.graalvm.visualvm.heapviewer.model.TextNode;
import org.graalvm.visualvm.heapviewer.ui.HeapViewPlugin;
import org.graalvm.visualvm.heapviewer.ui.HeapViewerActions;
import org.graalvm.visualvm.heapviewer.ui.TreeTableView;
import org.graalvm.visualvm.heapviewer.ui.TreeTableViewColumn;
import org.graalvm.visualvm.heapviewer.ui.UIThresholds;
import org.graalvm.visualvm.heapviewer.utils.NodesComputer;
import org.graalvm.visualvm.heapviewer.utils.ProgressIterator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import javax.swing.ImageIcon;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import org.graalvm.visualvm.heapviewer.java.InstancesWrapper;
import org.graalvm.visualvm.heapviewer.model.ErrorNode;
import org.graalvm.visualvm.heapviewer.utils.HeapOperations;
import org.graalvm.visualvm.heapviewer.utils.HeapUtils;
import org.graalvm.visualvm.lib.ui.swing.renderer.LabelRenderer;
import org.openide.util.NbBundle;
import org.openide.util.NbPreferences;
import org.openide.util.lookup.ServiceProvider;

/**
 *
 * @author Jiri Sedlacek
 */
@NbBundle.Messages({
    "PathToGCRootPlugin_Name=GC Root",
    "PathToGCRootPlugin_Description=GC Root",
    "PathToGCRootPlugin_NoRoot=<no GC root>",
    "PathToGCRootPlugin_IsRoot=<node is GC root>",
    "PathToGCRootPlugin_NoSelection=<no class or instance selected>",
    "PathToGCRootPlugin_MoreNodes=<another {0} GC roots left>",
    "PathToGCRootPlugin_SamplesContainer=<sample {0} GC roots>",
    "PathToGCRootPlugin_NodesContainer=<GC roots {0}-{1}>",
    "PathToGCRootPlugin_MenuShowMergedRoots=Show Merged GC Roots"
})
public class PathToGCRootPlugin extends HeapViewPlugin {
    
    private static final TreeTableView.ColumnConfiguration CCONF_CLASS = new TreeTableView.ColumnConfiguration(DataType.COUNT, null, DataType.COUNT, SortOrder.DESCENDING, Boolean.FALSE);
    private static final TreeTableView.ColumnConfiguration CCONF_INSTANCE = new TreeTableView.ColumnConfiguration(null, DataType.COUNT, DataType.NAME, SortOrder.UNSORTED, null);
    
    private static final String KEY_MERGED_GCROOTS = "mergedRoots"; // NOI18N
    
    private volatile boolean mergedRoots = readItem(KEY_MERGED_GCROOTS, true);
    
    private final Heap heap;
    private HeapViewerNode selected;
    
    private final TreeTableView objectsView;
    
    private volatile boolean showingClass;
    
    
    public PathToGCRootPlugin(HeapContext context, HeapViewerActions actions) {
        super(Bundle.PathToGCRootPlugin_Name(), Bundle.PathToGCRootPlugin_Description(), Icons.getIcon(ProfilerIcons.RUN_GC));
        
        heap = context.getFragment().getHeap();
        
        TreeTableViewColumn[] columns = new TreeTableViewColumn[] {
            new TreeTableViewColumn.Name(heap),
            new TreeTableViewColumn.LogicalValue(heap),
            new TreeTableViewColumn.Count(heap, true, true),
            new TreeTableViewColumn.OwnSize(heap, false, false),
            new TreeTableViewColumn.RetainedSize(heap, false, false),
            new TreeTableViewColumn.ObjectID(heap)
        };
        objectsView = new TreeTableView("java_objects_gcroots", context, actions, columns) { // NOI18N
            protected HeapViewerNode[] computeData(RootNode root, Heap heap, String viewID, HeapViewerNodeFilter viewFilter, List<DataType> dataTypes, List<SortOrder> sortOrders, Progress progress) throws InterruptedException {
                HeapViewerNode _selected;
                synchronized (objectsView) { _selected = selected; }

                if (_selected == null) return new HeapViewerNode[] { new TextNode(Bundle.PathToGCRootPlugin_NoSelection()) };

                Instance instance;
                InstancesWrapper wrapper = HeapViewerNode.getValue(_selected, DataType.INSTANCES_WRAPPER, heap);
                if (wrapper != null) {
                    instance = null;

                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            if (!mergedRoots && !CCONF_INSTANCE.equals(objectsView.getCurrentColumnConfiguration()))
                                objectsView.configureColumns(CCONF_INSTANCE);
                            else if (mergedRoots && !CCONF_CLASS.equals(objectsView.getCurrentColumnConfiguration()))
                                objectsView.configureColumns(CCONF_CLASS);
                        }
                    });

                    if (!mergedRoots) return new HeapViewerNode[] { new TextNode("<merged GC roots disabled>") };
                } else {
                    instance = HeapViewerNode.getValue(_selected, DataType.INSTANCE, heap);

                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            if (!CCONF_INSTANCE.equals(objectsView.getCurrentColumnConfiguration()))
                                objectsView.configureColumns(CCONF_INSTANCE);
                        }
                    });

                    if (instance == null) return new HeapViewerNode[] { new TextNode(Bundle.PathToGCRootPlugin_NoSelection()) };
                }
                
                HeapOperations.initializeGCRoots(heap);

                Collection<HeapViewerNode> data;
                if (instance != null) {
                    data = computeInstanceRoots(instance, progress);
                    if (data != null) showingClass = false;
                } else {
                    data = computeInstancesRoots(wrapper.getInstancesIterator(), wrapper.getInstancesCount(), progress);
                    if (data != null) showingClass = true;
                }

                if (data == null) return null;
                if (data.size() == 1) return new HeapViewerNode[] { data.iterator().next() };

                final Collection<HeapViewerNode> _data = data;
                NodesComputer<HeapViewerNode> computer = new NodesComputer<HeapViewerNode>(_data.size(), UIThresholds.MAX_MERGED_OBJECTS) {
                    protected boolean sorts(DataType dataType) {
                        return true;
                    }
                    protected HeapViewerNode createNode(HeapViewerNode node) {
                        return node;
                    }
                    protected ProgressIterator<HeapViewerNode> objectsIterator(int index, Progress progress) {
                        Iterator iterator = _data.iterator();
                        return new ProgressIterator(iterator, index, true, progress);
                    }
                    protected String getMoreNodesString(String moreNodesCount)  {
                        return Bundle.PathToGCRootPlugin_MoreNodes(moreNodesCount);
                    }
                    protected String getSamplesContainerString(String objectsCount)  {
                        return Bundle.PathToGCRootPlugin_SamplesContainer(objectsCount);
                    }
                    protected String getNodesContainerString(String firstNodeIdx, String lastNodeIdx)  {
                        return Bundle.PathToGCRootPlugin_NodesContainer(firstNodeIdx, lastNodeIdx);
                    }
                };

                return computer.computeNodes(root, heap, viewID, null, dataTypes, sortOrders, progress);
            }
            @Override
            protected void populatePopup(HeapViewerNode node, JPopupMenu popup) {
                if (popup.getComponentCount() > 0) popup.addSeparator();
                
                popup.add(new JCheckBoxMenuItem(Bundle.PathToGCRootPlugin_MenuShowMergedRoots(), mergedRoots) {
                    @Override
                    protected void fireActionPerformed(ActionEvent event) {
                        SwingUtilities.invokeLater(new Runnable() {
                            @Override
                            public void run() {
                                mergedRoots = isSelected();
                                storeItem(KEY_MERGED_GCROOTS, mergedRoots);
                                reloadView();
                            }
                        });
                    }
                });
            }
            protected void childrenChanged() {
                if (!showingClass) fullyExpandNode(getRoot());
            }
            protected void nodeExpanded(HeapViewerNode node) {
                if (showingClass && node instanceof GCInstanceNode) fullyExpandNode(node);
            }
            private void fullyExpandNode(HeapViewerNode node) {
                while (node != null) {
                    expandNode(node);
                    node = node.getNChildren() > 0 ? node.getChild(0) : null;
                }
            }
        };
    }
    
    
    @Override
    protected JComponent createComponent() {
        return objectsView.getComponent();
    }
        
    @Override
    protected void nodeSelected(final HeapViewerNode node, boolean adjusting) {
        synchronized (objectsView) { selected = node; }
        
        objectsView.reloadView();
    }
    
    
    @Override
    protected void closed() {
        objectsView.closed();
    }
    
    
    private static Collection<HeapViewerNode> computeInstanceRoots(Instance instance, Progress progress) throws InterruptedException {
        Instance nextInstance = instance.getNearestGCRootPointer();
                    
        if (nextInstance == null) {
            return Collections.singleton(new TextNode(Bundle.PathToGCRootPlugin_NoRoot()));
        } else if (nextInstance == instance) {
            return Collections.singleton(new TextNode(Bundle.PathToGCRootPlugin_IsRoot()));
        } else {
            ToRoot node = null;
            HeapViewerNode firstNode = null;
            ToRoot previousNode = null;
            
            Thread current = Thread.currentThread();
            
            progress.setupUnknownSteps();

            try {
                while (!current.isInterrupted() && instance != nextInstance) {
                    List<Value> references = instance.getReferences();
                    for (Value reference : references) {
                        if (nextInstance.equals(reference.getDefiningInstance())) {
                            if (reference instanceof ObjectFieldValue) node = new FieldToRoot((ObjectFieldValue)reference);
                            else if (reference instanceof ArrayItemValue) node = new ArrayItemToRoot((ArrayItemValue)reference);

                            if (firstNode == null) firstNode = (HeapViewerNode)node;
                            else previousNode.setChildren(new HeapViewerNode[] { (HeapViewerNode)node });

                            break;
                        }
                    }

                    instance = nextInstance;
                    nextInstance = instance.getNearestGCRootPointer();
                    progress.step();

                    previousNode = node;
                }
                if (current.isInterrupted()) throw new InterruptedException();
                
                if (node != null) node.setChildren(HeapViewerNode.NO_NODES);
            } finally {           
                progress.finish();
            }
            
            return Collections.singleton(firstNode);
        }
    }
    
    private static Collection<HeapViewerNode> computeInstancesRoots(Iterator<Instance> instances, int count, Progress progress) throws InterruptedException {
        Map<Instance, HeapViewerNode> gcRoots = new HashMap();
        
        Thread current = Thread.currentThread();
        
        progress.setupKnownSteps(count);
        
        try {
            while (!current.isInterrupted() && instances.hasNext()) {
                Instance instance = instances.next();
                Instance gcRoot = getGCRoot(instance, current);
                GCRootNode gcRootNode = (GCRootNode)gcRoots.get(gcRoot);
                if (gcRootNode == null) {
                    gcRootNode = new GCRootNode(gcRoot);
                    gcRoots.put(gcRoot, gcRootNode);
                }
                gcRootNode.addInstance(instance);
                progress.step();
            }
            if (current.isInterrupted()) throw new InterruptedException();
        } catch (OutOfMemoryError e) {
            System.err.println("Out of memory in PathToGCRootPlugin: " + e.getMessage()); // NOI18N
            HeapUtils.handleOOME(e);
            return Collections.singleton(new ErrorNode.OOME());
        } finally {
            progress.finish();
        }
        
        if (!gcRoots.isEmpty()) return gcRoots.values();
        else return Collections.singleton(new TextNode(Bundle.PathToGCRootPlugin_NoRoot()));
    }
    
    private static Instance getGCRoot(Instance instance, Thread current) {
        Instance previousInstance = null;
        while (!current.isInterrupted() && instance != null && instance != previousInstance) {
            previousInstance = instance;
            instance = instance.getNearestGCRootPointer();
        }
        return instance;
    }
    
    private static boolean readItem(String itemName, boolean initial) {
        return NbPreferences.forModule(PathToGCRootPlugin.class).getBoolean("PathToGCRootPlugin." + itemName, initial); // NOI18N
    }

    private static void storeItem(String itemName, boolean value) {
        NbPreferences.forModule(PathToGCRootPlugin.class).putBoolean("PathToGCRootPlugin." + itemName, value); // NOI18N
    }
    
    @NbBundle.Messages({
        "GCRootNode_MoreNodes=<another {0} instances left>",
        "GCRootNode_SamplesContainer=<sample {0} instances>",
        "GCRootNode_NodesContainer=<instances {0}-{1}>"
    })
    static class GCRootNode extends InstanceNode.IncludingNull {
        
//        private final int maxNodes = UIThresholds.MAX_MERGED_OBJECTS;
        
        private final List<Instance> instances = new ArrayList();
        
        
        public GCRootNode(Instance gcRoot) {
            super(gcRoot);
        }
        
        
        void addInstance(Instance instance) {
            instances.add(instance);
        }
        
        
        public int getCount() {
            return instances.size();
        }
        
        
//        protected HeapViewerNode[] computeChildren(RootNode root) {
//            int itemsCount = instances.size();
//            if (itemsCount <= maxNodes) {
//                HeapViewerNode[] nodes = new HeapViewerNode[itemsCount];
//                for (int i = 0; i < itemsCount; i++) nodes[i] = createNode(instances.get(i));
//                return nodes;
//            } else {
//                return super.computeChildren(root);
//            }
//        }

        protected HeapViewerNode[] lazilyComputeChildren(Heap heap, String viewID, HeapViewerNodeFilter viewFilter, List<DataType> dataTypes, List<SortOrder> sortOrders, Progress progress) throws InterruptedException {
            final Instance gcRoot = getInstance();
            final boolean isArray = gcRoot != null && gcRoot.getJavaClass().isArray();
            NodesComputer<Instance> computer = new NodesComputer<Instance>(instances.size(), UIThresholds.MAX_MERGED_OBJECTS) {
                protected boolean sorts(DataType dataType) {
                    if (DataType.COUNT.equals(dataType) || (DataType.OWN_SIZE.equals(dataType) && !isArray)) return false;
                    return true;
                }
                protected HeapViewerNode createNode(Instance object) {
                    return new GCInstanceNode(object) {
                        public boolean isLeaf() { return gcRoot == null; }
                    };
                }
                protected ProgressIterator<Instance> objectsIterator(int index, Progress progress) {
                    Iterator<Instance> iterator = instances.listIterator(index);
                    return new ProgressIterator(iterator, index, false, progress);
                }
                protected String getMoreNodesString(String moreNodesCount)  {
                    return Bundle.GCRootNode_MoreNodes(moreNodesCount);
                }
                protected String getSamplesContainerString(String objectsCount)  {
                    return Bundle.GCRootNode_SamplesContainer(objectsCount);
                }
                protected String getNodesContainerString(String firstNodeIdx, String lastNodeIdx)  {
                    return Bundle.GCRootNode_NodesContainer(firstNodeIdx, lastNodeIdx);
                }
            };
            return computer.computeNodes(GCRootNode.this, heap, viewID, null, dataTypes, sortOrders, progress);
        }
        
        
        protected Object getValue(DataType type, Heap heap) {
            if (type == DataType.COUNT) return getCount();

            return super.getValue(type, heap);
        }
        
        
        public boolean isLeaf() {
            return false;
        }
        
        
        static class Renderer extends InstanceNodeRenderer {
            
            private static final ImageIcon ICON = Icons.getImageIcon(ProfilerIcons.NODE_FORWARD);
        
            Renderer(Heap heap) {
                super(heap);
            }
            
            @Override
            public void setValue(Object value, int row) {
                if (value != null) {
                    GCRootNode node = (GCRootNode)value;
                    if (node.getInstance() == null) {
                        setNormalValue(Bundle.PathToGCRootPlugin_NoRoot());
                        setBoldValue(""); // NOI18N
                        setGrayValue(""); // NOI18N
                        setIcon(ICON);
                        return;
                    }
                }
                super.setValue(value, row);
                
                setIconTextGap(4);
                ((LabelRenderer)valueRenderers()[0]).setMargin(3, 3, 3, 0);
            }
            
            @Override
            protected ImageIcon getIcon(Instance instance, boolean isGCRoot) {
                return ICON;
            }

        }
        
    }
    
    private static class GCInstanceNode extends InstanceNode {
    
        public GCInstanceNode(Instance instance) {
            super(instance);
        }
        
        protected HeapViewerNode[] lazilyComputeChildren(Heap heap, String viewID, HeapViewerNodeFilter viewFilter, List<DataType> dataTypes, List<SortOrder> sortOrders, Progress progress) throws InterruptedException {
            Collection<HeapViewerNode> nodes = PathToGCRootPlugin.computeInstanceRoots(getInstance(), progress);
            return nodes == null ? null : nodes.toArray(HeapViewerNode.NO_NODES);
        }
    
    }
    
    private static interface ToRoot {
        
        public void setChildren(HeapViewerNode[] ch);
        
    }
    
    private static class FieldToRoot extends InstanceReferenceNode.Field implements ToRoot {
        
        public FieldToRoot(ObjectFieldValue value) {
            super(value, true);
        }
        
        public void setChildren(HeapViewerNode[] ch) {
            super.setChildren(ch);
        }
        
    }
    
    private static class ArrayItemToRoot extends InstanceReferenceNode.ArrayItem implements ToRoot {
        
        public ArrayItemToRoot(ArrayItemValue value) {
            super(value, true);
        } 
        
        public void setChildren(HeapViewerNode[] ch) {
            super.setChildren(ch);
        }
        
    }
    
    
    @ServiceProvider(service=HeapViewPlugin.Provider.class, position = 400)
    public static class Provider extends HeapViewPlugin.Provider {

        public HeapViewPlugin createPlugin(HeapContext context, HeapViewerActions actions, String viewID) {
            if (!viewID.startsWith("diff") && JavaHeapFragment.isJavaHeap(context)) return new PathToGCRootPlugin(context, actions); // NOI18N
            return null;
        }
        
    }
    
}
