package com.moyu.remote;

import android.app.Application;

public final class MoyuApplication extends Application {
    private MoyuDatabase database;
    private SecretStore secretStore;
    private ArtifactCache artifactCache;

    @Override public void onCreate() {
        super.onCreate();
        database = new MoyuDatabase(this);
        secretStore = new SecretStore(this);
        artifactCache = new ArtifactCache(getCacheDir());
        database.getWritableDatabase();
    }

    public MoyuDatabase database() { return database; }
    public SecretStore secrets() { return secretStore; }
    public ArtifactCache artifacts() { return artifactCache; }
}
