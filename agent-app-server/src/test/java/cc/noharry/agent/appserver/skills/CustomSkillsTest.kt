package cc.noharry.agent.appserver.skills
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.workspaceVfs
import cc.noharry.agent.vfs.VfsReadResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Skills a person adds on the device, beside a host's ([testHostSkills]); the form's findings are checked in Chinese. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh")
class CustomSkillsTest {
    @After
    fun bareHost() {
        AppHost.install(skills = { emptyList() })
    }

    private lateinit var context: Context

    @Before
    fun clean() {
        AppHost.install(skills = ::testHostSkills)
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("app_server_runtime_skills", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "custom-skills").deleteRecursively()
    }

    @Test
    fun theTemplateAndAFullHeaderPass() {
        val template = checkCustomSkill(customSkillTemplate(), emptySet())
        assertEquals(emptyList<SkillIssue>(), template.errors)
        assertEquals("my-skill", template.name)

        val full = checkCustomSkill(
            "---\r\nname: \"trip-notes\"\r\ndescription: \"记下行程里的要点，用户说「记一下」时用。\"\r\nlicense: MIT\r\n" +
                "metadata:\r\n  author: me\r\n  version: \"1.0\"\r\nscenes: [\"asr\", \"debug-console\"]\r\n---\r\n\r\n# 记要点\r\n先复述再记。\r\n",
            emptySet(),
        )
        assertEquals(emptyList<SkillIssue>(), full.errors)
        assertEquals("trip-notes", full.name)
        assertEquals("记下行程里的要点，用户说「记一下」时用。", full.description)
        assertFalse("saved with LF line ends", '\r' in full.normalized)
    }

    @Test
    fun eachBrokenRuleIsNamed() {
        fun errors(text: String, taken: Set<String> = emptySet()) = checkCustomSkill(text, taken).errors.map { it.code }
        fun skill(header: String, body: String = "# 做法\n照做。") = "---\n$header\n---\n$body\n"

        assertEquals(listOf(FindingCode.SKILLS_CHECK_EMPTY), errors(""))
        assertTrue(FindingCode.SKILLS_CHECK_FIRST_LINE in errors("# 只有正文"))
        assertTrue(FindingCode.SKILLS_CHECK_UNCLOSED in errors("---\nname: a\ndescription: 做一件事的时候用\n# 正文"))
        assertTrue(FindingCode.SKILLS_CHECK_NO_NAME in errors(skill("description: 做一件事的时候用")))
        listOf("My-Skill", "my_skill", "-skill", "skill-", "my--skill", "a".repeat(65)).forEach { name ->
            assertTrue(name, FindingCode.SKILLS_CHECK_NAME_FORMAT in errors(skill("name: $name\ndescription: 做一件事的时候用")))
        }
        val builtin = skill("name: screen-steps\ndescription: 做一件事的时候用")
        val taken = checkCustomSkill(builtin, builtinRuntimeSkillIds(context)).errors.single { it.code == FindingCode.SKILLS_CHECK_NAME_TAKEN }
        assertEquals(listOf("screen-steps"), taken.finding().more.map { it.args.single() })
        assertTrue(FindingCode.SKILLS_CHECK_NO_DESCRIPTION in errors(skill("name: a")))
        assertTrue(FindingCode.SKILLS_CHECK_DESCRIPTION_LINE in errors(skill("name: a\ndescription: >\n  做一件事的时候用")))
        assertTrue(FindingCode.SKILLS_CHECK_DESCRIPTION_BRACKETS in errors(skill("name: a\ndescription: 用 <tool> 的时候用")))
        assertTrue(FindingCode.SKILLS_CHECK_SINGLE_QUOTES in errors(skill("name: a\ndescription: '做一件事的时候用'")))
        assertTrue(FindingCode.SKILLS_CHECK_UNKNOWN_FIELD in errors(skill("name: a\ndescription: 做一件事的时候用\nversion: 1")))
        assertTrue(FindingCode.SKILLS_CHECK_DUPLICATE in errors(skill("name: a\ndescription: 做一件事的时候用\nname: b")))
        assertTrue(FindingCode.SKILLS_CHECK_METADATA in errors(skill("name: a\ndescription: 做一件事的时候用\nmetadata: x")))
        assertTrue(FindingCode.SKILLS_CHECK_SCENES in errors(skill("name: a\ndescription: 做一件事的时候用\nscenes: asr")))
        assertTrue(FindingCode.SKILLS_CHECK_NOT_FIELD in errors(skill("name: a\ndescription: 做一件事的时候用\nnope")))
        assertTrue(FindingCode.SKILLS_CHECK_EMPTY_BODY in errors(skill("name: a\ndescription: 做一件事的时候用", body = "\n")))
        assertTrue(FindingCode.SKILLS_CHECK_TOO_BIG in errors(skill("name: a\ndescription: 做一件事的时候用", body = "x\n".repeat(70_000))))
        val sections = skill("name: a\ndescription: 做一件事的时候用", body = "<!-- a2a -->\n发给助手\n<!-- /a2a -->\n<!-- /a2a -->")
        assertTrue(FindingCode.SKILLS_CHECK_SECTIONS in errors(sections))
        // An opening never closed is an ordinary comment.
        assertEquals(emptyList<FindingCode>(), errors(skill("name: a\ndescription: 做一件事的时候用", body = "<!-- todo -->\n照做。")))
    }

