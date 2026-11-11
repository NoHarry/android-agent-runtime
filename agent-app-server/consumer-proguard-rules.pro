# The JavaScript sandbox (cc.noharry:agent-exec-js) is the host's to add: without it, the server's references to it are
# never reached (JsSandbox checks first), so R8 need not find it.
-dontwarn cc.noharry.agent.exec.js.**

# A library may declare a plugin in its manifest (PluginDiscovery), which the server makes from its class name: every
# plugin keeps its name and the way it is made (a Kotlin object's INSTANCE, or a constructor that takes nothing).
-keep class * implements cc.noharry.agent.appserver.server.AppServerPlugin {
    public static ** INSTANCE;
    public <init>();
}
