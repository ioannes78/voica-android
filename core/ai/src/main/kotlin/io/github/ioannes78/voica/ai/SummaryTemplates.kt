package io.github.ioannes78.voica.ai

data class SummaryTemplateSpec(
    val id: String?,
    val name: String,
    val sections: List<AiSummarySectionType>,
    val focus: String?,
    val userInstruction: String?,
) {
    init {
        require(name.isNotBlank())
        require(sections.isNotEmpty())
        require(userInstruction == null || userInstruction.length <= MAX_USER_INSTRUCTION_CHARS)
    }

    companion object {
        const val MAX_USER_INSTRUCTION_CHARS = 4_000
    }
}

object SummaryTemplateCatalog {
    const val GENERIC = "generic"
    const val MEETING = "meeting"
    const val INTERVIEW = "interview"
    const val CLASS_TRAINING = "class-training"
    const val WORK_REPORT = "work-report"
    const val PROJECT = "project"
    const val SALES = "sales"
    const val BRAINSTORM = "brainstorm"
    const val PERSONAL_NOTE = "personal-note"

    private val presets =
        listOf(
            SummaryTemplateSpec(
                GENERIC,
                "通用总结",
                listOf(
                    AiSummarySectionType.SUMMARY,
                    AiSummarySectionType.KEY_POINT,
                    AiSummarySectionType.FOLLOW_UP,
                ),
                "提炼内容主旨、重要信息与后续事项。",
                null,
            ),
            SummaryTemplateSpec(
                MEETING,
                "会议纪要",
                listOf(
                    AiSummarySectionType.SUMMARY,
                    AiSummarySectionType.KEY_POINT,
                    AiSummarySectionType.DECISION,
                    AiSummarySectionType.ACTION_ITEM,
                    AiSummarySectionType.RISK,
                    AiSummarySectionType.FOLLOW_UP,
                ),
                "优先识别议题、结论、决策、负责人/待办、风险和未决问题。",
                null,
            ),
            SummaryTemplateSpec(
                INTERVIEW,
                "访谈总结",
                listOf(
                    AiSummarySectionType.SUMMARY,
                    AiSummarySectionType.KEY_POINT,
                    AiSummarySectionType.QA,
                    AiSummarySectionType.FACT,
                ),
                "保留被访谈者观点、关键问答和明确陈述的事实。",
                null,
            ),
            SummaryTemplateSpec(
                CLASS_TRAINING,
                "课程/培训",
                listOf(
                    AiSummarySectionType.SUMMARY,
                    AiSummarySectionType.KNOWLEDGE_POINT,
                    AiSummarySectionType.KEY_POINT,
                    AiSummarySectionType.QUESTION,
                ),
                "提取知识点、方法、例子、问题与可复习结构。",
                null,
            ),
            SummaryTemplateSpec(
                WORK_REPORT,
                "工作汇报",
                listOf(
                    AiSummarySectionType.SUMMARY,
                    AiSummarySectionType.FACT,
                    AiSummarySectionType.KEY_POINT,
                    AiSummarySectionType.RISK,
                    AiSummarySectionType.FOLLOW_UP,
                ),
                "突出进展、结果、数据、问题、风险与下一步。",
                null,
            ),
            SummaryTemplateSpec(
                PROJECT,
                "项目讨论",
                listOf(
                    AiSummarySectionType.SUMMARY,
                    AiSummarySectionType.DECISION,
                    AiSummarySectionType.ACTION_ITEM,
                    AiSummarySectionType.RISK,
                    AiSummarySectionType.QUESTION,
                ),
                "聚焦方案取舍、决策、任务、依赖、风险和开放问题。",
                null,
            ),
            SummaryTemplateSpec(
                SALES,
                "销售沟通",
                listOf(
                    AiSummarySectionType.SUMMARY,
                    AiSummarySectionType.KEY_POINT,
                    AiSummarySectionType.FACT,
                    AiSummarySectionType.RISK,
                    AiSummarySectionType.FOLLOW_UP,
                ),
                "聚焦客户需求、约束、异议、承诺和下一步跟进。",
                null,
            ),
            SummaryTemplateSpec(
                BRAINSTORM,
                "头脑风暴",
                listOf(
                    AiSummarySectionType.SUMMARY,
                    AiSummarySectionType.KEY_POINT,
                    AiSummarySectionType.RISK,
                    AiSummarySectionType.FOLLOW_UP,
                ),
                "归并创意、分歧、可行方向、风险和待验证假设。",
                null,
            ),
            SummaryTemplateSpec(
                PERSONAL_NOTE,
                "个人语音笔记",
                listOf(
                    AiSummarySectionType.SUMMARY,
                    AiSummarySectionType.KEY_POINT,
                    AiSummarySectionType.ACTION_ITEM,
                    AiSummarySectionType.FOLLOW_UP,
                ),
                "保留个人想法、提醒、待办和后续线索。",
                null,
            ),
        )

    fun all(): List<SummaryTemplateSpec> = presets

    fun find(id: String): SummaryTemplateSpec? =
        presets.firstOrNull { it.id == id }

    fun smart(): SummaryTemplateSpec =
        SummaryTemplateSpec(
            id = null,
            name = "智能总结",
            sections =
                listOf(
                    AiSummarySectionType.SUMMARY,
                    AiSummarySectionType.KEY_POINT,
                    AiSummarySectionType.DECISION,
                    AiSummarySectionType.ACTION_ITEM,
                    AiSummarySectionType.RISK,
                    AiSummarySectionType.QUESTION,
                    AiSummarySectionType.FACT,
                    AiSummarySectionType.QA,
                    AiSummarySectionType.KNOWLEDGE_POINT,
                    AiSummarySectionType.FOLLOW_UP,
                ),
            focus = "先判断内容类型，再选择真正适合该内容的章节；不要机械输出所有章节。",
            userInstruction = null,
        )
}
