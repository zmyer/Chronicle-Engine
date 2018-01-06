/*
 * Copyright 2016 higherfrequencytrading.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package net.openhft.chronicle.engine.api.management;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.engine.api.management.mbean.AssetTreeDynamicMBean;
import net.openhft.chronicle.engine.api.map.MapEvent;
import net.openhft.chronicle.engine.api.map.SubscriptionKeyValueStore;
import net.openhft.chronicle.engine.api.tree.Asset;
import net.openhft.chronicle.engine.api.tree.AssetTree;
import net.openhft.chronicle.engine.map.ObjectKeyValueStore;
import net.openhft.chronicle.engine.map.ObjectSubscription;
import net.openhft.chronicle.engine.tree.HostIdentifier;
import net.openhft.chronicle.engine.tree.TopologicalEvent;
import net.openhft.chronicle.network.api.session.SessionDetails;
import net.openhft.chronicle.network.api.session.SessionProvider;
import net.openhft.chronicle.threads.Threads;
import net.openhft.lang.thread.NamedThreadFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.*;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.rmi.registry.LocateRegistry;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/*
 * Created by peter.lawrey on 16/06/2015.
 */
public enum ManagementTools {
    ;

    private static final Logger LOG = LoggerFactory.getLogger(ManagementTools.class);

    //JMXConnectorServer for create jmx service
    private static JMXConnectorServer jmxServer;

    //MBeanServer for register MBeans
    @Nullable
    private static MBeanServer mbs = null;

    private static AssetTreeDynamicMBean dynamicMBean;

    //number of AssetTree enabled for management.
    private static int count = 0;

    public static int getCount() {
        return count;
    }

    private static void startJMXRemoteService(final int port) throws IOException {
        if (jmxServer == null) {
            mbs = ManagementFactory.getPlatformMBeanServer();

            // Create the RMI registry on port 9000
            LocateRegistry.createRegistry(port);

            // Build a URL which tells the RMIConnectorServer to bind to the RMIRegistry running on port 9000
            @NotNull JMXServiceURL url = new JMXServiceURL
                    ("service:jmx:rmi:///jndi/rmi://localhost:" + port + "/jmxrmi");
            @NotNull Map<String, String> env = new HashMap<>();
            env.put("com.sun.management.jmxremote", "true");
            env.put("com.sun.management.jmxremote.ssl", "false");
            env.put("com.sun.management.jmxremote.authenticate", "false");

            jmxServer = JMXConnectorServerFactory.newJMXConnectorServer(url, env, mbs);
            jmxServer.start();
        }
    }

    private static void stopJMXRemoteService() throws IOException {
        if (jmxServer != null) {
            mbs = null;
            jmxServer.stop();
        }
    }

    /**
     * It will enable the management for given object of AssetTree type. It will create a object of
     * MBeanServer and register AssetTree.
     *
     * @param assetTree the object of AssetTree type for enable management
     */
    public static void enableManagement(@NotNull AssetTree assetTree) {
        enableManagement(assetTree, 9000);
    }

    public static void enableManagement(@NotNull AssetTree assetTree, int port) {
        try {
            startJMXRemoteService(port);
            count++;
        } catch (IOException ie) {
            Jvm.warn().on(ManagementTools.class, "Error while enable management", ie);
        }
        registerViewOfTree(assetTree);
    }

    public static void disableManagement(@NotNull AssetTree assetTree) {

        String treeName = assetTree.toString();
        try {
            Set<ObjectName> objNames = mbs.queryNames(new ObjectName("*:type=" + treeName + ",*"), null);
            objNames.forEach(ManagementTools::unregisterTreeWithMBean);
        } catch (MalformedObjectNameException e) {
            Jvm.warn().on(ManagementTools.class, "Error while disable management", e);
        }
        count--;

        try {
            if (count == 0) {
                stopJMXRemoteService();
            }
        } catch (IOException e) {
            Jvm.warn().on(ManagementTools.class, "Error while stopping JMX remote service", e);
        }
    }

