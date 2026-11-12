# The app server (cc.noharry:agent-app-server) is the host's to add: this module only compiles against it, for its
# plugin (cc.noharry.agent.androiduse.appserver), which nothing else here names. Without the server the plugin is never
# loaded, so R8 need not find the server's classes. With it, the server's own rules keep the plugin.
-dontwarn cc.noharry.agent.appserver.**
