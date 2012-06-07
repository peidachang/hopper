package com.hopper.session;

import com.hopper.GlobalConfiguration;
import com.hopper.lifecycle.LifecycleListener;
import com.hopper.future.LatchFuture;

public abstract class SessionProxy implements Session {
    /**
     * {@link com.hopper.GlobalConfiguration} instance
     */
    protected static final GlobalConfiguration config = GlobalConfiguration.getInstance();
    /**
     * Singleton SessionManager instance
     */
    protected static SessionManager sessionManager = config.getSessionManager();

    /**
     * Associated connection
     */
    private Connection connection;

    /**
     * Unique session id
     */
    private String id;

    public void setId(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean isAlive() {
        return false;
    }

    @Override
    public void sendOneway(Message message) {
        if (connection == null) {
            throw new IllegalStateException("Not bound connection.");
        }

        connection.sendOneway(message);
    }

    @Override
    public void sendOnwayUntilComplete(Message message) {
        if (connection == null) {
            throw new IllegalStateException("Not bound connection.");
        }

        connection.sendOnwayUntilComplete(message);
    }

    @Override
    public LatchFuture<Message> send(Message message) {
        if (connection == null) {
            throw new IllegalStateException("Not bound connection.");
        }

        return connection.send(message);
    }

    @Override
    public void close() {
        if (connection != null) {
            connection.close();
        }
    }

    @Override
    public void setSessionManager(SessionManager manager) {
    }

    @Override
    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public void setConnection(Connection connection) {
        if (connection != null) {
            this.connection = connection;
            if (this instanceof LifecycleListener) {
                this.connection.addListener((LifecycleListener) this);
            }
        }
    }

    @Override
    public Connection getConnection() {
        return connection;
    }

}
