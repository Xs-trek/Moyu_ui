package com.easytier.jni;

/** ABI from EasyTier/EasyTier v2.6.4 easytier-contrib/easytier-android-jni. */
public final class EasyTierJNI {
    static {
        System.loadLibrary("easytier_ffi");
        System.loadLibrary("easytier_android_jni");
    }
    private EasyTierJNI() { }
    public static native int parseConfig(String config);
    public static native int runNetworkInstance(String config);
    public static native int retainNetworkInstance(String[] names);
    public static native String collectNetworkInfos(int maxLength);
    public static native String getLastError();
    public static int stopAllInstances() { return retainNetworkInstance(null); }
}

