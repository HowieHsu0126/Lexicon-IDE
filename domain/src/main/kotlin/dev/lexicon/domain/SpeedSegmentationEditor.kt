package dev.lexicon.domain

/** Pure sentence editing. Storage assigns a new immutable plan key after a successful edit. */
object SpeedSegmentationEditor {
    fun sentence(plan: SpeedPlan, text: String, chunkIndex: Int, basePlan: SpeedPlan? = null): SpeedSegmentation? {
        SpeedChunker.validate(plan, text)
        val selected = plan.chunks.getOrNull(chunkIndex) ?: return null
        if (plan.basePlanKey != null && basePlan == null) return null
        val base = basePlan ?: plan
        validateBase(plan, text, base)
        val current = plan.chunks.filter { it.paragraph == selected.paragraph && it.sentence == selected.sentence }
        val original = base.chunks.filter { it.paragraph == selected.paragraph && it.sentence == selected.sentence }
        require(original.isNotEmpty() && original.first().start == current.first().start && original.last().end == current.last().end)
        val start = current.first().start
        val end = current.last().end
        val suggested = original.dropLast(1).map { it.end }
        val breaks = current.dropLast(1).map { it.end }
        return SpeedSegmentation(key(plan), plan.basePlanKey ?: key(base), plan.articleId, plan.bodyHash,
            plan.granularity, selected.paragraph, selected.sentence, start, end, text.substring(start, end),
            suggested, breaks, SpeedChunker.legalBreaks(text, start, end), breaks != suggested)
    }

    fun edit(plan: SpeedPlan, text: String, paragraph: Int, sentence: Int, breakOffsets: List<Int>, basePlan: SpeedPlan? = null): SpeedPlan {
        val index = plan.chunks.indexOfFirst { it.paragraph == paragraph && it.sentence == sentence }
        require(index >= 0) { "待调整句子不属于此训练方案" }
        val editable = requireNotNull(SpeedSegmentationEditor.sentence(plan, text, index, basePlan)) { "基础切分不可用，请重新准备" }
        require(breakOffsets == breakOffsets.distinct().sorted()) { "意群边界须按原文顺序且不能重复" }
        require(breakOffsets.all { it in editable.legalBreaks }) { "这个位置会拆开单词或紧密搭配" }
        if (breakOffsets == editable.breaks) return plan
        val ranges = (listOf(editable.start) + breakOffsets + editable.end).zipWithNext()
        val replacement = ranges.map { (start, end) ->
            SenseChunk(start, end, paragraph, sentence, SpeedChunker.wordCount(text.substring(start, end)), "manual")
        }
        val chunks = plan.chunks.flatMapIndexed { position, chunk ->
            when {
                position == index -> replacement
                chunk.paragraph == paragraph && chunk.sentence == sentence -> emptyList()
                else -> listOf(chunk)
            }
        }
        return plan.copy(chunks = chunks, origin = "manual", basePlanKey = editable.basePlanKey, planKey = "").also {
            SpeedChunker.validate(it, text)
        }
    }

    fun restore(plan: SpeedPlan, text: String, basePlan: SpeedPlan): SpeedPlan {
        SpeedChunker.validate(plan, text)
        validateBase(plan, text, basePlan)
        return basePlan
    }

    private fun validateBase(plan: SpeedPlan, text: String, base: SpeedPlan) {
        SpeedChunker.validate(base, text)
        require(base.articleId == plan.articleId && base.bodyHash == plan.bodyHash && base.granularity == plan.granularity &&
            base.paragraphIndices == plan.paragraphIndices && base.questions == plan.questions) { "基础方案与当前原文不匹配" }
        require(base.basePlanKey == null) { "基础方案必须是最初的切分" }
        require(plan.basePlanKey == null || plan.basePlanKey == key(base)) { "基础方案标识不匹配" }
        require(plan.basePlanKey != null || key(base) == key(plan)) { "基础方案标识不匹配" }
    }

    private fun key(plan: SpeedPlan): String = plan.planKey.ifBlank { SpeedChunker.planKey(plan) }
}
