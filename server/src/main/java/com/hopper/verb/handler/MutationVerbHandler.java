package com.hopper.verb.handler;

import com.hopper.GlobalConfiguration;
import com.hopper.quorum.NoQuorumException;
import com.hopper.server.*;
import com.hopper.session.Message;
import com.hopper.session.MessageService;
import com.hopper.stage.Stage;
import com.hopper.storage.NotMatchOwnerException;
import com.hopper.storage.NotMatchStatusException;
import com.hopper.storage.StateNode;
import com.hopper.storage.StateStorage;
import com.hopper.thrift.ChannelBound;
import com.hopper.verb.Verb;
import com.hopper.verb.VerbHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * MutationVerbHandler processes state related operations
 */
public class MutationVerbHandler implements VerbHandler {
    /**
     * Logger
     */
    private static Logger logger = LoggerFactory.getLogger(MutationVerbHandler.class);

    private final ComponentManager componentManager = ComponentManagerFactory.getComponentManager();

    private final GlobalConfiguration config = componentManager.getGlobalConfiguration();

    private final Server server = componentManager.getDefaultServer();

    private final StateStorage storage = componentManager.getStateStorage();

    @Override
    public void doVerb(Message message) {

        Mutation mutation = (Mutation) message.getBody();

        if (mutation.getOp() == Mutation.OP.CREATE) {
            Mutation.Create create = (Mutation.Create) mutation.getEntity();
            create(create);
        } else if (mutation.getOp() == Mutation.OP.UPDATE_STATUS) {
            Mutation.UpdateStatus us = (Mutation.UpdateStatus) mutation.getEntity();
            updateStatus(us);
        } else if (mutation.getOp() == Mutation.OP.UPDATE_LEASE) {
            Mutation.UpdateLease ul = (Mutation.UpdateLease) mutation.getEntity();
            updateLease(ul);
        } else if (mutation.getOp() == Mutation.OP.WATCH) {
            Mutation.Watch watch = (Mutation.Watch) mutation.getEntity();
            watch(watch);
        }
    }

    private void create(Mutation.Create create) {
        MutationReply reply = new MutationReply();
        try {
            create(create.key, create.owner, create.initStatus, create.invalidateStatus);
            // reply mutation request only the operation success
            replyMutation(MutationReply.SUCCESS);
        } catch (NoQuorumException e) {
            logger.warn("No quorum nodes are alive, drops the create request.");
        } catch (ServiceUnavailableException e) {
            logger.warn("The server is unavailable, drops the create request.");
        }
    }

    /**
     * Create a state with initial value, if the key is existed, return with success
     */
    public void create(String key, String owner, int initStatus, int invalidateStatus) throws
            ServiceUnavailableException, NoQuorumException {
        // check server state
        server.assertServiceAvailable();

        // Local modification first
        StateNode node = newStateNode(key, initStatus, invalidateStatus, componentManager.getLeaderElection().getPaxos().getEpoch());

        storage.put(node);

        if (server.isLeader()) {
            Mutation mutation = new Mutation();
            mutation.addCreate(key, owner, initStatus, invalidateStatus);

            // Synchronizes the modification to majority nodes
            synchronizeMutationToQuorum(mutation);
        }
    }

    private void updateStatus(Mutation.UpdateStatus us) {
        try {
            updateStatus(us.key, us.expectStatus, us.newStatus, us.owner, us.lease);
            replyMutation(MutationReply.SUCCESS);
        } catch (NoQuorumException e) {
            logger.warn("No quorum nodes are alive, drops the create request.");
        } catch (ServiceUnavailableException e) {
            logger.warn("The server is unavailable, drops the create request.");
        } catch (NotMatchStatusException e) {
            replyMutation(MutationReply.STATUS_CAS);
        } catch (NotMatchOwnerException e) {
            replyMutation(MutationReply.OWNER_CAS);
        }
    }

    /**
     * Update the status bound with key with CAS condition
     */
    public void updateStatus(String key, int expectStatus, int newStatus, String owner,
                             int lease) throws ServiceUnavailableException, NotMatchStatusException,
            NotMatchOwnerException {

        // check server state
        server.assertServiceAvailable();

        StateNode node = getAndCreateNode(key);
        node.setStatus(expectStatus, newStatus, owner, lease);

        // Synchronizes the modification to majority nodes
        if (server.isLeader()) {
            Mutation mutation = new Mutation();
            mutation.addUpdateStatus(key, expectStatus, newStatus, owner, lease);

            synchronizeMutationToQuorum(mutation);
        }
    }