    private static void registerViewOfTree(@NotNull AssetTree tree) {
        Threads.withThreadGroup(tree.root().getView(ThreadGroup.class), () -> {
            @NotNull ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor(
                    new NamedThreadFactory("tree-watcher", true));

            @Nullable SessionProvider view = tree.root().findView(SessionProvider.class);
            @Nullable final SessionDetails sessionDetails = view.get();

            ses.submit(() -> {
                // set the session details on the JMX thread, to the same as the server system session details.
                @Nullable final SessionProvider view0 = tree.root().findView(SessionProvider.class);
                view0.set(sessionDetails);
            });

            tree.registerSubscriber("", TopologicalEvent.class, e -> {
                        // give the collection time to be setup.
                if (e.assetName() != null) {
                            ses.schedule(() -> handleTreeUpdate(tree, e, ses), 2000, TimeUnit.MILLISECONDS);
                        }
                    }
            );
            return null;
        });
    }

    private static void handleTreeUpdate(@NotNull AssetTree tree, @NotNull TopologicalEvent e, @NotNull ScheduledExecutorService ses) {
        try {
            @Nullable HostIdentifier hostIdentifier = tree.root().getView(HostIdentifier.class);
            int hostId = hostIdentifier == null ? 0 : hostIdentifier.hostId();
            String treeName = tree.toString();
            if (e.added()) {
                @NotNull String assetFullName = e.fullName();
                @Nullable Asset asset = tree.getAsset(assetFullName);
                if (asset == null) {
                    return;
                }

                @Nullable ObjectKeyValueStore<Object, Object> view = null;

                for (Class c : new Class[]{ObjectKeyValueStore.class, SubscriptionKeyValueStore.class}) {

                    view = (ObjectKeyValueStore) asset.getView(c);
                    if (view != null) {
                        break;
                    }
                }

                if (view == null) {
                    return;
                }

                @Nullable final ObjectKeyValueStore view0 = view;
                @Nullable ObjectSubscription objectSubscription = asset.getView(ObjectSubscription.class);
                //ObjectName atName = new ObjectName(createObjectNameUri(e.assetName(),e.name(),treeName));

                //start Dynamic MBeans Code
                @NotNull Map<String, String> m = new HashMap<>();
                m.put("size", "" + view.longSize());
                m.put("keyType", view.keyType().getName());
                m.put("valueType", view.valueType().getName());
                m.put("topicSubscriberCount", "" + objectSubscription.topicSubscriberCount());
                m.put("keySubscriberCount", "" + objectSubscription.keySubscriberCount());
                m.put("entrySubscriberCount", "" + objectSubscription.entrySubscriberCount());
                m.put("keyStoreValue", objectSubscription.getClass().getName());
                m.put("path", e.assetName() + "-" + e.name());

                for (int i = 0; i < view.segments(); i++) {
                    view.entriesFor(i, entry -> {
                        if (entry.getValue().toString().length() > 256) {
                            m.put("~" + entry.getKey().toString(), entry.getValue().toString().substring(0, 256) + "...");
                        } else {
                            m.put("~" + entry.getKey().toString(), entry.getValue().toString());
                        }
                    });
                }
                dynamicMBean = new AssetTreeDynamicMBean(m);
                @NotNull ObjectName atName = new ObjectName(createObjectNameUri(hostId, e.assetName(), e.name(), treeName));
                registerTreeWithMBean(dynamicMBean, atName);
                //end Dynamic MBeans Code

                tree.registerSubscriber(e.fullName(), MapEvent.class, (MapEvent me) ->
                        ses.schedule(() -> handleAssetUpdate(view0, atName, objectSubscription, e.assetName() + "-" + e.name()), 100, TimeUnit.MILLISECONDS));

                //AssetTreeJMX atBean = new AssetTreeJMX(view,objectKVSSubscription,e.assetName() + "-" + e.name(),getMapAsString(view));
                //registerTreeWithMBean(atBean, atName);

            } else {
                @NotNull ObjectName atName = new ObjectName(createObjectNameUri(hostId, e.assetName(), e.name(), treeName));
                unregisterTreeWithMBean(atName);
            }
        } catch (Throwable t) {
            Jvm.warn().on(ManagementTools.class, "Error while handle AssetTree update", t);
        }
    }

