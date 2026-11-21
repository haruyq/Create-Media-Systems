package org.haruyq.createmediasystems.items.instrument.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.haruyq.createmediasystems.items.instrument.client.InstrumentAnimation.Control.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.haruyq.createmediasystems.items.instrument.InstrumentKind;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

final class InstrumentModelTest {
    private static final long START = 1_000_000_000L;
    private static final long SETTLED = START + 220_000_000;
    private static final float EPSILON = .000003f;

    private static InstrumentModel.Geometry geometry(InstrumentKind kind) throws Exception {
        String path = "/assets/create_media_systems/models/item/instruments/" + kind.model + ".mesh.json";
        try (var input = InstrumentModelTest.class.getResourceAsStream(path)) {
            assertNotNull(input,path);
            return InstrumentModel.read(new InputStreamReader(input,StandardCharsets.UTF_8));
        }
    }

    @Test void guitarPlucksAndFretsOneStringWhileItsNutAndPressedFretStayAnchored() throws Exception {
        var data = geometry(InstrumentKind.STRATOCASTER);
        var rig = data.rig();
        assertEquals(.6477f,rig.scale(),EPSILON);
        assertEquals(.04191f,rig.nut_width(),EPSILON);
        assertEquals(.2413f,rig.radius(),EPSILON);
        assertEquals(22,data.parts().stream().filter(p->p.name().startsWith("fret_")).count());
        assertEquals(.32385f,GuitarStrings.fretX(rig,12),EPSILON);
        var guitar = new InstrumentAnimation(InstrumentKind.STRATOCASTER);
        guitar.fret(2,12,.75f,START);
        guitar.pluck(2,1,SETTLED);
        var frame = guitar.sample(SETTLED);
        var line = GuitarStrings.path(rig,2,frame);
        assertEquals(InstrumentModel.point(rig.strings().get(2).nut()),line[1]);
        assertEquals(GuitarStrings.fretX(rig,12),line[2].x,EPSILON);
        assertTrue(line[2].y>0.010f,"The bend moves the fretted contact sideways");
        assertTrue(line[2].z<line[1].z,"The string reaches the fret crown");
        assertEquals(InstrumentModel.point(rig.strings().get(2).saddle()),line[line.length-1]);
        for (int i = 0; i < 6; i++) if (i != 2) {
            assertArrayEquals(GuitarStrings.path(rig,i,InstrumentAnimation.rest()),GuitarStrings.path(rig,i,frame));
        }
        var later = GuitarStrings.path(rig,2,guitar.sample(SETTLED+20_000_000));
        assertEquals(line[2],later[2],"Vibration cannot move the fret contact");
        assertNotEquals(line[7],later[7],"The speaking part of the string oscillates");
        guitar.fret(2,0,0,SETTLED+30_000_000);
        assertArrayEquals(GuitarStrings.path(rig,2,InstrumentAnimation.rest()),
                GuitarStrings.path(rig,2,guitar.sample(SETTLED+2_000_000_000L)));
    }

    @Test void tremoloMovesEachSaddleWithTheBridgeAndStrummingCrossesTheStringsInOrder() throws Exception {
        var rig = geometry(InstrumentKind.STRATOCASTER).rig();
        var guitar = new InstrumentAnimation(InstrumentKind.STRATOCASTER);
        guitar.set(TREMOLO,1,START);
        guitar.strum(true,.8f,SETTLED);
        var frame = guitar.sample(SETTLED+3_000_000);
        assertNotEquals(0,frame.vibrations()[0]);
        assertEquals(0,frame.vibrations()[1]);
        for (int i = 0; i < 6; i++) {
            var points = GuitarStrings.path(rig,i,frame);
            var pivot = InstrumentModel.point(rig.bridge_pivot());
            var expected = InstrumentModel.point(rig.strings().get(i).saddle()).sub(pivot)
                    .rotateY((float)Math.toRadians(12)).add(pivot);
            assertEquals(0,expected.distance(points[points.length-1]),EPSILON);
        }
        assertTrue(guitar.sample(SETTLED+100_000_000).stroke()<0);
    }

