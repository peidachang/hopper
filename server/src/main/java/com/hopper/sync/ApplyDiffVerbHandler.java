package com.hopper.sync;

import com.hopper.server.ComponentManager;
import com.hopper.server.ComponentManagerFactory;
import com.hopper.session.Message;
import com.hopper.storage.StateNode;
import com.hopper.util.merkle.Difference;
import com.hopper.verb.Verb;
import com.hopper.verb.VerbHandler;

/**
 * The handler for processing apply diff result
 */
public class ApplyDiffVerbHandler implements VerbHandler {
    private final ComponentManager componentManager = ComponentManagerFactory.getComponentManager();

    @Override
    public void doVerb(Message message) {
        Difference<StateNode> difference = (Difference<StateNode>) message.getBody();

        DiffResult result = new DiffResult();
        result.setMaxXid(componentManager.getStateStorage().getMaxXid());
        result.setDifference(difference);

        componentManager.getDataSyncService().applyDiff(result);

        Message reply = message.createResponse(Verb.APPLY_DIFF_RESULT);
        reply.setBody(new byte[]{0});

        componentManager.getMessageService().responseOneway(reply);
    }
}
