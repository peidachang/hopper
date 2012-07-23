package com.hopper.sync;

import com.hopper.GlobalConfiguration;
import com.hopper.future.LatchFuture;
import com.hopper.lifecycle.LifecycleProxy;
import com.hopper.server.ComponentManager;
import com.hopper.server.ComponentManagerFactory;
import com.hopper.server.Endpoint;
import com.hopper.session.Message;
import com.hopper.stage.Stage;
import com.hopper.storage.StateNode;
import com.hopper.storage.StateNodeSnapshot;
import com.hopper.storage.StateStorage;
import com.hopper.utils.merkle.Difference;
import com.hopper.utils.merkle.MerkleTree;
import com.hopper.verb.Verb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * {@link DataSyncService} provides supports for data synchronization between nodes.
 */
public class DataSyncService extends LifecycleProxy {
    /**
     * Logger
     */
    private static final Logger logger = LoggerFactory.getLogger(DataSyncService.class);

    private final ComponentManager componentManager = ComponentManagerFactory.getComponentManager();

    /**
     * Global configuration reference
     */
    private final GlobalConfiguration config = componentManager.getGlobalConfiguration();
    /**
     * Storage reference
     */
    private final StateStorage storage = componentManager.getStateStorage();
    /**
     * Data synchronization thread pool
     */
    private ExecutorService threadPool;

    @Override
    protected void doInit() {
        threadPool = componentManager.getStageManager().getThreadPool(Stage.SYNC);
    }

    @Override
    protected void doShutdown() {
        if (threadPool != null) {
            threadPool.shutdown();
        }
    }

    @Override
    public String getInfo() {
        return "Data synchronization service";
    }

    /**
     * Compares the local merkle tree with remote(the remote is fresh) and return the comparison result.
     */
    public LatchFuture<DiffResult> diff(int remoteServerId) {
        RequireRemoteDiffTask task = new RequireRemoteDiffTask(remoteServerId);
        return (LatchFuture<DiffResult>) threadPool.submit(task);
    }

    /**
     * Executes the comparison result on local storage
     */
    public void applyDiff(DiffResult diff) {

        if (diff.getMaxXid() <= storage.getMaxXid()) {
            logger.debug("Ignoring the diff result, because oft he target xid {} is smaller than local {}",
                    new Object[]{diff.getMaxXid(), storage.getMaxXid()});
            return;
        }

        Difference difference = diff.getDifference();

        if (!difference.hasDifferences()) {
            return;
        }

        for (StateNodeSnapshot snapshot : difference.addedList) {
            StateNode node = newStateNode(snapshot.key, snapshot.version);
            node.update(snapshot);

            storage.put(node);
        }

        for (StateNodeSnapshot snapshot : difference.removedList) {
            storage.remove(snapshot.key);
        }

        for (StateNodeSnapshot snapshot : difference.updatedList) {
            StateNode node = storage.get(snapshot.key);
            if (node != null) {
                if (snapshot.version > node.getVersion()) {
                    node.update(snapshot);
                }
            }
        }
    }

    /**
     * Pull data from remote server(remote is fresh) with asynchronization
     */
    public void syncDataFromRemote(final int serverId) {
        Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    DiffResult diffResult = syncDiff(serverId);
                    applyDiff(diffResult);
                } catch (Exception e) {
                    logger.error("Failed to pull data from remote[server:{}]", new Object[]{serverId, e});
                }
            }
        };

        threadPool.execute(task);
    }

    /**
     * Push the local data to remote servers(local data is fresh)
     */
    public List<LatchFuture<Boolean>> syncDataToRemote(Integer[] remoteServers) {
        List<LatchFuture<Boolean>> futures = new ArrayList<LatchFuture<Boolean>>(remoteServers.length);
        for (int serverId : remoteServers) {
            RequireRemoteSyncTask task = new RequireRemoteSyncTask(config.getEndpoint(serverId));
            LatchFuture<Boolean> future = (LatchFuture<Boolean>) threadPool.submit(task);
            futures.add(future);
        }

        return futures;
    }

    private StateNode newStateNode(String key, long initialVersion) {
        StateNode node = new StateNode(key, initialVersion);
        node.setScheduleManager(componentManager.getScheduleManager());
        ExecutorService notifyExecutorService = componentManager.getStageManager().getThreadPool(Stage.STATE_CHANGE);
        node.setNotifyExecutorService(notifyExecutorService);

        return node;
    }

    /**
     * {@link com.hopper.sync.DataSyncService.RequireRemoteDiffTask} will send local data to remote
     * server, and requires the remote server to return the diff data.
     */
    private class RequireRemoteDiffTask implements Callable<DiffResult> {
        final GlobalConfiguration config = ComponentManagerFactory.getComponentManager().getGlobalConfiguration();
        final int remoteServerId;

        private RequireRemoteDiffTask(int remoteServerId) {
            this.remoteServerId = remoteServerId;
        }

        @Override
        public DiffResult call() throws Exception {
            return syncDiff(remoteServerId);
        }
    }

    private DiffResult syncDiff(int remoteServerId) throws Exception {
        Message message = new Message();
        message.setVerb(Verb.REQUIRE_DIFF);
        message.setId(Message.nextId());

        RequireDiff diff = new RequireDiff();
        diff.setMaxXid(storage.getMaxXid());
        diff.setTree(storage.getHashTree());

        message.setBody(diff);

        Future<Message> future = componentManager.getMessageService().send(message, remoteServerId);
        Message reply = future.get(config.getSyncTimeout(), TimeUnit.MILLISECONDS);

        return (DiffResult) reply.getBody();
    }

    private class RequireRemoteSyncTask implements Callable<Boolean> {
        final Endpoint remoteServer;

        private RequireRemoteSyncTask(Endpoint remoteServer) {
            this.remoteServer = remoteServer;
        }

        @Override
        public Boolean call() throws Exception {
            Message request = new Message();
            request.setVerb(Verb.REQUIRE_TREE);
            request.setId(Message.nextId());

            Future<Message> future = componentManager.getMessageService().send(request, remoteServer.serverId);

            Message reply = future.get(config.getRpcTimeout(), TimeUnit.MILLISECONDS);

            MerkleTree tree = (MerkleTree) reply.getBody();

            storage.getHashTree().loadHash();

            request = new Message();
            request.setVerb(Verb.APPLY_DIFF);
            request.setId(Message.nextId());

            Difference difference = storage.getHashTree().difference(tree);
            request.setBody(difference);

            future = componentManager.getMessageService().send(request, remoteServer.serverId);

            reply = future.get(config.getRpcTimeout(), TimeUnit.MILLISECONDS);

            byte[] body = (byte[]) reply.getBody();

            return body[0] == 0;
        }
    }
}
