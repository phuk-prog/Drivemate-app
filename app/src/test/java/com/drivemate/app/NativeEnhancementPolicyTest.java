package com.drivemate.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class NativeEnhancementPolicyTest {
    @Test public void keyAloneNeverAuthorizesEnhancement(){
        assertFalse(NativeEnhancementPolicy.enabled(false,"key"));
        assertFalse(NativeEnhancementPolicy.enabled(true,""));
        assertFalse(NativeEnhancementPolicy.enabled(true,null));
        assertTrue(NativeEnhancementPolicy.enabled(true,"key"));
    }
}