    private static void handleAssetUpdate(@NotNull ObjectKeyValueStore view, ObjectName atName, @NotNull ObjectSubscription objectSubscription, String path) {
        try {
            if (mbs != null && mbs.isRegistered(atName)) {
                /*AttributeList list = new AttributeList();
                list.add(new Attribute("Size",view.longSize()));
                list.add(new Attribute("Entries",getMapAsString(view)));
                list.add(new Attribute("KeyType",view.keyType().getName()));
                list.add(new Attribute("ValueType",view.valueType().getName()));
                list.add(new Attribute("TopicSubscriberCount",objectKVSSubscription.topicSubscriberCount()));
                list.add(new Attribute("EntrySubscriberCount",objectKVSSubscription.entrySubscriberCount()));
                list.add(new Attribute("KeySubscriberCount",objectKVSSubscription.keySubscriberCount()));
                list.add(new Attribute("Dynamic Attribute Key","Dynamic Attribute Value"));
                mbs.setAttributes(atName,list);*/
                //start Dynamic MBeans Code
                @NotNull Map m = new HashMap();
                m.put("size", "" + view.longSize());
                m.put("keyType", view.keyType().getName());
                m.put("valueType", view.valueType().getName());
                m.put("topicSubscriberCount", "" + objectSubscription.topicSubscriberCount());
                m.put("keySubscriberCount", "" + objectSubscription.keySubscriberCount());
                m.put("entrySubscriberCount", "" + objectSubscription.entrySubscriberCount());
                m.put("keyStoreValue", objectSubscription.getClass().getName());
                m.put("path", path);

                Iterator<Map.Entry> iterator = view.entrySetIterator();
                while (iterator.hasNext()) {
                    Map.Entry entry = iterator.next();
                    if (entry.getValue().toString().length() > 128) {
                        m.put("~" + entry.getKey().toString(), entry.getValue().toString().substring(0, 128) + "...");
                    } else {
                        m.put("~" + entry.getKey().toString(), entry.getValue().toString());
                    }
                }
                dynamicMBean = new AssetTreeDynamicMBean(m);
                unregisterTreeWithMBean(atName);
                registerTreeWithMBean(dynamicMBean, atName);
                //end Dynamic MBeans Code
            }
        } catch (Throwable t) {
            Jvm.warn().on(ManagementTools.class, "Error while handle Asset update", t);
        }
    }

    private static String createObjectNameUri(int hostId, @NotNull String assetName, String eventName, @NotNull String treeName) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("treeName=" + treeName);
        }
        @NotNull StringBuilder sb = new StringBuilder(256);
        sb.append("net.openhft.chronicle.engine:tree=");
        sb.append(hostId);
        sb.append(",type=");
        sb.append(treeName);
        //sb.append("net.openhft.chronicle.engine.api.tree:type=AssetTree");

        @NotNull String[] names = assetName.split("/");
        for (int i = 1; i < names.length; i++) {
            sb.append(",side").append(i).append("=").append(names[i]);
        }
        sb.append(",name=").append(eventName);
        return sb.toString();
    }

    private static void registerTreeWithMBean(AssetTreeDynamicMBean atBean, ObjectName atName) {
        try {
            if (mbs != null && !mbs.isRegistered(atName)) {
                mbs.registerMBean(atBean, atName);
            }
        } catch (@NotNull InstanceAlreadyExistsException | MBeanRegistrationException | NotCompliantMBeanException e) {
            Jvm.warn().on(ManagementTools.class, "Error register AssetTree with MBean", e);
        }
    }

    private static void unregisterTreeWithMBean(ObjectName atName) {
        try {
            if (mbs != null && mbs.isRegistered(atName)) {
                mbs.unregisterMBean(atName);
            }
        } catch (@NotNull InstanceNotFoundException | MBeanRegistrationException e) {
            Jvm.warn().on(ManagementTools.class, "Error unregister AssetTree with MBean", e);
        }
    }

    private static String getMapAsString(@NotNull ObjectKeyValueStore view) {

        long max = view.longSize() - 1;
        if (max == -1) {
            return "{}";
        }

        @NotNull StringBuilder sb = new StringBuilder();

        Iterator<Map.Entry> it = view.entrySetIterator();
        sb.append('{');
        for (int i = 0; ; i++) {
            Map.Entry e = it.next();

            String key = e.getKey().toString();
            String value = e.getValue().toString();

            sb.append(key);
            sb.append('=');

            if (value.length() > 128) {
                sb.append(value.substring(0, 128)).append("...");
            } else {
                sb.append(value);
            }

            if (i == max) {
                return sb.append('}').toString();
            }
            sb.append(", ");
        }
    }
}
