package cc.noharry.agent.runtime.thread

import cc.noharry.agent.ApprovalPolicy
import cc.noharry.agent.ThreadOption
import cc.noharry.agent.ThreadOptions

internal fun mergeThreadOptions(saved: ThreadOptions, patch: ThreadOptions): ThreadOptions = saved.copy(
    threadId = patch.threadId ?: saved.threadId,
    model = patch.model ?: saved.model.takeUnless { ThreadOption.MODEL in patch.reset },
    workspace = patch.workspace ?: saved.workspace.takeUnless { ThreadOption.WORKSPACE in patch.reset },
    scene = patch.scene ?: saved.scene.takeUnless { ThreadOption.SCENE in patch.reset },
    instructions = patch.instructions ?: saved.instructions.takeUnless { ThreadOption.INSTRUCTIONS in patch.reset },
    approvalPolicy = patch.approvalPolicy ?: saved.approvalPolicy ?: ApprovalPolicy.ASK,
    reset = emptySet(),
)
