package com.ricardochr.estantepinturas

import org.junit.Assert.assertEquals
import org.junit.Test

/** Abrir cualquier pantalla no debe fallar al iniciarse, y cada una debe poder guardarse y recuperarse. */
class NavTest {
    @Test fun pantallasSeGuardanYRecuperan() {
        val all = listOf(
            Screen.Settings, Screen.Light, Screen.Shopping, Screen.Backup, Screen.Gear, Screen.Compare, Screen.Sets,
            Screen.Guide, Screen.Schemes, Screen.Gaps, Screen.Wheel(null), Screen.Wheel("x|y"), Screen.Project("p1"),
            Screen.Storage(null, "a", null), Screen.LiveFind("a"),
        )
        for (s in all) assertEquals(s.key, Screen.parse(s.key)?.key)
    }
}
