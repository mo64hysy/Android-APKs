package com.google.android.apps.inputmethod.libs.mozc.session;

/**
 * JNI bridge required by Mozc's Android native library.
 * The package and class name must remain exactly as declared here.
 */
public final class MozcJNI {
    private MozcJNI() {}

    public static native boolean initialize();
    public static native byte[] evalCommand(byte[] command);
    public static native boolean onPostLoad(String userProfileDirectoryPath, String dataFilePath);
    public static native String getDataVersion();
}
