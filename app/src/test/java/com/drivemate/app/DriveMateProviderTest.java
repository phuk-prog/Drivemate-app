package com.drivemate.app;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class DriveMateProviderTest {
    @Test public void explicitOsmLimitNotGuess()throws Exception{
        assertEquals(32,DriveMateProvider.explicitLimit(new JSONObject("{\"maxspeed\":\"20 mph\"}")));
        assertEquals(48,DriveMateProvider.explicitLimit(new JSONObject("{\"maxspeed\":\"30 mph\"}")));
        assertEquals(0,DriveMateProvider.explicitLimit(new JSONObject("{\"maxspeed\":\"20 mph\",\"maxspeed:conditional\":\"30 mph @ (Mo-Fr)\"}")));
        assertEquals(0,DriveMateProvider.explicitLimit(new JSONObject("{\"maxspeed:forward\":\"20 mph\"}")));
        assertEquals(0,DriveMateProvider.explicitLimit(new JSONObject("{\"maxspeed\":\"signals\"}")));
    }
}
