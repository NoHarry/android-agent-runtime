package cc.noharry.agent.kernel.core.context
/** Optional prompt-time skill catalog seam. */
internal interface SkillCatalogPort {
    suspend fun catalog(scene: String?): SkillsCatalog
}
