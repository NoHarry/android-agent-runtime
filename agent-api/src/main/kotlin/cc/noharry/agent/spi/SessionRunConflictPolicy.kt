package cc.noharry.agent.spi

/** How a new turn handles an existing turn in the same conversation. */
enum class SessionRunConflictPolicy { RESUME_CURRENT, QUEUE_NEW, CANCEL_AND_REPLACE }
