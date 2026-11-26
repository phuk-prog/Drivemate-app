package com.drivemate.app;
/** Separate optional data consent; a key alone never enables an enhancement. */
final class NativeEnhancementPolicy {
    private NativeEnhancementPolicy(){}
    static boolean enabled(boolean consent,String key){
        return consent&&key!=null&&!key.trim().isEmpty()&&key.length()<=256;
    }
}
