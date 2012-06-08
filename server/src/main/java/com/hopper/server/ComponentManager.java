package com.hopper.server;

import com.hopper.GlobalConfiguration;
import com.hopper.cache.CacheManager;
import com.hopper.lifecycle.Lifecycle;
import com.hopper.lifecycle.LifecycleProxy;
import com.hopper.quorum.DefaultLeaderElection;
import com.hopper.quorum.LeaderElection;
import com.hopper.quorum.Paxos;
import com.hopper.session.ConnectionManager;
import com.hopper.session.MessageService;
import com.hopper.session.SessionManager;
import com.hopper.stage.StageManager;
import com.hopper.storage.StateStorage;
import com.hopper.storage.TreeStorage;
import com.hopper.storage.merkle.MapStorage;
import com.hopper.sync.DataSyncService;
import com.hopper.utils.ScheduleManager;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ComponentManager} manages all life cycle components
 */
public class ComponentManager extends LifecycleProxy {

    /**
     * All components
     */
    private final List<Lifecycle> components = new ArrayList<Lifecycle>();

    private GlobalConfiguration globalConfiguration;
    private CacheManager cacheManager;
    private StateStorage stateStorage;
    private Server server;
    private ScheduleManager scheduleManager;
    private SessionManager sessionManager;
    private DataSyncService dataSyncService;
    private StageManager stageManager;
    private LeaderElection leaderElection;
    private ConnectionManager connectionManager;
    private MessageService messageService;

    public void registerComponent(Lifecycle component) {
        components.add(component);
    }

    @Override
    protected void doInit() {
        this.globalConfiguration = createGlobalConfiguration();
        try {
            this.globalConfiguration.start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        this.cacheManager = createCacheManager();
        registerComponent(cacheManager);

        this.stateStorage = createStateStorage();
        registerComponent(stateStorage);

        this.server = createServer();
        registerComponent(server);

        this.scheduleManager = createScheduleManager();
        registerComponent(scheduleManager);

        this.sessionManager = createSessionManager();
        registerComponent(sessionManager);

        this.dataSyncService = createDataSyncService();
        registerComponent(dataSyncService);

        this.stageManager = createStageManager();
        registerComponent(stageManager);

        this.leaderElection = createLeaderElection();
        this.connectionManager = createConnectionManager();

        this.messageService = createMessageService();
    }

    @Override
    protected void doStart() {
        try {
            for (Lifecycle component : components) {
                component.start();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void doShutdown() {
        for (Lifecycle component : components) {
            component.shutdown();
        }
        this.globalConfiguration.shutdown();
    }

    public Server getDefaultServer() {
        return server;
    }

    private Server createServer() {
        DefaultServer server = new DefaultServer();
        server.setRpcEndpoint(globalConfiguration.getLocalRpcEndpoint());
        server.setServerEndpoint(globalConfiguration.getLocalServerEndpoint());

        Paxos paxos = new Paxos();
        server.setPaxos(paxos);
        return server;
    }

    public StateStorage getStateStorage() {
        return stateStorage;
    }

    private StateStorage createStateStorage() {
        GlobalConfiguration.StorageMode mode = globalConfiguration.getStorageMode();
        StateStorage storage = mode == GlobalConfiguration.StorageMode.TREE ? new TreeStorage() : new MapStorage();
        return storage;
    }

    public GlobalConfiguration getGlobalConfiguration() {
        return globalConfiguration;
    }

    private GlobalConfiguration createGlobalConfiguration() {
        return new GlobalConfiguration();
    }

    public ScheduleManager getScheduleManager() {
        return scheduleManager;
    }

    private ScheduleManager createScheduleManager() {
        ScheduleManager scheduleManager = new ScheduleManager();
        scheduleManager.setScheduleThreadCount(globalConfiguration.getScheduleThreadCount());
        return scheduleManager;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    private SessionManager createSessionManager() {
        return new SessionManager();
    }

    public LeaderElection getLeaderElection() {
        return leaderElection;
    }

    private LeaderElection createLeaderElection() {
        return new DefaultLeaderElection();
    }

    public CacheManager getCacheManager() {
        return cacheManager;
    }

    private CacheManager createCacheManager() {
        CacheManager cacheManager = new CacheManager();
        cacheManager.setScheduleManager(getScheduleManager());
        cacheManager.setEvictPeriod(globalConfiguration.getCacheEvictPeriod());

        return cacheManager;
    }

    public StageManager getStageManager() {
        return stageManager;
    }

    private StageManager createStageManager() {
        return new StageManager();
    }

    public DataSyncService getDataSyncService() {
        return dataSyncService;
    }

    private DataSyncService createDataSyncService() {
        return new DataSyncService();
    }

    public ConnectionManager getConnectionManager() {
        return connectionManager;
    }

    private ConnectionManager createConnectionManager() {
        return new ConnectionManager();
    }

    public MessageService getMessageService() {
        return messageService;
    }

    private MessageService createMessageService() {
        return new MessageService();
    }
}

