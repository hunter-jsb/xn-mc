package dev.fieldnotes.smoke;
public final class Smoke implements net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint {
 public void onPreLaunch() {
  try {
   ClassLoader loader = Thread.currentThread().getContextClassLoader();
   for (String name : new String[]{"dev.fieldnotes.guard.GuardClient", "net.minecraft.client.gui.screens.ConnectScreen", "net.minecraft.client.gui.screens.DisconnectedScreen", "net.minecraft.client.multiplayer.ClientPacketListener", "com.autoeater.AutoEaterClient"}) {
    Class.forName(name, false, loader);
    System.out.println("SMOKE_TRANSFORM_OK " + name);
   }
   Class<?> guardClient = Class.forName("dev.fieldnotes.guard.GuardClient", true, loader);
   Object instance = guardClient.getDeclaredConstructor().newInstance();
   guardClient.getMethod("onInitializeClient").invoke(instance);
   System.out.println("SMOKE_ENTRYPOINT_OK " + guardClient.getMethod("status").invoke(null));
   System.out.println("SMOKE_PASS: Fabric loaded and transformed all guard targets; game was not started");
   System.exit(0);
  } catch(Throwable e) {e.printStackTrace(); System.exit(73);}
 }
}
