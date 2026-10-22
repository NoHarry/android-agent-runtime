-keep class cc.noharry.agent.kernel.api.** { *; }
-keep class cc.noharry.agent.kernel.core.KernelRuntimeBuilder { *; }
-keep class cc.noharry.agent.kernel.core.CoreAgentRuntime { *; }
-keep class cc.noharry.agent.minimal.MinimalAgent { *; }
-keep class cc.noharry.agent.runtime.AgentRuntimeBuilder { *; }
-keep class cc.noharry.agent.runtime.RuntimeWorkspaceRef { *; }
-keep class cc.noharry.agent.runtime.HostSkill { *; }
-keep class cc.noharry.agent.runtime.NetworkToolConfirmationPolicy { *; }
-keep class cc.noharry.agent.runtime.eventlog.DurableSessionEventLogPlugin { *; }
-keep class cc.noharry.agent.dream.** { *; }
-keep class cc.noharry.agent.mcp.** { *; }
-keep class cc.noharry.agent.model.** { *; }
-keep class cc.noharry.agent.policy.** { *; }
-keep class cc.noharry.agent.transport.** { *; }
-keep class cc.noharry.agent.vfs.** { *; }
-keepclassmembers class cc.noharry.agent.** {
    public static ** Companion;
}
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