    @Test void fluteUsesTheSplitELinkageAndSeparateLowCAndCSharpPads() throws Exception {
        var model = geometry(InstrumentKind.FLUTE);
        var flute = new InstrumentAnimation(InstrumentKind.FLUTE);
        var chart = model.rig().fingerings();
        assertEquals(37,chart.size());
        assertTrue(flute.writtenNote(chart,88,START));
        var highE = flute.sample(SETTLED);
        assertEquals(0,highE.control(LEFT_RING),"The primary G key stays lifted for E6");
        assertEquals(1,highE.control(LOWER_G),"E mechanism closes the lower G cup");
        assertEquals(1,highE.control(RIGHT_MIDDLE));
        assertTrue(flute.writtenNote(chart,61,SETTLED));
        var cSharp = flute.sample(SETTLED+100_000_000);
        assertEquals(1,cSharp.control(CS_CUP));
        assertEquals(0,cSharp.control(LOW_C));
        assertTrue(flute.writtenNote(chart,60,SETTLED+110_000_000));
        var lowC = flute.sample(SETTLED+220_000_000);
        assertEquals(1,lowC.control(CS_CUP));
        assertEquals(1,lowC.control(LOW_C));
        assertFalse(flute.writtenNote(chart,59,SETTLED+220_000_000));
        flute.releaseFingering(SETTLED+220_000_000);
        assertEquals(0,flute.sample(SETTLED+330_000_000).control(CS_CUP));
    }

    @Test void trumpetWrittenNotesDriveTheCorrectIndependentValveStrokes() throws Exception {
        var model = geometry(InstrumentKind.TRUMPET);
        assertEquals(.123f,model.rig().bell(),EPSILON);
        assertEquals(.01165f,model.rig().bore(),EPSILON);
        assertEquals(.01646f,model.rig().mouth_rim(),EPSILON);
        assertEquals(31,model.rig().fingerings().size());
        var trumpet = new InstrumentAnimation(InstrumentKind.TRUMPET);
        assertTrue(trumpet.writtenNote(model.rig().fingerings(),62,START));
        var frame = trumpet.sample(SETTLED);
        assertEquals(1,frame.control(VALVE_1));
        assertEquals(0,frame.control(VALVE_2));
        assertEquals(1,frame.control(VALVE_3));
        for (var part : model.parts()) if (part.name().startsWith("piston_")) {
            assertEquals(-.0131f,part.motion().translation()[1],EPSILON);
            assertEquals(0,part.motion().translation()[0]);
        }
        assertTrue(trumpet.writtenNote(model.rig().fingerings(),72,SETTLED));
        frame = trumpet.sample(SETTLED+100_000_000);
        assertEquals(0,frame.control(VALVE_1));
        assertEquals(0,frame.control(VALVE_2));
        assertEquals(0,frame.control(VALVE_3));
    }

    @Test void windsContactLipsAndArmSegmentsKeepTheirLengths() throws Exception {
        for (var kind : new InstrumentKind[]{InstrumentKind.FLUTE, InstrumentKind.TRUMPET}) {
            var rig = geometry(kind).rig();
            var anim = new InstrumentAnimation(kind);
            anim.engage(true,START);
            var mounted = InstrumentPlayerPose.create(kind,rig,anim.sample(SETTLED),null,null);
            assertEquals(0,new Vector3f(0,-.095f,-.2535f).distance(
                    mounted.at(kind==InstrumentKind.FLUTE ? rig.embouchure() : rig.mouth())),.001f);
            for (int side : new int[]{-1,1}) {
                var shoulder = new Vector3f(side*.375f,.0625f,0);
                var target = side>0 ? mounted.leftGrip() : mounted.rightGrip();
                var pose = InstrumentPlayerArms.solve(shoulder,target,new Vector3f(side,1,0));
                assertEquals(0,pose.hand().distance(target),EPSILON);
                assertEquals(.354f,pose.shoulder().distance(pose.elbow()),EPSILON);
                assertEquals(.369f,pose.hand().distance(pose.elbow()),EPSILON);
            }
        }
    }

