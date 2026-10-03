package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.capabilities.Skill
import kotlin.test.Test
import kotlin.test.assertEquals

class SkillGroupsTest {

    private fun skill(name: String, category: String?) = Skill(name = name, category = category)

    @Test
    fun categoriesDifferingOnlyInCaseShareAGroup() {
        val groups = skillGroups(
            listOf(skill("a", "Research"), skill("b", "research"), skill("c", "creative"), skill("d", null), skill("e", " ")),
            query = "",
        )
        assertEquals(listOf("creative", "research", "other"), groups.keys.toList())
        assertEquals(listOf("a", "b"), groups.getValue("research").map { it.name })
        assertEquals(listOf("d", "e"), groups.getValue("other").map { it.name })
    }

    @Test
    fun theSearchMatchesCategoryToo() {
        val groups = skillGroups(listOf(skill("a", "Research"), skill("b", "creative")), query = "resea")
        assertEquals(listOf("a"), groups.values.flatten().map { it.name })
    }
}
