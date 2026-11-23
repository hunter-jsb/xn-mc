package xnmc.stasis;

import net.fabricmc.loader.api.FabricLoader;

/** Optional integration: XNMC is the only reconnect controller during a recall. */
final class MeteorReconnect {
    static boolean disable() {
        if (!FabricLoader.getInstance().isModLoaded("meteor-client")) return false;
        try {
            Class<?> modules = Class.forName("meteordevelopment.meteorclient.systems.modules.Modules");
            Class<?> module = Class.forName("meteordevelopment.meteorclient.systems.modules.Module");
            Object reconnect = modules.getMethod("get", Class.class).invoke(modules.getMethod("get").invoke(null),
                    Class.forName("meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect"));
            if (reconnect == null) throw new IllegalStateException("Meteor AutoReconnect not found");
            if ((boolean) module.getMethod("isActive").invoke(reconnect)) {
                module.getMethod("toggle").invoke(reconnect);
                if ((boolean) module.getMethod("isActive").invoke(reconnect))
                    throw new IllegalStateException("Could not turn off Meteor AutoReconnect");
                return true;
            }
            return false;
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("Could not check Meteor AutoReconnect; recall cancelled", e);
        }
    }
}