    private void updateLease(Mutation.UpdateLease ul) {

        if (storage.get(ul.key) == null) {
            replyMutation(MutationReply.NODE_MISSING);
            return;
        }

        try {
            updateLease(ul.key, ul.expectStatus, ul.owner, ul.lease);
            replyMutation(MutationReply.SUCCESS);
        } catch (ServiceUnavailableException e) {
            logger.warn("No quorum nodes are alive, drops the updateLease request.");
        } catch (NoQuorumException e) {
            logger.warn("The server is unavailable, drops the updateLease request.");
        } catch (NotMatchStatusException e) {
            replyMutation(MutationReply.STATUS_CAS);
        } catch (NotMatchOwnerException e) {
            replyMutation(MutationReply.OWNER_CAS);
        }
    }

    /**
     * Update the lease property bound with key with CAS condition
     */
    public void updateLease(String key, int expectStatus, String owner,
                            int lease) throws ServiceUnavailableException, NotMatchStatusException,
            NotMatchOwnerException {
        // check server state
        server.assertServiceAvailable();

        StateNode node = getAndCreateNode(key);
        node.expandLease(expectStatus, owner, lease);

        // Synchronizes the modification to majority nodes
        if (server.isLeader()) {
            Mutation mutation = new Mutation();
            mutation.addUpdateLease(key, expectStatus, owner, lease);

            synchronizeMutationToQuorum(mutation);
        }
    }

    private void watch(Mutation.Watch watch) {
        if (storage.get(watch.key) == null) {
            replyMutation(MutationReply.NODE_MISSING);
            return;
        }

        try {
            watch(watch.sessionId, watch.key, watch.expectStatus);
            replyMutation(MutationReply.SUCCESS);
        } catch (ServiceUnavailableException e) {
            logger.warn("No quorum nodes are alive, drops the updateLease request.");
        } catch (NoQuorumException e) {
            logger.warn("The server is unavailable, drops the updateLease request.");
        } catch (NotMatchStatusException e) {
            replyMutation(MutationReply.STATUS_CAS);
        }
    }

    /**
     * Watch the special status(add a listener)
     */
    public void watch(String sessionId, String key, int expectStatus) {
        // check server state
        server.assertServiceAvailable();

        StateNode node = getAndCreateNode(key);
        node.watch(sessionId, expectStatus);

        // Synchronizes the modification to majority nodes
        if (server.isLeader()) {
            Mutation mutation = new Mutation();
            mutation.addWatch(sessionId, key, expectStatus);

            synchronizeMutationToQuorum(mutation);
        }
    }

    private StateNode getAndCreateNode(String key) {
        StateNode node = storage.get(key);

        if (node == null) {
            synchronized (key) {
                node = storage.get(key);
                if (node == null) {
                    node = newStateNode(key, StateNode.DEFAULT_STATUS, StateNode.DEFAULT_INVALIDATE_STATUS,
                            componentManager.getLeaderElection()
                                    .getPaxos().getEpoch());
                    node.setScheduleManager(componentManager.getScheduleManager());
                    ExecutorService notifyExecutorService = componentManager.getStageManager().getThreadPool(Stage.STATE_CHANGE);
                    node.setNotifyExecutorService(notifyExecutorService);
                    storage.put(node);
                }
            }
        }

        return node;
    }

    private void synchronizeMutationToQuorum(Mutation mutation) {
        Message message = new Message();
        message.setVerb(Verb.MUTATION);
        message.setBody(mutation);

        List<Message> replies = componentManager.getMessageService().sendMessageToQuorum(message,
                MessageService.WAITING_MODE_QUORUM);

        // Failed to synchronize the modification to quorum
        if (replies.size() < config.getQuorumSize() - 1) {
            throw new NoQuorumException();
        }
    }

    /**
     * Reply the mutation result to sender
     */
    private void replyMutation(int replyStatus) {
        MutationReply reply = new MutationReply();
        reply.setStatus(replyStatus);
        Message response = new Message();
        response.setVerb(Verb.REPLY_MUTATION);
        response.setBody(reply);

        // send response
        Endpoint endpoint = config.getEndpoint(ChannelBound.get().getRemoteAddress());
        componentManager.getMessageService().sendOneway(response, endpoint.serverId);
    }

    private StateNode newStateNode(String key, int initialStatus, int invalidateStatus, long initialVersion) {
        StateNode node = new StateNode(key, StateNode.TYPE_TEMP, initialStatus, invalidateStatus, initialVersion);
        node.setScheduleManager(componentManager.getScheduleManager());
        ExecutorService notifyExecutorService = componentManager.getStageManager().getThreadPool(Stage.STATE_CHANGE);
        node.setNotifyExecutorService(notifyExecutorService);

        return node;
    }
}
