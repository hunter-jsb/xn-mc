package xnmc.portalwatch;

import java.util.*;

/** Route decisions from classified chunks. Unknown is never treated as old or fresh. */
public final class FrontierPlanner {
    public enum Kind { UNKNOWN, FRESH, GENERATED }
    public record Chunk(int x, int z) {
        public double radius() { return Math.hypot(x, z); }
        public double distance(Chunk other) { return Math.hypot((double) x - other.x, (double) z - other.z); }
        public Chunk offset(int dx, int dz) { return new Chunk(x + dx, z + dz); }
    }
    public record Step(Chunk target, String reason) {}
    /** Bounds are in this dimension's block coordinates, never scaled from the Overworld. */
    public record Bounds(double minX, double minZ, double maxX, double maxZ) {
        public static final Bounds UNBOUNDED = new Bounds(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        private static final double CLEARANCE = 4;
        public boolean contains(double x, double z) {
            return x >= minX + CLEARANCE && x < maxX - CLEARANCE
                    && z >= minZ + CLEARANCE && z < maxZ - CLEARANCE;
        }
        public boolean allows(Chunk chunk) {
            // The movement goal is the centre of block (8,8), not the chunk's corner.
            return contains(chunk.x() * 16.0 + 8.5, chunk.z() * 16.0 + 8.5);
        }
        public double distance(Chunk chunk) {
            double x = chunk.x() * 16.0 + 8.5, z = chunk.z() * 16.0 + 8.5;
            return Math.min(Math.min(x - minX, maxX - x), Math.min(z - minZ, maxZ - z));
        }
    }
    private static final int[][] DIRS = {{1,0},{0,1},{-1,0},{0,-1}};

    public static Step next(Map<Chunk, Kind> map, Chunk here, Set<Chunk> visited) {
        return next(map, here, visited, Bounds.UNBOUNDED);
    }

    public static Step next(Map<Chunk, Kind> map, Chunk here, Set<Chunk> visited, Bounds bounds) {
        return next(map,here,visited,bounds,ExploreSettings.Bias.OUTWARD,0,0,Set.of());
    }