    @Test
    fun anUnknownFieldNamesItsLineAndTheFieldsAllowed() {
        val text = "---\nname: a\ndescription: 做一件事的时候用\nversion: 1\n---\n照做。\n"
        val found = checkCustomSkill(text, emptySet()).errors.single().finding()

        assertEquals(FindingCode.SKILLS_CHECK_UNKNOWN_FIELD.name, found.code)
        assertEquals(listOf("4", "version"), found.more.take(2).map { it.args.single() })
        assertEquals(
            listOf("name", "description", "license", "compatibility", "metadata", "allowed-tools", "scenes"),
            found.more[2].more.map { it.args.single() },
        )
    }

    @Test
    fun warningsDoNotKeepASkillFromBeingSaved() {
        val check = checkCustomSkill("---\nname: a\ndescription: 短\n---\n" + "照做。\n".repeat(600), emptySet())
        assertEquals(emptyList<SkillIssue>(), check.errors)
        assertEquals(2, check.warnings.size)
    }

    @Test
    fun aSavedSkillLoadsOnReachesTheModelAndCanBeRenamedSwitchedAndDeleted() = runBlocking {
        val text = "---\nname: trip-notes\ndescription: 记下行程要点，用户说「记一下」时用。\n---\n\n# 记要点\n先复述再记。\n"
        assertNull(CustomSkills.save(context, text, editing = null))
        val added = loadRuntimeSkills(context).single { it.id == "trip-notes" }
        assertTrue("on when added", added.enabled)
        assertEquals("记下行程要点，用户说「记一下」时用。", added.description)
        assertFalse("read when needed, not preloaded", added.preload)
        val vfs = workspaceVfs(context, File(context.cacheDir, "custom-skills-vfs"))
        val found = vfs.read("/.agents/skills/trip-notes/SKILL.md", 0, 20_000) as VfsReadResult.Found
        assertTrue(found.text.contains("先复述再记。"))

        val refused = CustomSkills.save(context, text, editing = null)!!
        assertEquals(FindingCode.SKILLS_CHECK_NAME_TAKEN.name, refused.more.single().code)
        saveRuntimeSkill(context, "trip-notes", false)
        assertNull(CustomSkills.save(context, text.replace("trip-notes", "trip-memo"), editing = "trip-notes"))
        val ids = loadRuntimeSkills(context).map { it.id }
        assertFalse("trip-notes" in ids)
        assertFalse("the switch goes with the rename", loadRuntimeSkills(context).single { it.id == "trip-memo" }.enabled)

        assertTrue(CustomSkills.delete(context, "trip-memo"))
        assertFalse(loadRuntimeSkills(context).any { it.file != null })
        assertFalse("built-in skills are not deleted here", CustomSkills.delete(context, "screen-steps"))
    }
}