    @Test void theTrumpetGrowsAboutItsMouthpieceAndTheBellReadsFromThePlayersEye() throws Exception {
        var model = geometry(InstrumentKind.TRUMPET);
        var rig = model.rig();
        var anim = new InstrumentAnimation(InstrumentKind.TRUMPET);
        anim.engage(true, START);
        var eye = new Vector3f(0, -.19f, 0);
        var firstPerson = InstrumentPlayerPose.create(InstrumentKind.TRUMPET, rig, anim.sample(SETTLED), null, null, eye);
        var lips = new Vector3f(eye).add(0, .093f, -.027f);
        assertEquals(0, lips.distance(firstPerson.at(rig.mouth())), EPSILON);
        var thirdPerson = InstrumentPlayerPose.create(InstrumentKind.TRUMPET, rig, anim.sample(SETTLED), null, null);
        assertEquals(.5481f * 1.4f, thirdPerson.at(rig.mouth()).distance(thirdPerson.at(new float[]{rig.mouth()[0], 0, .5481f})), EPSILON);
        var rim = model.parts().stream().filter(part -> part.name().equals("rolled_bell_bead")).findFirst().orElseThrow();
        double left = Double.POSITIVE_INFINITY, right = Double.NEGATIVE_INFINITY;
        for (float[] quad : rim.quads()) {
            for (int i = 3; i < 23; i += 5) {
                var point = firstPerson.at(new float[]{quad[i], quad[i + 1], quad[i + 2]}).sub(eye);
                double x = point.x / -point.z;
                left = Math.min(left, x);
                right = Math.max(right, x);
            }
        }
        assertTrue(right - left > .22, "The rim takes up too little of the player's field of view");
    }

    @Test void guitarHangsAcrossTheTorsoWithBothPlayingHandsInReach() throws Exception {
        var rig = geometry(InstrumentKind.STRATOCASTER).rig();
        for (int fret : new int[]{1, 12, 22}) {
            var guitar = new InstrumentAnimation(InstrumentKind.STRATOCASTER);
            guitar.engage(true, START);
            guitar.fret(0, fret, 0, START);
            guitar.strum(true, 1, SETTLED);
            for (long time : new long[]{START, SETTLED, SETTLED + 100_000_000}) {
                var mount = InstrumentPlayerPose.create(InstrumentKind.STRATOCASTER, rig, guitar.sample(time), null, null);
                var bridge = mount.at(rig.strings().getFirst().saddle());
                var neck = mount.at(rig.strings().getFirst().nut()).sub(bridge);
                assertTrue(neck.x > 0 && neck.y < 0 && neck.z < 0, "Neck extends up and forward to the player's left");
                var face = mount.transform().transformDirection(new Vector3f(0, 0, 1));
                assertTrue(face.z < face.y && face.y < -.25f, "Strings face predominantly outwards, tipped up towards the player");
                for (int side : new int[]{-1, 1}) {
                    var target = side > 0 ? mount.leftGrip() : mount.rightGrip();
                    var shoulder = new Vector3f(side * .375f, .9f / 16, 0);
                    var arm = InstrumentPlayerArms.solve(shoulder, target, new Vector3f(side, 1, 0));
                    assertEquals(0, arm.hand().distance(target), EPSILON, "Hand reaches the guitar at fret " + fret);
                }
            }
        }
    }

    @Test void aQuickReleaseStartsFromTheCurrentPose() {
        var state = new InstrumentAnimation(InstrumentKind.FLUTE);
        state.engage(true,START);
        long switchAt = START+35_000_000;
        float first = state.sample(switchAt).playing();
        state.engage(false,switchAt);
        assertEquals(first,state.sample(switchAt).playing(),EPSILON);
        assertEquals(0,state.sample(SETTLED).playing());
    }
}
