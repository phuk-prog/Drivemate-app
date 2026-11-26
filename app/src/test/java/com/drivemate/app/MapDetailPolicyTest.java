package com.drivemate.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class MapDetailPolicyTest {
 @Test public void measuredHeightWinsAndUnknownIsEstimate(){assertEquals(15,MapDetailPolicy.heightMetres("15 m","2"),0);assertEquals(9,MapDetailPolicy.heightMetres("","3"),0);assertEquals(6,MapDetailPolicy.heightMetres("unknown",""),0);assertEquals(-1,MapDetailPolicy.explicitMetres("NaN"),0);assertEquals(-1,MapDetailPolicy.explicitMetres("-5"),0);}
}
