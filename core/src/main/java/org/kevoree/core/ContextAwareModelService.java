package org.kevoree.core;

import org.kevoree.ContainerRoot;
import org.kevoree.api.handler.ModelListener;
import org.kevoree.api.handler.UpdateCallback;

/**
 *
 * Created by duke on 6/2/14.
 */


public interface ContextAwareModelService {

    ContainerRoot getCurrentModel();

    ContainerRoot getPendingModel();

    String getNodeName();

    void registerModelListener(ModelListener listener, String callerPath);

    void unregisterModelListener(ModelListener listener, String callerPath);

    void update(ContainerRoot model, UpdateCallback callback, String callerPath);

    void submitScript(String script, UpdateCallback callback, String callerPath);
}