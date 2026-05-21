package com.brave.adblock;

public class AdBlockClient {
    public long nativeThis = 0;

    public enum FilterOption {
        UNKNOWN(0),
        SCRIPT(1),
        IMAGE(2),
        CSS(4),
        OBJECT(10),
        XHR(16),
        SUBDOCUMENT(64),
        DOCUMENT(128),
        OTHER(256);

        public final int value;

        FilterOption(int i) {
            this.value = i;
        }
    }

    static {
        System.loadLibrary("ad-block");
    }

    public AdBlockClient() {
        init();
    }

    public native void deinit();
    public native boolean deserialize(String str);
    public native void init();
    public native boolean matches(String str, int i, String str2);
    public native boolean parse(String str);
    public native boolean parseFile(String str);
    public native boolean serialize(String str);

    public boolean matches(String str, FilterOption filterOption, String str2) {
        return matches(str, filterOption.value, str2);
    }

    @Override
    protected void finalize() throws Throwable {
        deinit();
        super.finalize();
    }
}