    static Step next(Map<Chunk, Kind> map, Chunk here, Set<Chunk> visited, Bounds bounds,
                     ExploreSettings.Bias bias, int inset, int viewDistance, Set<Chunk> scanned) {
        Map<Chunk, Kind> inside = new HashMap<>();
        map.forEach((chunk, kind) -> { if (bounds.allows(chunk)) inside.put(chunk, kind); });
        boolean clipped = inside.size() != map.size();
        boolean nearBorder = bounds.distance(here) <= Math.max(128,(viewDistance+2)*16);
        double direction = bias == ExploreSettings.Bias.INWARD ? -1 : bias == ExploreSettings.Bias.OUTWARD && !nearBorder ? 1 : 0;
        map = inside;
        List<Chunk> generated = map.entrySet().stream().filter(e -> e.getValue() == Kind.GENERATED).map(Map.Entry::getKey).toList();
        if (generated.isEmpty()) return noRoute("no previously-generated terrain classified nearby", clipped);
        // Stay on the connected, previously-generated side of a border rather than leap between unrelated islands.
        Chunk root = generated.stream().min(Comparator.comparingDouble(here::distance)).orElseThrow();
        Map<Chunk, Chunk> parent = new HashMap<>();
        Queue<Chunk> queue = new ArrayDeque<>(); queue.add(root); parent.put(root, root);
        while (!queue.isEmpty()) {
            Chunk c = queue.remove();
            for (int[] d : DIRS) {
                Chunk n = c.offset(d[0], d[1]);
                if (map.getOrDefault(n, Kind.UNKNOWN) == Kind.GENERATED && !parent.containsKey(n)) {
                    parent.put(n,c); queue.add(n);
                }
            }
        }
        List<Chunk> strong = new ArrayList<>(), frontier = new ArrayList<>();
        Set<Chunk> allEdges = new HashSet<>();
        for (Chunk c : parent.keySet()) {
            boolean fresh = false, unknown = false;
            for (int[] d : DIRS) {
                Chunk n = c.offset(d[0], d[1]);
                Kind k = map.getOrDefault(n, Kind.UNKNOWN);
                fresh |= k == Kind.FRESH;
                // The edge of our query window is not itself evidence of a boundary.
                unknown |= map.containsKey(n) && k == Kind.UNKNOWN;
            }
            if (fresh || unknown) allEdges.add(c);
            if ((viewDistance<=0 && visited.contains(c)) || c.equals(here) || direction * (c.radius()-here.radius()) < -1.5) continue;
            if (fresh) strong.add(c);
            else if (unknown) frontier.add(c);
        }
        // Distance into the generated component. A narrow trail naturally limits the inset.
        Map<Chunk,Integer> depth = new HashMap<>();
        Queue<Chunk> interiorQueue = new ArrayDeque<>();
        for (Chunk c : allEdges) { depth.put(c,0); interiorQueue.add(c); }
        while (!interiorQueue.isEmpty()) {
            Chunk c = interiorQueue.remove();
            for (int[] d : DIRS) {
                Chunk n = c.offset(d[0],d[1]);
                if (parent.containsKey(n) && !depth.containsKey(n)) { depth.put(n,depth.get(c)+1); interiorQueue.add(n); }
            }
        }
        Map<Chunk,Chunk> insetTargets = new HashMap<>();
        for (Chunk edge : java.util.stream.Stream.concat(strong.stream(),frontier.stream()).toList()) {
            Chunk target = insetTarget(edge,inset,parent.keySet(),depth);
            if (!target.equals(here) && revealsTerrain(target,viewDistance,scanned,bounds)) insetTargets.put(edge,target);
        }
        strong.removeIf(c -> !insetTargets.containsKey(c));
        frontier.removeIf(c -> !insetTargets.containsKey(c));
        List<Chunk> candidates = strong.isEmpty() ? frontier : strong;
        if (candidates.isEmpty()) {
            if (nearBorder || direction == 0) {
                // With no classified boundary yet, keep sampling inside the border. Prefer
                // tangential steps, but permit inward travel instead of insisting on radial growth.
                Chunk insideStep = parent.keySet().stream()
                        .filter(c -> !visited.contains(c) && !c.equals(here) && here.distance(c) <= 2.5
                                && revealsTerrain(c,viewDistance,scanned,bounds))
                        .min(Comparator.<Chunk>comparingDouble(c -> nearBorder ? Math.abs(bounds.distance(c) - bounds.distance(here)) : 0)
                                .thenComparingDouble(c -> Math.abs(here.distance(c) - 2))
                                .thenComparingInt(Chunk::x).thenComparingInt(Chunk::z)).orElse(null);
                if (insideStep != null)
                    return new Step(insideStep, "seeking a classified boundary " + (nearBorder ? "along or inside the world border" : "without spawn bias"));
                return noRoute("no unsearched route remains nearby", clipped || nearBorder);
            }
            // If this whole sampled area is old terrain, seek the edge in short outward steps.
            double len = Math.max(1, here.radius());
            int dx = here.x == 0 && here.z == 0 ? (int)(direction*2) : (int) Math.round(direction*here.x / len * 2);
            int dz = here.x == 0 && here.z == 0 ? 0 : (int) Math.round(direction*here.z / len * 2);
            Chunk outward = here.offset(dx, dz);
            if (!visited.contains(outward) && map.getOrDefault(outward, Kind.UNKNOWN) == Kind.GENERATED
                    && revealsTerrain(outward,viewDistance,scanned,bounds))
                return new Step(outward, "seeking a boundary through classified generated terrain ("+bias.label+")");
            return noRoute("no unsearched trail remains in the selected direction", clipped);
        }
        candidates.sort(Comparator.<Chunk>comparingDouble(c -> here.distance(insetTargets.get(c)) * 3 - direction*(c.radius() - here.radius()) * 6)
                .thenComparingInt(Chunk::x).thenComparingInt(Chunk::z));
        Map<Chunk,Chunk> route = inset>0 ? interiorPath(parent.keySet(),root,null,depth,inset) : parent;
        Set<Chunk> tried = new HashSet<>();
        for (Chunk edge : candidates) {
            Chunk selected = insetTargets.get(edge);
            // Several edge cells can project onto the same inset destination.
            if (!tried.add(selected)) continue;
            List<Chunk> path = new ArrayList<>();
            for (Chunk c = selected; ; c = route.get(c)) {
                path.add(c);
                if (c.equals(root)) break;
            }
            Collections.reverse(path);
            // Check the actual short leg, not just the distant frontier destination.
            // Coverage holes can keep that destination eligible forever, even after
            // both ends of a short leg have already been completed.
            Chunk target = here.distance(root) > 1.5 ? root : path.get(Math.min(2, path.size() - 1));
            if (target.equals(here) && path.size() > 1) target = path.get(1);
            if (target.equals(here) || visited.contains(target)) continue;
            return new Step(target, "trail: " + bias.label + ", inset up to " + inset + " chunks"
                    + (strong.isEmpty() ? " (unclassified boundary; weak evidence)" : " (generated / fresh boundary)")
                    + (nearBorder ? "; staying inside world border" : ""));
        }
        return noRoute("remaining routes repeat completed waypoints", clipped || nearBorder);
    }

