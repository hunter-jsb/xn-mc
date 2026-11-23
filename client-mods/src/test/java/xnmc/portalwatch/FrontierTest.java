package xnmc.portalwatch;

import java.util.*;
import xnmc.portalwatch.FrontierPlanner.Chunk;
import xnmc.portalwatch.FrontierPlanner.Kind;

public final class FrontierTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        Map<Chunk, Kind> map = new HashMap<>();
        var here = new Chunk(10, 0);
        check(FrontierPlanner.next(map, here, Set.of()).target() == null, "No classification means stop");
        map.put(here, Kind.FRESH);
        map.put(here.offset(1, 0), Kind.UNKNOWN);
        check(FrontierPlanner.next(map, here, Set.of()).target() == null, "Fresh and unknown do not count as a trail");
        for (int sign : new int[]{-1, 1}) {
            map.clear();
            var start = new Chunk(10 * sign, 0);
            for (int x = 0; x < 10; x++) {
                map.put(new Chunk((10+x)*sign,0), Kind.GENERATED);
                map.put(new Chunk((10+x)*sign,1), Kind.FRESH);
            }
            var step = FrontierPlanner.next(map, start, Set.of());
            check(step.target().equals(new Chunk(12*sign,0)), "Follow outward on the generated side, including negative coordinates");
            check(map.get(step.target()) == Kind.GENERATED && step.reason().contains("fresh"), "Fresh-boundary evidence is explicit");
        }
        map.clear();
        map.put(here, Kind.GENERATED);
        map.put(here.offset(1,0), Kind.GENERATED);
        map.put(here.offset(1,1), Kind.UNKNOWN);
        var weak = FrontierPlanner.next(map,here,Set.of());
        check(weak.target().equals(here.offset(1,0)) && weak.reason().contains("weak"), "Unknown boundary is explicitly weaker evidence");
        check(FrontierPlanner.next(map,here,Set.of(here.offset(1,0))).target() == null, "Visited edge doesn't circle");
        map.clear();
        map.put(here, Kind.GENERATED);
        map.put(here.offset(1,0), Kind.GENERATED);
        check(FrontierPlanner.next(map,here,Set.of()).target() == null, "Missing query cells aren't boundary evidence");
        map.put(here.offset(0,2), Kind.GENERATED);
        map.put(here.offset(0,3), Kind.FRESH);
        check(FrontierPlanner.next(map,here,Set.of()).target() == null, "Don't jump to a disconnected island");
        map.clear();
        for (int x = 8; x <= 13; x++) for (int z = -3; z <= 3; z++) map.put(new Chunk(x,z), Kind.GENERATED);
        check(FrontierPlanner.next(map,here,Set.of()).target().equals(new Chunk(12,0)), "Uniform old terrain seeks outward only through known cells");
        borderChecks();
        progressChecks();
        searchPolicyChecks();
        savedSettingsChecks();
        trailSimulation();
        exhaustedTrailSimulation();
        System.out.println(checks + " frontier decisions passed");
    }

    private static void exhaustedTrailSimulation() {
        // Missing server-sent chunks can leave coverage holes indefinitely.
        // Replanning an inset route must not bounce between completed short legs.
        for (int inset=1; inset<=5; inset++) {
            Map<Chunk,Kind> map=new HashMap<>();
            for (int x=995;x<=1005;x++) for (int z=-1;z<=8;z++)
                map.put(new Chunk(x,z),z<0 ? Kind.FRESH : Kind.GENERATED);
            Chunk here=new Chunk(1000,0);
            Set<Chunk> visited=new HashSet<>();visited.add(here);
            boolean stopped=false;
            for (int leg=0;leg<=99;leg++) {
                var step=FrontierPlanner.next(map,here,visited,FrontierPlanner.Bounds.UNBOUNDED,
                        ExploreSettings.Bias.INWARD,inset,7,Set.of());
                if (step.target()==null) { stopped=true;break; }
                check(!visited.contains(step.target()),"Incomplete coverage never sends a completed leg again");
                check(map.get(step.target())==Kind.GENERATED,"Alternative route stays on generated terrain");
                here=step.target();visited.add(here);
            }
            check(stopped,"Exhausted finite trail stops instead of ping-ponging indefinitely");
        }
    }

    private static void trailSimulation() {
        for(var bias:new ExploreSettings.Bias[]{ExploreSettings.Bias.INWARD,ExploreSettings.Bias.OUTWARD}) {
            Chunk here=new Chunk(1000,0);
            Set<Chunk> scanned=new HashSet<>(),visited=new HashSet<>();
            for(int tick=0;tick<20;tick++) {
                // Model actual loaded/scanned chunks separately from the larger classification query.
                for(int x=here.x()-7;x<=here.x()+7;x++)for(int z=here.z()-7;z<=here.z()+7;z++)scanned.add(new Chunk(x,z));
                Map<Chunk,Kind> sample=new HashMap<>();
                for(int x=here.x()-15;x<=here.x()+15;x++)for(int z=here.z()-15;z<=here.z()+15;z++)
                    sample.put(new Chunk(x,z),z<0 ? Kind.FRESH : Kind.GENERATED);
                var step=FrontierPlanner.next(sample,here,visited,FrontierPlanner.Bounds.UNBOUNDED,bias,5,7,scanned);
                check(step.target()!=null,"Long trail continues despite the currently loaded area already being scanned");
                check(!visited.contains(step.target()),"Repeated planning doesn't oscillate through the same waypoints");
                here=step.target();visited.add(here);
            }
            check(bias==ExploreSettings.Bias.INWARD ? here.x()<990 : here.x()>1010,"Repeated inset route advances in selected direction");
            check(here.z()>=4 && here.z()<=5,"Route keeps the edge visible while seeing more trail interior");
        }
    }

    private static void searchPolicyChecks() {
        Map<Chunk,Kind> map=new HashMap<>();
        Chunk here=new Chunk(1000,0);
        for(int x=985;x<=1015;x++) {
            map.put(new Chunk(x,0),Kind.GENERATED);
            map.put(new Chunk(x,1),Kind.FRESH);
        }
        var outward=FrontierPlanner.next(map,here,Set.of(),FrontierPlanner.Bounds.UNBOUNDED,ExploreSettings.Bias.OUTWARD,0,0,Set.of());
        var inward=FrontierPlanner.next(map,here,Set.of(),FrontierPlanner.Bounds.UNBOUNDED,ExploreSettings.Bias.INWARD,0,0,Set.of());
        check(outward.target().x()>here.x() && inward.target().x()<here.x(),"Direction changes route on the same trail");
        // Wide old terrain with a fresh strip on one side. Head inside before following the edge.
        map.clear();
        for(int x=985;x<=1020;x++) {
            map.put(new Chunk(x,-1),Kind.FRESH);
            for(int z=0;z<=8;z++)map.put(new Chunk(x,z),Kind.GENERATED);
        }
        var inset=FrontierPlanner.next(map,here,Set.of(),FrontierPlanner.Bounds.UNBOUNDED,ExploreSettings.Bias.OUTWARD,3,0,Set.of());
        check(inset.target()!=null && inset.target().z()>=2,"Interior-weighted route moves off edge before advancing along it");
        check(map.get(inset.target())==Kind.GENERATED,"Inset stays in connected generated terrain");
        map.clear();
        for(int x=985;x<=1015;x++) {
            map.put(new Chunk(x,0),Kind.GENERATED);map.put(new Chunk(x,1),Kind.FRESH);
        }
        var narrow=FrontierPlanner.next(map,here,Set.of(),FrontierPlanner.Bounds.UNBOUNDED,ExploreSettings.Bias.OUTWARD,5,0,Set.of());
        check(narrow.target()!=null && narrow.target().z()==0,"Narrow trails reduce the inset instead of crossing fresh terrain");
        Set<Chunk> scanned=new HashSet<>();
        for(int x=970;x<=1030;x++)for(int z=-20;z<=20;z++)scanned.add(new Chunk(x,z));
        check(FrontierPlanner.next(map,here,Set.of(),FrontierPlanner.Bounds.UNBOUNDED,ExploreSettings.Bias.OUTWARD,3,5,scanned).target()==null,
                "Previously scanned area is not selected again even in a new trip");
        check(!FrontierPlanner.revealsTerrain(new Chunk(11,0),4,scannedSquare(-13,11),new FrontierPlanner.Bounds(-200,-200,200,200)),
                "Unsearchable terrain beyond border doesn't count as new coverage");
        check(FrontierPlanner.revealsTerrain(new Chunk(0,0),3,Set.of(new Chunk(0,0)),FrontierPlanner.Bounds.UNBOUNDED),
                "A route with genuinely unscanned visible terrain remains eligible");
    }

    private static Set<Chunk> scannedSquare(int min,int max) {
        Set<Chunk> result=new HashSet<>();
        for(int x=min;x<=max;x++)for(int z=min;z<=max;z++)result.add(new Chunk(x,z));
        return result;
    }

    private static void savedSettingsChecks() {
        try {
            var dir=java.nio.file.Files.createTempDirectory("portalwatch-test-");
            var path=dir.resolve("coverage.csv");
            var history=new ExploreHistory(path);
            history.record(-3125,2700);history.record(-3125,2700);history.save();
            var loaded=new ExploreHistory(path);
            check(loaded.scanned.equals(Set.of(new Chunk(-3125,2700))),"Coverage persists across trips/restarts without duplicates");
            check(new ExploreHistory(dir.resolve("other-server.csv")).scanned.isEmpty(),"Separate world file does not inherit coverage");
            loaded.clear();check(new ExploreHistory(path).scanned.isEmpty(),"Explicit coverage reset persists");
            var settings=new ExploreSettings();settings.pattern=ExploreSettings.Pattern.TRAIL;settings.bias=ExploreSettings.Bias.INWARD;settings.inset=4;
            var config=dir.resolve("options.properties");settings.save(config);
            var restored=new ExploreSettings();restored.load(config);
            check(restored.bias==ExploreSettings.Bias.INWARD && restored.insetChunks(8)==4,"Saved direction and inset restore");
            check(restored.insetChunks(3)==2,"Inset never exceeds visible trail range");
            restored.inset=-1;check(restored.insetChunks(8)==6,"Automatic inset keeps two chunks of visibility beyond edge");
            try(var files=java.nio.file.Files.list(dir)){for(var file:files.toList())java.nio.file.Files.delete(file);}
            java.nio.file.Files.delete(dir);
        } catch(java.io.IOException e){throw new AssertionError(e);}
    }

    private static void borderChecks() {
        var bounds = new FrontierPlanner.Bounds(-200, -200, 200, 200);
        check(bounds.allows(new Chunk(11,11)), "Interior chunk centre is safe");
        check(!bounds.allows(new Chunk(12,11)), "Positive border clearance excludes partial edge chunk");
        check(!bounds.allows(new Chunk(-13,0)), "Negative border clearance excludes partial edge chunk");
        check(!bounds.contains(196,0) && !bounds.contains(0,196), "Four-block maximum clearance on both axes");
        check(bounds.contains(-196,-196), "Minimum-side clearance and negative coordinates");
        var shifted = new FrontierPlanner.Bounds(1000,2000,1200,2200);
        check(shifted.contains(1100,2100) && !shifted.contains(0,0), "Respect a non-origin border centre without dimension scaling");
        for (int axis = 0; axis < 2; axis++) for (int sign : new int[]{-1,1}) {
            Map<Chunk,Kind> map = new HashMap<>();
            int edge = sign > 0 ? 11 : -12;
            Chunk here = axis == 0 ? new Chunk(edge,0) : new Chunk(0,edge);
            for (int d = -6; d <= 6; d++) {
                Chunk c = axis == 0 ? new Chunk(edge+d,0) : new Chunk(0,edge+d);
                map.put(c,Kind.GENERATED);
                map.put(c.offset(axis == 0 ? 0 : 1, axis == 0 ? 1 : 0),Kind.FRESH);
            }
            var step = FrontierPlanner.next(map,here,Set.of(),bounds);
            check(step.target() != null && bounds.allows(step.target()), "Safe route on every border side");
            check(step.target().radius() < here.radius(), "Follow inward trail near every border instead of insisting on outward travel");
        }
        Map<Chunk,Kind> corner = new HashMap<>();
        var here = new Chunk(11,11);
        corner.put(here,Kind.GENERATED);
        corner.put(here.offset(1,0),Kind.GENERATED);
        corner.put(here.offset(0,1),Kind.UNKNOWN);
        var stopped = FrontierPlanner.next(corner,here,Set.of(),bounds);
        check(stopped.target() == null && stopped.reason().contains("world border"), "No target beyond a corner; out-of-border unknown cells are not frontier evidence");
        corner.put(here.offset(0,-2),Kind.GENERATED);
        corner.put(here.offset(0,-1),Kind.GENERATED);
        var along = FrontierPlanner.next(corner,here,Set.of(),bounds);
        check(along.target().equals(here.offset(0,-2)), "Uniform terrain can take a tangential two-chunk step");
        check(FrontierPlanner.next(corner,here,Set.of(here.offset(0,-1),here.offset(0,-2)),bounds).target()==null,
                "Stop rather than revisit exhausted border route");
        var shrunk = new FrontierPlanner.Bounds(-160,-160,160,160);
        check(!shrunk.allows(along.target()), "Active goal becomes invalid after border shrink");
    }

    private static void progressChecks() {
        var progress = new FrontierProgress();
        progress.reset(32,1000);
        check(!progress.stalled(32,30_999), "Allow path calculation and temporary stalls");
        check(progress.stalled(32,31_000), "Stop at thirty seconds with no progress");
        progress.reset(32,0);
        check(!progress.stalled(31,29_000), "Real progress resets timeout");
        check(!progress.stalled(31,58_999) && progress.stalled(31,59_000), "Timeout measured since last advance");
        progress.reset(32,0);
        check(!progress.stalled(31.8,29_000) && progress.stalled(31.8,30_000), "Small jitter does not keep unreachable goals alive");
        progress.reset(8,60_000);
        check(!progress.stalled(8,60_000), "New or resumed goal gets a fresh timeout");
    }
}
