# The JavaScript sandbox (cc.noharry:agent-exec-js) is the host's to add: without it, the server's references to it are
# never reached (JsSandbox checks first), so R8 need not find it.
-dontwarn cc.noharry.agent.exec.js.**