    private static Map<Chunk,Chunk> interiorPath(Set<Chunk> connected, Chunk root, Chunk target, Map<Chunk,Integer> depth, int inset) {
        record Node(Chunk chunk, int cost) {}
        var queue = new PriorityQueue<Node>(Comparator.comparingInt(Node::cost).thenComparingInt(n->n.chunk().x()).thenComparingInt(n->n.chunk().z()));
        Map<Chunk,Integer> cost = new HashMap<>();
        Map<Chunk,Chunk> parent = new HashMap<>();
        cost.put(root,0); parent.put(root,root); queue.add(new Node(root,0));
        while(!queue.isEmpty()) {
            Node node=queue.remove(); Chunk c=node.chunk();
            if (node.cost()!=cost.get(c)) continue;
            if (c.equals(target)) break;
            for(int[] d:DIRS) {
                Chunk n=c.offset(d[0],d[1]);
                if(!connected.contains(n))continue;
                int next=node.cost()+1+Math.max(0,inset-depth.getOrDefault(n,0))*3;
                if(next<cost.getOrDefault(n,Integer.MAX_VALUE)) {
                    cost.put(n,next);parent.put(n,c);queue.add(new Node(n,next));
                }
            }
        }
        return parent;
    }

    private static Chunk insetTarget(Chunk edge, int inset, Set<Chunk> connected, Map<Chunk,Integer> depth) {
        Chunk best = edge;
        double score = Math.abs(depth.getOrDefault(edge,0)-inset)*4;
        for (int dx=-inset;dx<=inset;dx++) for (int dz=-inset;dz<=inset;dz++) {
            Chunk c=edge.offset(dx,dz);
            if (!connected.contains(c) || edge.distance(c)>inset) continue;
            double next = Math.abs(depth.getOrDefault(c,0)-inset)*4 + edge.distance(c);
            if (next<score) { score=next; best=c; }
        }
        return best;
    }

    static boolean revealsTerrain(Chunk target, int viewDistance, Set<Chunk> scanned, Bounds bounds) {
        if (viewDistance<=0) return true; // Legacy planner callers do not supply coverage.
        int radius=Math.max(0,viewDistance-1);
        for (int dx=-radius;dx<=radius;dx++) for (int dz=-radius;dz<=radius;dz++) {
            Chunk c=target.offset(dx,dz);
            if (bounds.allows(c) && !scanned.contains(c)) return true;
        }
        return false;
    }

    private static Step noRoute(String reason, boolean clipped) {
        return new Step(null, (clipped ? "world border limits the route; " : "")
                + reason + "; stopped instead of circling or guessing");
    }
}
