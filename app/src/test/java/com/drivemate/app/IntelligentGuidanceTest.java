package com.drivemate.app;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
public class IntelligentGuidanceTest {
    private TomTomApi.Instruction step(String msg,String maneuver,String junction,int exit,String road){
        return new TomTomApi.Instruction(100,msg,maneuver,junction,exit,0,road,"");
    }
    @Test public void roundaboutWordingMatchesExplicitExit(){
        TomTomApi.Instruction s=step("Exit roundabout","ROUNDABOUT","ROUNDABOUT",2,"A61");
        assertEquals("Take 2nd exit",IntelligentGuidance.action(s,"↻",2));
        assertEquals("At the roundabout, take the 2nd exit onto A61",IntelligentGuidance.direction(s,"↻",2));
        assertFalse(IntelligentGuidance.direction(s,"↻",0).contains("2nd"));
    }
    @Test public void actionAndRoadSeparateWithoutLosingExit(){
        TomTomApi.Instruction s=step("Take exit 33 onto A630","EXIT_LEFT","",0,"A630");
        String a=IntelligentGuidance.action(s,"↰",0);
        assertEquals("Take exit 33",a);assertEquals("A630",IntelligentGuidance.roadLine(a,"A630"));
    }
    @Test public void structuredRoadAndHighwayExit(){
        TomTomApi.Instruction s=new TomTomApi.Instruction(100,"Take exit","MOTORWAY_EXIT_LEFT","REGULAR",0,-10,"Sheffield Road","Sheffield","33","A630","LEFT");
        assertEquals("Take left exit 33",IntelligentGuidance.action(s,"↖",0));
        assertEquals("A630 · Sheffield Road",IntelligentGuidance.roadLine("Take left exit 33",s));
        assertEquals("↖",GuidanceLogic.symbol(s.maneuver,s.junctionType,s.turnAngleDegrees));
        assertEquals("Take left exit 33 onto A630 · Sheffield Road towards Sheffield",IntelligentGuidance.direction(s,"↖",0));
    }
    @Test public void explicitRoundaboutDirectionDoesNotInventNumber(){
        TomTomApi.Instruction s=step("Go left","ROUNDABOUT_LEFT","ROUNDABOUT",0,"A61");
        assertEquals(-1,IntelligentGuidance.roundaboutDirection(s));
        assertEquals("Take exit on the left",IntelligentGuidance.action(s,"↻",0));
        assertFalse(IntelligentGuidance.traceAgrees(new float[]{0,1,1,1,10,1},-1));
        assertTrue(IntelligentGuidance.traceAgrees(new float[]{0,1,1,1,10,1},1));
        assertFalse(IntelligentGuidance.traceAgrees(null,1));
    }
    @Test public void trustedLanePromptAndNoGuess(){
        RouteIntelligence.LaneSection section=new RouteIntelligence.LaneSection(0,100,Arrays.asList(
            new RouteIntelligence.Lane("LEFT",true),new RouteIntelligence.Lane("STRAIGHT",false)));
        assertEquals("Use the left lane",IntelligentGuidance.lanePhrase(section));
        assertEquals("",IntelligentGuidance.lanePhrase(null));
        assertEquals("",IntelligentGuidance.lanePhrase(new RouteIntelligence.LaneSection(0,100,Arrays.asList(new RouteIntelligence.Lane("LEFT",true)))));
    }
    @Test public void noUnverifiedTraceAndAdaptivePreview(){
        assertNull(IntelligentGuidance.roundaboutTrace(null,100,0,200));
        assertFalse(IntelligentGuidance.closeFollowing(300,5));
        assertTrue(IntelligentGuidance.closeFollowing(300,30));
        assertEquals("Take M 1 then A 6 1",IntelligentGuidance.pronounce("Take M1 then A61"));
        assertTrue(DrivingDisplayPolicy.signpostHorizon(30)>DrivingDisplayPolicy.signpostHorizon(5));
    }
}
