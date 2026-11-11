package cc.noharry.agent.appserver.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.runtime.RuntimeInspection.MAX_HOST_SYSTEM_PROMPT_BYTES
import cc.noharry.agent.runtime.RuntimeInspection.validateHostSystemPrompt
import cc.noharry.agent.vfs.VfsReadResult
import cc.noharry.agent.appserver.model.appSystemPrompt
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.RunAddOns
import cc.noharry.agent.appserver.session.SessionStore
import cc.noharry.agent.appserver.session.modelContext
import cc.noharry.agent.appserver.skills.loadRuntimeSkills
import cc.noharry.agent.appserver.skills.preloadedSkillBodies
import cc.noharry.agent.appserver.skills.saveRuntimeSkill
import cc.noharry.agent.appserver.skills.systemPromptIds
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal const val MIXED_BROADCAST_TEXT = "介绍下你自己，为什么叫这个名字，我想去深圳北站，在那找一个停车场，中间去一下广州塔附近的肯德基吃个饭，不走高速，出了肯德基之后找一下沿途的加油站我去加个油然后继续去深圳北站，开始导航后放首周杰伦的歌"

/** A run context without its closing line on the thinking's language ([THINK_IN_CHINESE]), which comes after everything. */
internal fun String.beforeThinkingLine(): String = removeSuffix(THINK_IN_CHINESE).trimEnd()
