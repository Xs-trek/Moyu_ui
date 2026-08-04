package com.moyu.remote;

import android.app.Application;

public final class MoyuApplication extends Application {
    private MoyuDatabase database;
    private SecretStore secretStore;

    @Override public void onCreate() {
        super.onCreate();
        database = new MoyuDatabase(this);
        secretStore = new SecretStore(this);
        database.getWritableDatabase();
    }

    public MoyuDatabase database() { return database; }
    public SecretStore secrets() { return secretStore; }
}

