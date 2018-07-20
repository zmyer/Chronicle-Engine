package net.openhft.chronicle.engine.cfg;

import net.openhft.chronicle.core.annotation.UsedViaReflection;
import net.openhft.chronicle.engine.api.tree.Asset;
import net.openhft.chronicle.engine.api.tree.AssetTree;
import net.openhft.chronicle.engine.api.tree.RequestContext;
import net.openhft.chronicle.engine.query.QueueConfig;
import net.openhft.chronicle.engine.tree.AssetRuleProvider;
import net.openhft.chronicle.engine.tree.MessageAdaptor;
import net.openhft.chronicle.engine.tree.VanillaAsset;
import net.openhft.chronicle.wire.AbstractMarshallableCfg;
import net.openhft.chronicle.wire.WireType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

import static net.openhft.chronicle.engine.api.tree.RequestContext.requestContext;

/**
 * @author Rob Austin.
 * Configures a Chronicle Queue
 */
public class QueueCfg extends AbstractMarshallableCfg implements Installable {

    private int masterId = 1;
    @Nullable
    private String basePath = null;
    @NotNull
    private Class topicClass = String.class;
    @NotNull
    private Class messageClass = String.class;
    @UsedViaReflection
    private boolean acknowledgment = false;
    @Nullable
    private MessageAdaptor messageAdaptor = null;
    @NotNull
    private WireType wireType = WireType.BINARY;
    @NotNull
    private String cluster = "";

    @Nullable
    @Override
    public Void install(@NotNull String uriPath, @NotNull AssetTree assetTree) throws Exception {

        @NotNull final RequestContext requestContext = requestContext(uriPath);

        if (requestContext.bootstrap() != null)
            throw new UnsupportedOperationException("Its not possible to set the bootstrap when " +
                    "acquiring a queue");

        final Function<String, Integer> queueSource = s -> s.equals(uriPath) ? masterId : 1;
        final Asset asset = assetTree.acquireAsset(uriPath);
        AssetRuleProvider ruleProvider = ((VanillaAsset) assetTree.root()).getRuleProvider();
        ruleProvider.configQueueServer((VanillaAsset) asset);
        final QueueConfig qc = asset.getView(QueueConfig.class);

        if (qc == null)
            asset.addView(new QueueConfig(queueSource, acknowledgment, messageAdaptor, wireType));

        assetTree.acquireView(requestContext.view("queue")
                .type(topicClass)
                .type2(messageClass)
                .basePath(basePath)
                .cluster(cluster));

        return null;
    }
}
