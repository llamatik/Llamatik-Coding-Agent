package com.llamatik.agent.core.agent

import com.llamatik.agent.core.host.AgentEnvironment
import com.llamatik.agent.core.model.ChatMessage
import com.llamatik.agent.core.model.ChatRole
import com.llamatik.agent.core.model.EditProposal
import com.llamatik.agent.core.tools.ToolCall
import com.llamatik.agent.core.tools.ToolExecutionContext
import com.llamatik.agent.core.tools.ToolResult

/**
 * Makes the edit tools act: the wrapped tool prepares a change, [review] lets the user accept or refuse
 * it, and an accepted change is written to disk. The model is told what happened, so it can carry on
 * (run the tests, fix a refused edit) the way an interactive coding agent does.
 */
class EditApplyingToolExecutor(
    private val delegate: ToolExecutor,
    private val env: AgentEnvironment,
    private val editTools: Set<String> = ToolPolicy.DEFAULT_EDIT_TOOLS,
    /** Shows the change and returns true to apply it. */
    private val review: (EditProposal) -> Boolean,
) : ToolExecutor by delegate {
    private val extractor = ProposalExtractor(env, editTools)

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val prepared = delegate.execute(call, context)
        if (call.name !in editTools) return prepared
        if (!prepared.success) return prepared

        val message = ChatMessage(role = ChatRole.TOOL, content = prepared.content, toolName = call.name)
        val result = extractor.buildProposalsFromToolMessages(listOf(message))
        val proposal = result.proposals.firstOrNull()
            ?: return ToolResult(call.name, false, "Error: " + (result.rejectionReasons.firstOrNull() ?: "the change could not be prepared"))

        if (proposal.oldContent == proposal.newContent) {
            return ToolResult(call.name, true, "No change: ${proposal.filePath} already has this content.")
        }
        // Small models often splice an edit in twice or cut a block short. Catch the obvious case before the user sees it.
        if (isBraceLanguage(proposal.filePath) && balanced(proposal.oldContent) && !balanced(proposal.newContent)) {
            return ToolResult(call.name, false,
                "Error: this change leaves the braces in ${proposal.filePath} unbalanced (a block was duplicated or cut short). " +
                    "Nothing was written. Read the file again and retry with an oldText that covers exactly the lines you change, and a newText that replaces only those.")
        }
        if (!review(proposal)) {
            return ToolResult(call.name, false, "The user declined this change to ${proposal.filePath}. Do not retry it; ask what they would like instead.")
        }
        return try {
            env.files.write(proposal.filePath, proposal.newContent)
            val verb = if (proposal.oldContent.isEmpty()) "Created" else "Updated"
            ToolResult(call.name, true, "$verb ${proposal.filePath}.")
        } catch (e: Exception) {
            ToolResult(call.name, false, "Error: could not write ${proposal.filePath}: ${e.message}")
        }
    }

    private fun isBraceLanguage(path: String) =
        path.substringAfterLast('.', "").lowercase() in setOf("kt", "kts", "java", "js", "jsx", "ts", "tsx", "c", "cc", "cpp", "h", "hpp", "cs", "go", "rs", "swift", "scala", "dart")

    private fun balanced(text: String): Boolean = text.count { it == '{' } == text.count { it == '}' }
}
