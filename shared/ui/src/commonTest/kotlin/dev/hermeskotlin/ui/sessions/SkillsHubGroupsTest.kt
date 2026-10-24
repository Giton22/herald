package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.capabilities.HubSkill
import kotlin.test.Test
import kotlin.test.assertEquals

class SkillsHubGroupsTest {

    @Test
    fun officialGroupsByCategoryWithOtherLast() {
        val groups = officialGroups(
            listOf(
                HubSkill("a"),
                HubSkill("b", category = "Research"),
                HubSkill("c", category = "research"),
                HubSkill("d", category = "creative"),
            ),
        )
        assertEquals(listOf("creative", "research", "other"), groups.keys.toList())
        assertEquals(listOf("b", "c"), groups.getValue("research").map { it.name })
    }
}
